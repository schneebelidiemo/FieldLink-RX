package ch.fieldlink.rx.decoder

import kotlin.math.roundToInt

internal data class Js8Frame(
    val text: String,
    val frequencyHz: Double,
    val snr: Int,
    val quality: Float,
    val submode: Int,
    val frameType: Int,
)

internal data class AssembledJs8Message(
    val text: String,
    val frequencyHz: Double,
    val snr: Int,
    val quality: Float,
    val complete: Boolean,
)

/** Reassembles JS8Call's First/Last-framed text without retaining it on disk. */
internal class Js8FrameAssembler(
    private val maximumStreams: Int = 3,
    private val timeoutMillis: Long = 90_000L,
) {
    companion object {
        private const val FIRST = 0b001
        private const val LAST = 0b010
    }

    private data class StreamKey(val submode: Int, val frequencyBucket: Int)
    private data class Stream(
        val text: StringBuilder,
        val frequencyHz: Double,
        var snr: Int,
        var quality: Float,
        var updatedAt: Long,
    )

    private val streams = linkedMapOf<StreamKey, Stream>()

    @Synchronized
    fun accept(frame: Js8Frame, nowMillis: Long = System.currentTimeMillis()): AssembledJs8Message? {
        streams.entries.removeAll { nowMillis - it.value.updatedAt > timeoutMillis }
        val key = StreamKey(frame.submode, (frame.frequencyHz / 5.0).roundToInt())
        val first = frame.frameType and FIRST != 0
        val last = frame.frameType and LAST != 0

        if (first && last) {
            streams.remove(key)
            return frame.asMessage(complete = true)
        }

        if (first) {
            streams[key] = Stream(
                text = StringBuilder(frame.text),
                frequencyHz = frame.frequencyHz,
                snr = frame.snr,
                quality = frame.quality,
                updatedAt = nowMillis,
            )
            trimToLimit()
            return null
        }

        val stream = streams[key]
        if (stream == null) {
            // A valid physical frame was decoded, but the beginning was outside
            // the live capture. Show it as incomplete instead of hiding it.
            return frame.asMessage(complete = false)
        }

        stream.text.append(frame.text)
        stream.snr = minOf(stream.snr, frame.snr)
        stream.quality = minOf(stream.quality, frame.quality)
        stream.updatedAt = nowMillis
        if (!last) return null

        streams.remove(key)
        return AssembledJs8Message(
            text = stream.text.toString(),
            frequencyHz = stream.frequencyHz,
            snr = stream.snr,
            quality = stream.quality,
            complete = true,
        )
    }

    @Synchronized
    fun clear() = streams.clear()

    private fun trimToLimit() {
        while (streams.size > maximumStreams) {
            val oldest = streams.minByOrNull { it.value.updatedAt }?.key ?: return
            streams.remove(oldest)
        }
    }

    private fun Js8Frame.asMessage(complete: Boolean) = AssembledJs8Message(
        text = text,
        frequencyHz = frequencyHz,
        snr = snr,
        quality = quality,
        complete = complete,
    )
}
