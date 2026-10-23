package dev.nottzaid.earshot.audio

/** The capture path: oboe::InputPreset::Unprocessed, the mic untouched and full range. */
const val CAPTURE_PRESET = 9

/**
 * The network link: as little delay as stays clean. The FEC block (6 packets of 2.5 ms) fits
 * well inside the PC's jitter buffer (LATENCY_MS in earshotd), so repair packets arrive while
 * they can still help.
 */
object Link {
    const val PACKET_US = 2500
    const val FEC_SOURCE = 6
    const val FEC_REPAIR = 3
}
