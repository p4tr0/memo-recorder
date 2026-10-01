package com.ingeniumtc.voicememo.recording

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val startedAt = LocalDateTime.of(2026, 10, 1, 9, 5, 0)

    @Test
    fun `names are collision-free when two recordings start in the same second`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer())
        storage.commit(storage.newPartialFile(startedAt).apply { writeText("a") })
        assertEquals("2026-10-01_09-05-00-2.aac.part", storage.newPartialFile(startedAt).name)
    }

    @Test
    fun `commit remuxes to m4a and removes the partial`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer())
        val partial = storage.newPartialFile(startedAt).apply { writeText("audio") }
        val final = storage.commit(partial)!!
        assertEquals("2026-10-01_09-05-00.m4a", final.name)
        assertEquals("audio", final.readText())
        assertEquals(listOf(final.name), tmp.root.list()!!.toList())
    }

    @Test
    fun `remux failure keeps the raw aac rather than losing audio`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer(failWith = IOException("muxer broke")))
        val final = storage.commit(storage.newPartialFile(startedAt).apply { writeText("audio") })!!
        assertEquals("2026-10-01_09-05-00.aac", final.name)
        assertEquals("audio", final.readText())
        assertEquals(listOf(final.name), tmp.root.list()!!.toList())
    }

    @Test
    fun `commit of a file with no audio deletes it`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer())
        assertNull(storage.commit(storage.newPartialFile(startedAt).apply { createNewFile() }))
        assertTrue(tmp.root.list()!!.isEmpty())
    }

    @Test
    fun `creates the directory on first use`() {
        val dir = File(tmp.root, "not-yet")
        RecordingStorage(dir, FakeRemuxer()).newPartialFile(startedAt)
        assertTrue(dir.isDirectory)
    }
}
