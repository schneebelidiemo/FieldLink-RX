package ch.fieldlink.rx.protocol

object FieldLinkFec {
    const val FIXED_BLOCK_LENGTH = 128
    private const val CONSTRAINT_LENGTH = 7
    private const val TAIL_BITS = CONSTRAINT_LENGTH - 1
    private const val POLYNOMIAL_A = 0x79 // 171 octal
    private const val POLYNOMIAL_B = 0x5b // 133 octal
    private const val INTERLEAVER_COLUMNS = 16
    private const val SOURCE_BIT_LENGTH = FIXED_BLOCK_LENGTH * 8
    private const val CODED_BIT_LENGTH = (SOURCE_BIT_LENGTH + TAIL_BITS) * 2
    const val INTERLEAVED_BIT_LENGTH = 2_064

    fun decode(interleaved: ByteArray): ByteArray {
        require(interleaved.size == INTERLEAVED_BIT_LENGTH) { "Invalid FieldLink FEC bit count." }
        val encoded = deinterleave(interleaved).copyOf(CODED_BIT_LENGTH)
        return bitsToBytes(viterbiDecode(encoded, SOURCE_BIT_LENGTH))
    }

    internal fun encode(block: ByteArray): ByteArray {
        require(block.size == FIXED_BLOCK_LENGTH)
        return interleave(convolutionalEncode(bytesToBits(block)))
    }

    private fun parity(input: Int): Int {
        var value = input
        value = value xor (value ushr 4)
        value = value xor (value ushr 2)
        value = value xor (value ushr 1)
        return value and 1
    }

    private fun bytesToBits(bytes: ByteArray): ByteArray {
        val bits = ByteArray(bytes.size * 8)
        var offset = 0
        for (byte in bytes) {
            for (bit in 7 downTo 0) bits[offset++] = (((byte.toInt() and 0xff) ushr bit) and 1).toByte()
        }
        return bits
    }

    private fun bitsToBytes(bits: ByteArray): ByteArray {
        require(bits.size % 8 == 0)
        val bytes = ByteArray(bits.size / 8)
        for (index in bits.indices) {
            val value = (bits[index].toInt() and 1) shl (7 - (index and 7))
            bytes[index ushr 3] = (bytes[index ushr 3].toInt() or value).toByte()
        }
        return bytes
    }

    private fun convolutionalEncode(source: ByteArray): ByteArray {
        val input = ByteArray(source.size + TAIL_BITS)
        source.copyInto(input)
        val output = ByteArray(input.size * 2)
        var state = 0
        var offset = 0
        for (sourceBit in input) {
            val register = ((state shl 1) or (sourceBit.toInt() and 1)) and 0x7f
            output[offset++] = parity(register and POLYNOMIAL_A).toByte()
            output[offset++] = parity(register and POLYNOMIAL_B).toByte()
            state = register and 0x3f
        }
        return output
    }

    private fun viterbiDecode(encoded: ByteArray, sourceLength: Int): ByteArray {
        require(encoded.size % 2 == 0)
        val steps = encoded.size / 2
        val states = 1 shl (CONSTRAINT_LENGTH - 1)
        val infinity = 1_000_000
        var metrics = IntArray(states) { infinity }
        metrics[0] = 0
        val previousStates = ByteArray(steps * states)
        val previousBits = ByteArray(steps * states)

        for (step in 0 until steps) {
            val nextMetrics = IntArray(states) { infinity }
            val receivedA = encoded[step * 2].toInt() and 1
            val receivedB = encoded[step * 2 + 1].toInt() and 1
            for (state in 0 until states) {
                if (metrics[state] >= infinity) continue
                for (bit in 0..1) {
                    val register = ((state shl 1) or bit) and 0x7f
                    val nextState = register and 0x3f
                    val distance = (parity(register and POLYNOMIAL_A) xor receivedA) +
                        (parity(register and POLYNOMIAL_B) xor receivedB)
                    val metric = metrics[state] + distance
                    if (metric < nextMetrics[nextState]) {
                        nextMetrics[nextState] = metric
                        previousStates[step * states + nextState] = state.toByte()
                        previousBits[step * states + nextState] = bit.toByte()
                    }
                }
            }
            metrics = nextMetrics
        }

        var state = 0
        val decoded = ByteArray(steps)
        for (step in steps - 1 downTo 0) {
            val index = step * states + state
            decoded[step] = previousBits[index]
            state = previousStates[index].toInt() and 0xff
        }
        return decoded.copyOf(sourceLength)
    }

    private fun interleave(bits: ByteArray): ByteArray {
        val rows = (bits.size + INTERLEAVER_COLUMNS - 1) / INTERLEAVER_COLUMNS
        val padded = bits.copyOf(rows * INTERLEAVER_COLUMNS)
        val output = ByteArray(padded.size)
        var offset = 0
        for (column in 0 until INTERLEAVER_COLUMNS) {
            for (row in 0 until rows) output[offset++] = padded[row * INTERLEAVER_COLUMNS + column]
        }
        return output
    }

    private fun deinterleave(bits: ByteArray): ByteArray {
        require(bits.size % INTERLEAVER_COLUMNS == 0)
        val rows = bits.size / INTERLEAVER_COLUMNS
        val output = ByteArray(bits.size)
        var offset = 0
        for (column in 0 until INTERLEAVER_COLUMNS) {
            for (row in 0 until rows) output[row * INTERLEAVER_COLUMNS + column] = bits[offset++]
        }
        return output
    }
}

