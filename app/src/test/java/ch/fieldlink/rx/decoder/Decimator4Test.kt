package ch.fieldlink.rx.decoder

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class Decimator4Test {
    @Test
    fun retainsAudioBandAndRejectsHighFrequencyAlias() {
        val retained = peak(decimateTone(1_000.0))
        val rejected = peak(decimateTone(10_000.0))

        assertTrue(retained > 0.7f)
        assertTrue(rejected < 0.08f)
    }

    private fun decimateTone(frequency: Double): FloatArray {
        val input = FloatArray(48_000) { index ->
            sin(2.0 * PI * frequency * index / 48_000.0).toFloat()
        }
        return Decimator4().process(input).drop(100).toFloatArray()
    }

    private fun peak(samples: FloatArray): Float = samples.maxOf { abs(it) }
}

