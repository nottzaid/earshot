package dev.nottzaid.earshot.service

import dev.nottzaid.earshot.net.FoundPc
import dev.nottzaid.earshot.net.PcState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase {
    /** Service not running. */
    Off,
    /** Looking for the PC on the network. */
    Searching,
    /** Connected; mic closed until an app on the PC listens. */
    Standby,
    /** Mic open and streaming. */
    Live,
}

enum class Problem { None, NoPc, PairedElsewhere, MicUnavailable, NetworkUnavailable }

data class Runtime(
    val phase: Phase = Phase.Off,
    val problem: Problem = Problem.None,
    val pc: FoundPc? = null,
    val pcName: String? = null,
    val connected: Boolean = false,
    val pcState: PcState = PcState(),
    val rttMs: Float? = null,
    val jitterMs: Float? = null,
)

/** Process-wide runtime state the service publishes and the UI and tile observe. */
object Earshot {
    private val _runtime = MutableStateFlow(Runtime())
    val runtime: StateFlow<Runtime> = _runtime.asStateFlow()

    internal fun update(f: (Runtime) -> Runtime) = _runtime.update(f)
}
