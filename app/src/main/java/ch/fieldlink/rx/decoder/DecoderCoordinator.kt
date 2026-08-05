package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.dsp.SpectrumAnalyzer
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import ch.fieldlink.rx.runtime.ReceiverRuntime

class DecoderCoordinator(
    password: CharArray,
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
        DecoderSlot("FieldLink", FieldLinkStreamDecoder(password.copyOf(), onMessage)),
        DecoderSlot(
            "CW",
            CwDecoder(onMessage) { text -> ReceiverRuntime.partial(DecodeMode.CW, text) },
        ),
        DecoderSlot(
            "RTTY",
            RttyDecoder(onMessage) { text -> ReceiverRuntime.partial(DecodeMode.RTTY, text) },
        ),
        DecoderSlot(
            "PSK",
            PskDecoder(onMessage) { mode, text -> ReceiverRuntime.partial(mode, text) },
        ),
    )

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

