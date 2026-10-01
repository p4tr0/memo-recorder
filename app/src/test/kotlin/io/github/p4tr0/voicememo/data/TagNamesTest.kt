package io.github.p4tr0.voicememo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TagNamesTest {
    @Test
    fun `trims and collapses whitespace`() {
        assertEquals(TagNames.Result.Valid("Work notes"), TagNames.validate("  Work \t  notes "))
    }

    @Test
    fun `an accent typed as two characters is the same name as the composed one`() {
        assertEquals(TagNames.validate("Caf\u00e9"), TagNames.validate("Cafe\u0301"))
    }

    @Test
    fun `keys fold case beyond ASCII`() {
        assertEquals(TagNames.key("Łódź"), TagNames.key("ŁÓDŹ"))
    }

    @Test
    fun `blank and too long names are refused`() {
        assertEquals(TagNames.Result.Blank, TagNames.validate("   "))
        assertEquals(TagNames.Result.TooLong, TagNames.validate("x".repeat(TagNames.MAX_LENGTH + 1)))
        assertEquals(TagNames.Result.Valid("x".repeat(TagNames.MAX_LENGTH)), TagNames.validate("x".repeat(30)))
    }
}
