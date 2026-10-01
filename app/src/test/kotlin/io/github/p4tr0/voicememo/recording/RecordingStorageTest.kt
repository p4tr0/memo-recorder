package io.github.p4tr0.voicememo.recording

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        storage.commit(storage.newPartialFile(startedAt).apply { writeText(FAKE_AUDIO) })
        assertEquals("2026-10-01_09-05-00-2.aac.part", storage.newPartialFile(startedAt).name)
    }

    @Test
    fun `commit remuxes to m4a and removes the partial`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer())
        val partial = storage.newPartialFile(startedAt).apply { writeText(FAKE_AUDIO) }
        val final = storage.commit(partial)!!
        assertEquals("2026-10-01_09-05-00.m4a", final.name)
        assertEquals(FAKE_AUDIO, final.readText())
        assertEquals(listOf(final.name), tmp.root.list()!!.toList())
    }

    @Test
    fun `remux failure keeps the raw aac rather than losing audio`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer(failWith = IOException("muxer broke")))
        val final = storage.commit(storage.newPartialFile(startedAt).apply { writeText(FAKE_AUDIO) })!!
        assertEquals("2026-10-01_09-05-00.aac", final.name)
        assertEquals(FAKE_AUDIO, final.readText())
        assertEquals(listOf(final.name), tmp.root.list()!!.toList())
    }

    @Test
    fun `commit of a file with no audio deletes it`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer())
        assertNull(storage.commit(storage.newPartialFile(startedAt).apply { createNewFile() }))
        assertTrue(tmp.root.list()!!.isEmpty())
    }

    @Test
    fun `commit of a file shorter than one ADTS header deletes it instead of keeping an empty aac`() {
        val remuxer = FakeRemuxer()
        val storage = RecordingStorage(tmp.root, remuxer)
        assertNull(storage.commit(storage.newPartialFile(startedAt).apply { writeText("abc") }))
        assertTrue(tmp.root.list()!!.isEmpty())
        assertEquals(0, remuxer.calls)
    }

    @Test
    fun `remux that drops audio keeps the raw aac next to the m4a`() {
        val audio = FAKE_AUDIO + "x".repeat(10_000)
        val storage = RecordingStorage(tmp.root, FakeRemuxer(keepBytes = FAKE_AUDIO.length))
        val final = storage.commit(storage.newPartialFile(startedAt).apply { writeText(audio) })!!
        assertEquals("2026-10-01_09-05-00.m4a", final.name)
        assertEquals(listOf("2026-10-01_09-05-00.aac", final.name), tmp.root.list()!!.sorted())
        assertEquals(audio, File(tmp.root, "2026-10-01_09-05-00.aac").readText())
    }

    @Test
    fun `a truncated last frame the extractor skipped still counts as complete`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer(keepBytes = FAKE_AUDIO.length))
        val final = storage.commit(storage.newPartialFile(startedAt).apply { writeText(FAKE_AUDIO + "partial") })!!
        assertEquals(listOf(final.name), tmp.root.list()!!.toList())
    }

    @Test
    fun `no CRC allowance hides audio dropped from the end of a long recording`() {
        // 10k frames of a CRC-less stream: a 2-byte-per-frame allowance would have absorbed these 15 KB.
        val audio = FAKE_AUDIO + "x".repeat(100_000)
        val storage = RecordingStorage(tmp.root, FakeRemuxer(keepBytes = audio.length - 15_000, reportFrames = 10_000))
        storage.commit(storage.newPartialFile(startedAt).apply { writeText(audio) })
        assertEquals(listOf("2026-10-01_09-05-00.aac", "2026-10-01_09-05-00.m4a"), tmp.root.list()!!.sorted())
    }

    @Test
    fun `no readable frames in a file with real data keeps it as raw aac`() {
        val audio = FAKE_AUDIO + "x".repeat(20_000)
        val storage = RecordingStorage(tmp.root, FakeRemuxer(failWith = NoAudioException("no frames")))
        val kept = storage.commit(storage.newPartialFile(startedAt).apply { writeText(audio) })!!
        assertEquals("2026-10-01_09-05-00.aac", kept.name)
        assertEquals(audio, kept.readText())
    }

    @Test
    fun `no readable frames in a file smaller than one frame deletes it`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer(failWith = NoAudioException("no frames")))
        assertNull(storage.commit(storage.newPartialFile(startedAt).apply { writeText(FAKE_AUDIO) }))
        assertTrue(tmp.root.list()!!.isEmpty())
    }

    @Test
    fun `commit throws and leaves the partial when audio can't be saved under any name`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer(failWith = IOException("muxer broke")))
        val partial = storage.newPartialFile(startedAt).apply { writeText(FAKE_AUDIO) }
        tmp.root.setWritable(false)
        try {
            assertThrows(IOException::class.java) { storage.commit(partial) }
        } finally {
            tmp.root.setWritable(true)
        }
        assertEquals(FAKE_AUDIO, partial.readText())
    }

    @Test
    fun `recovery that crashed last time keeps the raw aac without remuxing again`() {
        val remuxer = FakeRemuxer()
        val storage = RecordingStorage(tmp.root, remuxer)
        File(tmp.root, "crashy.aac.part").writeText(FAKE_AUDIO)
        File(tmp.root, "crashy.recovering").createNewFile()
        File(tmp.root, "orphan.recovering").createNewFile()
        assertEquals(listOf("crashy.aac"), storage.recoverInterrupted().map { it.name })
        assertEquals(listOf("crashy.aac"), tmp.root.list()!!.toList())
        assertEquals(0, remuxer.calls)
    }

    @Test
    fun `successful recovery leaves no marker behind`() {
        val storage = RecordingStorage(tmp.root, FakeRemuxer())
        File(tmp.root, "cut-off.aac.part").writeText(FAKE_AUDIO)
        storage.recoverInterrupted()
        assertEquals(listOf("cut-off.m4a"), tmp.root.list()!!.toList())
    }

    @Test
    fun `creates the directory on first use`() {
        val dir = File(tmp.root, "not-yet")
        RecordingStorage(dir, FakeRemuxer()).newPartialFile(startedAt)
        assertTrue(dir.isDirectory)
    }
}
