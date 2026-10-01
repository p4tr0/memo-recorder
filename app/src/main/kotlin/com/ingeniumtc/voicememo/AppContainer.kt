package com.ingeniumtc.voicememo

import android.content.Context
import android.util.Log
import com.ingeniumtc.voicememo.data.RecordingRepository
import com.ingeniumtc.voicememo.data.VoiceMemoDatabase
import com.ingeniumtc.voicememo.data.readMediaDurationMs
import com.ingeniumtc.voicememo.recording.MediaMuxerRemuxer
import com.ingeniumtc.voicememo.recording.MediaRecorderAudioRecorder
import com.ingeniumtc.voicememo.recording.RecordingController
import com.ingeniumtc.voicememo.recording.RecordingStorage
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

    val recordingController = RecordingController(
        storage = recordingStorage,
        recorderFactory = { MediaRecorderAudioRecorder(appContext) },
        scope = appScope,
        onFilesChanged = { appScope.launch { recordingRepository.sync() } }
    )
}
