package dev.nottzaid.earshot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings.Global
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.nottzaid.earshot.MainActivity
import dev.nottzaid.earshot.R
import dev.nottzaid.earshot.audio.EngineStatus
import dev.nottzaid.earshot.audio.LatencyMode
import dev.nottzaid.earshot.audio.Mic
import dev.nottzaid.earshot.audio.Mics
import dev.nottzaid.earshot.audio.NativeEngine
import dev.nottzaid.earshot.audio.SoundMode
import dev.nottzaid.earshot.data.Prefs
import dev.nottzaid.earshot.net.ControlEvent
import dev.nottzaid.earshot.net.Discovery
import dev.nottzaid.earshot.net.FoundPc
import dev.nottzaid.earshot.net.PROTO_VERSION
import dev.nottzaid.earshot.net.jsonOf
import dev.nottzaid.earshot.net.runControlSession
import dev.nottzaid.earshot.settings
import dev.nottzaid.earshot.tile.MicTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/** What the engine should be doing; null means mic closed. */
private data class EngineConfig(
    val host: String,
    val micId: Int,
    val sound: SoundMode,
    val latency: LatencyMode,
)

class MicService : LifecycleService() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineThread = Dispatchers.IO.limitedParallelism(1)
    private val outgoing = Channel<JSONObject>(Channel.BUFFERED)
    private val pcListening = MutableStateFlow(false)
    private val connectedPc = MutableStateFlow<FoundPc?>(null)
    @Volatile private var engineConfig: EngineConfig? = null
    @Volatile private var lastLocalMuteNs = 0L
    @Volatile private var muted = false
    private var started = false

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLowLatency: WifiManager.WifiLock
    private lateinit var wifiHighPerf: WifiManager.WifiLock

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "earshot:stream").apply { setReferenceCounted(false) }
        val wm = getSystemService(WifiManager::class.java)
        // Low-latency only applies with the screen on; high-perf covers screen-off.
        wifiLowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "earshot:ll").apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifiHighPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "earshot:hp").apply { setReferenceCounted(false) }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> lifecycleScope.launch { toggleMute() }
        }
        if (!started) {
            started = true
            startForeground(NOTIFICATION_ID, buildNotification(Earshot.runtime.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            Earshot.update { it.copy(phase = Phase.Searching, problem = Problem.None) }
            run()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lifecycleScope.launch(engineThread) { NativeEngine.stop() }
        releaseLocks()
        Earshot.update { Runtime(pcName = it.pcName) }
        MicTile.refresh(this)
        super.onDestroy()
    }

    private fun run() {
        val prefs = settings.prefs.stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        val mics = Mics.watch(this).stateIn(lifecycleScope, SharingStarted.Eagerly, Mics.list(this))

        lifecycleScope.launch { connectLoop(prefs.filterNotNull().first()) }

        // Live engine parameters: cheap atomics, applied on every change.
        lifecycleScope.launch {
            prefs.filterNotNull().collect { p ->
                muted = p.muted
                NativeEngine.setGainDb(p.gainDb)
                NativeEngine.setMuted(p.muted)
                NativeEngine.setHighPass(p.rumbleFilter)
            }
        }

        // Smart standby: an app on the PC opening the mic opens ours; closing it closes ours a little later,
        // so an app that briefly reopens its input doesn't bounce the phone's mic.
        lifecycleScope.launch {
            var releaseJob: Job? = null
            Earshot.runtime.map { it.pcState.listening }.distinctUntilChanged().collect { listening ->
                releaseJob?.cancel()
                if (listening) pcListening.value = true
                else releaseJob = launch {
                    delay(STANDBY_GRACE_MS)
                    pcListening.value = false
                }
            }
        }

        // The engine follows the desired config; restarts happen on one thread, in order.
        lifecycleScope.launch {
            combine(prefs.filterNotNull(), mics, connectedPc, pcListening) { p, m, pc, listening ->
                desiredConfig(p, m, pc, listening)
            }.distinctUntilChanged().collect { applyEngine(it) }
        }

        // Watch the engine for failures and keep the notification and tile honest.
        lifecycleScope.launch {
            val status = EngineStatus()
            var lastPhase: Phase? = null
            var lastMuted: Boolean? = null
            while (isActive) {
                status.refresh()
                if (engineConfig != null && status.state < 0) {
                    Earshot.update { it.copy(problem = if (status.state == NativeEngine.STATE_CAPTURE_FAILED) Problem.MicUnavailable else Problem.NetworkUnavailable) }
                    val cfg = engineConfig
                    engineConfig = null
                    delay(1500)
                    applyEngine(cfg) // try again; a route change or a busy mic usually clears quickly
                }
                val rt = Earshot.runtime.value
                if (rt.phase != lastPhase || muted != lastMuted) {
                    lastPhase = rt.phase
                    lastMuted = muted
                    notify(rt)
                    MicTile.refresh(this@MicService)
                }
                delay(250)
            }
        }

        // The desktop can mute the mic too: follow it, unless it is just echoing our own change.
        lifecycleScope.launch {
            Earshot.runtime.map { it.connected to it.pcState.muted }.distinctUntilChanged().collect { (connected, pcMuted) ->
                val p = prefs.value ?: return@collect
                if (connected && pcMuted != p.muted && System.nanoTime() - lastLocalMuteNs > 1_500_000_000) {
                    settings.setMuted(pcMuted)
                }
            }
        }

        // Settings the PC needs to hear about.
        lifecycleScope.launch {
            prefs.filterNotNull().map { it.latency to it.defaultMic }.distinctUntilChanged().collect { (lat, def) ->
                outgoing.trySend(jsonOf("t" to "set", "mode" to lat.key, "default" to def))
            }
        }
    }

    private fun desiredConfig(p: Prefs, mics: List<Mic>, pc: FoundPc?, listening: Boolean): EngineConfig? {
        // Audio is independent of the control link: once streaming to a host, a control blip doesn't stop it.
        val host = pc?.address ?: engineConfig?.host ?: return null
        if (pc != null && p.smartStandby && !listening) return null
        val mic = Mics.resolve(mics, p.micKey) ?: return null
        return EngineConfig(host, mic.id, p.sound, p.latency)
    }

    private suspend fun applyEngine(cfg: EngineConfig?) = withContext(engineThread) {
        if (cfg == engineConfig && (cfg == null || NativeEngine.readStatusState() == NativeEngine.STATE_RUNNING)) return@withContext
        NativeEngine.stop()
        engineConfig = cfg
        if (cfg == null) {
            releaseLocks()
            Earshot.update { it.copy(phase = if (it.connected) Phase.Standby else Phase.Searching) }
            return@withContext
        }
        val p = settings.prefs.first()
        NativeEngine.setGainDb(p.gainDb)
        NativeEngine.setMuted(p.muted)
        NativeEngine.setHighPass(p.rumbleFilter)
        val state = NativeEngine.start(
            cfg.host, cfg.micId, cfg.sound.preset,
            cfg.latency.packetUs, cfg.latency.fecSource, cfg.latency.fecRepair,
        )
        if (state == NativeEngine.STATE_RUNNING) {
            acquireLocks()
            Earshot.update { it.copy(phase = Phase.Live, problem = Problem.None) }
        } else {
            Earshot.update {
                it.copy(problem = if (state == NativeEngine.STATE_CAPTURE_FAILED) Problem.MicUnavailable else Problem.NetworkUnavailable)
            }
        }
    }

    private suspend fun connectLoop(initial: Prefs) {
        val deviceId = settings.deviceId()
        var hint = initial.pcHost
        var backoff = 500L
        while (currentCoroutineContext().isActive) {
            val p = settings.prefs.first()
            val found = Discovery.probe(hint)
            val pc = found.firstOrNull { it.address == p.pcHost && (it.pairedWith == null || it.pairedWith == deviceId) }
                ?: found.firstOrNull { it.pairedWith == deviceId }
                ?: found.firstOrNull { it.pairedWith == null }
            if (pc == null) {
                Earshot.update {
                    it.copy(
                        connected = false,
                        problem = if (found.isNotEmpty()) Problem.PairedElsewhere else Problem.NoPc,
                        pcName = found.firstOrNull()?.name ?: it.pcName,
                        phase = if (engineConfig != null) Phase.Live else Phase.Searching,
                    )
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(4000)
                continue
            }
            backoff = 500L
            hint = pc.address
            try {
                session(pc, deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // connection refused or dropped: back to discovery
            }
            connectedPc.value = null
            Earshot.update { it.copy(connected = false, rttMs = null, jitterMs = null, phase = if (engineConfig != null) Phase.Live else Phase.Searching) }
            delay(700)
        }
    }

    private suspend fun session(pc: FoundPc, deviceId: String) {
        val p = settings.prefs.first()
        val hello = jsonOf(
            "t" to "hello", "v" to PROTO_VERSION, "id" to deviceId, "name" to deviceName(),
            "secret" to (if (pc.address == p.pcHost) p.pcSecret else null),
            "prefs" to jsonOf("mode" to p.latency.key, "default" to p.defaultMic),
        )
        while (outgoing.tryReceive().isSuccess) Unit // drop anything queued for a previous session
        var avgRtt = -1f
        var jitter = 0f
        runControlSession(pc, hello, outgoing) { event ->
            when (event) {
                is ControlEvent.Welcome -> {
                    settings.rememberPc(pc.address, event.name, event.secret)
                    outgoing.trySend(jsonOf("t" to "set", "muted" to settings.prefs.first().muted))
                    lastLocalMuteNs = System.nanoTime() // the PC's first state predates our mute push
                    connectedPc.value = pc
                    Earshot.update { it.copy(connected = true, pc = pc, pcName = event.name, problem = Problem.None) }
                }
                is ControlEvent.Denied -> Earshot.update {
                    it.copy(problem = if (event.reason == "paired-elsewhere") Problem.PairedElsewhere else Problem.NoPc)
                }
                is ControlEvent.State -> Earshot.update {
                    it.copy(pcState = event.state, phase = if (engineConfig != null) Phase.Live else Phase.Standby)
                }
                is ControlEvent.Rtt -> {
                    if (avgRtt < 0) avgRtt = event.ms
                    jitter += (abs(event.ms - avgRtt) - jitter) * 0.2f
                    avgRtt += (event.ms - avgRtt) * 0.2f
                    Earshot.update { it.copy(rttMs = avgRtt, jitterMs = jitter) }
                }
            }
        }
    }

    private suspend fun toggleMute() {
        val muted = !settings.prefs.first().muted
        setMuted(muted)
    }

    private suspend fun setMuted(muted: Boolean) {
        lastLocalMuteNs = System.nanoTime()
        NativeEngine.setMuted(muted)
        settings.setMuted(muted)
        outgoing.trySend(jsonOf("t" to "set", "muted" to muted))
    }

    private fun deviceName(): String =
        Global.getString(contentResolver, Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    private fun acquireLocks() {
        if (!wakeLock.isHeld) wakeLock.acquire(12 * 60 * 60 * 1000L)
        if (!wifiLowLatency.isHeld) wifiLowLatency.acquire()
        if (!wifiHighPerf.isHeld) wifiHighPerf.acquire()
    }

    private fun releaseLocks() {
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLowLatency.isHeld) wifiLowLatency.release()
        if (wifiHighPerf.isHeld) wifiHighPerf.release()
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_mic), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun notify(rt: Runtime) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(rt))
    }

    private fun buildNotification(rt: Runtime): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = describe(this, rt, muted)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (muted) R.drawable.ic_stat_muted else R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .addAction(0, getString(if (muted) R.string.unmute else R.string.mute), serviceIntent(this, ACTION_TOGGLE_MUTE))
            .addAction(0, getString(R.string.stop), serviceIntent(this, ACTION_STOP))
            .build()
    }

    companion object {
        private const val CHANNEL = "mic"
        private const val NOTIFICATION_ID = 1
        private const val STANDBY_GRACE_MS = 3000L
        const val ACTION_START = "dev.nottzaid.earshot.START"
        const val ACTION_STOP = "dev.nottzaid.earshot.STOP"
        const val ACTION_TOGGLE_MUTE = "dev.nottzaid.earshot.TOGGLE_MUTE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MicService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MicService::class.java).setAction(ACTION_STOP))
        }

        fun toggleMute(context: Context) {
            context.startService(Intent(context, MicService::class.java).setAction(ACTION_TOGGLE_MUTE))
        }

        private fun serviceIntent(context: Context, action: String) = PendingIntent.getService(
            context, action.hashCode(), Intent(context, MicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** Title and one-line detail shared by the notification and the tile. */
        fun describe(context: Context, rt: Runtime, muted: Boolean): Pair<String, String> {
            val pc = rt.pcName ?: context.getString(R.string.your_pc)
            return when {
                rt.phase == Phase.Off -> context.getString(R.string.app_name) to context.getString(R.string.status_off)
                rt.problem == Problem.MicUnavailable -> context.getString(R.string.status_mic_busy) to context.getString(R.string.status_mic_busy_detail)
                rt.phase == Phase.Live && muted -> context.getString(R.string.status_muted) to context.getString(R.string.detail_to, pc)
                rt.phase == Phase.Live -> context.getString(R.string.status_live) to listeners(context, rt)
                rt.phase == Phase.Standby -> context.getString(R.string.status_standby) to context.getString(R.string.detail_standby, pc)
                rt.problem == Problem.PairedElsewhere -> context.getString(R.string.status_searching) to context.getString(R.string.detail_paired_elsewhere, pc)
                else -> context.getString(R.string.status_searching) to context.getString(R.string.detail_searching)
            }
        }

        fun listeners(context: Context, rt: Runtime): String {
            val pc = rt.pcName ?: context.getString(R.string.your_pc)
            val apps = rt.pcState.apps
            return when {
                !rt.connected -> context.getString(R.string.detail_to, pc)
                apps.isEmpty() -> context.getString(R.string.detail_nobody, pc)
                apps.size == 1 -> context.getString(R.string.detail_one, apps[0])
                apps.size == 2 -> context.getString(R.string.detail_two, apps[0], apps[1])
                else -> context.getString(R.string.detail_many, apps[0], apps.size - 1)
            }
        }
    }
}

private fun NativeEngine.readStatusState(): Int = EngineStatus().refresh().state
