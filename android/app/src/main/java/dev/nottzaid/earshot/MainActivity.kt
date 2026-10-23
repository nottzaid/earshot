package dev.nottzaid.earshot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.nottzaid.earshot.audio.EngineStatus
import dev.nottzaid.earshot.audio.Mics
import dev.nottzaid.earshot.audio.NativeEngine
import dev.nottzaid.earshot.data.Prefs
import dev.nottzaid.earshot.net.FoundPc
import dev.nottzaid.earshot.service.Earshot
import dev.nottzaid.earshot.service.MicService
import dev.nottzaid.earshot.service.Phase
import dev.nottzaid.earshot.service.Problem
import dev.nottzaid.earshot.service.Runtime
import dev.nottzaid.earshot.ui.Diagnostics
import dev.nottzaid.earshot.ui.EarshotTheme
import dev.nottzaid.earshot.ui.MainScreen
import dev.nottzaid.earshot.ui.MainUi
import dev.nottzaid.earshot.ui.Meters
import dev.nottzaid.earshot.ui.OrbLook
import dev.nottzaid.earshot.ui.PillDot
import dev.nottzaid.earshot.ui.SettingsSheet
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { EarshotTheme { App() } }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = context.settings
    val prefs by settings.prefs.collectAsStateWithLifecycle(initialValue = null)
    val runtime by Earshot.runtime.collectAsStateWithLifecycle()
    val mics by remember { Mics.watch(context) }.collectAsStateWithLifecycle(initialValue = Mics.list(context))
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }
    var runsLocked by remember { mutableStateOf(runsLocked(context)) }
    val meters = remember { Meters() }
    var diag by remember { mutableStateOf(Diagnostics(null, null, false, false, 0, 0, false)) }

    // First start only: without the exemption, the link dies a few minutes after the screen goes off.
    fun start() {
        MicService.start(context)
        if (!runsLocked(context) && prefs?.askedBackground == false) {
            askToRunLocked(context)
            scope.launch { settings.setAskedBackground() }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            micDenied = false
            start()
        } else {
            micDenied = true
        }
    }

    // Meters at display rate, straight from the engine; draw-phase reads keep this off the recomposer.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { runsLocked = runsLocked(context) } // back from the system dialog
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val status = EngineStatus()
            var last = 0L
            var orb = 0f
            var frames = 0
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                    last = now
                    status.refresh()
                    val rms = normalize(status.rms)
                    val peak = normalize(status.peak)
                    meters.rms.floatValue = rms
                    meters.peak.floatValue = peak
                    // Clip flash eases out rather than blinking off.
                    val clipTarget = if (status.clipping) 1f else 0f
                    meters.clipping.floatValue += (clipTarget - meters.clipping.floatValue) * (1 - exp(-dt / 0.08f))
                    // The orb breathes with the voice: quick to rise, slow to settle.
                    val tau = if (rms > orb) 0.05f else 0.35f
                    orb += (rms - orb) * (1 - exp(-dt / tau))
                    meters.orbLevel.floatValue = orb
                    if (++frames % 30 == 0) {
                        val rt = Earshot.runtime.value
                        diag = Diagnostics(rt.rttMs, rt.jitterMs, status.mmap, status.exclusive, status.burstFrames, status.overruns,
                            status.state == NativeEngine.STATE_RUNNING)
                    }
                }
            }
        }
    }

    val p = prefs ?: return
    val ui = describe(runtime, p, micDenied)

    MainScreen(
        ui = ui,
        prefs = p,
        mics = mics,
        meters = meters,
        onOrb = {
            when {
                runtime.phase != Phase.Off -> MicService.toggleMute(context)
                micDenied && !shouldAsk(context) -> context.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
                hasMic(context) -> start()
                else -> permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        },
        onStop = { MicService.stop(context) },
        onMic = { scope.launch { settings.setMic(it.key) } },
        onGainPreview = { NativeEngine.setGainDb(it) },
        onGainCommit = { scope.launch { settings.setGain(it) } },
        onOpenSettings = { showSettings = true },
        onPair = { MicService.pair(context, it.id) },
    )

    if (showSettings) {
        SettingsSheet(
            prefs = p,
            runtime = runtime,
            diagnostics = diag,
            onDismiss = { showSettings = false },
            onLatency = { scope.launch { settings.setLatency(it) } },
            onSmartStandby = { scope.launch { settings.setSmartStandby(it) } },
            onRumble = { scope.launch { settings.setRumble(it) } },
            onLevelControl = { scope.launch { settings.setLevelControl(it) } },
            onEchoCancel = { scope.launch { settings.setEchoCancel(it) } },
            onDefaultMic = { scope.launch { settings.setDefaultMic(it) } },
            runsLocked = runsLocked,
            onRunsLocked = { on ->
                // Granting has a system dialog; revoking only has the system list.
                if (on) askToRunLocked(context)
                else context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            onForget = {
                // Running: the service tells the PC to forget us too. Off: only this phone forgets.
                if (runtime.phase != Phase.Off) MicService.forget(context) else scope.launch { settings.forgetPc() }
            },
        )
    }
}

private fun hasMic(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun runsLocked(context: android.content.Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun askToRunLocked(context: android.content.Context) = context.startActivity(
    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null)),
)

private fun shouldAsk(context: android.content.Context) =
    (context as? ComponentActivity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true

/** Linear amplitude -> 0..1 over a -70..0 dBFS window (low enough that a quiet room still moves). */
private fun normalize(linear: Float): Float {
    if (linear <= 1e-6f) return 0f
    val db = 20f * log10(linear)
    return max(0f, (db + 70f) / 70f).coerceAtMost(1f)
}

private fun describe(rt: Runtime, p: Prefs, micDenied: Boolean): MainUi {
    val pc = rt.pcName ?: "your PC"
    val apps = rt.pcState.apps
    val listeners = when {
        apps.isEmpty() -> null
        apps.size == 1 -> "${apps[0]} is listening"
        apps.size == 2 -> "${apps[0]} and ${apps[1]} are listening"
        else -> "${apps[0]} and ${apps.size - 1} more are listening"
    }
    val pill = when {
        rt.phase == Phase.Off -> rt.pcName ?: "Not connected"
        rt.connected -> rt.rttMs?.let { "$pc · ${"%.0f".format(it)} ms" } ?: pc
        rt.problem == Problem.NeedsPairing -> "Not paired"
        else -> "Searching"
    }
    val dot = when {
        rt.phase == Phase.Off -> PillDot.Off
        rt.connected -> PillDot.Connected
        rt.problem == Problem.Unverified || rt.problem == Problem.NoWifi -> PillDot.Problem
        else -> PillDot.Searching
    }
    val muteLabel = if (p.muted) "Unmute" else "Mute"
    fun ui(look: OrbLook, title: String, detail: String, orbLabel: String = muteLabel, pairable: List<FoundPc> = emptyList()) =
        MainUi(look, title, detail, pill, dot, rt.phase != Phase.Off, orbLabel, pairable)
    // Muted overrides the orb everywhere the mic could be open, so it never looks live when it isn't.
    fun quiet(look: OrbLook) = if (p.muted) OrbLook.Muted else look

    return when {
        rt.phase == Phase.Off && micDenied ->
            ui(OrbLook.Off, "Mic access needed", "Earshot can't hear anything without it. Tap to allow.", "Allow microphone")
        rt.phase == Phase.Off -> ui(OrbLook.Off, "Off", "Tap to stream this phone's mic to $pc", "Start")
        rt.problem == Problem.MicUnavailable ->
            ui(OrbLook.Problem, "Mic unavailable", "Another app is holding the microphone. Retrying…")
        rt.problem == Problem.NoWifi && !rt.connected ->
            ui(quiet(OrbLook.Searching), "No Wi-Fi", "Earshot only streams over Wi-Fi. Connect to the same network as $pc.")
        rt.phase == Phase.Live && p.muted ->
            ui(OrbLook.Muted, "Muted", if (rt.connected) "Nothing reaches $pc until you unmute" else "Reconnecting to $pc…")
        rt.phase == Phase.Live ->
            ui(OrbLook.Live, "Live", when {
                !rt.connected -> "Streaming · reconnecting to $pc…"
                listeners != null -> listeners
                else -> "Streaming to $pc · no app is listening"
            })
        rt.phase == Phase.Standby ->
            ui(quiet(OrbLook.Standby), if (p.muted) "Muted" else "Standby", "Opens as soon as an app on $pc uses the mic")
        rt.problem == Problem.NeedsPairing -> {
            val free = rt.found.filter { it.pairable }
            when {
                free.isNotEmpty() -> ui(quiet(OrbLook.Searching), "Pair with your PC", "Choose the PC this phone should be a mic for.", pairable = free)
                rt.found.isNotEmpty() -> ui(quiet(OrbLook.Searching), "Already paired",
                    "${rt.found.first().name} trusts another phone. Run earshotd unpair on it to switch.")
                else -> ui(quiet(OrbLook.Searching), "Looking for PCs", "Start earshotd on your PC, on this Wi-Fi.")
            }
        }
        rt.problem == Problem.PcTaken ->
            ui(quiet(OrbLook.Searching), "Already paired", "That PC trusts another phone. Run earshotd unpair on it to switch.")
        rt.problem == Problem.Unverified ->
            ui(OrbLook.Problem, "Can't verify $pc", "Something answered as $pc but couldn't prove it. Not connecting.")
        else -> ui(quiet(OrbLook.Searching), "Searching", "Looking for $pc on this Wi-Fi. Is earshotd running?")
    }
}
