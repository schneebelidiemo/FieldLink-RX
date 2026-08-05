package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import ch.fieldlink.rx.protocol.Crc32
import ch.fieldlink.rx.protocol.FieldLinkCipher
import ch.fieldlink.rx.protocol.FieldLinkCrypto
import ch.fieldlink.rx.protocol.FieldLinkFec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class FieldLinkStreamDecoderTest {
    @Test
    fun `recognizes and decrypts desktop-compatible fast audio`() {
        val password = "0123456789abcdef"
        val messageId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val plaintext = """{"version":1,"kind":"message","callsign":"HB9ABC","text":"TEST OK"}"""
            .toByteArray(Charsets.UTF_8)
        val envelope = FieldLinkCrypto.seal(
            plaintext = plaintext,
            messageId = messageId,
            cipher = FieldLinkCipher.AES_256_GCM,
            password = password.toCharArray(),
            compressed = false,
        )
        val payloads = envelope.asList().chunked(96).map { it.toByteArray() }
        val messages = mutableListOf<DecodedMessage>()
        val decoder = FieldLinkStreamDecoder(
            password = password.toCharArray(),
            selectedMode = DecodeMode.FIELDLINK_FAST,
            emit = messages::add,
        )

        payloads.forEachIndexed { index, payload ->
            val fixedBlock = packetBlock(messageId, index, payloads.size, payload)
            val audio = modulateFast(FieldLinkFec.encode(fixedBlock))
            audio.asList().chunked(2_048).forEach { chunk ->
                decoder.process(chunk.toFloatArray(), null)
            }
        }
        decoder.close()

        val decoded = messages.firstOrNull { it.text == "TEST OK" }
        assertNotNull(decoded)
        assertEquals("HB9ABC", decoded?.callsign)
        assertFalse(decoded?.encryptedWithoutKey ?: true)
    }

    private fun packetBlock(
        messageId: ByteArray,
        index: Int,
        count: Int,
        payload: ByteArray,
    ): ByteArray {
        val header = ByteBuffer.allocate(18)
            .order(ByteOrder.BIG_ENDIAN)
            .put('F'.code.toByte())
            .put('P'.code.toByte())
            .put(1.toByte())
            .put(1.toByte()) // FieldLink Fast
            .put(messageId)
            .putShort(index.toShort())
            .putShort(count.toShort())
            .putShort(payload.size.toShort())
            .array()
        val content = header + payload
        val packet = content + ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(Crc32.calculate(content).toInt())
            .array()
        return ByteArray(FieldLinkFec.FIXED_BLOCK_LENGTH).also { block ->
            block[0] = (packet.size ushr 8).toByte()
            block[1] = packet.size.toByte()
            packet.copyInto(block, destinationOffset = 2)
        }
    }

    private fun modulateFast(bits: ByteArray): FloatArray {
        val tones = 16
        val bitsPerSymbol = 4
        val symbolSamples = 480
        val leadSamples = 7_200
        val preamble = IntArray(32).also { values ->
            for (index in 0 until 24) values[index] = if (index % 2 == 0) 0 else tones - 1
            intArrayOf(1, 14, 2, 13, 3, 12, 4, 11).copyInto(values, 24)
        }
        val data = IntArray((bits.size + bitsPerSymbol - 1) / bitsPerSymbol)
        for (symbol in data.indices) {
            var value = 0
            repeat(bitsPerSymbol) { bit ->
                value = (value shl 1) or
                    (bits.getOrElse(symbol * bitsPerSymbol + bit) { 0.toByte() }.toInt() and 1)
            }
            data[symbol] = value
        }
        val symbols = preamble + data
        val samples = FloatArray(leadSamples * 2 + symbols.size * symbolSamples)
        var offset = leadSamples
        var phase = 0.0
        symbols.forEach { tone ->
            val frequency = 1_500.0 + (tone - (tones - 1) / 2.0) * 100.0
            val phaseStep = 2.0 * PI * frequency / 48_000.0
            repeat(symbolSamples) { sample ->
                val edge = min(1.0, min(sample / 12.0, (symbolSamples - sample - 1) / 12.0))
                samples[offset++] = (sin(phase) * 0.64 * max(0.2, edge)).toFloat()
                phase = (phase + phaseStep) % (2.0 * PI)
            }
        }
        return samples
    }
}
