package com.ingeniumtc.voicememo.recording

import java.io.File
import java.io.IOException

/** Rewraps already-encoded audio into another container without re-encoding. */
fun interface AudioRemuxer {
    /** Throws [NoAudioException] if [input] holds no usable audio, or another exception if remuxing failed. */
    fun remux(input: File, output: File)
}

class NoAudioException(message: String) : IOException(message)
