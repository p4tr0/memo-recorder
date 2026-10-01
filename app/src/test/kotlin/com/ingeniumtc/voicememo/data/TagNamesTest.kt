package com.ingeniumtc.voicememo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TagNamesTest {
    @Test
    fun `trims and collapses whitespace`() {
        assertEquals(TagNames.Result.Valid("Work notes"), TagNames.validate("  Work \t  notes "))
    }

    @Test
    fun `blank and too long names are refused`() {
        assertEquals(TagNames.Result.Blank, TagNames.validate("   "))
        assertEquals(TagNames.Result.TooLong, TagNames.validate("x".repeat(TagNames.MAX_LENGTH + 1)))
        assertEquals(TagNames.Result.Valid("x".repeat(TagNames.MAX_LENGTH)), TagNames.validate("x".repeat(30)))
    }
}
