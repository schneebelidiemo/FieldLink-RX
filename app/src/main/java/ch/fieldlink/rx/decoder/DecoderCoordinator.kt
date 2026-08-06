package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.dsp.SpectrumAnalyzer
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import ch.fieldlink.rx.runtime.ReceiverRuntime

class DecoderCoordinator(
    password: CharArray,
    selectedMode: DecodeMode,
    private val onMessage: (DecodedMessage) -> Unit,
) : AutoCloseable {
    private data class DecoderSlot(
        val name: String,
        val decoder: AudioDecoder,
        var failures: Int = 0,
        var disabled: Boolean = false,
    )

    private val spectrumAnalyzer = SpectrumAnalyzer()
    private var latestSpectrum: SpectrumAnalysis? = null
    private val decoders = listOf(
        DecoderSlot(selectedMode.displayName, decoderFor(selectedMode, password)),
    )

    private fun decoderFor(mode: DecodeMode, password: CharArray): AudioDecoder = when (mode) {
        DecodeMode.FIELDLINK_MEDIUM,
        DecodeMode.FIELDLINK_WIDE -> FieldLinkStreamDecoder(
            password = password.copyOf(),
            selectedMode = mode,
            emit = onMessage,
            diagnostic = ReceiverRuntime::decoderDiagnostic,
        )

        DecodeMode.CW -> CwDecoder(
            emit = onMessage,
            settingsProvider = { ReceiverRuntime.state.value.cwSettings },
            status = ReceiverRuntime::cwTracks,
        )
        DecodeMode.RTTY -> RttyDecoder(onMessage) { text -> ReceiverRuntime.partial(mode, text) }
        DecodeMode.PSK31,
        DecodeMode.PSK63 -> PskDecoder(mode, onMessage) { activeMode, text -> ReceiverRuntime.partial(activeMode, text) }

        DecodeMode.FT8,
        DecodeMode.FT4 -> FtxLiveDecoder(mode, onMessage)

        DecodeMode.JS8 -> Js8LiveDecoder(onMessage)
    }.also {
        if (mode != DecodeMode.FIELDLINK_MEDIUM && mode != DecodeMode.FIELDLINK_WIDE) {
            password.fill('\u0000')
        }
    }

    fun process(samples: FloatArray) {
        val analysis = spectrumAnalyzer.add(samples)
        if (analysis != null) {
            latestSpectrum = analysis
            ReceiverRuntime.signal(analysis.signal, analysis.frame)
        }

        for (slot in decoders) {
            if (slot.disabled) continue
            try {
                slot.decoder.process(samples, latestSpectrum)
                slot.failures = 0
            } catch (_: Throwable) {
                slot.failures += 1
                if (slot.failures >= 3) slot.disabled = true
            }
        }
    }

    override fun close() {
        decoders.forEach { slot -> runCatching { slot.decoder.close() } }
    }
}
