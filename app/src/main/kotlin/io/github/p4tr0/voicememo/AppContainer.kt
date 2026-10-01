package io.github.p4tr0.voicememo

import android.content.Context
import android.util.Log
import io.github.p4tr0.voicememo.data.AudioImport
import io.github.p4tr0.voicememo.data.AutoTagger
import io.github.p4tr0.voicememo.data.ImportSummary
import io.github.p4tr0.voicememo.data.Importer
import io.github.p4tr0.voicememo.data.RecordingRepository
import io.github.p4tr0.voicememo.data.SharedPreferencesTagSelection
import io.github.p4tr0.voicememo.data.TagSelection
import io.github.p4tr0.voicememo.data.VoiceMemoDatabase
import io.github.p4tr0.voicememo.data.readMediaDurationMs
import io.github.p4tr0.voicememo.data.readRecordedAt
import io.github.p4tr0.voicememo.recording.MediaMuxerRemuxer
import io.github.p4tr0.voicememo.recording.MediaRecorderAudioRecorder
import io.github.p4tr0.voicememo.recording.RecordingController
import io.github.p4tr0.voicememo.recording.RecordingStorage
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Manual DI: app-lifetime singletons. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    // A stray exception must never kill the process mid-recording: that loses the whole file.
    val appScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e -> Log.e("VoiceMemo", "Uncaught in appScope", e) }
    )

    val recordingStorage = RecordingStorage(File(appContext.filesDir, "recordings"), MediaMuxerRemuxer())

    private val database = VoiceMemoDatabase.create(appContext)

    val recordingRepository = RecordingRepository(recordingStorage, database.recordings(), ::readMediaDurationMs)

    private val tagPrefs = SharedPreferencesTagSelection(appContext)
    val tagSelection: TagSelection = tagPrefs

    // Recordings made while the library is filtered by a tag get that tag; see AutoTagger.
    private val autoTagger = AutoTagger(recordingStorage, recordingRepository, tagSelection, tagPrefs, appScope)

    val recordingController = RecordingController(
        storage = recordingStorage,
        recorderFactory = { MediaRecorderAudioRecorder(appContext) },
        scope = appScope,
        onFilesChanged = {
            appScope.launch { recordingRepository.sync() }
            autoTagger.filesChanged()
        },
        onSessionStarted = autoTagger::sessionStarted
    )

    val importer = Importer(recordingStorage, recordingRepository, tagSelection, { readRecordedAt(it) })

    private val _importResults = Channel<ImportSummary>(Channel.BUFFERED)

    /** One summary per share into the app; buffered, since a share can cold-start the app before the UI exists. */
    val importResults: Flow<ImportSummary> = _importResults.receiveAsFlow()

    /**
     * On the app scope and off the main thread (describing the files queries the sender), so the copy finishes
     * even if the screen goes away.
     */
    fun importShared(describe: () -> List<AudioImport>) {
        appScope.launch(Dispatchers.IO) {
            val items = describe()
            if (items.isNotEmpty()) _importResults.send(importer.import(items))
        }
    }
}
