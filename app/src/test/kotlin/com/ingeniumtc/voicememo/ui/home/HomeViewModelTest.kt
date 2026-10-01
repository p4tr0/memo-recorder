package com.ingeniumtc.voicememo.ui.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ingeniumtc.voicememo.data.Recording
import com.ingeniumtc.voicememo.data.RecordingRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VoiceMemoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dir = tmp.newFolder("recordings")
        val storage = RecordingStorage(dir, FakeRemuxer())
        val repository = RecordingRepository(storage, db.recordings(), { 1_000 }, dispatcher)
        controller = RecordingController(storage, { SilentRecorder() }, appScope, dispatcher)
        viewModel = HomeViewModel(controller, repository, { playback }) {
            controller.start()
            true
        }
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `starting a recording pauses playback first`() {
        viewModel.startRecording()
        assertEquals(listOf("pause"), playback.calls)
        assertTrue(controller.state.value is RecordingState.Active)
    }

    @Test
    fun `playback can't be started while recording`() {
        viewModel.startRecording()
        playback.calls.clear()
        viewModel.togglePlayback(recording(), "title")
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `playback toggles when idle`() {
        viewModel.togglePlayback(recording(), "title")
        assertEquals(listOf("toggle 2026-10-01_09-05-00.m4a"), playback.calls)
    }

    @Test
    fun `delete unloads the recording from the player before deleting it`() = runTest(dispatcher) {
        val recording = recording()
        viewModel.delete(recording)
        assertEquals(listOf("stop 2026-10-01_09-05-00.m4a"), playback.calls)
        assertTrue(!recording.file.exists())
    }

    @Test
    fun `a failed delete reaches a collector that subscribes later`() = runTest(dispatcher) {
        val recording = recording()
        dir.setWritable(false)
        try {
            viewModel.delete(recording)
        } finally {
            dir.setWritable(true)
        }
        assertEquals(recording, viewModel.deleteFailures.first())
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
