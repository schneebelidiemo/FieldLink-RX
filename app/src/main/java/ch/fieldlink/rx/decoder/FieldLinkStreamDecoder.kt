package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.audio.PcmRecorder
import ch.fieldlink.rx.dsp.FloatRingBuffer
import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecoderDiagnostic
import ch.fieldlink.rx.model.DecoderStage
import ch.fieldlink.rx.model.DecodedMessage
import ch.fieldlink.rx.protocol.FieldLinkDecodeResult
import ch.fieldlink.rx.protocol.FieldLinkFec
import ch.fieldlink.rx.protocol.FieldLinkMessageCodec
import ch.fieldlink.rx.protocol.FieldLinkPacketAssembler
import ch.fieldlink.rx.protocol.FieldLinkPacketCodec
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

class FieldLinkStreamDecoder(
    private val password: CharArray,
    selectedMode: DecodeMode,
    private val emit: (DecodedMessage) -> Unit,
    private val diagnostic: ((DecoderDiagnostic) -> Unit)? = null,
) : AudioDecoder {
    companion object {
        private const val CENTER_HZ = 1_500.0
        private const val PREAMBLE_SYMBOLS = 32
        // The last eight tones are the unique sync word. Requiring six of them
        // prevents the long alternating lead-in from locking two symbols early,
        // while allowing an acoustic path to damage part of the lead-in.
        private const val MIN_ALTERNATING_MATCHES = 12
        private const val MIN_SYNC_MATCHES = 6
        private const val DIAGNOSTIC_PREAMBLE_MATCHES = 12
        private const val PHASE_COUNT = 8
        private const val BUFFER_SAMPLES = 2_200_000
    }

    private data class Profile(
        val mode: DecodeMode,
        val tones: Int,
        val bitsPerSymbol: Int,
        val spacingHz: Double,
        val symbolSamples: Int,
        val offsets: IntArray,
    ) {
        val dataSymbols = ceil(FieldLinkFec.INTERLEAVED_BIT_LENGTH.toDouble() / bitsPerSymbol).toInt()
        val frameSamples = (PREAMBLE_SYMBOLS + dataSymbols) * symbolSamples
        val pattern: IntArray = IntArray(PREAMBLE_SYMBOLS).also { values ->
            for (index in 0 until 24) values[index] = if (index % 2 == 0) 0 else tones - 1
            val sync = intArrayOf(1, tones - 2, 2, tones - 3, 3, tones - 4, 4, tones - 5)
            sync.copyInto(values, 24)
        }
    }

    private data class ToneDetection(
        val start: Long,
        val tone: Int,
        val offsetHz: Int,
        val confidence: Float,
    )

    private data class Candidate(
        val start: Long,
        val offsetHz: Double,
        val preambleQuality: Float,
        val score: Double,
    )

    private inner class ModeDetector(val profile: Profile) {
        private val hop = profile.symbolSamples / PHASE_COUNT
        private val histories = Array(PHASE_COUNT) { ArrayDeque<ToneDetection>() }
        private val nextStarts = LongArray(PHASE_COUNT) { phase -> (phase * hop).toLong() }
        private var bestReportedMatches = 0
        var pending: Candidate? = null
        var ignoreBefore: Long = 0

        fun scan() {
            if (pending != null) return
            var bestCandidate: Candidate? = null
            for (phase in 0 until PHASE_COUNT) {
                var next = maxOf(nextStarts[phase], ring.startSample)
                val phaseRemainder = ((next - phase * hop) % profile.symbolSamples + profile.symbolSamples) % profile.symbolSamples
                if (phaseRemainder != 0L) next += profile.symbolSamples - phaseRemainder
                while (next + profile.symbolSamples <= ring.endSample) {
                    nextStarts[phase] = next + profile.symbolSamples
                    if (next >= ignoreBefore) {
                        val detection = detectTone(ring.copy(next, profile.symbolSamples), next, profile)
                        val history = histories[phase]
                        history.addLast(detection)
                        while (history.size > PREAMBLE_SYMBOLS) history.removeFirst()
                        if (history.size == PREAMBLE_SYMBOLS) {
                            var alternatingMatches = 0
                            var syncMatches = 0
                            var weightedOffset = 0.0
                            var confidence = 0.0
                            history.forEachIndexed { index, value ->
                                if (value.tone == profile.pattern[index]) {
                                    if (index < 24) alternatingMatches += 1 else syncMatches += 1
                                    weightedOffset += value.offsetHz * value.confidence
                                    confidence += value.confidence
                                }
                            }
                            val matches = alternatingMatches + syncMatches
                            if (matches >= DIAGNOSTIC_PREAMBLE_MATCHES && matches > bestReportedMatches) {
                                bestReportedMatches = matches
                                diagnostic?.invoke(
                                    DecoderDiagnostic(
                                        stage = DecoderStage.PREAMBLE,
                                        preambleMatches = matches,
                                        syncMatches = syncMatches,
                                    ),
                                )
                            }
                            if (
                                alternatingMatches >= MIN_ALTERNATING_MATCHES &&
                                syncMatches >= MIN_SYNC_MATCHES
                            ) {
                                val averageConfidence = if (matches > 0) confidence / matches else 0.0
                                val candidate = Candidate(
                                    start = history.first.start,
                                    offsetHz = if (confidence > 0.0) weightedOffset / confidence else 0.0,
                                    preambleQuality = matches.toFloat() / PREAMBLE_SYMBOLS,
                                    score = syncMatches * 10.0 + alternatingMatches + averageConfidence,
                                )
                                if (bestCandidate == null || candidate.score > bestCandidate.score) {
                                    bestCandidate = candidate
                                }
                            }
                        }
                    }
                    next += profile.symbolSamples
                }
            }
            bestCandidate?.let { candidate ->
                pending = candidate
                histories.forEach { it.clear() }
                bestReportedMatches = 0
            }
        }

        fun decodeReady(): DecodedFrame? {
            val candidate = pending ?: return null
            if (candidate.start < ring.startSample) {
                pending = null
                return null
            }
            if (ring.endSample < candidate.start + profile.frameSamples) return null
            val frame = ring.copy(candidate.start, profile.frameSamples)
            val symbols = IntArray(profile.dataSymbols)
            var confidenceSum = 0.0
            val dataStart = PREAMBLE_SYMBOLS * profile.symbolSamples
            for (index in symbols.indices) {
                val start = dataStart + index * profile.symbolSamples
                val detection = detectTone(
                    samples = frame.copyOfRange(start, start + profile.symbolSamples),
                    absoluteStart = candidate.start + start,
                    profile = profile,
                    fixedOffsetHz = candidate.offsetHz,
                )
                symbols[index] = detection.tone
                confidenceSum += detection.confidence
            }

            val bits = ByteArray(symbols.size * profile.bitsPerSymbol)
            var bitOffset = 0
            for (symbol in symbols) {
                for (bit in profile.bitsPerSymbol - 1 downTo 0) {
                    bits[bitOffset++] = ((symbol ushr bit) and 1).toByte()
                }
            }
            pending = null
            ignoreBefore = candidate.start + profile.frameSamples
            for (phase in 0 until PHASE_COUNT) {
                nextStarts[phase] = maxOf(nextStarts[phase], ignoreBefore + phase * hop)
            }

            return DecodedFrame(
                mode = profile.mode,
                bits = bits.copyOf(FieldLinkFec.INTERLEAVED_BIT_LENGTH),
                centerFrequencyHz = CENTER_HZ + candidate.offsetHz,
                quality = ((candidate.preambleQuality + confidenceSum / symbols.size) / 2.0).toFloat().coerceIn(0f, 1f),
            )
        }
    }

    private data class DecodedFrame(
        val mode: DecodeMode,
        val bits: ByteArray,
        val centerFrequencyHz: Double,
        val quality: Float,
    )

    private val ring = FloatRingBuffer(BUFFER_SAMPLES)
    private val assembler = FieldLinkPacketAssembler()
    private val detectors = listOf(
        ModeDetector(
            Profile(
                mode = DecodeMode.FIELDLINK_FAST,
                tones = 16,
                bitsPerSymbol = 4,
                spacingHz = 100.0,
                symbolSamples = 480,
                offsets = intArrayOf(-60, -30, 0, 30, 60),
            ),
        ),
        ModeDetector(
            Profile(
                mode = DecodeMode.FIELDLINK_WIDE,
                tones = 8,
                bitsPerSymbol = 3,
                spacingHz = 20.0,
                symbolSamples = 2_400,
                offsets = intArrayOf(-18, -12, -6, 0, 6, 12, 18),
            ),
        ),
    ).filter { it.profile.mode == selectedMode }.also {
        require(it.size == 1) { "A FieldLink Fast or Wide decoder must be selected." }
    }

    override fun process(samples: FloatArray, spectrum: SpectrumAnalysis?) {
        ring.append(samples)
        for (detector in detectors) {
            detector.scan()
            val frame = detector.decodeReady() ?: continue
            decodeFrame(frame)
        }
    }

    private fun decodeFrame(frame: DecodedFrame) {
        diagnostic?.invoke(DecoderDiagnostic(stage = DecoderStage.FRAME))
        try {
            val block = FieldLinkFec.decode(frame.bits)
            val packet = FieldLinkPacketCodec.fixedBlockToPacket(block)
            val envelope = assembler.add(packet) ?: return
            when (val result = FieldLinkMessageCodec.decode(envelope, packet.messageId, password)) {
                is FieldLinkDecodeResult.Success -> {
                    diagnostic?.invoke(DecoderDiagnostic(stage = DecoderStage.SUCCESS))
                    emit(
                        DecodedMessage(
                            id = packet.messageId.joinToString("") { "%02x".format(it.toInt() and 0xff) },
                            mode = frame.mode,
                            text = result.body.text.orEmpty().ifBlank { result.body.kind },
                            callsign = result.body.callsign,
                            coordinates = result.body.coordinates,
                            audioFrequencyHz = frame.centerFrequencyHz,
                            quality = frame.quality,
                            uncertain = frame.quality < 0.72f,
                        ),
                    )
                }
                is FieldLinkDecodeResult.EncryptedWithoutKey -> {
                    diagnostic?.invoke(DecoderDiagnostic(stage = DecoderStage.ENCRYPTED))
                    emit(
                        DecodedMessage(
                            id = packet.messageId.joinToString("") { "%02x".format(it.toInt() and 0xff) },
                            mode = frame.mode,
                            text = "Encrypted message received",
                            audioFrequencyHz = frame.centerFrequencyHz,
                            quality = frame.quality,
                            uncertain = false,
                            encryptedWithoutKey = true,
                        ),
                    )
                }
                is FieldLinkDecodeResult.Damaged -> emitDamaged(frame, result.reason)
            }
        } catch (error: Throwable) {
            emitDamaged(frame, error.message ?: "Damaged FieldLink frame")
        }
    }

    private fun emitDamaged(frame: DecodedFrame, reason: String) {
        diagnostic?.invoke(DecoderDiagnostic(stage = DecoderStage.DAMAGED, detail = reason))
        emit(
            DecodedMessage(
                mode = frame.mode,
                text = reason,
                audioFrequencyHz = frame.centerFrequencyHz,
                quality = frame.quality.coerceAtMost(0.45f),
                uncertain = true,
                complete = false,
            ),
        )
    }

    override fun close() {
        password.fill('\u0000')
    }

    private fun detectTone(
        samples: FloatArray,
        absoluteStart: Long,
        profile: Profile,
        fixedOffsetHz: Double? = null,
    ): ToneDetection {
        var bestTone = 0
        var bestOffset = 0
        var bestPower = -1.0
        var secondPower = -1.0
        val offsets = fixedOffsetHz?.let { intArrayOf(it.toInt()) } ?: profile.offsets
        for (tone in 0 until profile.tones) {
            val nominal = CENTER_HZ + (tone - (profile.tones - 1) / 2.0) * profile.spacingHz
            var toneBest = -1.0
            var toneOffset = 0
            for (offset in offsets) {
                val power = goertzel(samples, nominal + offset)
                if (power > toneBest) {
                    toneBest = power
                    toneOffset = offset
                }
            }
            if (toneBest > bestPower) {
                secondPower = bestPower
                bestPower = toneBest
                bestTone = tone
                bestOffset = toneOffset
            } else if (toneBest > secondPower) {
                secondPower = toneBest
            }
        }
        val confidence = if (bestPower <= 0.0) 0f else ((bestPower - secondPower.coerceAtLeast(0.0)) / bestPower).toFloat()
        return ToneDetection(absoluteStart, bestTone, bestOffset, confidence.coerceIn(0f, 1f))
    }

    private fun goertzel(samples: FloatArray, frequencyHz: Double): Double {
        val omega = 2.0 * PI * frequencyHz / PcmRecorder.SAMPLE_RATE
        val coefficient = 2.0 * cos(omega)
        var previous = 0.0
        var previousPrevious = 0.0
        // A rectangular symbol window is deliberate: FieldLink tone spacing is
        // exactly one Fourier bin (100 Hz/10 ms or 20 Hz/50 ms). A Hann window
        // broadens the main lobe across adjacent MFSK tones.
        for (sample in samples) {
            val current = sample + coefficient * previous - previousPrevious
            previousPrevious = previous
            previous = current
        }
        return previousPrevious * previousPrevious + previous * previous - coefficient * previous * previousPrevious
    }
}
