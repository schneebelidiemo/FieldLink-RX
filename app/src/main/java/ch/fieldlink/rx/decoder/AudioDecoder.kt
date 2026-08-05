package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.dsp.SpectrumAnalysis

interface AudioDecoder : AutoCloseable {
    fun process(samples: FloatArray, spectrum: SpectrumAnalysis?)
    override fun close() = Unit
}

