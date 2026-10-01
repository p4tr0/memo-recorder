package io.github.p4tr0.voicememo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedFormatTest {
    @Test
    fun formats() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:07", formatElapsed(7_999))
        assertEquals("12:34", formatElapsed(754_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
        assertEquals("0:00", formatElapsed(-5))
    }
}
