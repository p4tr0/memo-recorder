package com.ingeniumtc.voicememo.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ingeniumtc.voicememo.recording.FAKE_AUDIO
import com.ingeniumtc.voicememo.recording.FakeRemuxer
import com.ingeniumtc.voicememo.recording.RecordingStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AutoTaggerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: VoiceMemoDatabase
    private lateinit var dir: File
    private lateinit var storage: RecordingStorage
    private lateinit var repository: RecordingRepository
    private val prefs = FakePrefs()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VoiceMemoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dir = tmp.newFolder("recordings")
        storage = RecordingStorage(dir, FakeRemuxer())
        repository = RecordingRepository(storage, db.recordings(), { 1_000 }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun TestScope.tagger() = AutoTagger(
        storage,
        repository,
        prefs,
        prefs,
        backgroundScope,
        UnconfinedTestDispatcher(testScheduler)
    )

    @Test
    fun `a recording started while filtered gets the tag when saved`() = runTest {
        val work = repository.createTag("Work")
        prefs.selected = work.id
        val tagger = tagger()

        tagger.sessionStarted(partial("2026-10-01_09-05-00"))
        prefs.selected = null // Switching chips mid-recording doesn't change it.
        save("2026-10-01_09-05-00")
        tagger.filesChanged()

        assertEquals(
            setOf(work.id),
            awaitRecordings {
                it.singleOrNull()?.tagIds?.isNotEmpty() == true
            }.single().tagIds
        )
        assertNull(prefs.pending)
    }

    @Test
    fun `nothing is tagged when started under All`() = runTest {
        prefs.selected = null
        val tagger = tagger()
        tagger.sessionStarted(partial("2026-10-01_09-05-00"))
        save("2026-10-01_09-05-00")
        tagger.filesChanged()
        advanceUntilIdle()
        assertNull(prefs.pending)
        repository.sync()
        assertTrue(repository.recordings.first().single().tagIds.isEmpty())
    }

    @Test
    fun `the complete raw copy next to an incomplete m4a is tagged too`() = runTest {
        val work = repository.createTag("Work")
        prefs.selected = work.id
        val tagger = tagger()
        tagger.sessionStarted(partial("2026-10-01_09-05-00"))
        File(dir, "2026-10-01_09-05-00.aac.part").renameTo(File(dir, "2026-10-01_09-05-00.aac"))
        File(dir, "2026-10-01_09-05-00.m4a").writeText(FAKE_AUDIO)
        tagger.filesChanged()
        val tagged = awaitRecordings { all -> all.size == 2 && all.all { it.tagIds.isNotEmpty() } }
        assertEquals(listOf(setOf(work.id), setOf(work.id)), tagged.map { it.tagIds })
    }

    @Test
    fun `a recording recovered on the next launch still gets its tag`() = runTest {
        val work = repository.createTag("Work")
        prefs.selected = work.id
        tagger().sessionStarted(partial("2026-10-01_09-05-00"))
        advanceUntilIdle()

        // Process death: a new tagger, as on the next launch, then recovery finishes the file.
        val nextLaunch = tagger()
        storage.recoverInterrupted()
        nextLaunch.filesChanged()

        assertEquals(
            setOf(work.id),
            awaitRecordings {
                it.singleOrNull()?.tagIds?.isNotEmpty() == true
            }.single().tagIds
        )
    }

    @Test
    fun `waits while the recording is unfinished, and forgets a discarded one`() = runTest {
        val work = repository.createTag("Work")
        prefs.selected = work.id
        val tagger = tagger()
        val partial = partial("2026-10-01_09-05-00")
        tagger.sessionStarted(partial)
        tagger.filesChanged() // Some other save while this one is still recording.
        advanceUntilIdle()
        assertEquals("2026-10-01_09-05-00", prefs.pending?.first)

        partial.delete() // Discarded as too short.
        tagger.filesChanged()
        advanceUntilIdle()
        assertNull(prefs.pending)
    }

    @Test
    fun `one failure doesn't stop later recordings from being tagged`() = runTest {
        val work = repository.createTag("Work")
        prefs.selected = work.id
        prefs.failNextLoad = true
        val tagger = tagger()
        tagger.sessionStarted(partial("2026-10-01_09-05-00")) // Throws inside, logged.
        advanceUntilIdle()

        tagger.sessionStarted(partial("2026-10-02_09-05-00"))
        save("2026-10-02_09-05-00")
        tagger.filesChanged()
        val tagged = awaitRecordings { all -> all.any { it.id.startsWith("2026-10-02") && it.tagIds.isNotEmpty() } }
        assertEquals(setOf(work.id), tagged.single { it.id.startsWith("2026-10-02") }.tagIds)
    }

    /** Room works on its own threads, so results are awaited in real time rather than with the test scheduler. */
    private suspend fun awaitRecordings(until: (List<Recording>) -> Boolean): List<Recording> =
        withContext(Dispatchers.Default) { withTimeout(5_000) { repository.recordings.first(until) } }

    private fun partial(baseName: String) = File(dir, "$baseName.aac.part").apply { writeText(FAKE_AUDIO) }

    private fun save(baseName: String) {
        File(dir, "$baseName.aac.part").renameTo(File(dir, "$baseName.m4a"))
    }

    private class FakePrefs :
        TagSelection,
        PendingTag {
        var selected: Long? = null
        var pending: Pair<String, Long>? = null
        var failNextLoad = false

        override suspend fun load(): Long? {
            if (failNextLoad) {
                failNextLoad = false
                error("disk full")
            }
            return selected
        }

        override suspend fun save(tagId: Long?) {
            selected = tagId
        }

        override suspend fun loadPending() = pending

        override suspend fun savePending(baseName: String, tagId: Long) {
            pending = baseName to tagId
        }

        override suspend fun clearPending() {
            pending = null
        }
    }
}
