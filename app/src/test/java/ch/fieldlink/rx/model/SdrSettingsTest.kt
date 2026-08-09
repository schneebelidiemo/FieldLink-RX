package ch.fieldlink.rx.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrSettingsTest {
    @Test
    fun `default SDR session is USB automatic gain and muted`() {
        val settings = SdrSettings()

        assertEquals(14_074_000L, settings.frequencyHz)
        assertEquals(SdrModulation.USB, settings.modulation)
        assertEquals(3_000, settings.bandwidthHz)
        assertTrue(settings.automaticGain)
        assertTrue(settings.monitorMuted)
        assertFalse(settings.squelchEnabled)
    }

    @Test
    fun `automatic bandwidth follows each manual modulation choice`() {
        assertEquals(1_000, SdrSettings(modulation = SdrModulation.CW).bandwidthHz)
        assertEquals(10_000, SdrSettings(modulation = SdrModulation.AM).bandwidthHz)
        assertEquals(12_500, SdrSettings(modulation = SdrModulation.NFM).bandwidthHz)
        assertEquals(180_000, SdrSettings(modulation = SdrModulation.WFM).bandwidthHz)
    }
}
