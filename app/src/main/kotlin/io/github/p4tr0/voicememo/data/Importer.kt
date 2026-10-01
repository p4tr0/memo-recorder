package io.github.p4tr0.voicememo.data

import android.util.Log
import io.github.p4tr0.voicememo.recording.RecordingStorage
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One file handed to the app, e.g. shared from another recorder. [open] may be called once. */
class AudioImport(
    val displayName: String?,
    val mimeType: String?,
    /** Epoch millis, if the sender knows it. */
    val lastModified: Long?,
    val open: () -> InputStream?
)

data class ImportSummary(val imported: Int, val duplicates: Int, val failed: Int)

/**
 * Copies audio from other apps into the library, unchanged. Each file keeps its format, gets the date it was
 * recorded (from its name, then its metadata, then its timestamp), a title from its name unless that is only a
 * date, and the tag selected in the library. A file whose bytes are already in the library is skipped. The
 * sender's copy is never touched.
 */
class Importer(
    private val storage: RecordingStorage,
    private val repository: RecordingRepository,
    private val selection: TagSelection,
    /** When the audio says it was recorded, from its own metadata. */
    private val readRecordedAt: (File) -> LocalDateTime?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> LocalDateTime = LocalDateTime::now
) {
    private val mutex = Mutex()

    // Not cancellable: the user handed the files over and left, so a half-finished import would be a surprise.
    suspend fun import(items: List<AudioImport>): ImportSummary = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            storage.deleteImportLeftovers()
            val known = ContentIndex(storage.finishedFiles().orEmpty())
            val added = mutableListOf<Pair<File, String?>>()
            var duplicates = 0
            var failed = 0
            for (item in items) {
                when (val result = importOne(item, known)) {
                    is One.Added -> added += result.file to result.title
                    One.Duplicate -> duplicates++
                    One.Failed -> failed++
                }
            }
            if (added.isNotEmpty()) {
                repository.sync()
                val tagId = selection.load()
                for ((file, title) in added) {
                    if (title != null) repository.setTitle(file.name, title)
                    if (tagId != null) repository.tagFile(file.name, tagId)
                }
            }
            ImportSummary(imported = added.size, duplicates = duplicates, failed = failed)
        }
    }

    private fun importOne(item: AudioImport, known: ContentIndex): One {
        val extension = extensionOf(item) ?: return One.Failed.also { Log.w(TAG, "Not audio: ${item.displayName}") }
        val temp = storage.newImportTemp()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val copied = item.open()?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                    }
                    total
                }
            }
            if (copied == null || copied == 0L) {
                temp.delete()
                return One.Failed
            }
            val hash = digest.digest().toHex()
            if (known.contains(copied, hash)) {
                temp.delete()
                return One.Duplicate
            }
            val name = item.displayName?.substringBeforeLast('.')
            val recordedAt = name?.let(::dateInName)
                ?: runCatching { readRecordedAt(temp) }.getOrNull()
                ?: item.lastModified?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone()) }
                ?: now()
            val file = storage.commitImport(temp, recordedAt, extension)
            known.add(copied, hash)
            return One.Added(file, name?.let(::titleFrom))
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not import ${item.displayName}", e)
            temp.delete()
            return One.Failed
        }
    }

    private sealed interface One {
        data class Added(val file: File, val title: String?) : One

        data object Duplicate : One

        data object Failed : One
    }

    /** Hashes library files lazily, and only those the same size as an incoming file. */
    private class ContentIndex(private val files: List<File>) {
        private val hashes = mutableMapOf<Long, MutableSet<String>>()
        private val hashedSizes = mutableSetOf<Long>()

        fun contains(size: Long, hash: String): Boolean {
            if (hashedSizes.add(size)) {
                files.filter { it.length() == size }.forEach { hashes.getOrPut(size) { mutableSetOf() } += hashOf(it) }
            }
            return hash in hashes[size].orEmpty()
        }

        fun add(size: Long, hash: String) {
            hashes.getOrPut(size) { mutableSetOf() } += hash
        }

        private fun hashOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }
    }

    internal companion object {
        private const val TAG = "Importer"
        private const val BUFFER_SIZE = 64 * 1024

        private val MIME_EXTENSIONS = mapOf(
            "audio/mp4" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/m4a" to "m4a",
            "audio/aac" to "aac",
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/ogg" to "ogg",
            "application/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/flac" to "flac",
            "audio/amr" to "amr",
            "audio/3gpp" to "3gp"
        )

        /** From the file name when it is a format the app plays, else from the MIME type. */
        fun extensionOf(item: AudioImport): String? {
            item.displayName?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it in RecordingStorage.IMPORTABLE_EXTENSIONS }
                ?.let { return it }
            return item.mimeType?.lowercase()?.let(MIME_EXTENSIONS::get)
        }

        // 2025-03-01 16:40:12, 2025_03_01_16_40_12, 20250301_164012, with or without seconds.
        private val DATE_IN_NAME =
            Regex("""(\d{4})[-_.]?(\d{2})[-_.]?(\d{2})[ _T.-]?(\d{2})[-_.:h]?(\d{2})(?:[-_.:m]?(\d{2}))?""")

        fun dateInName(name: String): LocalDateTime? {
            val match = DATE_IN_NAME.find(name) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() }
            return runCatching {
                LocalDateTime.of(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!, parts[4]!!, parts[5] ?: 0)
            }.getOrNull()
        }

        /**
         * The name as a title, or null when it carries nothing beyond a date ("2025_03_01_16_40_12"), so the
         * list shows the date nicely formatted instead.
         */
        fun titleFrom(name: String): String? {
            val readable = name.replace('_', ' ').trim()
            val withoutDate = DATE_IN_NAME.replace(name, "")
            return readable.takeIf { withoutDate.count(Char::isLetter) >= 2 }?.take(TITLE_MAX_LENGTH)
        }

        private const val TITLE_MAX_LENGTH = 100

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
