package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.audio.PcmRecorder
import ch.fieldlink.rx.dsp.FloatRingBuffer
import ch.fieldlink.rx.dsp.SpectralPeak
import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PskDecoder(
    selectedMode: DecodeMode,
    private val emit: (DecodedMessage) -> Unit,
    private val partial: (DecodeMode, String) -> Unit,
) : AudioDecoder {
    companion object {
        private const val RING_SAMPLES = 600_000
        private const val PHASE_COUNT = 4
        private val VARICODE = listOf(
            "1", "111111111", "101011111", "111110101", "111011011", "1011010101", "1010111011", "101111111",
            "11111011", "11110111", "101101111", "111011111", "1110101", "110101", "1010111", "110101111",
            "10110111", "10111101", "11101101", "11111111", "101110111", "101011011", "101101011", "110101101",
            "110101011", "110110111", "11110101", "110111101", "111101101", "1010101", "111010111", "1010101111",
            "1010111101", "1111101", "11101011", "10101101", "10110101", "1110111", "11011011", "11111101",
            "101010101", "1111111", "111111101", "101111101", "11010111", "10111011", "11011101", "10101011",
            "11010101", "111011101", "10101111", "1101111", "1101101", "101010111", "110110101", "101011101",
            "101110101", "101111011", "1010101101", "111110111", "111101111", "111111011", "1010111111", "101101101",
            "1011011111", "1011", "1011111", "101111", "101101", "11", "111101", "1011011",
            "101011", "1101", "111101011", "10111111", "11011", "111011", "1111", "111",
            "111111", "110111111", "10101", "10111", "101", "110111", "1111011", "1101011",
            "11011111", "1011101", "111010101", "1010110111", "110111011", "1010110101", "1011010111",
        ).mapIndexed { index, code -> code to (index + 32).toChar() }.toMap()
    }

    private data class ComplexValue(val real: Double, val imaginary: Double)

    private inner class PhaseTrack {
        var previous: ComplexValue? = null
        val bits = StringBuilder()
        val text = StringBuilder()
        var valid = 0
        var invalid = 0
        var contrast = 0.0
        var symbols = 0

        fun add(value: ComplexValue) {
            val previousValue = previous
            previous = value
            if (previousValue == null) return
            val dot = previousValue.real * value.real + previousValue.imaginary * value.imaginary
            val cross = previousValue.real * value.imaginary - previousValue.imaginary * value.real
            val magnitude = hypot(dot, cross)
            val bit = if (dot >= 0.0) '1' else '0'
            contrast += if (magnitude <= 1e-12) 0.0 else abs(dot) / magnitude
            symbols += 1
            bits.append(bit)
            if (bits.length > 12) {
                invalid += 1
                bits.deleteCharAt(0)
            }
            if (bits.length >= 2 && bits[bits.lastIndex] == '0' && bits[bits.lastIndex - 1] == '0') {
                val code = bits.substring(0, bits.length - 2)
                bits.clear()
                if (code.isEmpty()) return
                val decoded = VARICODE[code]
                if (decoded == null) {
                    invalid += 1
                } else {
                    valid += 1
                    text.append(decoded)
                }
            }
        }

        fun score(): Double {
            val validity = valid.toDouble() / (valid + invalid).coerceAtLeast(1)
            val phaseContrast = contrast / symbols.coerceAtLeast(1)
            return validity * phaseContrast
        }

        fun clear() {
            previous = null
            bits.clear()
            text.clear()
            valid = 0
            invalid = 0
            contrast = 0.0
            symbols = 0
        }
    }

    private inner class RateDecoder(
        val mode: DecodeMode,
        val symbolSamples: Int,
    ) {
        private val tracks = Array(PHASE_COUNT) { PhaseTrack() }
        private val nextStarts = LongArray(PHASE_COUNT) { phase -> (phase * symbolSamples / PHASE_COUNT).toLong() }
        private var lastPublishedLength = 0

        fun processAvailable() {
            val frequency = carrierHz ?: return
            for (phase in 0 until PHASE_COUNT) {
                var next = maxOf(nextStarts[phase], ring.startSample)
                val base = phase * symbolSamples / PHASE_COUNT
                val remainder = ((next - base) % symbolSamples + symbolSamples) % symbolSamples
                if (remainder != 0L) next += symbolSamples - remainder
                while (next + symbolSamples <= ring.endSample) {
                    tracks[phase].add(mix(ring.copy(next, symbolSamples), frequency, next))
                    next += symbolSamples
                    nextStarts[phase] = next
                }
            }
            val best = tracks.maxBy { it.score() }
            if (best.text.length != lastPublishedLength) {
                lastPublishedLength = best.text.length
                partial(mode, best.text.toString().takeLast(1_000))
            }
        }

        fun flush() {
            val best = tracks.maxBy { it.score() }
            val value = best.text.toString().trim()
            if (value.length >= 2) {
                val quality = best.score().toFloat().coerceIn(0.05f, 1f)
                emit(
                    DecodedMessage(
                        mode = mode,
                        text = value,
                        audioFrequencyHz = carrierHz ?: 0.0,
                        quality = quality,
                        uncertain = quality < 0.62f || best.invalid > best.valid / 2,
                    ),
                )
            }
            tracks.forEach { it.clear() }
            lastPublishedLength = 0
            partial(mode, "")
        }

        fun resetTiming() {
            for (phase in 0 until PHASE_COUNT) {
                nextStarts[phase] = ring.endSample + phase * symbolSamples / PHASE_COUNT
            }
            tracks.forEach { it.clear() }
            lastPublishedLength = 0
        }
    }

    private val ring = FloatRingBuffer(RING_SAMPLES)
    private val rates = listOf(
        RateDecoder(DecodeMode.PSK31, symbolSamples = 1_536),
        RateDecoder(DecodeMode.PSK63, symbolSamples = 768),
    ).filter { it.mode == selectedMode }.also {
        require(it.size == 1) { "A PSK31 or PSK63 decoder must be selected." }
    }
    private var carrierHz: Double? = null
    private var lastCarrierMillis = 0L

    override fun process(samples: FloatArray, spectrum: SpectrumAnalysis?) {
        ring.append(samples)
        spectrum?.let { updateCarrier(it.peaks) }
        rates.forEach { it.processAvailable() }
        if (carrierHz != null && System.currentTimeMillis() - lastCarrierMillis > 2_500L) {
            rates.forEach { it.flush() }
            carrierHz = null
        }
    }

    private fun updateCarrier(peaks: List<SpectralPeak>) {
        val candidate = peaks.firstOrNull { it.frequencyHz in 300.0..2_800.0 && it.levelDb > -74f } ?: return
        val old = carrierHz
        if (old == null || abs(old - candidate.frequencyHz) > 35.0) {
            rates.forEach { it.flush() }
            carrierHz = candidate.frequencyHz
            rates.forEach { it.resetTiming() }
        } else {
            carrierHz = old * 0.85 + candidate.frequencyHz * 0.15
        }
        lastCarrierMillis = System.currentTimeMillis()
    }

    private fun mix(samples: FloatArray, frequencyHz: Double, absoluteStart: Long): ComplexValue {
        var real = 0.0
        var imaginary = 0.0
        for (index in samples.indices) {
            val absoluteSample = absoluteStart + index
            val angle = 2.0 * PI * frequencyHz * absoluteSample / PcmRecorder.SAMPLE_RATE
            val window = 0.5 - 0.5 * cos(2.0 * PI * index / (samples.size - 1).coerceAtLeast(1))
            real += samples[index] * window * cos(angle)
            imaginary -= samples[index] * window * sin(angle)
        }
        return ComplexValue(real, imaginary)
    }

    override fun close() {
        rates.forEach { it.flush() }
        carrierHz = null
    }
}
