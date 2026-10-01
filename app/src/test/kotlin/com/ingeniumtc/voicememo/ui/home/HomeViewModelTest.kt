package com.ingeniumtc.voicememo.ui.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ingeniumtc.voicememo.data.Recording
import com.ingeniumtc.voicememo.data.RecordingRepository
import com.ingeniumtc.voicememo.data.TagNames
import com.ingeniumtc.voicememo.data.TagSelection
import com.ingeniumtc.voicememo.data.VoiceMemoDatabase
import com.ingeniumtc.voicememo.playback.Playback
import com.ingeniumtc.voicememo.playback.PlaybackState
import com.ingeniumtc.voicememo.recording.AudioRecorder
import com.ingeniumtc.voicememo.recording.FAKE_AUDIO
import com.ingeniumtc.voicememo.recording.FakeRemuxer
import com.ingeniumtc.voicememo.recording.RecordingController
import com.ingeniumtc.voicememo.recording.RecordingState
import com.ingeniumtc.voicememo.recording.RecordingStorage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
class HomeViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val playback = FakePlayback()
    private lateinit var db: VoiceMemoDatabase
    private lateinit var dir: File
    private lateinit var controller: RecordingController
    private lateinit var repository: RecordingRepository
    private val tagSelection = FakeTagSelection()
    private lateinit var created: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VoiceMemoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dir = tmp.newFolder("recordings")
        val storage = RecordingStorage(dir, FakeRemuxer())
        repository = RecordingRepository(storage, db.recordings(), { 1_000 }, dispatcher)
        controller = RecordingController(storage, { SilentRecorder() }, appScope, dispatcher)
    }

    /** Created lazily so a test can set the remembered tag first, as a previous launch would have. */
    private fun viewModel(): HomeViewModel {
        if (!::created.isInitialized) {
            created = HomeViewModel(
                controller = controller,
                repository = repository,
                tagSelection = tagSelection,
                playbackFactory = { playback },
                startRecordingService = {
                    controller.start()
                    true
                }
            )
        }
        return created
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `starting a recording pauses playback first`() {
        viewModel().startRecording()
        assertEquals(listOf("pause"), playback.calls)
        assertTrue(controller.state.value is RecordingState.Active)
    }

    @Test
    fun `playback can't be started while recording`() {
        viewModel().startRecording()
        playback.calls.clear()
        viewModel().togglePlayback(recording(), "title")
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `playback toggles when idle`() {
        viewModel().togglePlayback(recording(), "title")
        assertEquals(listOf("toggle 2026-10-01_09-05-00.m4a"), playback.calls)
    }

    @Test
    fun `delete unloads the recording from the player before deleting it`() = runTest(dispatcher) {
        val recording = recording()
        viewModel().delete(recording)
        assertEquals(listOf("stop 2026-10-01_09-05-00.m4a"), playback.calls)
        assertTrue(!recording.file.exists())
    }

    @Test
    fun `a failed delete reaches a collector that subscribes later`() = runTest(dispatcher) {
        val recording = recording()
        dir.setWritable(false)
        try {
            viewModel().delete(recording)
        } finally {
            dir.setWritable(true)
        }
        assertEquals(recording, viewModel().deleteFailures.first())
    }

    @Test
    fun `filters by the selected tag, and All shows everything`() = runTest(dispatcher) {
        val (tagged, untagged) = syncTwoRecordings()
        val work = repository.createTag("Work")
        repository.setTagged(tagged, work, tagged = true)
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }

        val all = vm.awaitLibrary { work.id in it.all.single { r -> r.id == tagged.id }.tagIds }
        assertEquals(listOf(untagged.id, tagged.id), all.recordings.map { it.id })
        vm.selectTag(work)
        val filtered = vm.awaitLibrary { it.selectedTag == work }
        assertEquals(listOf(tagged.id), filtered.recordings.map { it.id })
        assertEquals(2, filtered.all.size)
        vm.selectTag(null)
        assertEquals(2, vm.awaitLibrary { it.selectedTag == null }.recordings.size)
    }

    @Test
    fun `the selected tag is remembered and restored on the next launch`() = runTest(dispatcher) {
        syncTwoRecordings()
        val work = repository.createTag("Work")
        viewModel().selectTag(work)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { tagSelection.savedFlow.first { it == work.id } }
        }

        val nextLaunch = HomeViewModel(controller, repository, tagSelection, { FakePlayback() }) { true }
        backgroundScope.launch { nextLaunch.library.collect {} }
        assertEquals(work, nextLaunch.awaitLibrary().selectedTag)
    }

    @Test
    fun `a tag picked before the remembered one loads is not overwritten by it`() = runTest(dispatcher) {
        syncTwoRecordings()
        val work = repository.createTag("Work")
        val ideas = repository.createTag("Ideas")
        tagSelection.saved = ideas.id
        tagSelection.gate = CompletableDeferred()
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }

        vm.selectTag(work)
        tagSelection.gate!!.complete(Unit)
        assertEquals(work, vm.awaitLibrary().selectedTag)
    }

    @Test
    fun `a remembered tag that no longer exists falls back to All`() = runTest(dispatcher) {
        syncTwoRecordings()
        tagSelection.saved = 999
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }
        val library = vm.awaitLibrary()
        assertEquals(null, library.selectedTag)
        assertEquals(2, library.recordings.size)
    }

    @Test
    fun `nothing is shown until the remembered tag has been read`() = runTest(dispatcher) {
        syncTwoRecordings()
        tagSelection.gate = CompletableDeferred()
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }
        assertEquals(null, vm.library.value)
        tagSelection.gate!!.complete(Unit)
        vm.awaitLibrary()
    }

    @Test
    fun `adding a tag creates and selects it, and a blank name is refused`() = runTest(dispatcher) {
        syncTwoRecordings()
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }

        assertEquals(TagNames.Result.Blank, vm.addTag("   "))
        assertTrue(vm.awaitLibrary().tags.isEmpty())

        assertEquals(null, vm.addTag(" Ideas "))
        val library = vm.awaitLibrary { it.selectedTag != null }
        val ideas = library.tags.single()
        assertEquals("Ideas", ideas.name)
        assertEquals(ideas, library.selectedTag)
        assertEquals(ideas.id, tagSelection.saved)
    }

    @Test
    fun `adding the same tag twice, in any case, makes one tag and selects it`() = runTest(dispatcher) {
        syncTwoRecordings()
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }
        vm.addTag("Work")
        vm.addTag("work")
        val library = vm.awaitLibrary { it.selectedTag != null }
        withContext(Dispatchers.Default) { delay(200) } // Let both creates land.
        assertEquals(listOf("Work"), repository.tags.first().map { it.name })
        assertEquals("Work", library.selectedTag!!.name)
    }

    @Test
    fun `renaming a tag refuses blank names and names other tags have`() = runTest(dispatcher) {
        syncTwoRecordings()
        val work = repository.createTag("Work")
        repository.createTag("Ideas")
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }
        vm.awaitLibrary { it.tags.size == 2 }

        assertEquals(TagNames.Result.Blank, vm.renameTag(work, "  "))
        assertEquals(TagNames.Result.Taken, vm.renameTag(work, "IDEAS"))
        assertEquals(null, vm.renameTag(work, "Office"))
        assertEquals(
            listOf("Office", "Ideas"),
            vm.awaitLibrary {
                "Office" in it.tags.map { t -> t.name }
            }.tags.map { it.name }
        )
    }

    @Test
    fun `deleting the selected tag falls back to All and keeps the recordings`() = runTest(dispatcher) {
        val (tagged, _) = syncTwoRecordings()
        val work = repository.createTag("Work")
        repository.setTagged(tagged, work, tagged = true)
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }
        vm.selectTag(work)
        vm.awaitLibrary { it.selectedTag == work }

        vm.deleteTag(work)

        val library = vm.awaitLibrary { it.tags.isEmpty() && it.selectedTag == null }
        assertEquals(2, library.recordings.size)
        withContext(Dispatchers.Default) { withTimeout(5_000) { tagSelection.savedFlow.first { it == null } } }
    }

    @Test
    fun `adding a tag from a recording tags it without changing the filter`() = runTest(dispatcher) {
        val (first, _) = syncTwoRecordings()
        val vm = viewModel()
        backgroundScope.launch { vm.library.collect {} }

        assertEquals(null, vm.addTagTo(first, "Work"))
        val library = vm.awaitLibrary { l -> l.all.single { it.id == first.id }.tagIds.isNotEmpty() }
        val work = library.tags.single()
        assertEquals(setOf(work.id), library.all.single { it.id == first.id }.tagIds)
        assertEquals(null, library.selectedTag)
    }

    /**
     * Room emits on its own threads, so state is awaited rather than read. [until] lets a test wait for the
     * effect of an action it just took.
     */
    private suspend fun HomeViewModel.awaitLibrary(until: (Library) -> Boolean = { true }): Library =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { library.filterNotNull().first(until) }
        }

    private suspend fun syncTwoRecordings(): Pair<Recording, Recording> {
        File(dir, "2026-10-01_09-05-00.m4a").writeText(FAKE_AUDIO)
        File(dir, "2026-10-02_09-05-00.m4a").writeText(FAKE_AUDIO)
        repository.sync()
        val all = repository.recordings.first()
        return all.last() to all.first()
    }

    private class FakeTagSelection : TagSelection {
        val savedFlow = MutableStateFlow<Long?>(null)
        var saved: Long?
            get() = savedFlow.value
            set(value) {
                savedFlow.value = value
            }
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun load(): Long? {
            gate?.await()
            return saved
        }

        override suspend fun save(tagId: Long?) {
            saved = tagId
        }
    }

    private fun recording() = Recording(
        file = File(dir, "2026-10-01_09-05-00.m4a").apply { writeText(FAKE_AUDIO) },
        title = null,
        createdAt = Instant.EPOCH,
        durationMs = 1_000,
        sizeBytes = FAKE_AUDIO.length.toLong()
    )

    private class FakePlayback : Playback {
        val calls = mutableListOf<String>()
        override val state = MutableStateFlow(PlaybackState())
        override val positionMs = MutableStateFlow(0L)

        override fun toggle(recording: Recording, title: String) {
            calls += "toggle ${recording.id}"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek $positionMs"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun stop(recordingId: String) {
            calls += "stop $recordingId"
        }

        override fun release() {
            calls += "release"
        }
    }

    private class SilentRecorder : AudioRecorder {
        override var onError: (() -> Unit)? = null

        override fun start(output: File) {
            output.writeText(FAKE_AUDIO)
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override fun stop() = Unit

        override fun release() = Unit

        override fun maxAmplitude() = 0
    }
}
