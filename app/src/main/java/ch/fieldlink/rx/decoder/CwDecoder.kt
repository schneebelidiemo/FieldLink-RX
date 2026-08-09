package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.audio.PcmRecorder
import ch.fieldlink.rx.dsp.FixedSampleAccumulator
import ch.fieldlink.rx.dsp.SpectralPeak
import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.model.CwSettings
import ch.fieldlink.rx.model.CwTrackSnapshot
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10

class CwDecoder(
    private val emit: (DecodedMessage) -> Unit,
    private val settingsProvider: () -> CwSettings,
    private val status: (List<CwTrackSnapshot>) -> Unit,
) : AudioDecoder {
    companion object {
        private const val TICK_SAMPLES = 480 // 10 ms at 48 kHz; required for 60 WPM dots.
        private const val MAX_TRACKS = 3
        private const val MIN_TRACK_DISTANCE_HZ = 40.0
        private const val ACQUISITION_DISTANCE_HZ = 20.0
        private const val REQUIRED_STABLE_PEAKS = 4
        private const val CANDIDATE_TIMEOUT_TICKS = 80
        private const val MIN_WPM = 3
        private const val MAX_WPM = 60
        private const val AUTOMATIC_MIN_HZ = 300.0
        private const val AUTOMATIC_MAX_HZ = 1_000.0

        private val MORSE = mapOf(
            ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E",
            "..-." to "F", "--." to "G", "...." to "H", ".." to "I", ".---" to "J",
            "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N", "---" to "O",
            ".--." to "P", "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T",
            "..-" to "U", "...-" to "V", ".--" to "W", "-..-" to "X", "-.--" to "Y",
            "--.." to "Z", ".----" to "1", "..---" to "2", "...--" to "3", "....-" to "4",
            "....." to "5", "-...." to "6", "--..." to "7", "---.." to "8", "----." to "9",
            "-----" to "0", ".-.-.-" to ".", "--..--" to ",", "..--.." to "?", ".----." to "'",
            "-.-.--" to "!", "-..-." to "/", "-.--.-" to ")", ".-..." to "&",
            "---..." to ":", "-.-.-." to ";", ".-.-." to "<AR>", "...-.-" to "<SK>",
            "-...-" to "<BT>", "-.--." to "<KN>", "-....-" to "-", "..--.-" to "_",
            ".-..-." to "\"", "...-..-" to "\$", ".--.-." to "@",
        )
    }

    private data class Candidate(
        var frequencyHz: Double,
        var levelDb: Float,
        var stableHits: Int,
        var lastSeenTick: Long,
    )

    private inner class Track(
        var frequencyHz: Double,
        var levelDb: Float,
    ) {
        var lastPeakTick = tickCounter
            private set

        private var toneOn = false
        private var stateTicks = 0
        private var dotTicks: Double? = null
        private var symbol = StringBuilder()
        private var symbolInvalid = false
        private var text = StringBuilder()
        private var validCharacters = 0
        private var invalidCharacters = 0
        private var timingScore = 0.65f
        private var signalScore = 0.65f
        private var noiseFloorDb = -96.0
        private var markConfidenceSum = 0.0f
        private var markConfidenceSamples = 0
        private var emittedAfterSilence = false

        fun update(block: FloatArray, settings: CwSettings) {
            val tonePower = goertzel(block, frequencyHz)
            val sidePower = listOf(-140.0, -90.0, 90.0, 140.0)
                .sumOf { offset -> goertzel(block, frequencyHz + offset) } / 4.0
            val toneDb = normalizedPowerDb(tonePower, block.size)
            val sideDb = normalizedPowerDb(sidePower, block.size)
            val ratioDb = 10.0 * log10((tonePower + 1e-12) / (sidePower + 1e-12))
            val sensitivity = settings.sensitivity.coerceIn(0, 100)
            val narrowbandRequiredDb = 12.0 - sensitivity * 0.06
            val fixedMinimumDb = -48.0 - sensitivity * 0.40
            val adaptiveMarginDb = 14.0 - sensitivity * 0.08
            val levelThreshold = if (settings.automaticNoiseThreshold) {
                noiseFloorDb + adaptiveMarginDb
            } else {
                fixedMinimumDb
            }
            val hysteresis = if (toneOn) 1.5 else 0.0
            val nextOn = ratioDb >= narrowbandRequiredDb - hysteresis && toneDb >= levelThreshold - hysteresis

            val toneConfidence = (
                ((ratioDb - narrowbandRequiredDb + 8.0) / 16.0) * 0.55 +
                    ((toneDb - levelThreshold + 12.0) / 24.0) * 0.45
                ).toFloat().coerceIn(0f, 1f)
            val noiseRate = if (sideDb > noiseFloorDb) 0.04 else 0.12
            noiseFloorDb = noiseFloorDb * (1.0 - noiseRate) + sideDb * noiseRate

            stateTicks += 1
            if (nextOn) {
                markConfidenceSum += toneConfidence
                markConfidenceSamples += 1
            }
            if (nextOn == toneOn) {
                if (!toneOn && stateTicks >= settings.messageGapSeconds.coerceIn(1, 15) * 100) {
                    finalizeTransmission()
                }
                return
            }

            if (toneOn) onMarkEnded(stateTicks, settings) else onGapEnded(stateTicks, settings)
            toneOn = nextOn
            stateTicks = 1
        }

        fun retune(peak: SpectralPeak) {
            frequencyHz = frequencyHz * 0.80 + peak.frequencyHz * 0.20
            levelDb = levelDb * 0.70f + peak.levelDb * 0.30f
            lastPeakTick = tickCounter
        }

        fun flushIfStale(settings: CwSettings) {
            val timeoutTicks = settings.messageGapSeconds.coerceIn(1, 15) * 100 + 75
            if (tickCounter - lastPeakTick > timeoutTicks) finalizeTransmission()
        }

        fun forceFinalize() = finalizeTransmission()

        private fun currentDotTicks(settings: CwSettings): Double = if (settings.automaticSpeed) {
            dotTicks ?: (120.0 / 18.0)
        } else {
            120.0 / settings.manualWpm.coerceIn(MIN_WPM, MAX_WPM)
        }

        private fun onMarkEnded(ticks: Int, settings: CwSettings) {
            val markConfidence = consumeMarkConfidence()
            val maximumDotTicks = 120.0 / MIN_WPM
            val maximumMarkTicks = ceil(maximumDotTicks * 3.8).toInt()
            if (ticks !in 1..maximumMarkTicks) {
                symbolInvalid = true
                timingScore *= 0.82f
                return
            }

            if (settings.automaticSpeed) {
                val current = dotTicks
                dotTicks = when {
                    current == null && ticks > maximumDotTicks -> (ticks / 3.0).coerceIn(2.0, maximumDotTicks)
                    current == null -> ticks.toDouble().coerceIn(2.0, maximumDotTicks)
                    ticks < current * 1.75 -> (current * 0.78 + ticks * 0.22).coerceIn(2.0, maximumDotTicks)
                    else -> current
                }
            }

            val dot = currentDotTicks(settings)
            val ratio = ticks / dot
            val timingQuality = when {
                ratio in 0.45..1.85 -> {
                    symbol.append('.')
                    1.0 - abs(1.0 - ratio)
                }
                ratio in 1.85..4.40 -> {
                    symbol.append('-')
                    1.0 - abs(3.0 - ratio) / 3.0
                }
                else -> {
                    symbolInvalid = true
                    0.0
                }
            }.coerceIn(0.0, 1.0)
            timingScore = timingScore * 0.86f + timingQuality.toFloat() * 0.14f
            signalScore = signalScore * 0.86f + markConfidence * 0.14f
            emittedAfterSilence = false
        }

        private fun consumeMarkConfidence(): Float {
            val average = if (markConfidenceSamples == 0) {
                0.0f
            } else {
                markConfidenceSum / markConfidenceSamples
            }
            markConfidenceSum = 0.0f
            markConfidenceSamples = 0
            return average
        }

        private fun onGapEnded(ticks: Int, settings: CwSettings) {
            val dot = currentDotTicks(settings)
            when {
                ticks >= dot * 6.0 -> {
                    finalizeCharacter()
                    appendSpace()
                }
                ticks >= dot * 2.15 -> finalizeCharacter()
            }
        }

        private fun finalizeCharacter() {
            if (symbol.isEmpty() && !symbolInvalid) return
            val decoded = if (symbolInvalid || symbol.length > 6) null else MORSE[symbol.toString()]
            if (decoded == null) {
                text.append('?')
                invalidCharacters += 1
            } else {
                text.append(decoded)
                validCharacters += 1
            }
            symbol = StringBuilder()
            symbolInvalid = false
        }

        private fun appendSpace() {
            if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
        }

        private fun finalizeTransmission() {
            if (emittedAfterSilence) return
            finalizeCharacter()
            val value = text.toString().trim()
            val containsProsign = value.contains('<') && value.contains('>')
            if (value.isNotBlank() && validCharacters > 0) {
                val shortFragment = validCharacters < 3 && !containsProsign
                val rendered = if (shortFragment && !value.endsWith('?')) "$value?" else value
                val quality = quality()
                emit(
                    DecodedMessage(
                        mode = DecodeMode.CW,
                        text = rendered,
                        audioFrequencyHz = frequencyHz,
                        quality = quality,
                        uncertain = shortFragment || invalidCharacters > 0 || quality < 0.55f,
                        complete = !shortFragment,
                        speedWpm = speedWpm(),
                    ),
                )
            }
            text = StringBuilder()
            symbol = StringBuilder()
            symbolInvalid = false
            validCharacters = 0
            invalidCharacters = 0
            emittedAfterSilence = true
        }

        fun snapshot(settings: CwSettings): CwTrackSnapshot {
            val value = text.toString().trim()
            val containsProsign = value.contains('<') && value.contains('>')
            val visible = if ((validCharacters >= 3 || containsProsign) && timingScore >= 0.50f) value else ""
            return CwTrackSnapshot(
                frequencyHz = frequencyHz,
                speedWpm = speedWpm(settings),
                quality = quality(),
                text = visible,
            )
        }

        fun isEmpty(): Boolean = text.isBlank() && symbol.isEmpty() && !toneOn

        private fun speedWpm(settings: CwSettings = settingsProvider()): Double =
            (120.0 / currentDotTicks(settings)).coerceIn(MIN_WPM.toDouble(), MAX_WPM.toDouble())

        private fun quality(): Float = ((timingScore + signalScore) / 2f).coerceIn(0.05f, 1f)
    }

    private val accumulator = FixedSampleAccumulator(TICK_SAMPLES)
    private val candidates = mutableListOf<Candidate>()
    private val tracks = mutableListOf<Track>()
    private var tickCounter = 0L

    override fun process(samples: FloatArray, spectrum: SpectrumAnalysis?) {
        spectrum?.let { updateTracks(it.peaks, settingsProvider()) }
        accumulator.add(samples) { block ->
            tickCounter += 1
            val settings = settingsProvider()
            tracks.forEach { it.update(block, settings) }
            tracks.forEach { it.flushIfStale(settings) }
            val timeoutTicks = settings.messageGapSeconds.coerceIn(1, 15) * 100 + 75
            tracks.removeAll { tickCounter - it.lastPeakTick > timeoutTicks && it.isEmpty() }
        }
        publishStatus()
    }

    private fun updateTracks(peaks: List<SpectralPeak>, settings: CwSettings) {
        val sensitivity = settings.sensitivity.coerceIn(0, 100)
        val minimumLevelDb = (-48.0 - sensitivity * 0.40).toFloat()
        val frequencyRange = if (settings.automaticTone) {
            AUTOMATIC_MIN_HZ..AUTOMATIC_MAX_HZ
        } else {
            (settings.manualToneHz.coerceIn(200, 1_500) - 30.0)..
                (settings.manualToneHz.coerceIn(200, 1_500) + 30.0)
        }
        tracks.filter { it.frequencyHz !in frequencyRange }.forEach { it.forceFinalize() }
        tracks.removeAll { it.frequencyHz !in frequencyRange }
        candidates.removeAll { it.frequencyHz !in frequencyRange }
        val plausible = peaks
            .filter { it.frequencyHz in frequencyRange && it.levelDb >= minimumLevelDb }
            .sortedByDescending { it.levelDb }
            .fold(mutableListOf<SpectralPeak>()) { selected, peak ->
                if (selected.none { abs(it.frequencyHz - peak.frequencyHz) < MIN_TRACK_DISTANCE_HZ }) selected += peak
                selected
            }
            .take(MAX_TRACKS)

        for (peak in plausible) {
            val existingTrack = tracks.minByOrNull { abs(it.frequencyHz - peak.frequencyHz) }
            if (existingTrack != null && abs(existingTrack.frequencyHz - peak.frequencyHz) <= ACQUISITION_DISTANCE_HZ) {
                existingTrack.retune(peak)
                continue
            }

            val candidate = candidates.minByOrNull { abs(it.frequencyHz - peak.frequencyHz) }
            if (candidate != null && abs(candidate.frequencyHz - peak.frequencyHz) <= ACQUISITION_DISTANCE_HZ) {
                candidate.frequencyHz = candidate.frequencyHz * 0.75 + peak.frequencyHz * 0.25
                candidate.levelDb = peak.levelDb
                candidate.stableHits += 1
                candidate.lastSeenTick = tickCounter
                if (
                    candidate.stableHits >= REQUIRED_STABLE_PEAKS &&
                    tracks.size < MAX_TRACKS &&
                    tracks.none { abs(it.frequencyHz - candidate.frequencyHz) < MIN_TRACK_DISTANCE_HZ }
                ) {
                    tracks += Track(candidate.frequencyHz, candidate.levelDb)
                    candidates.remove(candidate)
                }
            } else if (tracks.size + candidates.size < MAX_TRACKS * 3) {
                candidates += Candidate(peak.frequencyHz, peak.levelDb, 1, tickCounter)
            }
        }
        candidates.removeAll { tickCounter - it.lastSeenTick > CANDIDATE_TIMEOUT_TICKS }
    }

    private fun publishStatus() {
        val settings = settingsProvider()
        status(tracks.sortedBy { it.frequencyHz }.take(MAX_TRACKS).map { it.snapshot(settings) })
    }

    private fun normalizedPowerDb(power: Double, sampleCount: Int): Double =
        10.0 * log10(power / (sampleCount.toDouble() * sampleCount.toDouble()) + 1e-12)

    private fun goertzel(samples: FloatArray, frequencyHz: Double): Double {
        if (frequencyHz <= 0.0 || frequencyHz >= PcmRecorder.SAMPLE_RATE / 2.0) return 0.0
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
        tracks.forEach { it.forceFinalize() }
        tracks.clear()
        candidates.clear()
        accumulator.clear()
        status(emptyList())
    }
}
