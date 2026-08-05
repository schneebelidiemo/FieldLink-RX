package ch.fieldlink.rx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {
    @Test
    fun identifiesKnownTone() {
        val size = 8_192
        val rate = 48_000
        val expectedBin = 256
        val frequency = expectedBin.toDouble() * rate / size
        val real = DoubleArray(size) { index -> sin(2.0 * PI * frequency * index / rate) }
        val imaginary = DoubleArray(size)

        Fft.transform(real, imaginary)

        val peak = (1 until size / 2).maxBy { real[it] * real[it] + imaginary[it] * imaginary[it] }
        assertEquals(expectedBin, peak)
        assertTrue(real[peak] * real[peak] + imaginary[peak] * imaginary[peak] > 1_000_000.0)
    }
}

