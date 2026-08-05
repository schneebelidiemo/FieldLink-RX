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
import kotlin.math.log10

class CwDecoder(
    private val emit: (DecodedMessage) -> Unit,
    private val partial: (String) -> Unit,
) : AudioDecoder {
    companion object {
        private const val TICK_SAMPLES = 480 // 10 ms at 48 kHz
        private const val MAX_TRACKS = 3
        private const val TRACK_DISTANCE_HZ = 28.0
        private const val TRACK_TIMEOUT_TICKS = 250
        private val MORSE = mapOf(
            ".-" to 'A', "-..." to 'B', "-.-." to 'C', "-.." to 'D', "." to 'E',
            "..-." to 'F', "--." to 'G', "...." to 'H', ".." to 'I', ".---" to 'J',
            "-.-" to 'K', ".-.." to 'L', "--" to 'M', "-." to 'N', "---" to 'O',
            ".--." to 'P', "--.-" to 'Q', ".-." to 'R', "..." to 'S', "-" to 'T',
            "..-" to 'U', "...-" to 'V', ".--" to 'W', "-..-" to 'X', "-.--" to 'Y',
            "--.." to 'Z', ".----" to '1', "..---" to '2', "...--" to '3', "....-" to '4',
            "....." to '5', "-...." to '6', "--..." to '7', "---.." to '8', "----." to '9',
            "-----" to '0', ".-.-.-" to '.', "--..--" to ',', "..--.." to '?', ".----." to '\'',
            "-.-.--" to '!', "-..-." to '/', "-.--." to '(', "-.--.-" to ')', ".-..." to '&',
            "---..." to ':', "-.-.-." to ';', "-...-" to '=', ".-.-." to '+', "-....-" to '-',
            "..--.-" to '_', ".-..-." to '"', "...-..-" to '$', ".--.-." to '@',
        )
    }

    private inner class Track(
        var frequencyHz: Double,
        var levelDb: Float,
    ) {
        var lastPeakTick = tickCounter
        private var toneOn = false
        private var stateTicks = 0
        private var dotTicks = 8.0
        private var symbol = StringBuilder()
        private var text = StringBuilder()
        private var timingScore = 0.7f
        private var emittedAfterSilence = false

        fun update(block: FloatArray) {
            val tonePower = goertzel(block, frequencyHz)
            val sidePower = (goertzel(block, frequencyHz - 55.0) + goertzel(block, frequencyHz + 55.0)) / 2.0
            val ratioDb = 10.0 * log10((tonePower + 1e-12) / (sidePower + 1e-12))
            val nextOn = ratioDb > 5.5 && tonePower > 0.001
            stateTicks += 1
            if (nextOn == toneOn) {
                if (!toneOn && stateTicks > (dotTicks * 10).toInt()) finalizeTransmission()
                return
            }

            if (toneOn) onMarkEnded(stateTicks) else onGapEnded(stateTicks)
            toneOn = nextOn
            stateTicks = 1
        }

        fun retune(peak: SpectralPeak) {
            frequencyHz = frequencyHz * 0.75 + peak.frequencyHz * 0.25
            levelDb = peak.levelDb
            lastPeakTick = tickCounter
        }

        fun flushIfStale() {
            if (tickCounter - lastPeakTick > TRACK_TIMEOUT_TICKS) finalizeTransmission()
        }

        private fun onMarkEnded(ticks: Int) {
            val ratio = ticks / dotTicks
            if (ratio < 2.0) {
                symbol.append('.')
                dotTicks = (dotTicks * 0.85 + ticks * 0.15).coerceIn(2.0, 30.0)
                timingScore = (timingScore * 0.9f + (1.0 - abs(1.0 - ratio)).coerceIn(0.0, 1.0).toFloat() * 0.1f)
            } else {
                symbol.append('-')
                val dashDot = ticks / (dotTicks * 3.0)
                timingScore = (timingScore * 0.9f + (1.0 - abs(1.0 - dashDot)).coerceIn(0.0, 1.0).toFloat() * 0.1f)
            }
            emittedAfterSilence = false
        }

        private fun onGapEnded(ticks: Int) {
            if (ticks >= dotTicks * 6.0) {
                finalizeCharacter()
                appendSpace()
            } else if (ticks >= dotTicks * 2.2) {
                finalizeCharacter()
            }
        }

        private fun finalizeCharacter() {
            if (symbol.isEmpty()) return
            val decoded = MORSE[symbol.toString()] ?: '�'
            text.append(decoded)
            symbol = StringBuilder()
            publishPartial()
        }

        private fun appendSpace() {
            if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
            publishPartial()
        }

        private fun finalizeTransmission() {
            if (emittedAfterSilence) return
            finalizeCharacter()
            val value = text.toString().trim()
            if (value.length >= 2) {
                emit(
                    DecodedMessage(
                        mode = DecodeMode.CW,
                        text = value,
                        audioFrequencyHz = frequencyHz,
                        quality = timingScore.coerceIn(0.05f, 1f),
                        uncertain = timingScore < 0.62f || value.contains('�'),
                    ),
                )
            }
            text = StringBuilder()
            symbol = StringBuilder()
            emittedAfterSilence = true
            publishAllPartials()
        }

        fun currentText(): String = text.toString()
    }

    private val accumulator = FixedSampleAccumulator(TICK_SAMPLES)
    private val tracks = mutableListOf<Track>()
    private var tickCounter = 0L

    override fun process(samples: FloatArray, spectrum: SpectrumAnalysis?) {
        spectrum?.let { updateTracks(it.peaks) }
        accumulator.add(samples) { block ->
            tickCounter += 1
            tracks.forEach { it.update(block) }
            tracks.forEach { it.flushIfStale() }
            tracks.removeAll { tickCounter - it.lastPeakTick > TRACK_TIMEOUT_TICKS && it.currentText().isBlank() }
        }
    }

    private fun updateTracks(peaks: List<SpectralPeak>) {
        val plausible = peaks.filter { it.frequencyHz in 300.0..2_800.0 && it.levelDb > -72f }
            .take(MAX_TRACKS)
        for (peak in plausible) {
            val existing = tracks.minByOrNull { abs(it.frequencyHz - peak.frequencyHz) }
            if (existing != null && abs(existing.frequencyHz - peak.frequencyHz) <= TRACK_DISTANCE_HZ) {
                existing.retune(peak)
            } else if (tracks.size < MAX_TRACKS) {
                tracks += Track(peak.frequencyHz, peak.levelDb)
            }
        }
    }

    private fun publishAllPartials() {
        partial(tracks.map { it.currentText() }.filter { it.isNotBlank() }.joinToString("  ·  "))
    }

    private fun Track.publishPartial() = publishAllPartials()

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
        tracks.forEach { it.flushIfStale() }
        tracks.clear()
        accumulator.clear()
        partial("")
    }

}
