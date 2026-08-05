package ch.fieldlink.rx.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FieldLinkCryptoTest {
    private val messageId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val password = "FieldLink-Testpasswort-2026".toCharArray()
    private val payload = "Grüezi 🌍 – Unicode".toByteArray()

    @Test
    fun roundTripsAllCipherSuites() {
        for (cipher in FieldLinkCipher.entries) {
            val envelope = FieldLinkCrypto.seal(payload, messageId, cipher, password, compressed = false)
            val opened = FieldLinkCrypto.open(envelope, messageId, password)
            assertArrayEquals(payload, opened.plaintext)
        }
    }

    @Test
    fun rejectsWrongPassword() {
        val envelope = FieldLinkCrypto.seal(payload, messageId, FieldLinkCipher.AES_256_GCM, password, false)
        assertThrows(EncryptedFieldLinkMessageException::class.java) {
            FieldLinkCrypto.open(envelope, messageId, "Falsches-Passwort-1234".toCharArray())
        }
    }
}

