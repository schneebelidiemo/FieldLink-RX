package ch.fieldlink.rx.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfSpectrumAnalyzerTest {
    @Test
    fun `waterfall spans the full sample bandwidth and locates an RF tone`() {
        val analyzer = RfSpectrumAnalyzer()
        val center = 145_500_000L
        val sampleRate = SdrDemodulator.INPUT_SAMPLE_RATE
        val toneHz = 200_000
        val count = sampleRate / 10 + 4_096
        val iq = ByteArray(count * 2)
        for (index in 0 until count) {
            val phase = 2.0 * PI * toneHz * index / sampleRate
            iq[index * 2] = ((0.75 * cos(phase) * 127.0 + 127.5).toInt() and 0xff).toByte()
            iq[index * 2 + 1] = ((0.75 * sin(phase) * 127.0 + 127.5).toInt() and 0xff).toByte()
        }

        val frame = requireNotNull(analyzer.add(iq, center))
        assertEquals(center - sampleRate / 2, frame.minFrequencyHz.toLong())
        assertEquals(center + sampleRate / 2, frame.maxFrequencyHz.toLong())
        val peakBin = frame.bins.indices.maxBy { frame.bins[it] }
        val estimatedOffset = (peakBin + 0.5) * sampleRate / frame.bins.size - sampleRate / 2.0
        assertTrue(kotlin.math.abs(estimatedOffset - toneHz) < 20_000)
    }
}
