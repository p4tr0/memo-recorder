package io.github.p4tr0.voicememo.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** ADTS (.aac) to MPEG-4 (.m4a). Copies AAC frames as-is, so it is lossless and fast (about 1s per hour of audio). */
internal class MediaMuxerRemuxer : AudioRemuxer {

    override fun remux(input: File, output: File): RemuxResult {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw NoAudioException("No audio track in ${input.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                DEFAULT_BUFFER_SIZE
            }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()

            val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val outTrack = muxer.addTrack(format)
                muxer.start()
                var samples = 0
                var payloadBytes = 0L
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(outTrack, buffer, info)
                    samples++
                    payloadBytes += size
                    extractor.advance()
                }
                // MediaMuxer.stop() throws on an empty track, so report it as what it is.
                if (samples == 0) throw NoAudioException("No audio frames in ${input.name}")
                muxer.stop()
                return RemuxResult(samples, payloadBytes)
            } finally {
                muxer.release()
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
