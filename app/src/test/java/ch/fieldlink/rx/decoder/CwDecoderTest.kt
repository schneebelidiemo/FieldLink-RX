package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.dsp.SpectralPeak
import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.model.CwSettings
import ch.fieldlink.rx.model.CwTrackSnapshot
import ch.fieldlink.rx.model.DecodedMessage
import ch.fieldlink.rx.model.SignalSnapshot
import ch.fieldlink.rx.model.SpectrumFrame
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CwDecoderTest {
    private val messages = mutableListOf<DecodedMessage>()
    private var snapshots = emptyList<CwTrackSnapshot>()
    private var sampleOffset = 0L
    private val settings = CwSettings(
        automaticSpeed = true,
        automaticTone = true,
        automaticNoiseThreshold = true,
        sensitivity = 60,
        messageGapSeconds = 3,
    )

    @Test
    fun `decodes a stable morse message after three valid characters`() {
        val decoder = decoder()
        acquire(decoder, listOf(700.0))
        play(decoder, mapOf(700.0 to morse("... --- ...")))

        assertEquals(1, messages.size)
        assertEquals("SOS", messages.single().text)
        assertFalse(messages.single().uncertain)
        assertTrue(messages.single().complete)
        assertTrue(messages.single().speedWpm!! in 10.0..25.0)
    }

    @Test
    fun `marks a short fragment uncertain and appends question mark`() {
        val decoder = decoder()
        acquire(decoder, listOf(700.0))
        play(decoder, mapOf(700.0 to morse(".")))

        assertEquals("E?", messages.single().text)
        assertTrue(messages.single().uncertain)
        assertFalse(messages.single().complete)
    }

    @Test
    fun `accepts an amateur radio prosign as a complete message`() {
        val decoder = decoder()
        acquire(decoder, listOf(700.0))
        play(decoder, mapOf(700.0 to morse(".-.-.")))

        assertEquals("<AR>", messages.single().text)
        assertFalse(messages.single().uncertain)
        assertTrue(messages.single().complete)
    }

    @Test
    fun `does not turn a continuous fan-like tone into morse`() {
        val decoder = decoder()
        acquire(decoder, listOf(420.0))
        repeat(600) { tick ->
            val amplitude = 0.16 + 0.025 * sin(2.0 * PI * tick / 130.0)
            feed(decoder, doubleArrayOf(420.0), doubleArrayOf(amplitude))
        }
        decoder.close()

        assertTrue(messages.isEmpty())
    }

    @Test
    fun `keeps three separated signals in independent tracks`() {
        val decoder = decoder()
        val frequencies = listOf(450.0, 700.0, 950.0)
        acquire(decoder, frequencies)
        val timeline = morse("... --- ...")
        play(decoder, frequencies.associateWith { timeline })

        assertEquals(3, messages.size)
        assertTrue(messages.all { it.text == "SOS" })
        assertEquals(listOf(450, 700, 950), messages.map { it.audioFrequencyHz.toInt() }.sorted())
        assertEquals(3, snapshots.size)
    }

    private fun decoder() = CwDecoder(
        emit = messages::add,
        settingsProvider = { settings },
        status = { snapshots = it },
    )

    private fun acquire(decoder: CwDecoder, frequencies: List<Double>) {
        repeat(6) { feed(decoder, doubleArrayOf(), doubleArrayOf(), frequencies) }
    }

    private fun play(decoder: CwDecoder, signals: Map<Double, BooleanArray>) {
        val ticks = signals.values.maxOf { it.size }
        val frequencies = signals.keys.sorted()
        repeat(ticks) { tick ->
            val active = frequencies.filter { signals.getValue(it).getOrElse(tick) { false } }
            feed(
                decoder,
                active.toDoubleArray(),
                DoubleArray(active.size) { 0.24 / active.size.coerceAtLeast(1) },
                frequencies,
            )
        }
    }

    private fun feed(
        decoder: CwDecoder,
        activeFrequencies: DoubleArray,
        amplitudes: DoubleArray,
        visiblePeaks: List<Double> = activeFrequencies.toList(),
    ) {
        val samples = FloatArray(TICK_SAMPLES) { index ->
            activeFrequencies.indices.sumOf { signal ->
                amplitudes[signal] * sin(
                    2.0 * PI * activeFrequencies[signal] * (sampleOffset + index) / SAMPLE_RATE,
                )
            }.toFloat()
        }
        sampleOffset += TICK_SAMPLES
        decoder.process(samples, spectrum(visiblePeaks))
    }

    private fun spectrum(frequencies: List<Double>) = SpectrumAnalysis(
        signal = SignalSnapshot(),
        frame = SpectrumFrame(FloatArray(256)),
        peaks = frequencies.map { SpectralPeak(it, -24f) },
    )

    private fun morse(pattern: String, dotTicks: Int = 8): BooleanArray {
        val timeline = mutableListOf<Boolean>()
        val characters = pattern.split(' ')
        characters.forEachIndexed { characterIndex, character ->
            character.forEachIndexed { markIndex, mark ->
                repeat(if (mark == '.') dotTicks else dotTicks * 3) { timeline += true }
                if (markIndex < character.lastIndex) repeat(dotTicks) { timeline += false }
            }
            if (characterIndex < characters.lastIndex) repeat(dotTicks * 3) { timeline += false }
        }
        repeat(320) { timeline += false }
        return timeline.toBooleanArray()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000.0
        const val TICK_SAMPLES = 480
    }
}
