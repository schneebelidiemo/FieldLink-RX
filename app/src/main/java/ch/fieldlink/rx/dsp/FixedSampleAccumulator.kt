package ch.fieldlink.rx.dsp

class FixedSampleAccumulator(private val blockSize: Int) {
    private val buffer = FloatArray(blockSize)
    private var count = 0

    fun add(samples: FloatArray, consume: (FloatArray) -> Unit) {
        var source = 0
        while (source < samples.size) {
            val copyCount = minOf(blockSize - count, samples.size - source)
            samples.copyInto(buffer, count, source, source + copyCount)
            count += copyCount
            source += copyCount
            if (count == blockSize) {
                consume(buffer.copyOf())
                count = 0
            }
        }
    }

    fun clear() {
        buffer.fill(0f)
        count = 0
    }
}

