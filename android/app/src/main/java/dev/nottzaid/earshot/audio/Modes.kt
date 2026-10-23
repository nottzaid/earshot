package dev.nottzaid.earshot.audio

/** The capture path: oboe::InputPreset::Unprocessed, the mic untouched and full range. */
const val CAPTURE_PRESET = 9

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
    // The FEC block (source packets x packet length) stays at or under half the PC's target,
    // so repair packets arrive while they can still help.
    Steady("steady", "Steady", 100, 5000, 10, 6, "For crowded or distant Wi-Fi"),
}
