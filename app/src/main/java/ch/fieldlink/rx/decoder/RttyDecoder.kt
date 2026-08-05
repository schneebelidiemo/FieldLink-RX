package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.audio.PcmRecorder
import ch.fieldlink.rx.dsp.FixedSampleAccumulator
import ch.fieldlink.rx.dsp.SpectralPeak
import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

class RttyDecoder(
    private val emit: (DecodedMessage) -> Unit,
    private val partial: (String) -> Unit,
) : AudioDecoder {
    companion object {
        private const val TICK_SAMPLES = 240 // 5 ms
        private const val TICKS_PER_SECOND = 200.0
        private const val BAUD = 45.45
        private const val BIT_TICKS = TICKS_PER_SECOND / BAUD
        private const val SHIFT_HZ = 170.0

        private val LETTERS = arrayOf(
            "", "E", "\n", "A", " ", "S", "I", "U",
            "\r", "D", "R", "J", "N", "F", "C", "K",
            "T", "Z", "L", "W", "H", "Y", "P", "Q",
            "O", "B", "G", "", "M", "X", "V", "",
        )
        private val FIGURES = arrayOf(
            "", "3", "\n", "-", " ", "'", "8", "7",
            "\r", "$", "4", "\u0007", ",", "!", ":", "(",
            "5", "+", ")", "2", "£", "6", "0", "1",
            "9", "?", "&", "", ".", "/", ";", "",
        )
    }

    private inner class Channel(
        var markHz: Double,
        var spaceHz: Double,
    ) {
        private var figures = false
        private var previousMark = true
        private var receiving = false
        private var nextSampleTick = 0.0
        private var bitIndex = 0
        private var value = 0
        private var currentTick = 0L
        private var lastCharacterTick = 0L
        private var confidenceSum = 0.0
        private var confidenceCount = 0
        private var invalidCharacters = 0
        private val text = StringBuilder()

        fun process(block: FloatArray) {
            currentTick += 1
            val markPower = goertzel(block, markHz)
            val spacePower = goertzel(block, spaceHz)
            val isMark = markPower >= spacePower
            val confidence = abs(markPower - spacePower) / (markPower + spacePower + 1e-12)

            if (!receiving && previousMark && !isMark) {
                receiving = true
                bitIndex = 0
                value = 0
                nextSampleTick = currentTick + BIT_TICKS * 1.5
            }

            if (receiving && currentTick >= nextSampleTick) {
                if (bitIndex < 5) {
                    if (isMark) value = value or (1 shl bitIndex)
                    bitIndex += 1
                    nextSampleTick += BIT_TICKS
                } else {
                    receiving = false
                    if (isMark) accept(value, confidence) else invalidCharacters += 1
                }
            }
            previousMark = isMark

            if (text.isNotEmpty() && currentTick - lastCharacterTick > 400) flush()
        }

        fun retune(low: Double, high: Double) {
            markHz = markHz * 0.8 + low * 0.2
            spaceHz = spaceHz * 0.8 + high * 0.2
        }

        private fun accept(code: Int, confidence: Double) {
            when (code) {
                31 -> figures = false
                27 -> figures = true
                0 -> Unit
                else -> {
                    val decoded = (if (figures) FIGURES else LETTERS)[code]
                    if (decoded.isEmpty()) {
                        invalidCharacters += 1
                        return
                    }
                    text.append(decoded)
                    lastCharacterTick = currentTick
                    confidenceSum += confidence
                    confidenceCount += 1
                    partial(text.toString().takeLast(1_000))
                }
            }
        }

        fun shouldReverse(): Boolean = invalidCharacters >= 8 && text.length < 3

        fun reverse() {
            val previous = markHz
            markHz = spaceHz
            spaceHz = previous
            invalidCharacters = 0
            receiving = false
            previousMark = true
            text.clear()
        }

        fun flush() {
            val decoded = text.toString().trim()
            if (decoded.length >= 2) {
                val quality = if (confidenceCount == 0) 0f else (confidenceSum / confidenceCount).toFloat()
                emit(
                    DecodedMessage(
                        mode = DecodeMode.RTTY,
                        text = decoded,
                        audioFrequencyHz = (markHz + spaceHz) / 2.0,
                        quality = quality.coerceIn(0.05f, 1f),
                        uncertain = quality < 0.58f || invalidCharacters > decoded.length / 2,
                    ),
                )
            }
            text.clear()
            confidenceSum = 0.0
            confidenceCount = 0
            invalidCharacters = 0
            partial("")
        }
    }

    private val accumulator = FixedSampleAccumulator(TICK_SAMPLES)
    private var channel: Channel? = null

    override fun process(samples: FloatArray, spectrum: SpectrumAnalysis?) {
        spectrum?.let { selectPair(it.peaks) }
        accumulator.add(samples) { block ->
            channel?.let { active ->
                active.process(block)
                if (active.shouldReverse()) active.reverse()
            }
        }
    }

    private fun selectPair(peaks: List<SpectralPeak>) {
        var bestPair: Pair<SpectralPeak, SpectralPeak>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (first in peaks) {
            for (second in peaks) {
                if (second.frequencyHz <= first.frequencyHz) continue
                val separation = second.frequencyHz - first.frequencyHz
                if (abs(separation - SHIFT_HZ) > 18.0) continue
                val score = first.levelDb + second.levelDb
                if (score > bestScore) {
                    bestScore = score
                    bestPair = first to second
                }
            }
        }
        val pair = bestPair ?: return
        if (pair.first.levelDb < -76f || pair.second.levelDb < -76f) return
        val active = channel
        if (active == null || abs((active.markHz + active.spaceHz) / 2.0 - (pair.first.frequencyHz + pair.second.frequencyHz) / 2.0) > 80.0) {
            active?.flush()
            channel = Channel(pair.first.frequencyHz, pair.second.frequencyHz)
        } else {
            active.retune(pair.first.frequencyHz, pair.second.frequencyHz)
        }
    }

    private fun goertzel(samples: FloatArray, frequencyHz: Double): Double {
        val omega = 2.0 * PI * frequencyHz / PcmRecorder.SAMPLE_RATE
        val coefficient = 2.0 * cos(omega)
        var previous = 0.0
        var previousPrevious = 0.0
        for (sample in samples) {
            val current = sample + coefficient * previous - previousPrevious
            previousPrevious = previous
            previous = current
        }
        return previousPrevious * previousPrevious + previous * previous - coefficient * previous * previousPrevious
    }

    override fun close() {
        channel?.flush()
        channel = null
        accumulator.clear()
    }
}
