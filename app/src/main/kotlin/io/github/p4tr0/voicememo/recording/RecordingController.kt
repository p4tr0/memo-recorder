package io.github.p4tr0.voicememo.recording

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import kotlin.math.log10
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the single recording session. All recorder calls run on one serialized dispatcher, off the main
 * thread, so commands from the UI, the notification, and recorder callbacks can't interleave.
 * Commands are fire-and-forget on an app-lifetime [scope] so a stop survives the UI going away.
 */
class RecordingController(
    private val storage: RecordingStorage,
    private val recorderFactory: () -> AudioRecorder,
    private val scope: CoroutineScope,
    @Suppress("OPT_IN_USAGE")
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    /** Called on the recorder dispatcher whenever finished files may have changed: after a save or recovery. */
    private val onFilesChanged: () -> Unit = {},
    /** Called on the recorder dispatcher once a recording is running, with its `.part` file. */
    private val onSessionStarted: (File) -> Unit = {}
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)

    /** Input level in 0..1 with peak-hold decay, updated every [METER_INTERVAL_MS] while recording. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _events = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RecordingEvent> = _events.asSharedFlow()

    private var recorder: AudioRecorder? = null
    private var partialFile: File? = null
    private var meterJob: Job? = null

    fun start() = command {
        if (_state.value != RecordingState.Idle) return@command
        val output = storage.newPartialFile(now())
        val r = recorderFactory()
        // Identity check: an error queued from a previous recorder must not end a newer session.
        r.onError = { command { if (recorder === r) finish(RecordingEvent.Reason.RecorderError) } }
        try {
            r.start(output)
        } catch (e: Exception) {
            r.release()
            storage.discard(output)
            _events.tryEmit(RecordingEvent.Failed(RecordingEvent.Reason.CouldNotStart))
            return@command
        }
        recorder = r
        partialFile = output
        _state.value = RecordingState.Active(isPaused = false, accumulatedMs = 0, resumedAt = clock())
        onSessionStarted(output)
        startMeter(r)
    }

    fun pause() = command {
        val s = _state.value as? RecordingState.Active ?: return@command
        if (s.isPaused) return@command
        val r = recorder ?: return@command
        // A recorder in its error state throws here before onError arrives. End cleanly instead of crashing.
        if (runCatching { r.pause() }.isFailure) {
            finish(RecordingEvent.Reason.RecorderError)
            return@command
        }
        meterJob?.cancel()
        _amplitude.value = 0f
        _state.value = s.copy(isPaused = true, accumulatedMs = s.elapsedMs(clock()))
    }

    fun resume() = command {
        val s = _state.value as? RecordingState.Active ?: return@command
        if (!s.isPaused) return@command
        val r = recorder ?: return@command
        if (runCatching { r.resume() }.isFailure) {
            finish(RecordingEvent.Reason.RecorderError)
            return@command
        }
        _state.value = s.copy(isPaused = false, resumedAt = clock())
        startMeter(r)
    }

    fun stop() = command { finish(failureReason = RecordingEvent.Reason.TooShort) }

    /**
     * Finishes recordings cut off by process death. Runs on the recorder dispatcher, so it is ordered
     * before any [start] issued after it and can never touch a live recording.
     */
    fun recoverInterruptedRecordings() = command {
        if (_state.value != RecordingState.Idle) return@command
        val recovered = storage.recoverInterrupted()
        if (recovered.isNotEmpty()) Log.i(TAG, "Recovered ${recovered.size} interrupted recording(s)")
        // Always, so the library also picks up files from a run whose save finished but whose sync didn't.
        onFilesChanged()
    }

    /** For failures outside the recorder, such as the system refusing to start the foreground service. */
    fun reportStartFailure() = command {
        if (_state.value == RecordingState.Idle) {
            _events.tryEmit(RecordingEvent.Failed(RecordingEvent.Reason.CouldNotStart))
        } else {
            finish(RecordingEvent.Reason.CouldNotStart)
        }
    }

    private fun finish(failureReason: RecordingEvent.Reason) {
        val s = _state.value as? RecordingState.Active ?: return
        val r = recorder ?: return
        val partial = partialFile ?: return
        meterJob?.cancel()
        val duration = s.elapsedMs(clock())
        val stopped = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        partialFile = null
        _amplitude.value = 0f

        // Even when stop() failed, ADTS frames written before the failure are playable, so try to keep them.
        // Saving happens before going Idle so the foreground service keeps the process alive meanwhile.
        val commit = runCatching { storage.commit(partial) }
            .onFailure { Log.e(TAG, "Could not save ${partial.name}", it) }
        val saved = commit.getOrNull()
        if (saved != null) onFilesChanged()
        _state.value = RecordingState.Idle
        _events.tryEmit(
            when {
                saved != null -> RecordingEvent.Saved(saved, duration)

                // The audio is still in the partial, and the next launch's recovery finishes it.
                commit.isFailure -> RecordingEvent.Failed(RecordingEvent.Reason.SaveFailed)

                stopped -> RecordingEvent.Failed(RecordingEvent.Reason.TooShort)

                else -> RecordingEvent.Failed(failureReason)
            }
        )
    }

    private fun startMeter(r: AudioRecorder) {
        meterJob?.cancel()
        meterJob = scope.launch(dispatcher) {
            readAmplitude(r) // The first reading covers time before recording started.
            while (isActive) {
                delay(METER_INTERVAL_MS)
                val level = normalize(readAmplitude(r))
                _amplitude.value = maxOf(level, _amplitude.value * PEAK_DECAY)
            }
        }
    }

    /** Metering is cosmetic: a recorder in its error state reads as silence until onError ends the session. */
    private fun readAmplitude(r: AudioRecorder): Int = runCatching { r.maxAmplitude() }.getOrDefault(0)

    private fun command(block: () -> Unit) {
        scope.launch(dispatcher) { block() }
    }

    internal companion object {
        private const val TAG = "RecordingController"
        const val METER_INTERVAL_MS = 50L
        private const val PEAK_DECAY = 0.8f
        private const val FLOOR_DB = -50f

        /** Maps raw amplitude onto 0..1 on a dB scale, which matches perceived loudness. */
        fun normalize(raw: Int): Float {
            if (raw <= 0) return 0f
            val db = 20f * log10(raw / 32_767f)
            return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
    }
}
