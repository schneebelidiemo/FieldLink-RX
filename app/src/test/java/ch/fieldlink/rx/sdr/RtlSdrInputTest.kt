package ch.fieldlink.rx.sdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtlSdrInputTest {
    @Test
    fun acceptsAndroidTaggedPointerAsNativeHandle() {
        val taggedPointer = 0xb400007113881490UL.toLong()

        assertTrue(RtlSdrInput.isValidNativeHandle(taggedPointer))
        assertFalse(RtlSdrInput.isValidNativeHandle(0L))
    }
}
