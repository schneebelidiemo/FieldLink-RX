package ch.fieldlink.rx.sdr

import ch.fieldlink.rx.model.SdrModulation
import ch.fieldlink.rx.model.SdrSettings
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

    @Test
    fun restartsStreamOnlyForHardwareControlChanges() {
        val automatic = SdrSettings()
        val manual = automatic.copy(automaticGain = false)

        assertTrue(RtlSdrInput.requiresHardwareRestart(automatic, manual))
        assertTrue(RtlSdrInput.requiresHardwareRestart(manual, manual.copy(manualGainPercent = 60)))
        assertTrue(RtlSdrInput.requiresHardwareRestart(automatic, automatic.copy(frequencyHz = 7_074_000)))
        assertTrue(RtlSdrInput.requiresHardwareRestart(automatic, automatic.copy(ppmCorrection = 2)))
        assertFalse(RtlSdrInput.requiresHardwareRestart(automatic, automatic.copy(manualGainPercent = 60)))
        assertFalse(RtlSdrInput.requiresHardwareRestart(automatic, automatic.copy(modulation = SdrModulation.AM)))
        assertFalse(RtlSdrInput.requiresHardwareRestart(automatic, automatic.copy(squelchEnabled = true)))
    }
}
