package io.github.p4tr0.voicememo.data

import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExportSummary(val exported: Int, val alreadyThere: Int, val failed: Int)

/** A folder recordings are exported to, e.g. one the user picked in the system file picker. */
interface ExportTarget {
    /** Names and sizes of the files directly in the folder, or null if it can't be read. */
    fun existingFiles(): Map<String, Long>?

    /** A new, empty file. The folder may give it another name if [name] is taken. Null if it can't be created. */
    fun create(name: String): ExportFile?
}

interface ExportFile {
    /** Copies [source] in and syncs it to disk where the folder allows. */
    fun write(source: File)

    /** Removes a file whose [write] failed, so no truncated copy is left behind. */
    fun delete()
}

/**
 * Copies recordings out of the library, unchanged, named after their title (or their date when untitled). The
 * library is only read. A file already in the folder under the same name and size is taken to be the same
 * recording from an earlier export and skipped, so exporting again only adds what's new. Names match ignoring
 * case (FAT and exFAT SD cards do) and a " (1)" the folder added to avoid a clash.
 */
class Exporter(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    /** Null if the folder couldn't be read. */
    suspend fun export(recordings: List<Recording>, target: ExportTarget): ExportSummary? = withContext(ioDispatcher) {
        // Only what was there before: two recordings with the same title and size in one export are two recordings.
        val existing = target.existingFiles()?.entries?.groupBy({ matchKey(it.key) }, { it.value })
            ?: return@withContext null
        var exported = 0
        var alreadyThere = 0
        var failed = 0
        for (recording in recordings) {
            val name = exportName(recording)
            val size = recording.file.length()
            when {
                size > 0 && existing[matchKey(name)].orEmpty().contains(size) -> alreadyThere++
                exportOne(recording, name, target) -> exported++
                else -> failed++
            }
        }
        ExportSummary(exported = exported, alreadyThere = alreadyThere, failed = failed)
    }

    private fun exportOne(recording: Recording, name: String, target: ExportTarget): Boolean {
        if (!recording.file.isFile) return false.also { Log.w(TAG, "Missing ${recording.file.name}") }
        val file = try {
            target.create(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not create $name", e)
            null
        } ?: return false
        return try {
            file.write(recording.file)
            true
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not export ${recording.file.name}", e)
            file.delete()
            false
        }
    }

    internal companion object {
        private const val TAG = "Exporter"

        /** Leaves room for a " (12)" the folder may add, and for the extension, under the usual 255-byte limit. */
        private const val MAX_NAME_BYTES = 200

        // Not allowed on FAT/exFAT SD cards or in some providers. Control characters are never safe.
        private val UNSAFE = Regex("""[\\/:*?"<>|\x00-\x1F\x7F]""")

        private val CLASH_SUFFIX = Regex(""" \(\d+\)$""")

        /** "Song idea (1).M4A" and "song idea.m4a" are the same name for "already there". */
        fun matchKey(name: String): String {
            val base = name.substringBeforeLast('.').replace(CLASH_SUFFIX, "")
            return "$base.${name.substringAfterLast('.', "")}".lowercase()
        }

        /** "Grocery list.m4a"; the date-named file name when untitled or the title has nothing usable. */
        fun exportName(recording: Recording): String {
            val base = recording.title?.let(::safeBaseName)?.takeIf { it.isNotEmpty() }
                ?: recording.file.nameWithoutExtension
            return "$base.${recording.file.extension}"
        }

        private fun safeBaseName(title: String): String {
            val cleaned = title.replace(UNSAFE, "_").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
            if (cleaned.toByteArray().size <= MAX_NAME_BYTES) return cleaned
            // Cut on a code point boundary, so an emoji or accented letter isn't split in half.
            val out = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < cleaned.length) {
                val codePoint = cleaned.codePointAt(i)
                val chars = String(Character.toChars(codePoint))
                bytes += chars.toByteArray().size
                if (bytes > MAX_NAME_BYTES) break
                out.append(chars)
                i += chars.length
            }
            return out.toString().trimEnd('.', ' ')
        }
    }
}
