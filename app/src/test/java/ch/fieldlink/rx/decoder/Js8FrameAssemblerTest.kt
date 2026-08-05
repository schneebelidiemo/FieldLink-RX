package ch.fieldlink.rx.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Js8FrameAssemblerTest {
    @Test
    fun `joins first middle and last frame`() {
        val assembler = Js8FrameAssembler()

        assertNull(assembler.accept(frame("HELLO ", 0b001), 1L))
        assertNull(assembler.accept(frame("FROM ", 0b000), 2L))
        val result = assembler.accept(frame("JS8", 0b010), 3L)!!

        assertEquals("HELLO FROM JS8", result.text)
        assertTrue(result.complete)
        assertEquals(0.8f, result.quality, 0.0f)
    }

    @Test
    fun `emits standalone first-last frame immediately`() {
        val result = Js8FrameAssembler().accept(frame("CQ HB9ABC", 0b011), 1L)!!

        assertEquals("CQ HB9ABC", result.text)
        assertTrue(result.complete)
    }

    @Test
    fun `marks frame without beginning incomplete`() {
        val result = Js8FrameAssembler().accept(frame("TAIL", 0b010), 1L)!!

        assertEquals("TAIL", result.text)
        assertFalse(result.complete)
    }

    private fun frame(text: String, type: Int) = Js8Frame(
        text = text,
        frequencyHz = 1_500.0,
        snr = -10,
        quality = 0.8f,
        submode = 0,
        frameType = type,
    )
}
