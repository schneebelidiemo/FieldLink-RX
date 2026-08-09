package ch.fieldlink.rx.sdr

internal object NativeRtlSdrBridge {
    init {
        System.loadLibrary("fieldlink_sdr")
    }

    external fun open(fileDescriptor: Int, devicePath: String): Long
    external fun lastOpenError(): Int
    external fun configure(
        handle: Long,
        frequencyHz: Long,
        sampleRateHz: Int,
        automaticGain: Boolean,
        manualGainPercent: Int,
        ppmCorrection: Int,
    ): Int
    external fun run(handle: Long, listener: IqListener): Int
    external fun setFrequency(handle: Long, frequencyHz: Long): Int
    external fun setGain(handle: Long, automaticGain: Boolean, manualGainPercent: Int): Int
    external fun setPpm(handle: Long, ppmCorrection: Int): Int
    external fun cancel(handle: Long)
    external fun close(handle: Long)

    fun interface IqListener {
        fun onIqSamples(samples: ByteArray)
    }
}
