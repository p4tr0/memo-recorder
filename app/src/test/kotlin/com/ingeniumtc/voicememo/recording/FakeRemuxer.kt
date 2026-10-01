package com.ingeniumtc.voicememo.recording

import java.io.File

/** Copies bytes through; an empty input has no audio, like a real ADTS stream with no frames. */
class FakeRemuxer(private val failWith: Exception? = null) : AudioRemuxer {
    override fun remux(input: File, output: File) {
        if (input.length() == 0L) throw NoAudioException("empty")
        failWith?.let { throw it }
        input.copyTo(output, overwrite = true)
    }
}
