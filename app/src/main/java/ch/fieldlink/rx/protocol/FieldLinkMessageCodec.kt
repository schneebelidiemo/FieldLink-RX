package ch.fieldlink.rx.protocol

import ch.fieldlink.rx.model.Coordinates
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater

data class FieldLinkBody(
    val kind: String,
    val callsign: String,
    val text: String?,
    val coordinates: Coordinates?,
)

sealed interface FieldLinkDecodeResult {
    data class Success(val body: FieldLinkBody, val cipher: FieldLinkCipher) : FieldLinkDecodeResult
    data class EncryptedWithoutKey(val cipher: FieldLinkCipher?) : FieldLinkDecodeResult
    data class Damaged(val reason: String) : FieldLinkDecodeResult
}

object FieldLinkMessageCodec {
    private const val MAX_DECOMPRESSED_BYTES = 256 * 1024

    fun decode(
        envelopeBytes: ByteArray,
        messageId: ByteArray,
        password: CharArray,
    ): FieldLinkDecodeResult {
        val cipherHint = FieldLinkCipher.entries.firstOrNull { it.id == envelopeBytes.getOrNull(5)?.toInt() }
        val opened = try {
            FieldLinkCrypto.open(envelopeBytes, messageId, password)
        } catch (_: EncryptedFieldLinkMessageException) {
            return FieldLinkDecodeResult.EncryptedWithoutKey(cipherHint)
        } catch (error: Throwable) {
            return FieldLinkDecodeResult.Damaged(error.message ?: "Damaged FieldLink envelope.")
        }

        return try {
            val jsonBytes = if (opened.compressed) inflateRaw(opened.plaintext) else opened.plaintext
            val json = JSONObject(jsonBytes.toString(StandardCharsets.UTF_8))
            require(json.getInt("version") == 1) { "Unsupported FieldLink message version." }
            val callsign = json.getString("callsign")
            require(callsign.matches(Regex("^[A-Za-z0-9/]{3,16}$"))) { "Invalid FieldLink callsign." }
            val text = json.optString("text").takeIf { json.has("text") }
            require(text == null || text.codePointCount(0, text.length) <= 500) { "FieldLink text is too long." }
            val position = json.optJSONObject("position")?.let { value ->
                Coordinates(value.getDouble("latitude"), value.getDouble("longitude")).also {
                    require(it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0) {
                        "Invalid FieldLink coordinates."
                    }
                }
            }
            FieldLinkDecodeResult.Success(
                body = FieldLinkBody(
                    kind = json.getString("kind"),
                    callsign = callsign,
                    text = text,
                    coordinates = position,
                ),
                cipher = opened.cipher,
            )
        } catch (error: Throwable) {
            FieldLinkDecodeResult.Damaged(error.message ?: "Damaged FieldLink message.")
        } finally {
            opened.plaintext.fill(0)
        }
    }

    private fun inflateRaw(compressed: ByteArray): ByteArray {
        val inflater = Inflater(true)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        try {
            inflater.setInput(compressed)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) throw DataFormatException("Incomplete DEFLATE stream.")
                } else {
                    output.write(buffer, 0, count)
                    require(output.size() <= MAX_DECOMPRESSED_BYTES) { "FieldLink payload is too large." }
                }
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

