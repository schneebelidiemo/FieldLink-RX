package ch.fieldlink.rx.dsp

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpectrumAnalyzerTest {
    @Test
    fun throttlesAudioFftToAboutTenFramesPerSecond() {
        val analyzer = SpectrumAnalyzer()
        var sampleIndex = 0

        fun block(): FloatArray = FloatArray(2_048) {
            val value = sin(2.0 * PI * 1_000.0 * sampleIndex / 48_000.0).toFloat()
            sampleIndex += 1
            value
        }

        repeat(3) { assertNull(analyzer.add(block())) }
        assertNotNull(analyzer.add(block()))
        assertNull(analyzer.add(block()))
        assertNull(analyzer.add(block()))
        assertNotNull(analyzer.add(block()))
    }
}
