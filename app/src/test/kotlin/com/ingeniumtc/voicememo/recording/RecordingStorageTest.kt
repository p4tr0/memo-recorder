package com.ingeniumtc.voicememo.recording

import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val startedAt = LocalDateTime.of(2026, 10, 1, 9, 5, 0)

    @Test
    fun `names collide-free when two recordings start in the same second`() {
        val dir = tmp.newFolder()
        val storage = RecordingStorage(dir)
        val first = storage.newPartialFile(startedAt).apply { writeText("a") }
        storage.commit(first)
        val second = storage.newPartialFile(startedAt)
        assertEquals("2026-10-01_09-05-00-2.m4a.part", second.name)
    }

    @Test
    fun `commit renames the partial file`() {
        val dir = tmp.newFolder()
        val storage = RecordingStorage(dir)
        val partial = storage.newPartialFile(startedAt).apply { writeText("audio") }
        val final = storage.commit(partial)
        assertEquals("2026-10-01_09-05-00.m4a", final.name)
        assertEquals("audio", final.readText())
        assertFalse(partial.exists())
    }

    @Test
    fun `creates the directory on first use`() {
        val dir = File(tmp.root, "not-yet")
        RecordingStorage(dir).newPartialFile(startedAt)
        assertTrue(dir.isDirectory)
    }
}
