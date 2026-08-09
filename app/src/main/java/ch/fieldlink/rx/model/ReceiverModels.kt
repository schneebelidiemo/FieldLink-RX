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
    val speedWpm: Double? = null,
)

data class CwSettings(
    val automaticSpeed: Boolean = true,
    val manualWpm: Int = 18,
    val automaticTone: Boolean = true,
    val manualToneHz: Int = 700,
    val automaticNoiseThreshold: Boolean = true,
    val sensitivity: Int = 50,
    val messageGapSeconds: Int = 3,
)

data class CwTrackSnapshot(
    val frequencyHz: Double,
    val speedWpm: Double,
    val quality: Float,
    val text: String,
)

data class AudioInput(
    val id: Int,
    val productName: String,
    val typeName: String,
    val isBuiltIn: Boolean,
    val kind: AudioInputKind = AudioInputKind.MICROPHONE,
    val usbDeviceId: Int? = null,
)

enum class AudioInputKind {
    MICROPHONE,
    RTL_SDR,
}

enum class SdrModulation(val displayName: String) {
    USB("USB"),
    LSB("LSB"),
    CW("CW"),
    AM("AM"),
    NFM("NFM"),
    WFM("WFM"),
}

data class SdrSettings(
    val frequencyHz: Long = 14_074_000L,
    val modulation: SdrModulation = SdrModulation.USB,
    val automaticBandwidth: Boolean = true,
    val manualBandwidthHz: Int = 3_000,
    val automaticGain: Boolean = true,
    val manualGainPercent: Int = 50,
    val ppmCorrection: Int = 0,
    val squelchEnabled: Boolean = false,
    val squelchThresholdDb: Int = -80,
    val monitorMuted: Boolean = true,
) {
    val bandwidthHz: Int
        get() = if (automaticBandwidth) modulation.defaultBandwidthHz else manualBandwidthHz
            .coerceIn(500, 200_000)

    companion object {
        const val MIN_FREQUENCY_HZ = 500_000L
        const val MAX_FREQUENCY_HZ = 1_766_000_000L
        const val TUNING_STEP_HZ = 100L
    }
}

val SdrModulation.defaultBandwidthHz: Int
    get() = when (this) {
        SdrModulation.USB,
        SdrModulation.LSB -> 3_000
        SdrModulation.CW -> 1_000
        SdrModulation.AM -> 10_000
        SdrModulation.NFM -> 12_500
        SdrModulation.WFM -> 180_000
    }

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
    RTL_SDR,
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
    val sdrSettings: SdrSettings = SdrSettings(),
    val signal: SignalSnapshot = SignalSnapshot(),
    val waterfall: List<SpectrumFrame> = emptyList(),
    val rfWaterfall: List<SpectrumFrame> = emptyList(),
    val messages: List<DecodedMessage> = emptyList(),
    val partialTexts: Map<DecodeMode, String> = emptyMap(),
    val cwSettings: CwSettings = CwSettings(),
    val cwTracks: List<CwTrackSnapshot> = emptyList(),
    val captureInfo: AudioCaptureInfo? = null,
    val decoderDiagnostic: DecoderDiagnostic = DecoderDiagnostic(),
    val error: String? = null,
)
