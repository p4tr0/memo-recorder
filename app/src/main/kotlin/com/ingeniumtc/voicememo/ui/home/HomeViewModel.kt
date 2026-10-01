package com.ingeniumtc.voicememo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ingeniumtc.voicememo.VoiceMemoApp
import com.ingeniumtc.voicememo.data.Recording
import com.ingeniumtc.voicememo.data.RecordingRepository
import com.ingeniumtc.voicememo.data.Tag
import com.ingeniumtc.voicememo.data.TagNames
import com.ingeniumtc.voicememo.data.TagSelection
import com.ingeniumtc.voicememo.playback.MediaControllerPlayback
import com.ingeniumtc.voicememo.playback.Playback
import com.ingeniumtc.voicememo.playback.PlaybackState
import com.ingeniumtc.voicememo.recording.RecordingController
import com.ingeniumtc.voicememo.recording.RecordingEvent
import com.ingeniumtc.voicememo.recording.RecordingService
import com.ingeniumtc.voicememo.recording.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The library as the Home screen shows it: [recordings] is already filtered by [selectedTag]. */
data class Library(
    val tags: List<Tag>,
    /** Null means All. */
    val selectedTag: Tag?,
    val recordings: List<Recording>,
    /** Every recording, whatever the filter. */
    val all: List<Recording>
) {
    /** True when there are no recordings at all, whatever the filter. */
    val isEmpty: Boolean get() = all.isEmpty()
}

/** Wraps the selection so "loaded, All" (tagId null) differs from "not loaded yet" (no Selection). */
private data class Selection(val tagId: Long?)

class HomeViewModel(
    private val controller: RecordingController,
    private val repository: RecordingRepository,
    private val tagSelection: TagSelection,
    playbackFactory: (CoroutineScope) -> Playback,
    private val startRecordingService: () -> Boolean
) : ViewModel() {
    private val playback = playbackFactory(viewModelScope)

    val recordingState: StateFlow<RecordingState> = controller.state
    val amplitude: StateFlow<Float> = controller.amplitude
    val events: SharedFlow<RecordingEvent> = controller.events
    val playbackState: StateFlow<PlaybackState> = playback.state
    val playbackPositionMs: StateFlow<Long> = playback.positionMs

    // Null until the saved choice is read, so the list doesn't flash "All" before switching to the remembered tag.
    private val selectedTagId = MutableStateFlow<Selection?>(null)
    private val saveMutex = Mutex()

    /** Null while loading. */
    val library: StateFlow<Library?> =
        combine(repository.recordings, repository.tags, selectedTagId) { all, tags, selection ->
            selection ?: return@combine null
            // A remembered tag that no longer exists falls back to All.
            val selected = tags.firstOrNull { it.id == selection.tagId }
            Library(
                tags = tags,
                selectedTag = selected,
                recordings = if (selected == null) all else all.filter { selected.id in it.tagIds },
                all = all
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch {
            val remembered = Selection(tagSelection.load())
            // Unless the user already picked a tag while this was loading: theirs wins.
            selectedTagId.compareAndSet(null, remembered)
        }
    }

    // A channel, not a shared flow, so a failure isn't dropped while the screen resubscribes after rotation.
    private val _deleteFailures = Channel<Recording>(Channel.BUFFERED)

    /** Recordings whose file couldn't be deleted. They stay in the list. */
    val deleteFailures: Flow<Recording> = _deleteFailures.receiveAsFlow()

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
            if (!repository.delete(recording)) _deleteFailures.trySend(recording)
        }
    }

    /** Null selects All. Remembered for the next launch. */
    fun selectTag(tag: Tag?) {
        selectedTagId.value = Selection(tag?.id)
        // Saves whatever is selected by the time the lock is free, so quick taps can't land out of order.
        viewModelScope.launch {
            saveMutex.withLock { selectedTagId.value?.let { tagSelection.save(it.tagId) } }
        }
    }

    /**
     * Creates a tag from what the user typed and selects it, or selects the existing tag with that name. Returns
     * why the name was refused, or null if it was accepted.
     */
    fun addTag(input: String): TagNames.Result? = withValidName(input) { name ->
        viewModelScope.launch { selectTag(repository.createTag(name)) }
    }

    /** Creates (or reuses) a tag and puts it on [recording], without changing the filter. */
    fun addTagTo(recording: Recording, input: String): TagNames.Result? = withValidName(input) { name ->
        viewModelScope.launch { repository.setTagged(recording, repository.createTag(name), tagged = true) }
    }

    private val _renameFailures = Channel<TagNames.Result>(Channel.BUFFERED)

    /** A rename refused by the database (name taken) after it passed validation. */
    val renameFailures: Flow<TagNames.Result> = _renameFailures.receiveAsFlow()

    /**
     * Returns why the name was refused (blank, too long, taken by another tag), or null if it was submitted. If a
     * tag with that name was created a moment ago in another way, the database refuses it and [renameFailures]
     * says so.
     */
    fun renameTag(tag: Tag, input: String): TagNames.Result? {
        val valid = TagNames.validate(input) as? TagNames.Result.Valid ?: return TagNames.validate(input)
        val key = TagNames.key(valid.name)
        // Known tags answer this at once, so the dialog can show it; the database still guards the rare race.
        if (library.value?.tags.orEmpty().any { it.id != tag.id && TagNames.key(it.name) == key }) {
            return TagNames.Result.Taken
        }
        viewModelScope.launch {
            if (!repository.renameTag(tag, valid.name)) _renameFailures.trySend(TagNames.Result.Taken)
        }
        return null
    }

    /** Recordings keep existing, just without this tag. If it was selected, the list falls back to All. */
    fun deleteTag(tag: Tag) {
        viewModelScope.launch {
            repository.deleteTag(tag)
            if (selectedTagId.value?.tagId == tag.id) selectTag(null)
        }
    }

    fun setTagged(recording: Recording, tag: Tag, tagged: Boolean) {
        viewModelScope.launch { repository.setTagged(recording, tag, tagged) }
    }

    private fun withValidName(input: String, onValid: (String) -> Unit): TagNames.Result? =
        when (val result = TagNames.validate(input)) {
            is TagNames.Result.Valid -> {
                onValid(result.name)
                null
            }

            else -> result
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
                    tagSelection = app.container.tagSelection,
                    playbackFactory = { scope -> MediaControllerPlayback(app, scope) },
                    startRecordingService = { RecordingService.start(app) }
                )
            }
        }
    }
}
