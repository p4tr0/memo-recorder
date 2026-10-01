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

    private fun file(name: String): File = File(dir, name).apply { writeText(FAKE_AUDIO) }
}
