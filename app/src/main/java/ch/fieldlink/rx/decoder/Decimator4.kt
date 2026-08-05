package ch.fieldlink.rx.decoder

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class Decimator4 {
    companion object {
        private const val FACTOR = 4
        private const val TAP_COUNT = 33
        private const val INPUT_RATE = 48_000.0
        private const val CUTOFF_HZ = 4_000.0
    }

    private val taps = designTaps()
    private val history = FloatArray(TAP_COUNT)
    private var writeIndex = 0
    private var phase = 0

    fun process(input: FloatArray): FloatArray {
        val output = FloatArray((input.size + FACTOR - 1) / FACTOR)
        var outputSize = 0
        for (sample in input) {
            history[writeIndex] = sample
            writeIndex = (writeIndex + 1) % history.size
            phase += 1
            if (phase != FACTOR) continue
            phase = 0

            var sum = 0.0
            var source = writeIndex
            for (tap in taps.indices) {
                source = if (source == 0) history.lastIndex else source - 1
                sum += history[source] * taps[tap]
            }
            output[outputSize++] = sum.toFloat()
        }
        return output.copyOf(outputSize)
    }

    private fun designTaps(): DoubleArray {
        val normalizedCutoff = CUTOFF_HZ / INPUT_RATE
        val middle = (TAP_COUNT - 1) / 2.0
        val result = DoubleArray(TAP_COUNT) { index ->
            val offset = index - middle
            val sinc = if (offset == 0.0) {
                2.0 * normalizedCutoff
            } else {
                sin(2.0 * PI * normalizedCutoff * offset) / (PI * offset)
            }
            val blackman = 0.42 - 0.5 * cos(2.0 * PI * index / (TAP_COUNT - 1)) +
                0.08 * cos(4.0 * PI * index / (TAP_COUNT - 1))
            sinc * blackman
        }
        val scale = result.sum()
        return result.map { it / scale }.reversed().toDoubleArray()
    }
}

