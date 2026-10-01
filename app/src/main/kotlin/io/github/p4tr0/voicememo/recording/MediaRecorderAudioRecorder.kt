package io.github.p4tr0.voicememo.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Hardware AAC in an ADTS stream. Unlike MPEG-4, ADTS needs no index written on stop, so audio survives
 * the process being killed. [RecordingStorage] remuxes it to .m4a after a clean stop.
 */
internal class MediaRecorderAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    override var onError: (() -> Unit)? = null

    override fun start(output: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder = r
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(SAMPLE_RATE_HZ)
        r.setAudioEncodingBitRate(BIT_RATE_BPS)
        r.setOutputFile(output)
        r.setOnErrorListener { _, _, _ -> onError?.invoke() }
        r.prepare()
        r.start()
    }

    override fun pause() {
        recorder?.pause()
    }

    override fun resume() {
        recorder?.resume()
    }

    override fun stop() {
        checkNotNull(recorder) { "Recorder not started" }.stop()
    }

    override fun release() {
        recorder?.release()
        recorder = null
    }

    override fun maxAmplitude(): Int = recorder?.maxAmplitude ?: 0

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val BIT_RATE_BPS = 96_000
    }
}
