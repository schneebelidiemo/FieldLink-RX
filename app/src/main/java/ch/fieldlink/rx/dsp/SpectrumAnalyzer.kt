package ch.fieldlink.rx.dsp

import ch.fieldlink.rx.audio.PcmRecorder
import ch.fieldlink.rx.model.SignalSnapshot
import ch.fieldlink.rx.model.SpectrumFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class SpectralPeak(
    val frequencyHz: Double,
    val levelDb: Float,
)

data class SpectrumAnalysis(
    val signal: SignalSnapshot,
    val frame: SpectrumFrame,
    val peaks: List<SpectralPeak>,
)

class SpectrumAnalyzer(
    private val sampleRate: Int = PcmRecorder.SAMPLE_RATE,
) {
    companion object {
        const val FFT_SIZE = 8_192
        private const val DISPLAY_BINS = 256
        private const val MAX_DISPLAY_FREQUENCY = 3_000
        private const val EPSILON = 1e-12
    }

    private val ring = FloatArray(FFT_SIZE)
    private val window = DoubleArray(FFT_SIZE) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))
    }
    private var writeIndex = 0
    private var sampleCount = 0L
    private var samplesSinceAnalysis = 0

    fun add(samples: FloatArray): SpectrumAnalysis? {
        for (sample in samples) {
            ring[writeIndex] = sample
            writeIndex = (writeIndex + 1) and (FFT_SIZE - 1)
            sampleCount += 1
            samplesSinceAnalysis += 1
        }
        if (sampleCount < FFT_SIZE || samplesSinceAnalysis < sampleRate / 10) return null
        samplesSinceAnalysis = 0
        return analyze()
    }

    private fun analyze(): SpectrumAnalysis {
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        var squareSum = 0.0
        for (index in 0 until FFT_SIZE) {
            val sample = ring[(writeIndex + index) and (FFT_SIZE - 1)].toDouble()
            squareSum += sample * sample
            real[index] = sample * window[index]
        }
        Fft.transform(real, imaginary)

        val maxSourceBin = (MAX_DISPLAY_FREQUENCY.toLong() * FFT_SIZE / sampleRate).toInt()
        val powers = FloatArray(maxSourceBin + 1)
        for (bin in 0..maxSourceBin) {
            val magnitudeSquared = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            powers[bin] = (10.0 * log10(magnitudeSquared / (FFT_SIZE * FFT_SIZE) + EPSILON)).toFloat()
        }

        val display = FloatArray(DISPLAY_BINS)
        for (displayBin in display.indices) {
            val start = displayBin * maxSourceBin / DISPLAY_BINS
            val end = max(start + 1, (displayBin + 1) * maxSourceBin / DISPLAY_BINS)
            var strongest = -120f
            for (sourceBin in start..end.coerceAtMost(maxSourceBin)) strongest = max(strongest, powers[sourceBin])
            display[displayBin] = strongest.coerceIn(-110f, -10f)
        }

        val peaks = localPeaks(powers)
        val strongest = peaks.firstOrNull() ?: SpectralPeak(0.0, -120f)
        val rms = sqrt(squareSum / FFT_SIZE)
        val rmsDb = (20.0 * log10(rms + EPSILON)).toFloat().coerceIn(-120f, 0f)
        return SpectrumAnalysis(
            signal = SignalSnapshot(
                rmsDb = rmsDb,
                peakFrequencyHz = strongest.frequencyHz,
                peakDb = strongest.levelDb,
            ),
            frame = SpectrumFrame(display),
            peaks = peaks,
        )
    }

    private fun localPeaks(power: FloatArray): List<SpectralPeak> {
        val candidates = mutableListOf<SpectralPeak>()
        val minimumBin = (200L * FFT_SIZE / sampleRate).toInt()
        for (bin in max(2, minimumBin) until power.lastIndex - 1) {
            if (power[bin] <= power[bin - 1] || power[bin] < power[bin + 1]) continue
            if (power[bin] < -82f) continue
            val frequency = bin.toDouble() * sampleRate / FFT_SIZE
            candidates += SpectralPeak(frequency, power[bin])
        }
        val selected = mutableListOf<SpectralPeak>()
        for (candidate in candidates.sortedByDescending { it.levelDb }) {
            if (selected.none { kotlin.math.abs(it.frequencyHz - candidate.frequencyHz) < 18.0 }) {
                selected += candidate
                if (selected.size == 12) break
            }
        }
        return selected
    }
}
