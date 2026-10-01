package com.ingeniumtc.voicememo.recording

import java.io.File

/** Thin seam over [android.media.MediaRecorder] so [RecordingController] can be tested without hardware. */
interface AudioRecorder {
    /** Called from an arbitrary thread when the underlying recorder hits an unrecoverable error. */
    var onError: (() -> Unit)?

    /** Configures, prepares, and starts recording into [output]. Throws if the microphone can't be opened. */
    fun start(output: File)

    fun pause()

    fun resume()

    /** Finalizes the file. Throws if no valid audio was captured (for example, stopped immediately). */
    fun stop()

    fun release()

    /** Peak amplitude (0..32767) since the previous call. */
    fun maxAmplitude(): Int
}
