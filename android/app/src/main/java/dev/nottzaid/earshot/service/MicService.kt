package dev.nottzaid.earshot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import dev.nottzaid.earshot.audio.CAPTURE_PRESET
import dev.nottzaid.earshot.data.Prefs
import dev.nottzaid.earshot.net.ControlEvent
import dev.nottzaid.earshot.net.Discovery
import dev.nottzaid.earshot.net.FoundPc
import dev.nottzaid.earshot.net.HandshakeException
import dev.nottzaid.earshot.net.PROTO_VERSION
import dev.nottzaid.earshot.net.Trust
import dev.nottzaid.earshot.net.jsonOf
import dev.nottzaid.earshot.net.runControlSession
import dev.nottzaid.earshot.settings
import dev.nottzaid.earshot.tile.MicTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** What the engine should be doing; null means mic closed. */
private data class EngineConfig(
    val host: String,
    val micId: Int,
    val latency: LatencyMode,
)

class MicService : LifecycleService() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineThread = Dispatchers.IO.limitedParallelism(1)
    private val outgoing = Channel<JSONObject>(Channel.BUFFERED)
    private val pcListening = MutableStateFlow(false)
    private val connectedPc = MutableStateFlow<FoundPc?>(null)
    /** True once the control link has been gone long enough that streaming should stop. */
    private val linkLost = MutableStateFlow(false)
    private val pairTarget = MutableStateFlow<String?>(null)
    private val forgetRequested = MutableStateFlow(false)
    private lateinit var desired: StateFlow<EngineConfig?>

    @Volatile private var engineConfig: EngineConfig? = null
    @Volatile private var muted = false
    @Volatile private var destroyed = false
    /** The PC link holds the CPU awake: asleep, the phone hears the PC tens of seconds late, or not at all. */
    @Volatile private var linkHeld = false
    private val muteSeq = AtomicInteger(0)
    private var started = false

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLowLatency: WifiManager.WifiLock
    private lateinit var wifiHighPerf: WifiManager.WifiLock
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

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
            ACTION_TOGGLE_MUTE -> {
                lifecycleScope.launch { setMuted(!settings.prefs.first().muted) }
                if (!started) stopSelf() // a stray tap from the tile or notification: don't open the mic for it
            }
            ACTION_PAIR -> pairTarget.value = intent.getStringExtra(EXTRA_PC)
            ACTION_FORGET -> forgetRequested.value = true
        }
        if (!started && intent?.action in setOf(ACTION_START, ACTION_PAIR)) {
            started = true
            startForeground(NOTIFICATION_ID, buildNotification(Earshot.runtime.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            Earshot.update { it.copy(phase = Phase.Searching, problem = Problem.None) }
            bindToWifi()
            run()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        // Stop synchronously: a queued stop would be cancelled with the scope and leave the mic open.
        runBlocking {
            withTimeoutOrNull(3000) {
                withContext(engineThread) {
                    NativeEngine.stop()
                    engineConfig = null
                }
            }
        }
        releaseLocks()
        unbindWifi()
        Earshot.update { Runtime(pcName = it.pcName, muted = it.muted) }
        MicTile.refresh(this)
        super.onDestroy()
    }

    private fun run() {
        val prefs = settings.prefs.stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        val mics = Mics.watch(this).stateIn(lifecycleScope, SharingStarted.Eagerly, Mics.list(this))

        lifecycleScope.launch { connectLoop() }

        // Live engine parameters: cheap atomics, applied on every change.
        lifecycleScope.launch {
            prefs.filterNotNull().collect { p ->
                muted = p.muted
                Earshot.update { it.copy(muted = p.muted) }
                NativeEngine.setGainDb(p.effectiveGainDb)
                NativeEngine.setMuted(p.muted)
                NativeEngine.setHighPass(p.rumbleFilter)
            }
        }

        // Smart standby: an app on the PC opening the mic opens ours; closing it closes ours a little later,
        // so an app that briefly reopens its input doesn't bounce the phone's mic.
        lifecycleScope.launch {
            var releaseJob: Job? = null
            Earshot.runtime.map { it.connected && it.pcState.listening }.distinctUntilChanged().collect { listening ->
                releaseJob?.cancel()
                if (listening) pcListening.value = true
                else releaseJob = launch {
                    delay(STANDBY_GRACE_MS)
                    pcListening.value = false
                }
            }
        }

        // Losing the PC: keep streaming through a short blip, then close the mic.
        lifecycleScope.launch {
            var lostJob: Job? = null
            connectedPc.map { it != null }.distinctUntilChanged().collect { connected ->
                lostJob?.cancel()
                if (connected) {
                    linkLost.value = false
                    linkHeld = true
                    syncWakeLock()
                } else lostJob = launch {
                    delay(LINK_LOST_MS) // still awake here, so the reconnect isn't left to the phone's next wakeup
                    linkLost.value = true
                    linkHeld = false
                    syncWakeLock()
                }
            }
        }

        // The engine follows the desired config; restarts happen on one thread, in order.
        desired = combine(prefs.filterNotNull(), mics, connectedPc, pcListening, linkLost) { p, m, pc, listening, lost ->
            desiredConfig(p, m, pc, listening, lost)
        }.distinctUntilChanged().stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        lifecycleScope.launch { desired.collect { applyEngine(it) } }

        // Watch the engine for failures, keep the wake lock fresh, and keep notification and tile honest.
        lifecycleScope.launch {
            val status = EngineStatus()
            var lastShown: Triple<Phase, Boolean, Problem>? = null
            var ticks = 0
            while (isActive) {
                status.refresh()
                if (engineConfig != null && status.state < 0) {
                    Earshot.update { it.copy(problem = if (status.state == NativeEngine.STATE_CAPTURE_FAILED) Problem.MicUnavailable else Problem.NoWifi) }
                    delay(1500)
                    applyEngine(desired.value, force = true) // whatever is wanted *now*, not what failed
                }
                if (++ticks % (30 * 60 * 4) == 0 && wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_MS) // renew: a link can outlive one timeout
                val rt = Earshot.runtime.value
                val shown = Triple(rt.phase, muted, rt.problem)
                if (shown != lastShown) {
                    lastShown = shown
                    notify(rt)
                    MicTile.refresh(this@MicService)
                }
                delay(250)
            }
        }

        // Settings the PC needs to hear about.
        lifecycleScope.launch {
            prefs.filterNotNull().map { it.latency to it.defaultMic }.distinctUntilChanged().collect { (lat, def) ->
                outgoing.trySend(jsonOf("t" to "set", "mode" to lat.key, "default" to def))
            }
        }
    }

    private fun desiredConfig(p: Prefs, mics: List<Mic>, pc: FoundPc?, listening: Boolean, lost: Boolean): EngineConfig? {
        if (lost) return null
        // A control blip shorter than LINK_LOST_MS doesn't stop audio that is already flowing.
        val host = pc?.address ?: engineConfig?.host ?: return null
        if (p.smartStandby && !listening) return null
        val mic = Mics.resolve(mics, p.micKey) ?: return null
        return EngineConfig(host, mic.id, p.latency)
    }

    private suspend fun applyEngine(cfg: EngineConfig?, force: Boolean = false) = withContext(engineThread) {
        if (destroyed) return@withContext
        if (!force && cfg == engineConfig && (cfg == null || EngineStatus().refresh().state == NativeEngine.STATE_RUNNING)) return@withContext
        NativeEngine.stop()
        engineConfig = cfg
        if (cfg == null) {
            releaseLocks()
            Earshot.update {
                it.copy(
                    phase = if (it.connected) Phase.Standby else Phase.Searching,
                    problem = if (it.problem == Problem.MicUnavailable) Problem.None else it.problem,
                )
            }
            return@withContext
        }
        val p = settings.prefs.first()
        NativeEngine.setGainDb(p.effectiveGainDb)
        NativeEngine.setMuted(p.muted)
        NativeEngine.setHighPass(p.rumbleFilter)
        val state = NativeEngine.start(
            cfg.host, cfg.micId, CAPTURE_PRESET,
            cfg.latency.packetUs, cfg.latency.fecSource, cfg.latency.fecRepair,
        )
        if (destroyed) {
            NativeEngine.stop()
            return@withContext
        }
        if (state == NativeEngine.STATE_RUNNING) {
            acquireLocks()
            Earshot.update { it.copy(phase = Phase.Live, problem = Problem.None) }
        } else {
            Earshot.update {
                it.copy(problem = if (state == NativeEngine.STATE_CAPTURE_FAILED) Problem.MicUnavailable else Problem.NoWifi)
            }
        }
    }

    // ------------------------------------------------------------ PC link

    private suspend fun connectLoop() {
        val deviceId = settings.deviceId()
        var backoff = 500L
        while (currentCoroutineContext().isActive) {
            val p = settings.prefs.first()
            if (forgetRequested.value) {
                forgetRequested.value = false
                settings.forgetPc()
                continue
            }
            val found = Discovery.probe(p.pcHost)
            val target: Pair<FoundPc, Trust?>? = if (p.paired) {
                found.firstOrNull { it.id == p.pcId }?.let { it to Trust(p.pcId!!, p.pcSecret!!) }
            } else {
                found.firstOrNull { it.id == pairTarget.value }?.let { it to null }
            }
            if (target == null) {
                Earshot.update {
                    it.copy(
                        connected = false,
                        found = found,
                        problem = if (p.paired) Problem.NoPc else Problem.NeedsPairing,
                        phase = if (engineConfig != null) Phase.Live else Phase.Searching,
                    )
                }
                // Unpaired: keep the list fresh while the user chooses; paired: back off while the PC is away.
                delay(if (p.paired) backoff else 1500)
                if (p.paired) backoff = (backoff * 2).coerceAtMost(4000)
                continue
            }
            backoff = 500L
            val (pc, trust) = target
            try {
                session(pc, deviceId, trust)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HandshakeException) {
                when (e.reason) {
                    // The PC no longer trusts us (earshotd unpair): our secret is dead, pair again.
                    "not-paired" -> settings.forgetPc()
                    "paired-elsewhere" -> {
                        pairTarget.value = null
                        Earshot.update { it.copy(problem = Problem.PcTaken) }
                        delay(2500)
                    }
                    "bad-proof", "wrong-pc" -> {
                        Earshot.update { it.copy(problem = Problem.Unverified) }
                        delay(4000)
                    }
                }
            } catch (_: Exception) {
                // connection refused or dropped: back to discovery
            }
            connectedPc.value = null
            Earshot.update { it.copy(connected = false, rttMs = null, jitterMs = null, phase = if (engineConfig != null) Phase.Live else Phase.Searching) }
            delay(700)
        }
    }

    private suspend fun session(pc: FoundPc, deviceId: String, trust: Trust?) {
        val p = settings.prefs.first()
        val hello = jsonOf(
            "t" to "hello", "v" to PROTO_VERSION, "id" to deviceId, "name" to deviceName(),
            "prefs" to jsonOf("mode" to p.latency.key, "default" to p.defaultMic),
        )
        while (outgoing.tryReceive().isSuccess) Unit // drop anything queued for a previous session
        var avgRtt = -1f
        var jitter = 0f
        val forgetWatch = lifecycleScope.launch {
            forgetRequested.first { it }
            outgoing.trySend(jsonOf("t" to "unpair")) // the PC forgets us too, then closes the session
        }
        try {
            runControlSession(pc, hello, trust, outgoing) { event ->
                when (event) {
                    is ControlEvent.Welcome -> {
                        settings.rememberPc(pc.address, event.pcId, event.name, event.secret)
                        pairTarget.value = null
                        // Our mute wins at connect; the sequence number lets us ignore the PC's stale echoes.
                        outgoing.trySend(jsonOf("t" to "set", "muted" to settings.prefs.first().muted, "seq" to muteSeq.incrementAndGet()))
                        connectedPc.value = pc
                        Earshot.update { it.copy(connected = true, pc = pc, pcName = event.name, problem = Problem.None, found = emptyList()) }
                    }
                    is ControlEvent.Denied -> if (event.reason == "not-paired") settings.forgetPc()
                    is ControlEvent.Unpaired -> {
                        forgetRequested.value = false
                        settings.forgetPc()
                    }
                    is ControlEvent.State -> {
                        val s = event.state
                        if (s.muteSeq >= muteSeq.get() && s.muted != muted) settings.setMuted(s.muted) // changed on the PC
                        Earshot.update { it.copy(pcState = s, phase = if (engineConfig != null) Phase.Live else Phase.Standby) }
                    }
                    is ControlEvent.Rtt -> {
                        if (avgRtt < 0) avgRtt = event.ms
                        jitter += (abs(event.ms - avgRtt) - jitter) * 0.2f
                        avgRtt += (event.ms - avgRtt) * 0.2f
                        Earshot.update { it.copy(rttMs = avgRtt, jitterMs = jitter) }
                    }
                }
            }
        } finally {
            forgetWatch.cancel()
        }
    }

    private suspend fun setMuted(muted: Boolean) {
        NativeEngine.setMuted(muted)
        settings.setMuted(muted)
        outgoing.trySend(jsonOf("t" to "set", "muted" to muted, "seq" to muteSeq.incrementAndGet()))
    }

    private fun deviceName(): String =
        Global.getString(contentResolver, Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    // ------------------------------------------------------------ Wi-Fi and locks

    /**
     * Pins every socket of this process, native ones included, to Wi-Fi. If Wi-Fi goes away
     * the stream fails instead of quietly continuing over mobile data to a private address.
     */
    private fun bindToWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                Earshot.update { if (it.problem == Problem.NoWifi) it.copy(problem = Problem.None) else it }
            }

            override fun onLost(network: Network) {
                Earshot.update { it.copy(problem = Problem.NoWifi) }
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        wifiCallback = cb
    }

    private fun unbindWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        wifiCallback = null
        cm.bindProcessToNetwork(null)
    }

    private fun acquireLocks() {
        if (destroyed) return
        syncWakeLock()
        if (!wifiLowLatency.isHeld) wifiLowLatency.acquire()
        if (!wifiHighPerf.isHeld) wifiHighPerf.acquire()
    }

    private fun releaseLocks() {
        if (wifiLowLatency.isHeld) wifiLowLatency.release()
        if (wifiHighPerf.isHeld) wifiHighPerf.release()
        syncWakeLock()
    }

    /** CPU awake while streaming or linked to the PC; the Wi-Fi locks are for streaming only. */
    @Synchronized
    private fun syncWakeLock() {
        val want = !destroyed && (linkHeld || engineConfig != null)
        if (want && !wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_MS)
        else if (!want && wakeLock.isHeld) wakeLock.release()
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
        if (destroyed) return
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
        private const val LINK_LOST_MS = 8000L
        private const val WAKE_LOCK_MS = 2 * 60 * 60 * 1000L
        const val ACTION_START = "dev.nottzaid.earshot.START"
        const val ACTION_STOP = "dev.nottzaid.earshot.STOP"
        const val ACTION_TOGGLE_MUTE = "dev.nottzaid.earshot.TOGGLE_MUTE"
        const val ACTION_PAIR = "dev.nottzaid.earshot.PAIR"
        const val ACTION_FORGET = "dev.nottzaid.earshot.FORGET"
        private const val EXTRA_PC = "pc"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MicService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MicService::class.java).setAction(ACTION_STOP))
        }

        fun toggleMute(context: Context) {
            context.startService(Intent(context, MicService::class.java).setAction(ACTION_TOGGLE_MUTE))
        }

        /** Pair with a PC the user picked from the discovered list. */
        fun pair(context: Context, pcId: String) {
            ContextCompat.startForegroundService(context, Intent(context, MicService::class.java).setAction(ACTION_PAIR).putExtra(EXTRA_PC, pcId))
        }

        /** Forget the paired PC; it is told too when connected. Only meaningful while running. */
        fun forget(context: Context) {
            context.startService(Intent(context, MicService::class.java).setAction(ACTION_FORGET))
        }

        private fun serviceIntent(context: Context, action: String) = PendingIntent.getService(
            context, action.hashCode(), Intent(context, MicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** Title and one-line detail for the notification and the tile. */
        fun describe(context: Context, rt: Runtime, muted: Boolean): Pair<String, String> {
            val pc = rt.pcName ?: context.getString(R.string.your_pc)
            return when {
                rt.phase == Phase.Off -> context.getString(R.string.app_name) to context.getString(R.string.status_off)
                rt.problem == Problem.MicUnavailable -> context.getString(R.string.status_mic_busy) to context.getString(R.string.status_mic_busy_detail)
                muted -> context.getString(R.string.status_muted) to context.getString(R.string.detail_muted, pc)
                rt.phase == Phase.Live -> context.getString(R.string.status_live) to listeners(context, rt)
                rt.phase == Phase.Standby -> context.getString(R.string.status_standby) to context.getString(R.string.detail_standby, pc)
                rt.problem == Problem.NeedsPairing -> context.getString(R.string.status_pair) to context.getString(R.string.detail_pair)
                else -> context.getString(R.string.status_searching) to context.getString(R.string.detail_searching)
            }
        }

        private fun listeners(context: Context, rt: Runtime): String {
            val pc = rt.pcName ?: context.getString(R.string.your_pc)
            val apps = rt.pcState.apps
            return when {
                !rt.connected -> context.getString(R.string.detail_reconnecting, pc)
                apps.isEmpty() -> context.getString(R.string.detail_nobody, pc)
                apps.size == 1 -> context.getString(R.string.detail_one, apps[0])
                apps.size == 2 -> context.getString(R.string.detail_two, apps[0], apps[1])
                else -> context.getString(R.string.detail_many, apps[0], apps.size - 1)
            }
        }
    }
}
