package com.ingeniumtc.voicememo.recording

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Recordings live in app-private storage.
 *
 * Audio is captured as ADTS AAC into `<name>.aac.part`. ADTS is a stream of self-contained frames, so the
 * file stays playable up to the last frame even if the process is killed. On [commit] it is losslessly
 * remuxed into `<name>.m4a` (seekable, with a duration header). If remuxing fails for any reason other
 * than there being no audio at all, the raw `.aac` is kept instead: captured audio is never deleted.
 */
class RecordingStorage(private val dir: File, private val remuxer: AudioRemuxer) {

    fun newPartialFile(startedAt: LocalDateTime): File {
        dir.mkdirs()
        val base = startedAt.format(FILE_NAME_FORMAT)
        var candidate = base
        var n = 2
        while (FINAL_EXTENSIONS.any { File(dir, "$candidate$it").exists() }) {
            candidate = "$base-${n++}"
        }
        return File(dir, "$candidate$PARTIAL")
    }

    /**
     * Turns a `.part` recording into its final file and returns it, or returns null (and deletes the
     * partial) when it contains no audio. Throws only if the audio couldn't be saved under any name, in
     * which case the partial is left for [recoverInterrupted].
     */
    fun commit(partial: File): File? {
        // Smaller than one ADTS header means not a single frame was written. Remuxing it would fail with a
        // generic extractor error and wrongly keep an empty .aac.
        if (partial.length() < ADTS_HEADER_BYTES) {
            partial.delete()
            return null
        }
        val base = partial.name.removeSuffix(PARTIAL)
        val m4a = File(dir, "$base$M4A")
        // Remux into a temp name so a kill mid-remux never leaves a truncated .m4a that looks finished.
        val temp = File(dir, "$base$M4A$TEMP")
        return try {
            val result = remuxer.remux(partial, temp)
            // MediaMuxer doesn't fsync. Without this, a power loss right after stop can persist the rename
            // and the delete below but not the data, losing the whole recording.
            fsync(temp)
            if (!temp.renameTo(m4a)) throw IOException("Could not rename ${temp.name}")
            fsyncDir()
            if (accountsForAllAudio(partial, result)) {
                partial.delete()
            } else {
                // The extractor stops at the first bad frame, so the .m4a may be missing audio. Keep the raw
                // stream next to it.
                Log.w(TAG, "${m4a.name} holds ${result.frames} frames but ${partial.name} has more, keeping raw AAC")
                keepRaw(partial)
            }
            m4a
        } catch (e: NoAudioException) {
            temp.delete()
            // "No frames" only means the extractor couldn't read the first one, not that the file is empty.
            if (accountsForAllAudio(partial, RemuxResult(frames = 0, payloadBytes = 0))) {
                partial.delete()
                null
            } else {
                Log.w(TAG, "No readable audio in ${partial.name} but it has data, keeping raw AAC", e)
                keepRaw(partial) ?: throw IOException("Could not save ${partial.name}", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remux failed for ${partial.name}, keeping raw AAC", e)
            temp.delete()
            keepRaw(partial) ?: throw IOException("Could not save ${partial.name}", e)
        }
    }

    fun discard(partial: File) {
        partial.delete()
    }

    /**
     * Finishes recordings interrupted by process death. Call via
     * [RecordingController.recoverInterruptedRecordings], which guarantees no recording is in progress.
     */
    fun recoverInterrupted(): List<File> {
        dir.listFiles { f -> f.name.endsWith(TEMP) }?.forEach { it.delete() }
        val partials = dir.listFiles { f -> f.name.endsWith(PARTIAL) }.orEmpty()
        val recovered = partials.mapNotNull { partial ->
            // A native crash in the muxer kills the process, so this would otherwise retry, and crash, on every
            // launch. If the marker survived, the last attempt crashed: keep the raw stream without remuxing.
            val marker = File(dir, partial.name.removeSuffix(PARTIAL) + RECOVERING)
            runCatching {
                if (marker.exists() && partial.length() >= ADTS_HEADER_BYTES) {
                    Log.w(TAG, "Recovering ${partial.name} crashed last time, keeping raw AAC")
                    keepRaw(partial)
                } else {
                    marker.createNewFile()
                    commit(partial)
                }
            }
                .onFailure { Log.e(TAG, "Could not recover ${partial.name}", it) }
                .getOrNull()
                .also { if (!partial.exists()) marker.delete() }
        }
        dir.listFiles { f -> f.name.endsWith(RECOVERING) }
            ?.filterNot { File(dir, it.name.removeSuffix(RECOVERING) + PARTIAL).exists() }
            ?.forEach { it.delete() }
        return recovered
    }

    /** Renames [partial] to `.aac`, or returns null if that failed (the partial is then left in place). */
    private fun keepRaw(partial: File): File? {
        val aac = File(dir, partial.name.removeSuffix(PARTIAL) + AAC)
        // MediaRecorder doesn't fsync either, so make sure the data lands before the rename does.
        runCatching { fsync(partial) }.onFailure { Log.w(TAG, "Could not sync ${partial.name}", it) }
        if (!partial.renameTo(aac)) return null
        fsyncDir()
        return aac
    }

    private fun fsync(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    /** Persists renames and deletes. Best effort: it only narrows the window, and the file data is already synced. */
    private fun fsyncDir() {
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not sync ${dir.name}", e)
        }
    }

    private companion object {
        const val TAG = "RecordingStorage"
        const val AAC = ".aac"
        const val M4A = ".m4a"
        const val PARTIAL = ".aac.part"
        const val TEMP = ".tmp"
        const val RECOVERING = ".recovering"
        val FINAL_EXTENSIONS = listOf(M4A, AAC, PARTIAL)
        val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        /** An ADTS header is 7 bytes, or 9 with a CRC. MediaRecorder writes no CRC. */
        const val ADTS_HEADER_BYTES = 7
        const val ADTS_CRC_HEADER_BYTES = 9

        /** A frame's length field is 13 bits, so a truncated last frame the extractor skipped is under 8 KiB. */
        const val MAX_FRAME_BYTES = 8_191

        /**
         * True if [result] covers everything in [input] except at most one truncated final frame. The header size
         * comes from the stream itself: a per-frame CRC allowance on a CRC-less stream would grow with length and
         * hide tens of seconds of dropped audio in a long recording.
         */
        fun accountsForAllAudio(input: File, result: RemuxResult): Boolean {
            val unaccounted = input.length() - result.payloadBytes - result.frames.toLong() * adtsHeaderBytes(input)
            return unaccounted <= MAX_FRAME_BYTES
        }

        /** `protection_absent` is the last bit of the second header byte. */
        private fun adtsHeaderBytes(input: File): Int {
            val second = input.inputStream().use {
                it.read()
                it.read()
            }
            return if (second < 0 || second and 0x01 == 1) ADTS_HEADER_BYTES else ADTS_CRC_HEADER_BYTES
        }
    }
}
