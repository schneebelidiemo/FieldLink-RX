package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.dsp.FloatRingBuffer
import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class FtxLiveDecoder(
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

    private data class Work(
        val ft8: FloatArray?,
        val ft4: FloatArray?,
    )

    private val decimator = Decimator4()
    private val ring = FloatRingBuffer(FT8_WINDOW + FT8_HOP)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FieldLink-RX-FTX").apply { priority = Thread.NORM_PRIORITY }
    }
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val recent = mutableMapOf<String, Long>()
    private var nextFt8 = FT8_WINDOW.toLong()
    private var nextFt4 = FT4_WINDOW.toLong()

    override fun process(samples: FloatArray, spectrum: SpectrumAnalysis?) {
        val reduced = decimator.process(samples)
        if (reduced.isEmpty()) return
        ring.append(reduced)

        var ft8Due = false
        while (ring.endSample >= nextFt8) {
            ft8Due = true
            nextFt8 += FT8_HOP
        }
        var ft4Due = false
        while (ring.endSample >= nextFt4) {
            ft4Due = true
            nextFt4 += FT4_HOP
        }
        if (!ft8Due && !ft4Due) return
        if (!busy.compareAndSet(false, true)) return

        val work = Work(
            ft8 = if (ft8Due && ring.size >= FT8_WINDOW) ring.copy(ring.endSample - FT8_WINDOW, FT8_WINDOW) else null,
            ft4 = if (ft4Due && ring.size >= FT4_WINDOW) ring.copy(ring.endSample - FT4_WINDOW, FT4_WINDOW) else null,
        )
        executor.execute {
            try {
                val combined = buildList {
                    work.ft8?.let { samples ->
                        NativeFtxBridge.decode(samples, DecodeModeKey.FT8, MAXIMUM_MESSAGES)
                            .forEach { add(DecodeMode.FT8 to it) }
                    }
                    work.ft4?.let { samples ->
                        NativeFtxBridge.decode(samples, DecodeModeKey.FT4, MAXIMUM_MESSAGES)
                            .forEach { add(DecodeMode.FT4 to it) }
                    }
                }
                combined.sortedByDescending { it.second.score }
                    .take(MAXIMUM_MESSAGES)
                    .forEach { (mode, result) -> emitResult(mode, result) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun emitResult(mode: DecodeMode, result: NativeFtxResult) {
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > 30_000L }
        }
        if (closed.get()) return
        val identity = "${mode.name}:${result.text}:${result.frequencyHz.roundToInt()}"
        val duplicate = synchronized(recent) {
            val previous = recent[identity]
            recent[identity] = now
            previous != null && now - previous < 14_000L
        }
        if (duplicate) return
        val quality = ((result.score - 8) / 24f).coerceIn(0.15f, 1f)
        emit(
            DecodedMessage(
                mode = mode,
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
