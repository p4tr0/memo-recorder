package com.ingeniumtc.voicememo.recording

import java.io.File
import java.io.IOException

/** Rewraps already-encoded audio into another container without re-encoding. */
fun interface AudioRemuxer {
    /**
     * Throws [NoAudioException] if [input] holds no usable audio, or another exception if remuxing failed.
     * Returns what was copied, so the caller can check nothing was silently dropped.
     */
    fun remux(input: File, output: File): RemuxResult
}

/** [payloadBytes] counts frame payloads only, without the ADTS headers the extractor strips. */
data class RemuxResult(val frames: Int, val payloadBytes: Long)

class NoAudioException(message: String) : IOException(message)
