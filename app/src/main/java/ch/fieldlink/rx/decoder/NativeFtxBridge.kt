package ch.fieldlink.rx.decoder

internal data class NativeFtxResult(
    val text: String,
    val frequencyHz: Double,
    val score: Int,
    val timeSeconds: Double,
)

internal object NativeFtxBridge {
    init {
        System.loadLibrary("fieldlink_ftx")
    }

    fun decode(samples: FloatArray, mode: DecodeModeKey, maximumMessages: Int): List<NativeFtxResult> =
        decodeNative(samples, mode.nativeValue, maximumMessages).mapNotNull(::parse)

    private external fun decodeNative(samples: FloatArray, protocol: Int, maximumMessages: Int): Array<String>

    private fun parse(record: String): NativeFtxResult? {
        val fields = record.split('\u001f')
        if (fields.size != 4) return null
        return NativeFtxResult(
            text = fields[0],
            frequencyHz = fields[1].toDoubleOrNull() ?: return null,
            score = fields[2].toIntOrNull() ?: return null,
            timeSeconds = fields[3].toDoubleOrNull() ?: return null,
        )
    }
}

internal enum class DecodeModeKey(val nativeValue: Int) {
    FT4(0),
    FT8(1),
}

