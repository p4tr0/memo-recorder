package io.github.p4tr0.voicememo.ui.home

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.p4tr0.voicememo.VoiceMemoApp
import io.github.p4tr0.voicememo.data.FolderTransfers
import io.github.p4tr0.voicememo.data.Recording
import io.github.p4tr0.voicememo.data.RecordingRepository
import io.github.p4tr0.voicememo.data.Tag
import io.github.p4tr0.voicememo.data.TagNames
import io.github.p4tr0.voicememo.data.TagSelection
import io.github.p4tr0.voicememo.data.TransferResult
import io.github.p4tr0.voicememo.playback.MediaControllerPlayback
import io.github.p4tr0.voicememo.playback.Playback
import io.github.p4tr0.voicememo.playback.PlaybackState
import io.github.p4tr0.voicememo.recording.RecordingController
import io.github.p4tr0.voicememo.recording.RecordingEvent
import io.github.p4tr0.voicememo.recording.RecordingService
import io.github.p4tr0.voicememo.recording.RecordingState
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The library as the Home screen shows it: [recordings] is already filtered by [selectedTag] and [search]. */
data class Library(
    val tags: List<Tag>,
    /** Null means All. */
    val selectedTag: Tag?,
    val recordings: List<Recording>,
    /** Every recording, whatever the filter. */
    val all: List<Recording>,
    /** What's typed into the search bar, or null while it's closed. */
    val search: String? = null
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
    /** Imports (from a folder or shared in) and exports. */
    private val transfers: FolderTransfers,
    playbackFactory: (CoroutineScope) -> Playback,
    /** Keeps the search across process death, like the screen's other saved state. */
    private val savedState: SavedStateHandle,
    /** Filters off the main thread: matching formats every recording's date. */
    private val filterDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Read on each search, so a time zone change shows up without a restart. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val startRecordingService: () -> Boolean
) : ViewModel() {
    private val playback = playbackFactory(viewModelScope)

    val recordingState: StateFlow<RecordingState> = controller.state
    val amplitude: StateFlow<Float> = controller.amplitude
    val events: SharedFlow<RecordingEvent> = controller.events
    val playbackState: StateFlow<PlaybackState> = playback.state
    val playbackPositionMs: StateFlow<Long> = playback.positionMs
    val transferBusy: StateFlow<Boolean> = transfers.busy
    val transferResults: Flow<TransferResult> = transfers.results

    // Null until the saved choice is read, so the list doesn't flash "All" before switching to the remembered tag.
    private val selectedTagId = MutableStateFlow<Selection?>(null)
    private val saveMutex = Mutex()

    /** Null while loading. */
    private val search: StateFlow<String?> = savedState.getStateFlow(SEARCH_KEY, null)

    val library: StateFlow<Library?> =
        combine(repository.recordings, repository.tags, selectedTagId, search) { all, tags, selection, search ->
            selection ?: return@combine null
            // A remembered tag that no longer exists falls back to All.
            val selected = tags.firstOrNull { it.id == selection.tagId }
            val matcher = search?.let { SearchMatcher(it, zone()) }
            Library(
                tags = tags,
                selectedTag = selected,
                recordings = all.filter { recording ->
                    (selected == null || recording.tagId == selected.id) &&
                        (matcher == null || matcher.matches(recording))
                },
                all = all,
                search = search
            )
        }.flowOn(filterDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

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

    /** Opens the search bar, empty. */
    fun openSearch() {
        savedState[SEARCH_KEY] = ""
    }

    /** Ignored while the search bar is closed. */
    fun setSearch(query: String) {
        if (search.value != null) savedState[SEARCH_KEY] = query
    }

    /** Closes the search bar and shows the whole list again. */
    fun closeSearch() {
        savedState[SEARCH_KEY] = null
    }

    /** Requires RECORD_AUDIO to be granted already. */
    fun startRecording() {
        // The list it filters is hidden while recording, and all of it is shown again afterwards.
        closeSearch()
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

    /** Copies every audio file in [folder] and its subfolders into the library. */
    fun importFrom(folder: Uri) = transfers.importFolder(folder)

    /**
     * Copies the recordings shown (all, or those with the selected tag) to [folder]. Read from the repository by
     * the transfer, not from [library], which is still loading if the process was killed while the folder picker
     * was open, and goes away with this ViewModel.
     */
    fun exportTo(folder: Uri) {
        val selection = selectedTagId.value
        transfers.exportTo(folder) {
            val tagId = (selection ?: Selection(tagSelection.load())).tagId
            val all = repository.recordings.first()
            // Like the list: a tag that no longer exists shows All.
            if (tagId == null ||
                repository.tags.first().none { it.id == tagId }
            ) {
                all
            } else {
                all.filter { it.tagId == tagId }
            }
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

    /** Creates (or reuses) a tag and makes it [recording]'s tag, replacing any other, without changing the filter. */
    fun addTagTo(recording: Recording, input: String): TagNames.Result? = withValidName(input) { name ->
        viewModelScope.launch { repository.setTag(recording, repository.createTag(name)) }
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

    /** A recording carries one tag: this replaces any other. Null removes it. */
    fun setTag(recording: Recording, tag: Tag?) {
        viewModelScope.launch { repository.setTag(recording, tag) }
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
        private const val SEARCH_KEY = "search"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as VoiceMemoApp
                HomeViewModel(
                    controller = app.container.recordingController,
                    repository = app.container.recordingRepository,
                    tagSelection = app.container.tagSelection,
                    transfers = app.container.transfers,
                    savedState = createSavedStateHandle(),
                    playbackFactory = { scope -> MediaControllerPlayback(app, scope) },
                    startRecordingService = { RecordingService.start(app) }
                )
            }
        }
    }
}
