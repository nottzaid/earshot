package dev.nottzaid.earshot.audio

/** JNI bridge to engine.cpp: Oboe capture -> DSP -> ROC sender. */
object NativeEngine {
    init {
        System.loadLibrary("earshot")
    }

    const val STATE_STOPPED = 0
    const val STATE_RUNNING = 1
    const val STATE_CAPTURE_FAILED = -1
    const val STATE_LINK_FAILED = -2

    /** Returns one of the STATE_ constants. */
    external fun start(host: String, deviceId: Int, preset: Int, packetUs: Int, fecSource: Int, fecRepair: Int): Int
    external fun stop()
    external fun setGainDb(db: Float)
    external fun setMuted(muted: Boolean)

    /** Fills [out] (size >= STATUS_SIZE); see the Status accessors. */
    external fun readStatus(out: FloatArray)

    const val STATUS_SIZE = 10
}

/** Typed view over the array [NativeEngine.readStatus] fills. */
@JvmInline
value class EngineStatus(val raw: FloatArray = FloatArray(NativeEngine.STATUS_SIZE)) {
    val peak get() = raw[0]
    val rms get() = raw[1]
    val clipping get() = raw[2] > 0f
    val receivers get() = raw[3].toInt()
    val mmap get() = raw[4] > 0f
    val exclusive get() = raw[5] > 0f
    val burstFrames get() = raw[6].toInt()
    val overruns get() = raw[7].toInt()
    val state get() = raw[8].toInt()
    val sessionId get() = raw[9].toInt()

    fun refresh(): EngineStatus {
        NativeEngine.readStatus(raw)
        return this
    }
}
