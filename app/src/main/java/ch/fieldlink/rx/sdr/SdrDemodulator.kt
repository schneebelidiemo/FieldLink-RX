package ch.fieldlink.rx.sdr

import ch.fieldlink.rx.model.SdrModulation
import ch.fieldlink.rx.model.SdrSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

data class SdrAudioBlock(
    val samples: FloatArray,
    val rfLevelDb: Float,
)

class SdrDemodulator(
    private val inputSampleRate: Int = INPUT_SAMPLE_RATE,
    private val outputSampleRate: Int = OUTPUT_SAMPLE_RATE,
) {
    companion object {
        const val INPUT_SAMPLE_RATE = 2_400_000
        const val OUTPUT_SAMPLE_RATE = 48_000
        private const val EPSILON = 1e-12
        private const val CW_PITCH_HZ = 700.0
    }

    private val decimation = inputSampleRate / outputSampleRate
    private var dcI = 0.0
    private var dcQ = 0.0
    private var filteredI = 0.0
    private var filteredQ = 0.0
    private var audioFiltered = 0.0
    private var amAverage = 0.0
    private var previousI = 0.0
    private var previousQ = 0.0
    private var cwCos = 1.0
    private var cwSin = 0.0
    private var shiftCos = 1.0
    private var shiftSin = 0.0
    private var accumulator = 0.0
    private var accumulatorCount = 0

    init {
        require(inputSampleRate % outputSampleRate == 0) {
            "The SDR sample rate must be an integer multiple of the decoder sample rate."
        }
    }

    fun reset() {
        dcI = 0.0
        dcQ = 0.0
        filteredI = 0.0
        filteredQ = 0.0
        audioFiltered = 0.0
        amAverage = 0.0
        previousI = 0.0
        previousQ = 0.0
        cwCos = 1.0
        cwSin = 0.0
        shiftCos = 1.0
        shiftSin = 0.0
        accumulator = 0.0
        accumulatorCount = 0
    }

    fun process(iq: ByteArray, settings: SdrSettings, tunerOffsetHz: Int = 0): SdrAudioBlock {
        require(iq.size % 2 == 0) { "RTL-SDR I/Q data must contain complete byte pairs." }
        val maximumOutput = (iq.size / 2 + accumulatorCount) / decimation
        val output = FloatArray(maximumOutput)
        var outputCount = 0
        var powerSum = 0.0
        var powerCount = 0
        val rfCutoff = rfCutoff(settings)
            .coerceIn(250.0, inputSampleRate * 0.45)
        val rfAlpha = 1.0 - exp(-2.0 * PI * rfCutoff / inputSampleRate)
        val audioCutoff = audioCutoff(settings)
        val audioAlpha = 1.0 - exp(-2.0 * PI * audioCutoff / inputSampleRate)
        val cwIncrement = 2.0 * PI * CW_PITCH_HZ / inputSampleRate
        val cwRootCos = kotlin.math.cos(cwIncrement)
        val cwRootSin = kotlin.math.sin(cwIncrement)
        val shiftIncrement = 2.0 * PI * tunerOffsetHz / inputSampleRate
        val shiftRootCos = kotlin.math.cos(shiftIncrement)
        val shiftRootSin = kotlin.math.sin(shiftIncrement)

        var index = 0
        while (index < iq.size) {
            val rawI = ((iq[index].toInt() and 0xff) - 127.5) / 127.5
            val rawQ = ((iq[index + 1].toInt() and 0xff) - 127.5) / 127.5
            index += 2

            dcI += 0.00002 * (rawI - dcI)
            dcQ += 0.00002 * (rawQ - dcQ)
            val centeredI = rawI - dcI
            val centeredQ = rawQ - dcQ
            val shiftedI = centeredI * shiftCos - centeredQ * shiftSin
            val shiftedQ = centeredI * shiftSin + centeredQ * shiftCos
            val nextShiftCos = shiftCos * shiftRootCos - shiftSin * shiftRootSin
            shiftSin = shiftCos * shiftRootSin + shiftSin * shiftRootCos
            shiftCos = nextShiftCos
            filteredI += rfAlpha * (shiftedI - filteredI)
            filteredQ += rfAlpha * (shiftedQ - filteredQ)

            val power = filteredI * filteredI + filteredQ * filteredQ
            powerSum += power
            powerCount += 1

            val demodulated = when (settings.modulation) {
                SdrModulation.USB,
                SdrModulation.LSB -> filteredI
                SdrModulation.CW -> {
                    val value = filteredI * cwCos - filteredQ * cwSin
                    val nextCwCos = cwCos * cwRootCos - cwSin * cwRootSin
                    cwSin = cwCos * cwRootSin + cwSin * cwRootCos
                    cwCos = nextCwCos
                    value
                }
                SdrModulation.AM -> {
                    val envelope = sqrt(power)
                    amAverage += 0.00002 * (envelope - amAverage)
                    (envelope - amAverage) * 2.0
                }
                SdrModulation.NFM,
                SdrModulation.WFM -> {
                    val cross = previousI * filteredQ - previousQ * filteredI
                    val dot = previousI * filteredI + previousQ * filteredQ
                    previousI = filteredI
                    previousQ = filteredQ
                    val deviation = if (settings.modulation == SdrModulation.NFM) 5_000.0 else 75_000.0
                    fastAtan2(cross, dot) * inputSampleRate / (2.0 * PI * deviation)
                }
            }

            audioFiltered += audioAlpha * (demodulated - audioFiltered)
            accumulator += audioFiltered
            accumulatorCount += 1
            if (accumulatorCount == decimation) {
                output[outputCount++] = (accumulator / decimation).toFloat().coerceIn(-1f, 1f)
                accumulator = 0.0
                accumulatorCount = 0
            }
        }

        val rfLevel = (10.0 * log10(powerSum / powerCount.coerceAtLeast(1) + EPSILON))
            .toFloat()
            .coerceIn(-120f, 0f)
        val result = if (outputCount == output.size) output else output.copyOf(outputCount)
        normalizeOscillators()
        if (settings.squelchEnabled && rfLevel < settings.squelchThresholdDb) result.fill(0f)
        return SdrAudioBlock(result, rfLevel)
    }

    private fun normalizeOscillators() {
        val shiftMagnitude = sqrt(shiftCos * shiftCos + shiftSin * shiftSin).coerceAtLeast(EPSILON)
        shiftCos /= shiftMagnitude
        shiftSin /= shiftMagnitude
        val cwMagnitude = sqrt(cwCos * cwCos + cwSin * cwSin).coerceAtLeast(EPSILON)
        cwCos /= cwMagnitude
        cwSin /= cwMagnitude
    }

    private fun fastAtan2(y: Double, x: Double): Double {
        val absoluteY = abs(y) + EPSILON
        val angle = if (x >= 0.0) {
            val ratio = (x - absoluteY) / (x + absoluteY)
            PI / 4.0 - PI / 4.0 * ratio
        } else {
            val ratio = (x + absoluteY) / (absoluteY - x)
            3.0 * PI / 4.0 - PI / 4.0 * ratio
        }
        return if (y < 0.0) -angle else angle
    }

    private fun audioCutoff(settings: SdrSettings): Double = when (settings.modulation) {
        SdrModulation.USB,
        SdrModulation.LSB -> settings.bandwidthHz.toDouble().coerceIn(300.0, 12_000.0)
        SdrModulation.CW -> (settings.bandwidthHz / 2.0).coerceIn(200.0, 2_000.0)
        SdrModulation.AM -> (settings.bandwidthHz / 2.0).coerceIn(1_000.0, 10_000.0)
        SdrModulation.NFM -> (settings.bandwidthHz / 2.0).coerceIn(2_500.0, 12_000.0)
        SdrModulation.WFM -> 15_000.0
    }

    private fun rfCutoff(settings: SdrSettings): Double = when (settings.modulation) {
        SdrModulation.USB,
        SdrModulation.LSB -> settings.bandwidthHz.toDouble()
        else -> settings.bandwidthHz / 2.0
    }
}
