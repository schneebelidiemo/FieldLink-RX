package ch.fieldlink.rx.sdr

import ch.fieldlink.rx.dsp.Fft
import ch.fieldlink.rx.model.SpectrumFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

class RfSpectrumAnalyzer(
    private val sampleRate: Int = SdrDemodulator.INPUT_SAMPLE_RATE,
) {
    companion object {
        private const val FFT_SIZE = 2_048
        private const val DISPLAY_BINS = 256
        private const val EPSILON = 1e-12
    }

    private val iRing = DoubleArray(FFT_SIZE)
    private val qRing = DoubleArray(FFT_SIZE)
    private val window = DoubleArray(FFT_SIZE) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))
    }
    private var writeIndex = 0
    private var totalSamples = 0L
    private var samplesSinceFrame = 0

    fun reset() {
        iRing.fill(0.0)
        qRing.fill(0.0)
        writeIndex = 0
        totalSamples = 0L
        samplesSinceFrame = 0
    }

    fun add(iq: ByteArray, centerFrequencyHz: Long): SpectrumFrame? {
        var index = 0
        while (index + 1 < iq.size) {
            iRing[writeIndex] = ((iq[index].toInt() and 0xff) - 127.5) / 127.5
            qRing[writeIndex] = ((iq[index + 1].toInt() and 0xff) - 127.5) / 127.5
            writeIndex = (writeIndex + 1) and (FFT_SIZE - 1)
            totalSamples += 1
            samplesSinceFrame += 1
            index += 2
        }
        if (totalSamples < FFT_SIZE || samplesSinceFrame < sampleRate / 10) return null
        samplesSinceFrame = 0
        return analyze(centerFrequencyHz)
    }

    private fun analyze(centerFrequencyHz: Long): SpectrumFrame {
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        for (index in 0 until FFT_SIZE) {
            val source = (writeIndex + index) and (FFT_SIZE - 1)
            real[index] = iRing[source] * window[index]
            imaginary[index] = qRing[source] * window[index]
        }
        Fft.transform(real, imaginary)

        val binsPerDisplay = FFT_SIZE / DISPLAY_BINS
        val display = FloatArray(DISPLAY_BINS)
        for (displayBin in display.indices) {
            var strongest = -120f
            val shiftedStart = displayBin * binsPerDisplay
            for (offset in 0 until binsPerDisplay) {
                val shiftedBin = shiftedStart + offset
                val sourceBin = (shiftedBin + FFT_SIZE / 2) and (FFT_SIZE - 1)
                val magnitudeSquared = real[sourceBin] * real[sourceBin] + imaginary[sourceBin] * imaginary[sourceBin]
                val db = (10.0 * log10(magnitudeSquared / (FFT_SIZE * FFT_SIZE) + EPSILON)).toFloat()
                strongest = max(strongest, db)
            }
            display[displayBin] = strongest.coerceIn(-120f, -10f)
        }
        val halfSpan = sampleRate / 2L
        return SpectrumFrame(
            bins = display,
            minFrequencyHz = (centerFrequencyHz - halfSpan).coerceAtLeast(0).toInt(),
            maxFrequencyHz = (centerFrequencyHz + halfSpan).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}
