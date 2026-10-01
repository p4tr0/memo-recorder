package com.ingeniumtc.voicememo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ingeniumtc.voicememo.VoiceMemoApp
import com.ingeniumtc.voicememo.data.Recording
import com.ingeniumtc.voicememo.data.RecordingRepository
import com.ingeniumtc.voicememo.playback.MediaControllerPlayback
import com.ingeniumtc.voicememo.playback.Playback
import com.ingeniumtc.voicememo.playback.PlaybackState
import com.ingeniumtc.voicememo.recording.RecordingController
import com.ingeniumtc.voicememo.recording.RecordingEvent
import com.ingeniumtc.voicememo.recording.RecordingService
import com.ingeniumtc.voicememo.recording.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val controller: RecordingController,
    private val repository: RecordingRepository,
    playbackFactory: (CoroutineScope) -> Playback,
    private val startRecordingService: () -> Boolean
) : ViewModel() {
    private val playback = playbackFactory(viewModelScope)

    val recordingState: StateFlow<RecordingState> = controller.state
    val amplitude: StateFlow<Float> = controller.amplitude
    val events: SharedFlow<RecordingEvent> = controller.events
    val playbackState: StateFlow<PlaybackState> = playback.state

    val recordings: StateFlow<List<Recording>?> =
        repository.recordings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _deleteFailures = MutableSharedFlow<Recording>(extraBufferCapacity = 1)

    /** Recordings whose file couldn't be deleted. They stay in the list. */
    val deleteFailures: SharedFlow<Recording> = _deleteFailures.asSharedFlow()

    /** Requires RECORD_AUDIO to be granted already. */
    fun startRecording() {
        // Otherwise the speaker would be recorded too.
        playback.pause()
        if (!startRecordingService()) controller.reportStartFailure()
    }

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun stop() = controller.stop()

    /** Ignored while recording: the list is hidden then, and playing would be picked up by the microphone. */
    fun togglePlayback(recording: Recording, title: String) {
        if (controller.state.value == RecordingState.Idle) playback.toggle(recording, title)
    }

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun rename(recording: Recording, title: String) {
        viewModelScope.launch { repository.rename(recording, title) }
    }

    fun delete(recording: Recording) {
        playback.stop(recording.id)
        viewModelScope.launch {
            if (!repository.delete(recording)) _deleteFailures.tryEmit(recording)
        }
    }

    override fun onCleared() {
        playback.release()
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as VoiceMemoApp
                HomeViewModel(
                    controller = app.container.recordingController,
                    repository = app.container.recordingRepository,
                    playbackFactory = { scope -> MediaControllerPlayback(app, scope) },
                    startRecordingService = { RecordingService.start(app) }
                )
            }
        }
    }
}
