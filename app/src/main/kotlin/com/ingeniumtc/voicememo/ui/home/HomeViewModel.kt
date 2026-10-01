package com.ingeniumtc.voicememo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ingeniumtc.voicememo.VoiceMemoApp
import com.ingeniumtc.voicememo.recording.RecordingController
import com.ingeniumtc.voicememo.recording.RecordingEvent
import com.ingeniumtc.voicememo.recording.RecordingService
import com.ingeniumtc.voicememo.recording.RecordingState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class HomeViewModel(private val controller: RecordingController, private val startRecordingService: () -> Unit) :
    ViewModel() {
    val recordingState: StateFlow<RecordingState> = controller.state
    val amplitude: StateFlow<Float> = controller.amplitude
    val events: SharedFlow<RecordingEvent> = controller.events

    /** Requires RECORD_AUDIO to be granted already. */
    fun startRecording() = startRecordingService()

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun stop() = controller.stop()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as VoiceMemoApp
                HomeViewModel(app.container.recordingController) { RecordingService.start(app) }
            }
        }
    }
}
