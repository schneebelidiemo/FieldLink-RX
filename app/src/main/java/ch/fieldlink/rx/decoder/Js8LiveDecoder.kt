package ch.fieldlink.rx.decoder

import ch.fieldlink.rx.dsp.FloatRingBuffer
import ch.fieldlink.rx.dsp.SpectrumAnalysis
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Live receive-only JS8Call decoder. A dense overlap is used because audio
 * capture is not assumed to begin on a JS8 slot boundary. The native worker
 * checks Normal, Fast, Turbo/40, Slow and Ultra/60 automatically.
 */
class Js8LiveDecoder(
    private val emit: (DecodedMessage) -> Unit,
) : AudioDecoder {
    companion object {
        private const val OUTPUT_RATE = 12_000
        private const val WINDOW = 30 * OUTPUT_RATE
        private const val HOP = OUTPUT_RATE
        private const val MAXIMUM_MESSAGES = 3
    }

    private val decimator = Decimator4()
    private val ring = FloatRingBuffer(WINDOW + HOP)
    private val assembler = Js8FrameAssembler(MAXIMUM_MESSAGES)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FieldLink-RX-JS8").apply { priority = Thread.NORM_PRIORITY }
    }
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val recent = mutableMapOf<String, Long>()
    private var nextDecode = WINDOW.toLong()

    override fun process(samples: FloatArray, spectrum: SpectrumAnalysis?) {
        val reduced = decimator.process(samples)
        if (reduced.isEmpty()) return
        ring.append(reduced)

        var due = false
        while (ring.endSample >= nextDecode) {
            due = true
            nextDecode += HOP
        }
        if (!due || ring.size < WINDOW || !busy.compareAndSet(false, true)) return
        val capture = ring.copy(ring.endSample - WINDOW, WINDOW)

        executor.execute {
            try {
                NativeJs8Bridge.decode(capture, MAXIMUM_MESSAGES)
                    .sortedWith(compareByDescending<NativeJs8Result> { it.quality }.thenByDescending { it.snr })
                    .take(MAXIMUM_MESSAGES)
                    .forEach(::accept)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun accept(result: NativeJs8Result) {
        if (closed.get()) return
        val assembled = assembler.accept(
            Js8Frame(
                text = result.text,
                frequencyHz = result.frequencyHz,
                snr = result.snr,
                quality = result.quality,
                submode = result.submode,
                frameType = result.frameType,
            ),
        ) ?: return

        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > 90_000L }
        }
        val identity = "${assembled.text}:${(assembled.frequencyHz / 5.0).roundToInt()}"
        val duplicate = synchronized(recent) {
            val previous = recent[identity]
            recent[identity] = now
            previous != null && now - previous < 28_000L
        }
        if (duplicate || closed.get()) return

        emit(
            DecodedMessage(
                mode = DecodeMode.JS8,
                text = assembled.text,
                audioFrequencyHz = assembled.frequencyHz,
                quality = assembled.quality.coerceIn(0.05f, 1f),
                uncertain = !assembled.complete || assembled.quality < 0.70f || assembled.snr < -22,
                complete = assembled.complete,
            ),
        )
    }

    override fun close() {
        closed.set(true)
        assembler.clear()
        executor.shutdownNow()
    }
}
