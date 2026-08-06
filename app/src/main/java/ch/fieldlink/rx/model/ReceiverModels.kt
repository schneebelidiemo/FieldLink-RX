package ch.fieldlink.rx.model

import java.time.Instant
import java.util.UUID

enum class DecodeMode(val displayName: String) {
    FIELDLINK_MEDIUM("FieldLink Medium"),
    FIELDLINK_WIDE("FieldLink Wide"),
    CW("CW/Morse"),
    RTTY("RTTY"),
    PSK31("PSK31"),
    PSK63("PSK63"),
    FT8("FT8"),
    FT4("FT4"),
    JS8("JS8Call"),
}

data class Coordinates(
    val latitude: Double,
    val longitude: Double,
)

data class DecodedMessage(
    val id: String = UUID.randomUUID().toString(),
    val mode: DecodeMode,
    val text: String,
    val callsign: String? = null,
    val coordinates: Coordinates? = null,
    val receivedAt: Instant = Instant.now(),
    val audioFrequencyHz: Double,
    val quality: Float,
    val uncertain: Boolean,
    val complete: Boolean = true,
    val encryptedWithoutKey: Boolean = false,
)

data class AudioInput(
    val id: Int,
    val productName: String,
    val typeName: String,
    val isBuiltIn: Boolean,
)

enum class ReceiverPhase {
    NEEDS_PASSWORD,
    STARTING,
    LISTENING,
    STOPPED,
    ERROR,
}

data class SpectrumFrame(
    val bins: FloatArray,
    val minFrequencyHz: Int = 0,
    val maxFrequencyHz: Int = 3_000,
)

data class SignalSnapshot(
    val rmsDb: Float = -120f,
    val peakFrequencyHz: Double = 0.0,
    val peakDb: Float = -120f,
)

data class AudioCaptureInfo(
    val sampleRateHz: Int,
    val source: AudioCaptureSource,
    val routedDevice: String,
)

enum class AudioCaptureSource {
    UNPROCESSED,
    VOICE_RECOGNITION,
    MICROPHONE,
    OTHER,
}

enum class AudioCaptureMode {
    AUTOMATIC,
    UNPROCESSED,
    VOICE_RECOGNITION,
    MICROPHONE,
}

enum class DecoderStage {
    WAITING,
    PREAMBLE,
    FRAME,
    SUCCESS,
    ENCRYPTED,
    DAMAGED,
}

data class DecoderDiagnostic(
    val stage: DecoderStage = DecoderStage.WAITING,
    val preambleMatches: Int? = null,
    val syncMatches: Int? = null,
    val detail: String? = null,
)

data class ReceiverState(
    val phase: ReceiverPhase = ReceiverPhase.NEEDS_PASSWORD,
    val selectedInputId: Int? = null,
    val selectedMode: DecodeMode? = null,
    val audioCaptureMode: AudioCaptureMode = AudioCaptureMode.MICROPHONE,
    val inputs: List<AudioInput> = emptyList(),
    val signal: SignalSnapshot = SignalSnapshot(),
    val waterfall: List<SpectrumFrame> = emptyList(),
    val messages: List<DecodedMessage> = emptyList(),
    val partialTexts: Map<DecodeMode, String> = emptyMap(),
    val captureInfo: AudioCaptureInfo? = null,
    val decoderDiagnostic: DecoderDiagnostic = DecoderDiagnostic(),
    val error: String? = null,
)
