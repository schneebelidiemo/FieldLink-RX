package ch.fieldlink.rx.decoder

internal data class NativeJs8Result(
    val text: String,
    val frequencyHz: Double,
    val snr: Int,
    val quality: Float,
    val submode: Int,
    val frameType: Int,
    val timeSeconds: Double,
)

internal object NativeJs8Bridge {
    init {
        System.loadLibrary("fieldlink_js8")
    }

    fun decode(samples: FloatArray, maximumMessages: Int): List<NativeJs8Result> =
        decodeNative(samples, maximumMessages.coerceIn(1, 3)).mapNotNull(::parse)

    private external fun decodeNative(samples: FloatArray, maximumMessages: Int): Array<String>

    private fun parse(record: String): NativeJs8Result? {
        val fields = record.split('\u001f')
        if (fields.size != 7) return null
        return NativeJs8Result(
            text = fields[0],
            frequencyHz = fields[1].toDoubleOrNull() ?: return null,
            snr = fields[2].toIntOrNull() ?: return null,
            quality = fields[3].toFloatOrNull() ?: return null,
            submode = fields[4].toIntOrNull() ?: return null,
            frameType = fields[5].toIntOrNull() ?: return null,
            timeSeconds = fields[6].toDoubleOrNull() ?: return null,
        )
    }
}
