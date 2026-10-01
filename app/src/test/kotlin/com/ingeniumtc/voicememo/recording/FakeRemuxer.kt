package com.ingeniumtc.voicememo.recording

import java.io.File
import java.io.IOException

/**
 * Treats the input as a single ADTS frame and copies it through. Like the real extractor, an empty input
 * fails with a generic [IOException], not [NoAudioException], so storage has to catch that case itself.
 * [keepBytes] simulates the extractor stopping early at a corrupt frame.
 */
class FakeRemuxer(
    private val failWith: Exception? = null,
    private val keepBytes: Int? = null,
    private val reportFrames: Int = 1
) : AudioRemuxer {
    var calls = 0
        private set

    override fun remux(input: File, output: File): RemuxResult {
        calls++
        if (input.length() == 0L) throw IOException("Failed to instantiate extractor")
        failWith?.let { throw it }
        val bytes = input.readBytes().let { if (keepBytes != null) it.copyOf(keepBytes) else it }
        output.writeBytes(bytes)
        return RemuxResult(frames = reportFrames, payloadBytes = bytes.size - 7L * reportFrames)
    }
}

/**
 * Long enough to hold an ADTS header, so storage treats it as audio. The second byte, 'c' (0x63), has the
 * protection_absent bit set like MediaRecorder's output, so storage expects 7-byte headers.
 */
const val FAKE_AUDIO = "acts-frame"
