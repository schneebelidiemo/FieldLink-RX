package ch.fieldlink.rx.sdr

import ch.fieldlink.rx.model.SdrModulation
import ch.fieldlink.rx.model.SdrSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrDemodulatorTest {
    private val sampleRate = SdrDemodulator.INPUT_SAMPLE_RATE
    private val tunerOffset = 12_000

    @Test
    fun `USB and LSB produce decoder audio at the original tone frequency`() {
        val usb = decode(
            modulation = SdrModulation.USB,
            iq = complexTone(-tunerOffset + 1_000, 160_000),
        )
        val lsb = decode(
            modulation = SdrModulation.LSB,
            iq = complexTone(-tunerOffset - 1_000, 160_000),
        )

        assertEquals(1_000.0, estimateFrequency(usb), 45.0)
        assertEquals(1_000.0, estimateFrequency(lsb), 45.0)
    }

    @Test
    fun `CW adds a stable 700 Hz receiver pitch`() {
        val audio = decode(
            modulation = SdrModulation.CW,
            iq = complexTone(-tunerOffset, 160_000),
        )

        assertEquals(700.0, estimateFrequency(audio), 45.0)
    }

    @Test
    fun `AM NFM and WFM demodulate a one kHz tone`() {
        val count = 200_000
        val am = decode(SdrModulation.AM, amplitudeModulated(1_000, count))
        val nfm = decode(SdrModulation.NFM, frequencyModulated(1_000, 3_000, count))
        val wfm = decode(SdrModulation.WFM, frequencyModulated(1_000, 30_000, count))

        assertEquals(1_000.0, estimateFrequency(am), 55.0)
        assertEquals(1_000.0, estimateFrequency(nfm), 55.0)
        assertEquals(1_000.0, estimateFrequency(wfm), 55.0)
    }

    @Test
    fun `enabled squelch suppresses an empty channel`() {
        val settings = SdrSettings(
            modulation = SdrModulation.USB,
            squelchEnabled = true,
            squelchThresholdDb = -60,
        )
        val block = SdrDemodulator().process(ByteArray(100_000) { 128.toByte() }, settings, tunerOffset)

        assertTrue(block.samples.all { it == 0f })
        assertTrue(block.rfLevelDb < -60f)
    }

    private fun decode(modulation: SdrModulation, iq: ByteArray): FloatArray =
        SdrDemodulator().process(
            iq,
            SdrSettings(modulation = modulation, automaticBandwidth = true),
            tunerOffset,
        ).samples

    private fun complexTone(frequencyHz: Int, count: Int): ByteArray = iqBytes(count) { index ->
        val phase = 2.0 * PI * frequencyHz * index / sampleRate
        0.72 * cos(phase) to 0.72 * sin(phase)
    }

    private fun amplitudeModulated(audioHz: Int, count: Int): ByteArray = iqBytes(count) { index ->
        val carrierPhase = -2.0 * PI * tunerOffset * index / sampleRate
        val envelope = 0.50 + 0.20 * sin(2.0 * PI * audioHz * index / sampleRate)
        envelope * cos(carrierPhase) to envelope * sin(carrierPhase)
    }

    private fun frequencyModulated(audioHz: Int, deviationHz: Int, count: Int): ByteArray {
        var phase = 0.0
        return iqBytes(count) { index ->
            val instantaneous = -tunerOffset + deviationHz * sin(2.0 * PI * audioHz * index / sampleRate)
            phase += 2.0 * PI * instantaneous / sampleRate
            0.72 * cos(phase) to 0.72 * sin(phase)
        }
    }

    private fun iqBytes(count: Int, sample: (Int) -> Pair<Double, Double>): ByteArray {
        val result = ByteArray(count * 2)
        for (index in 0 until count) {
            val (i, q) = sample(index)
            result[index * 2] = ((i.coerceIn(-1.0, 1.0) * 127.0 + 127.5).toInt() and 0xff).toByte()
            result[index * 2 + 1] = ((q.coerceIn(-1.0, 1.0) * 127.0 + 127.5).toInt() and 0xff).toByte()
        }
        return result
    }

    private fun estimateFrequency(samples: FloatArray): Double {
        val start = (samples.size / 3).coerceAtMost(samples.lastIndex)
        var crossings = 0
        var previous = samples[start]
        for (index in start + 1 until samples.size) {
            val current = samples[index]
            if ((previous < 0f && current >= 0f) || (previous >= 0f && current < 0f)) crossings += 1
            previous = current
        }
        val seconds = (samples.size - start).toDouble() / SdrDemodulator.OUTPUT_SAMPLE_RATE
        return crossings / (2.0 * seconds)
    }
}
