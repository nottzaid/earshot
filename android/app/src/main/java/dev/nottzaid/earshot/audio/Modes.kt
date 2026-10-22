package dev.nottzaid.earshot.audio

/** How much the phone processes the sound before it leaves. Values are oboe::InputPreset. */
enum class SoundMode(val preset: Int, val title: String, val blurb: String) {
    Studio(9 /* Unprocessed */, "Studio", "Untouched, full range"),
    Voice(10 /* VoicePerformance */, "Voice", "Natural, lowest latency"),
    Call(7 /* VoiceCommunication */, "Call", "Echo and noise cancelled"),
}

/**
 * Network latency vs. robustness. The phone owns packet size and FEC block,
 * the PC owns the jitter buffer target (LATENCY_MS in earshotd); both use [key].
 */
enum class LatencyMode(
    val key: String,
    val title: String,
    val targetMs: Int,
    val packetUs: Int,
    val fecSource: Int,
    val fecRepair: Int,
    val blurb: String,
) {
    Tight("tight", "Tight", 30, 2500, 6, 3, "Lowest delay, needs a clean signal"),
    Balanced("balanced", "Balanced", 50, 2500, 10, 5, "Low delay, rides out Wi-Fi hiccups"),
    Steady("steady", "Steady", 100, 5000, 16, 8, "For crowded or distant Wi-Fi"),
}
