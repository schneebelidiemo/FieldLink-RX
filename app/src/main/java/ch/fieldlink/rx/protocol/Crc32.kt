package ch.fieldlink.rx.protocol

object Crc32 {
    private val table = LongArray(256) { index ->
        var value = index.toLong()
        repeat(8) {
            value = if ((value and 1L) != 0L) 0xedb8_8320L xor (value ushr 1) else value ushr 1
        }
        value and 0xffff_ffffL
    }

    fun calculate(bytes: ByteArray): Long {
        var value = 0xffff_ffffL
        for (byte in bytes) {
            val tableIndex = ((value xor (byte.toInt() and 0xff).toLong()) and 0xff).toInt()
            value = table[tableIndex] xor (value ushr 8)
        }
        return (value xor 0xffff_ffffL) and 0xffff_ffffL
    }
}

