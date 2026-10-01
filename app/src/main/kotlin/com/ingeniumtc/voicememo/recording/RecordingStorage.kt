package com.ingeniumtc.voicememo.recording

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Recordings live in app-private storage. A recording is written to a `.part` file and renamed to `.m4a`
 * only after a clean stop: an MP4 is unplayable until the recorder writes its index on stop, so a `.part`
 * file left behind by a killed process can't be recovered and is deleted.
 */
class RecordingStorage(private val dir: File) {

    fun newPartialFile(startedAt: LocalDateTime): File {
        dir.mkdirs()
        val base = startedAt.format(FILE_NAME_FORMAT)
        var candidate = base
        var n = 2
        while (File(dir, "$candidate.$EXTENSION").exists() || File(dir, "$candidate.$EXTENSION.$PARTIAL").exists()) {
            candidate = "$base-${n++}"
        }
        return File(dir, "$candidate.$EXTENSION.$PARTIAL")
    }

    /** Promotes a finished `.part` file to its final `.m4a` name. */
    fun commit(partial: File): File {
        val final = File(partial.parentFile, partial.name.removeSuffix(".$PARTIAL"))
        if (!partial.renameTo(final)) throw IOException("Could not save ${final.name}")
        return final
    }

    fun discard(partial: File) {
        partial.delete()
    }

    /** Call via [RecordingController.deleteAbandonedRecordings], which guarantees no recording is in progress. */
    fun deleteAbandonedPartials() {
        dir.listFiles { f -> f.name.endsWith(".$PARTIAL") }?.forEach { it.delete() }
    }

    private companion object {
        const val EXTENSION = "m4a"
        const val PARTIAL = "part"
        val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
