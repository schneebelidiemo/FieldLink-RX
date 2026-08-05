package ch.fieldlink.rx.protocol

import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.XChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class FieldLinkCipher(val id: Int, val nonceLength: Int) {
    NONE(0, 0),
    AES_256_GCM(1, 12),
    XCHACHA20_POLY1305(2, 24),
}

data class OpenedEnvelope(
    val cipher: FieldLinkCipher,
    val compressed: Boolean,
    val plaintext: ByteArray,
)

class EncryptedFieldLinkMessageException(cause: Throwable? = null) :
    Exception("Encrypted FieldLink message received.", cause)

object FieldLinkCrypto {
    private val magic = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 1
    private const val SALT_LENGTH = 16
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun open(bytes: ByteArray, messageId: ByteArray, password: CharArray): OpenedEnvelope {
        require(messageId.size == 8) { "FieldLink message ID must contain eight bytes." }
        require(bytes.size >= 24 && bytes.copyOfRange(0, 4).contentEquals(magic)) {
            "Unknown FieldLink envelope."
        }
        require(bytes[4].toInt() == VERSION) { "Unsupported FieldLink envelope version." }
        val cipher = FieldLinkCipher.entries.firstOrNull { it.id == (bytes[5].toInt() and 0xff) }
            ?: error("Unknown FieldLink cipher.")
        val compressed = (bytes[6].toInt() and 1) != 0
        val salt = bytes.copyOfRange(7, 7 + SALT_LENGTH)
        val nonceLengthOffset = 7 + SALT_LENGTH
        val nonceLength = bytes[nonceLengthOffset].toInt() and 0xff
        require(nonceLength == cipher.nonceLength) { "Invalid FieldLink nonce length." }
        val nonceStart = nonceLengthOffset + 1
        val nonceEnd = nonceStart + nonceLength
        require(nonceEnd <= bytes.size) { "Damaged FieldLink envelope header." }
        val header = bytes.copyOfRange(0, nonceEnd)
        val ciphertext = bytes.copyOfRange(nonceEnd, bytes.size)
        if (cipher == FieldLinkCipher.NONE) {
            return OpenedEnvelope(cipher, compressed, ciphertext)
        }
        if (password.size < 16) throw EncryptedFieldLinkMessageException()

        val key = deriveKey(password, salt)
        return try {
            val associatedData = header + messageId
            val plaintext = when (cipher) {
                FieldLinkCipher.AES_256_GCM -> decryptAes(ciphertext, key, bytes.copyOfRange(nonceStart, nonceEnd), associatedData)
                FieldLinkCipher.XCHACHA20_POLY1305 -> decryptXChaCha(ciphertext, key, bytes.copyOfRange(nonceStart, nonceEnd), associatedData)
                FieldLinkCipher.NONE -> error("Unreachable")
            }
            OpenedEnvelope(cipher, compressed, plaintext)
        } catch (error: Throwable) {
            throw EncryptedFieldLinkMessageException(error)
        } finally {
            key.fill(0)
        }
    }

    internal fun seal(
        plaintext: ByteArray,
        messageId: ByteArray,
        cipher: FieldLinkCipher,
        password: CharArray,
        compressed: Boolean,
    ): ByteArray {
        val salt = if (cipher == FieldLinkCipher.NONE) ByteArray(SALT_LENGTH) else ByteArray(SALT_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(cipher.nonceLength).also(random::nextBytes)
        val header = magic + byteArrayOf(VERSION.toByte(), cipher.id.toByte(), if (compressed) 1 else 0) +
            salt + byteArrayOf(nonce.size.toByte()) + nonce
        if (cipher == FieldLinkCipher.NONE) return header + plaintext

        val key = deriveKey(password, salt)
        return try {
            val associatedData = header + messageId
            val ciphertext = when (cipher) {
                FieldLinkCipher.AES_256_GCM -> encryptAes(plaintext, key, nonce, associatedData)
                FieldLinkCipher.XCHACHA20_POLY1305 -> encryptXChaCha(plaintext, key, nonce, associatedData)
                FieldLinkCipher.NONE -> error("Unreachable")
            }
            header + ciphertext
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(password))
        val encodedPassword = ByteArray(buffer.remaining())
        buffer.get(encodedPassword)
        return try {
            SCrypt.generate(encodedPassword, salt, 1 shl 14, 8, 1, 32)
        } finally {
            encodedPassword.fill(0)
        }
    }

    private fun decryptAes(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun encryptAes(plaintext: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun decryptXChaCha(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
        runXChaCha(false, ciphertext, key, nonce, aad)

    private fun encryptXChaCha(plaintext: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
        runXChaCha(true, plaintext, key, nonce, aad)

    private fun runXChaCha(encrypt: Boolean, input: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        val cipher = XChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(input.size))
        var length = cipher.processBytes(input, 0, input.size, output, 0)
        length += cipher.doFinal(output, length)
        return output.copyOf(length)
    }
}
