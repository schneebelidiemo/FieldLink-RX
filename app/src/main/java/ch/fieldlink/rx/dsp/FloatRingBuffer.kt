package ch.fieldlink.rx.dsp

class FloatRingBuffer(capacity: Int) {
    private val data = FloatArray(capacity)
    private var writeIndex = 0
    var startSample: Long = 0
        private set
    var endSample: Long = 0
        private set

    val size: Int get() = (endSample - startSample).toInt()

    fun append(samples: FloatArray) {
        for (sample in samples) {
            data[writeIndex] = sample
            writeIndex = (writeIndex + 1) % data.size
            endSample += 1
            if (endSample - startSample > data.size) startSample = endSample - data.size
        }
    }

    fun copy(start: Long, length: Int): FloatArray {
        require(start >= startSample && start + length <= endSample) { "Audio range is no longer buffered." }
        val result = FloatArray(length)
        val oldestIndex = if (size == data.size) writeIndex else 0
        var source = (oldestIndex + (start - startSample).toInt()) % data.size
        for (index in result.indices) {
            result[index] = data[source]
            source = (source + 1) % data.size
        }
        return result
    }
}

