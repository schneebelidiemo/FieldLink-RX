package ch.fieldlink.rx.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FieldLinkFecTest {
    @Test
    fun roundTripsFixedBlock() {
        val block = ByteArray(FieldLinkFec.FIXED_BLOCK_LENGTH) { index -> (index * 13 + 9).toByte() }
        assertArrayEquals(block, FieldLinkFec.decode(FieldLinkFec.encode(block)))
    }

    @Test
    fun correctsSparseBitErrors() {
        val block = ByteArray(FieldLinkFec.FIXED_BLOCK_LENGTH) { index -> (index * 29 + 3).toByte() }
        val encoded = FieldLinkFec.encode(block)
        for (index in encoded.indices step 173) encoded[index] = (encoded[index].toInt() xor 1).toByte()
        assertArrayEquals(block, FieldLinkFec.decode(encoded))
    }
}

