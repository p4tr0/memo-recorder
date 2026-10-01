package com.ingeniumtc.voicememo.recording

import java.io.File

sealed interface RecordingState {
    data object Idle : RecordingState

    /**
     * A recording in progress. Elapsed time is derived rather than ticked, so state only changes on
     * pause/resume: [accumulatedMs] covers finished segments and [resumedAt] (elapsedRealtime) marks
     * the start of the current one.
     */
    data class Active(val isPaused: Boolean, val accumulatedMs: Long, val resumedAt: Long) : RecordingState {
        fun elapsedMs(now: Long): Long = if (isPaused) accumulatedMs else accumulatedMs + (now - resumedAt)
    }
}

sealed interface RecordingEvent {
    data class Saved(val file: File, val durationMs: Long) : RecordingEvent

    data class Failed(val reason: Reason) : RecordingEvent

    enum class Reason {
        /** Microphone unavailable, permission revoked, or the foreground service was not allowed. */
        CouldNotStart,

        /** Stopped before any audio was captured. */
        TooShort,

        /** The recorder failed mid-recording and the file could not be finalized. */
        RecorderError
    }
}
