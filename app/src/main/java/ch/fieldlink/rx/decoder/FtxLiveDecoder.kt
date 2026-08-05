package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.dsp.FloatRingBuffer
import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class FtxLiveDecoder(
    private val selectedMode: DecodeMode,
    private val emit: (DecodedMessage) -> Unit,
) : AudioDecoder {
    companion object {
        private const val OUTPUT_RATE = 12_000
        private const val FT8_WINDOW = 15 * OUTPUT_RATE
        // Dense overlap is intentional: the native decoder searches only start
        // positions for which all data symbols fit inside the captured window.
        // A 3 s / 2.5 s hop guarantees such a window without relying on the
        // phone clock being aligned to an FT8 or FT4 slot boundary.
        private const val FT8_HOP = FT8_WINDOW / 5
        private const val FT4_WINDOW = 15 * OUTPUT_RATE / 2
        private const val FT4_HOP = FT4_WINDOW / 3
        private const val MAXIMUM_MESSAGES = 3
    }

    private val decimator = Decimator4()
    private val window = if (selectedMode == DecodeMode.FT8) FT8_WINDOW else FT4_WINDOW
    private val hop = if (selectedMode == DecodeMode.FT8) FT8_HOP else FT4_HOP
    private val nativeMode = if (selectedMode == DecodeMode.FT8) DecodeModeKey.FT8 else DecodeModeKey.FT4
    private val ring = FloatRingBuffer(window + hop)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FieldLink-RX-FTX").apply { priority = Thread.NORM_PRIORITY }
    }
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val recent = mutableMapOf<String, Long>()
    private var nextDecode = window.toLong()

    init {
        require(selectedMode == DecodeMode.FT8 || selectedMode == DecodeMode.FT4) {
            "An FT8 or FT4 decoder must be selected."
        }
    }

    override fun process(samples: FloatArray, spectrum: SpectrumAnalysis?) {
        val reduced = decimator.process(samples)
        if (reduced.isEmpty()) return
        ring.append(reduced)

        var due = false
        while (ring.endSample >= nextDecode) {
            due = true
            nextDecode += hop
        }
        if (!due || ring.size < window) return
        if (!busy.compareAndSet(false, true)) return
        val capture = ring.copy(ring.endSample - window, window)
        executor.execute {
            try {
                NativeFtxBridge.decode(capture, nativeMode, MAXIMUM_MESSAGES)
                    .sortedByDescending { it.score }
                    .take(MAXIMUM_MESSAGES)
                    .forEach(::emitResult)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun emitResult(result: NativeFtxResult) {
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > 30_000L }
        }
        if (closed.get()) return
        val identity = "${selectedMode.name}:${result.text}:${result.frequencyHz.roundToInt()}"
        val duplicate = synchronized(recent) {
            val previous = recent[identity]
            recent[identity] = now
            previous != null && now - previous < 14_000L
        }
        if (duplicate) return
        val quality = ((result.score - 8) / 24f).coerceIn(0.15f, 1f)
        emit(
            DecodedMessage(
                mode = selectedMode,
                text = result.text,
                audioFrequencyHz = result.frequencyHz,
                quality = quality,
                uncertain = result.score < 14,
            ),
        )
    }

    override fun close() {
        closed.set(true)
        executor.shutdownNow()
    }
}
