package io.github.p4tr0.voicememo.ui.home

import io.github.p4tr0.voicememo.data.Recording
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val grocery = recording("Grocery list", "2026-09-30T18:42:00Z")
    private val untitled = recording(null, "2026-10-01T09:05:00Z")
    private val lodz = recording("Spotkanie w Łodzi, część 2", "2026-09-21T12:00:00Z")

    @Test
    fun `every word must appear, in any order and any case`() {
        assertTrue(matches(grocery, "LIST grocery"))
        assertTrue(matches(grocery, "groc"))
        assertFalse(matches(grocery, "grocery shopping"))
    }

    @Test
    fun `accents and Polish letters are ignored, both ways`() {
        assertTrue(matches(lodz, "lodzi czesc"))
        assertTrue(matches(lodz, "ŁÓDZI"))
        assertEquals("spotkanie w lodzi czesc 2", searchKey("  Spotkanie   w Łodzi, część 2! "))
    }

    @Test
    fun `the date matches as the list shows it and spelled out`() {
        assertTrue(matches(grocery, "sep 30"))
        assertTrue(matches(grocery, "september 30 2026"))
        assertTrue(matches(grocery, "6:42 pm"))
        assertTrue(matches(untitled, "oct 1"))
        assertFalse(matches(untitled, "sep"))
    }

    @Test
    fun `a date matches as a phrase, so a day doesn't match inside the year`() {
        val sep18 = recording(null, "2026-09-18T08:45:00Z")
        val sep21 = recording(null, "2026-09-21T12:00:00Z")
        assertFalse(matches(sep18, "sep 2"))
        assertTrue(matches(sep21, "sep 2"))
        assertFalse(matches(grocery, "30 sep"))
    }

    @Test
    fun `a blank query matches everything`() {
        assertTrue(matches(grocery, ""))
        assertTrue(matches(untitled, "   "))
    }

    private fun matches(recording: Recording, query: String) =
        matchesSearch(recording, query, ZoneOffset.UTC, Locale.US)

    private fun recording(title: String?, createdAt: String) =
        Recording(File("x.m4a"), title, Instant.parse(createdAt), durationMs = 1_000, sizeBytes = 10)
}
