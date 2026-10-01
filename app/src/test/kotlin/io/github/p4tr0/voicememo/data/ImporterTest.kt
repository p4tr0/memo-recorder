package io.github.p4tr0.voicememo.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.p4tr0.voicememo.recording.FakeRemuxer
import io.github.p4tr0.voicememo.recording.RecordingStorage
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class ImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: VoiceMemoDatabase
    private lateinit var dir: File
    private lateinit var repository: RecordingRepository
    private lateinit var importer: Importer
    private var selectedTag: Long? = null
    private var metadataDate: LocalDateTime? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VoiceMemoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dir = tmp.newFolder("recordings")
        val storage = RecordingStorage(dir, FakeRemuxer())
        repository =
            RecordingRepository(storage, db.recordings(), { 1_000 }, Dispatchers.Unconfined, { ZoneOffset.UTC })
        val selection = object : TagSelection {
            override suspend fun load() = selectedTag

            override suspend fun save(tagId: Long?) = Unit
        }
        importer = Importer(
            storage,
            repository,
            selection,
            readRecordedAt = { metadataDate },
            ioDispatcher = Dispatchers.Unconfined,
            zone = { ZoneOffset.UTC },
            now = { LocalDateTime.of(2026, 10, 1, 12, 0) }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `copies files unchanged in their own format, dated from the name`() = runTest {
        val summary = importer.import(
            listOf(audio("2025_03_01_16_40_12.m4a", "aac-bytes"), audio("Rec 20250302-101500.mp3", "mp3-bytes"))
        )

        assertEquals(ImportSummary(imported = 2, duplicates = 0, failed = 0), summary)
        val files = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("2025-03-01_16-40-12.m4a", "2025-03-02_10-15-00.mp3"), files)
        assertEquals("aac-bytes", File(dir, "2025-03-01_16-40-12.m4a").readText())
        val recordings = repository.recordings.first()
        assertEquals(2, recordings.size)
        // A name that is only a date shows the date; one with words keeps them.
        assertEquals(listOf("Rec 20250302-101500", null), recordings.map { it.title })
    }

    @Test
    fun `without a date in the name, uses the metadata, then the timestamp, then now`() = runTest {
        metadataDate = LocalDateTime.of(2024, 5, 6, 7, 8, 9)
        importer.import(listOf(audio("Meeting with Anna.m4a", "a")))
        metadataDate = null
        importer.import(listOf(audio("Groceries.ogg", "b", lastModified = 1_700_000_000_000)))
        importer.import(listOf(audio("Idea.opus", "c")))

        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("2023-11-14_22-13-20.ogg", "2024-05-06_07-08-09.m4a", "2026-10-01_12-00-00.opus"), names)
        assertEquals(
            setOf("Meeting with Anna", "Groceries", "Idea"),
            repository.recordings.first().map {
                it.title
            }.toSet()
        )
    }

    @Test
    fun `the same audio twice is skipped, within one share and across shares`() = runTest {
        val first = importer.import(listOf(audio("a.m4a", "same"), audio("copy of a.m4a", "same")))
        val second = importer.import(listOf(audio("a again.m4a", "same"), audio("b.m4a", "different")))

        assertEquals(ImportSummary(imported = 1, duplicates = 1, failed = 0), first)
        assertEquals(ImportSummary(imported = 1, duplicates = 1, failed = 0), second)
        assertEquals(2, repository.recordings.first().size)
    }

    @Test
    fun `non-audio and unreadable files fail without stopping the rest`() = runTest {
        val summary = importer.import(
            listOf(
                audio("notes.txt", "text", mime = "text/plain"),
                AudioImport("gone.m4a", "audio/mp4", null) { null },
                audio("empty.m4a", ""),
                audio("ok.m4a", "audio")
            )
        )
        assertEquals(ImportSummary(imported = 1, duplicates = 0, failed = 3), summary)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun `an extensionless file is named from its MIME type`() = runTest {
        importer.import(listOf(audio("voice note", "x", mime = "audio/mpeg")))
        assertTrue(dir.listFiles()!!.single().name.endsWith(".mp3"))
    }

    @Test
    fun `imports get the selected tag, and none under All`() = runTest {
        val work = repository.createTag("Work")
        selectedTag = work.id
        importer.import(listOf(audio("Standup.m4a", "a")))
        selectedTag = null
        importer.import(listOf(audio("Groceries.m4a", "b")))

        val tags = repository.recordings.first().associate { it.title to it.tagId }
        assertEquals(work.id, tags["Standup"])
        assertNull(tags["Groceries"])
    }

    @Test
    fun `a copy left half-done by a kill is cleaned up by the next import`() = runTest {
        File(dir, "import-123.importing").writeText("half")
        importer.import(listOf(audio("a.m4a", "a")))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".importing") })
    }

    @Test
    fun `dates and titles are read from common recorder file names`() {
        assertEquals(LocalDateTime.of(2025, 3, 1, 16, 40, 12), Importer.dateInName("2025_03_01_16_40_12"))
        assertEquals(LocalDateTime.of(2025, 3, 1, 16, 40, 0), Importer.dateInName("Recording 2025-03-01 16.40"))
        assertEquals(LocalDateTime.of(2025, 3, 1, 16, 40, 12), Importer.dateInName("REC_20250301_164012"))
        assertNull(Importer.dateInName("Meeting with Anna"))
        assertNull(Importer.dateInName("2025_13_45_99_99_99")) // Not a real date.

        assertNull(Importer.titleFrom("2025_03_01_16_40_12"))
        assertEquals("Lecture notes 2025 03 01", Importer.titleFrom("Lecture_notes_2025_03_01"))
    }

    private fun audio(name: String, content: String, mime: String? = null, lastModified: Long? = null) =
        AudioImport(name, mime, lastModified) { content.byteInputStream() }
}
