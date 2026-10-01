package com.ingeniumtc.voicememo.recording

import android.util.Log
import java.io.File
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
     * partial) when it contains no audio.
     */
    fun commit(partial: File): File? {
        val base = partial.name.removeSuffix(PARTIAL)
        val m4a = File(dir, "$base$M4A")
        // Remux into a temp name so a kill mid-remux never leaves a truncated .m4a that looks finished.
        val temp = File(dir, "$base$M4A$TEMP")
        return try {
            remuxer.remux(partial, temp)
            if (!temp.renameTo(m4a)) throw IOException("Could not rename ${temp.name}")
            partial.delete()
            m4a
        } catch (e: NoAudioException) {
            temp.delete()
            partial.delete()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Remux failed for ${partial.name}, keeping raw AAC", e)
            temp.delete()
            val aac = File(dir, "$base$AAC")
            if (!partial.renameTo(aac)) throw IOException("Could not save ${aac.name}", e)
            aac
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
        return partials.mapNotNull { partial ->
            runCatching { commit(partial) }
                .onFailure { Log.e(TAG, "Could not recover ${partial.name}", it) }
                .getOrNull()
        }
    }

    private companion object {
        const val TAG = "RecordingStorage"
        const val AAC = ".aac"
        const val M4A = ".m4a"
        const val PARTIAL = ".aac.part"
        const val TEMP = ".tmp"
        val FINAL_EXTENSIONS = listOf(M4A, AAC, PARTIAL)
        val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
