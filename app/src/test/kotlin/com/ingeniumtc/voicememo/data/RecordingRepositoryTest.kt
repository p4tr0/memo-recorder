package com.ingeniumtc.voicememo.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ingeniumtc.voicememo.recording.FAKE_AUDIO
import com.ingeniumtc.voicememo.recording.FakeRemuxer
import com.ingeniumtc.voicememo.recording.RecordingStorage
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RecordingRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: VoiceMemoDatabase
    private lateinit var dir: File
    private lateinit var storage: RecordingStorage
    private lateinit var repository: RecordingRepository
    private val durationReads = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VoiceMemoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dir = tmp.newFolder("recordings")
        storage = RecordingStorage(dir, FakeRemuxer())
        repository = RecordingRepository(
            storage = storage,
            dao = db.recordings(),
            readDurationMs = {
                durationReads += it.name
                4_200
            },
            ioDispatcher = Dispatchers.Unconfined,
            zone = { ZoneOffset.UTC }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `sync lists finished recordings newest first and ignores in-progress and junk files`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        file("2026-10-02_10-00-00.aac")
        file("2026-10-03_11-00-00.aac.part")
        file("2026-10-03_11-00-00.m4a.tmp")
        file("notes.txt")

        repository.sync()

        val recordings = repository.recordings.first()
        assertEquals(listOf("2026-10-02_10-00-00.aac", "2026-10-01_09-05-00.m4a"), recordings.map { it.id })
        val newest = recordings.first()
        assertEquals(LocalDateTime.of(2026, 10, 2, 10, 0).toInstant(ZoneOffset.UTC), newest.createdAt)
        assertEquals(4_200, newest.durationMs)
        assertEquals(FAKE_AUDIO.length.toLong(), newest.sizeBytes)
        assertNull(newest.title)
    }

    @Test
    fun `sync reads durations only for new files and drops rows whose file is gone`() = runTest {
        val kept = file("2026-10-01_09-05-00.m4a")
        val removed = file("2026-10-02_10-00-00.m4a")
        repository.sync()
        durationReads.clear()

        assertTrue(removed.delete())
        file("2026-10-03_11-00-00.m4a")
        repository.sync()

        assertEquals(listOf("2026-10-03_11-00-00.m4a"), durationReads)
        assertEquals(
            listOf("2026-10-03_11-00-00.m4a", kept.name),
            repository.recordings.first().map { it.id }
        )
    }

    @Test
    fun `a duration that can't be read doesn't hide the recording`() = runTest {
        val failing = RecordingRepository(storage, db.recordings(), { error("unreadable") }, Dispatchers.Unconfined)
        file("2026-10-01_09-05-00.aac")
        failing.sync()
        assertEquals(0, failing.recordings.first().single().durationMs)
    }

    @Test
    fun `a file with an unexpected name falls back to its modification time`() = runTest {
        file("imported.m4a").setLastModified(1_000_000)
        repository.sync()
        assertEquals(1_000_000, repository.recordings.first().single().createdAt.toEpochMilli())
    }

    @Test
    fun `rename trims and a blank title resets to the date`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        repository.sync()
        val recording = repository.recordings.first().single()

        repository.rename(recording, "  Standup notes  ")
        assertEquals("Standup notes", repository.recordings.first().single().title)

        repository.rename(recording, "   ")
        assertNull(repository.recordings.first().single().title)
    }

    @Test
    fun `rename survives a later sync`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        repository.sync()
        repository.rename(repository.recordings.first().single(), "Idea")
        repository.sync()
        assertEquals("Idea", repository.recordings.first().single().title)
    }

    @Test
    fun `delete removes the file and the row`() = runTest {
        val audio = file("2026-10-01_09-05-00.m4a")
        repository.sync()
        assertTrue(repository.delete(repository.recordings.first().single()))
        assertFalse(audio.exists())
        assertTrue(repository.recordings.first().isEmpty())
    }

    @Test
    fun `delete that can't remove the file keeps the row`() = runTest {
        val audio = file("2026-10-01_09-05-00.m4a")
        repository.sync()
        dir.setWritable(false)
        try {
            assertFalse(repository.delete(repository.recordings.first().single()))
        } finally {
            dir.setWritable(true)
        }
        assertTrue(audio.exists())
        assertEquals(1, repository.recordings.first().size)
    }

    @Test
    fun `a directory that can't be listed keeps every row and title`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        repository.sync()
        repository.rename(repository.recordings.first().single(), "Keep me")

        dir.setReadable(false)
        try {
            repository.sync()
        } finally {
            dir.setReadable(true)
        }
        assertEquals("Keep me", repository.recordings.first().single().title)
    }

    @Test
    fun `an m4a and the raw aac kept next to it are both listed and told apart`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        file("2026-10-01_09-05-00.aac")
        repository.sync()
        val recordings = repository.recordings.first()
        assertEquals(2, recordings.size)
        assertEquals(listOf(false, true), recordings.sortedBy { it.id.endsWith(".aac") }.map { it.isRawAac })
    }

    @Test
    fun `tag names are unique ignoring case, and re-adding returns the existing tag`() = runTest {
        val work = repository.createTag("Work")
        assertEquals(work, repository.createTag("work"))
        assertEquals(listOf("Work"), repository.tags.first().map { it.name })
    }

    @Test
    fun `tags are listed in the order they were created`() = runTest {
        repository.createTag("Zebra")
        repository.createTag("Apple")
        assertEquals(listOf("Zebra", "Apple"), repository.tags.first().map { it.name })
    }

    @Test
    fun `tagging and untagging a recording`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        repository.sync()
        val recording = repository.recordings.first().single()
        val work = repository.createTag("Work")

        repository.setTag(recording, work)
        repository.setTag(recording, work) // Idempotent.
        assertEquals(work.id, repository.recordings.first().single().tagId)

        repository.setTag(recording, null)
        assertTrue(repository.recordings.first().single().tagId == null)
    }

    @Test
    fun `a recording whose file disappears takes its tag links with it, and the tag stays`() = runTest {
        val audio = file("2026-10-01_09-05-00.m4a")
        repository.sync()
        val work = repository.createTag("Work")
        repository.setTag(repository.recordings.first().single(), work)

        assertTrue(audio.delete())
        repository.sync()
        // Recreating a file with the same name must not resurrect the old tags.
        file("2026-10-01_09-05-00.m4a")
        repository.sync()

        assertTrue(repository.recordings.first().single().tagId == null)
        assertEquals(listOf(work), repository.tags.first())
    }

    @Test
    fun `tagging a file without a row is ignored rather than crashing`() = runTest {
        val work = repository.createTag("Work")
        repository.tagFile("never-existed.m4a", work.id)
        assertTrue(repository.recordings.first().isEmpty())
    }

    @Test
    fun `uniqueness ignores case in every script, not just ASCII`() = runTest {
        val city = repository.createTag("Łódź")
        assertEquals(city, repository.createTag("łódź"))
        assertEquals(repository.createTag("ÄRZTE"), repository.createTag("ärzte"))
        assertEquals(2, repository.tags.first().size)
    }

    @Test
    fun `renaming a tag keeps its recordings, and a name taken by another tag is refused`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        repository.sync()
        val work = repository.createTag("work")
        repository.createTag("Ideas")
        repository.setTag(repository.recordings.first().single(), work)

        assertTrue(repository.renameTag(work, "Work")) // A different case of its own name is fine.
        assertFalse(repository.renameTag(work, "ideas"))
        assertTrue(repository.renameTag(work, "Office"))

        assertEquals(listOf("Office", "Ideas"), repository.tags.first().map { it.name })
        assertEquals(work.id, repository.recordings.first().single().tagId)
        // The old name is free again: this makes a new tag rather than returning Office.
        assertTrue(repository.createTag("work").id != work.id)
    }

    @Test
    fun `deleting a tag untags its recordings and keeps them`() = runTest {
        val audio = file("2026-10-01_09-05-00.m4a")
        repository.sync()
        val work = repository.createTag("Work")
        repository.setTag(repository.recordings.first().single(), work)

        repository.deleteTag(work)

        assertTrue(repository.tags.first().isEmpty())
        val recording = repository.recordings.first().single()
        assertTrue(recording.tagId == null)
        assertTrue(audio.exists())
    }

    @Test
    fun `a recording carries one tag, so setting another replaces it, and null clears it`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        repository.sync()
        val recording = repository.recordings.first().single()
        val work = repository.createTag("Work")
        val ideas = repository.createTag("Ideas")

        repository.setTag(recording, work)
        repository.setTag(recording, ideas)
        assertEquals(ideas.id, repository.recordings.first().single().tagId)

        repository.setTag(recording, null)
        assertNull(repository.recordings.first().single().tagId)
    }

    @Test
    fun `each new tag's hue is far from the previous tag's`() = runTest {
        val hues = (1..40).map { repository.createTag("Tag $it").hue }
        hues.zipWithNext().forEach { (previous, next) ->
            assertTrue("$previous then $next", TagHues.distance(previous, next) >= TagHues.MIN_DISTANCE)
        }
        assertEquals(hues, repository.tags.first().map { it.hue })
    }

    @Test
    fun `re-adding an existing tag keeps its color`() = runTest {
        val work = repository.createTag("Work")
        assertEquals(work.hue, repository.createTag("WORK").hue)
    }

    @Test
    fun `deleting a recording keeps its tags for other recordings`() = runTest {
        file("2026-10-01_09-05-00.m4a")
        file("2026-10-02_09-05-00.m4a")
        repository.sync()
        val work = repository.createTag("Work")
        repository.recordings.first().forEach { repository.setTag(it, work) }

        repository.delete(repository.recordings.first().first())

        assertEquals(listOf(work), repository.tags.first())
        assertEquals(work.id, repository.recordings.first().single().tagId)
    }

    private fun file(name: String): File = File(dir, name).apply { writeText(FAKE_AUDIO) }
}
