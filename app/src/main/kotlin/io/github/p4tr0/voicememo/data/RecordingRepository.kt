package io.github.p4tr0.voicememo.data

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import io.github.p4tr0.voicememo.recording.RecordingStorage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A finished recording as the UI sees it. */
data class Recording(
    val file: File,
    val title: String?,
    val createdAt: Instant,
    val durationMs: Long,
    val sizeBytes: Long,
    /** The recording's one tag, or null. */
    val tagId: Long? = null
) {
    val id: String get() = file.name

    /**
     * The raw stream kept when remuxing failed or dropped audio. It may sit next to an `.m4a` of the same
     * recording, and is then the complete copy, so the UI must make the two distinguishable.
     */
    val isRawAac: Boolean get() = file.extension == "aac"
}

/** [hue] is 0 until 359; the UI picks lightness and saturation per theme. */
data class Tag(val id: Long, val name: String, val hue: Int = 0)

/**
 * The library of finished recordings. Files on disk are the source of truth and Room caches their metadata,
 * so a recording recovered at launch, or a file whose row was lost, still shows up after the next [sync].
 */
class RecordingRepository(
    private val storage: RecordingStorage,
    private val dao: RecordingDao,
    /** Duration in ms, read from the file. Can be slow (it opens the file), so only called for new files. */
    private val readDurationMs: (File) -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val random: Random = Random.Default
) {
    // Serializes sync and delete so a delete can't be undone by a sync that listed the file just before.
    private val mutex = Mutex()
    private val tagMutex = Mutex()

    val recordings: Flow<List<Recording>> =
        combine(dao.observeAll(), dao.observeRecordingTags()) { rows, links ->
            val tagByFile = links.associate { it.fileName to it.tagId }
            rows.map { it.toRecording(tagByFile[it.fileName]) }
        }

    val tags: Flow<List<Tag>> = dao.observeTags().map { rows -> rows.map { it.toTag() } }

    /** Adds rows for files without one and drops rows whose file is gone. */
    suspend fun sync() = withContext(ioDispatcher) {
        mutex.withLock {
            // Treating a failed listing as empty would drop every row, and with them every title.
            val files = storage.finishedFiles() ?: run {
                Log.w(TAG, "Could not list recordings, skipping sync")
                return@withLock
            }
            val known = dao.allFileNames().toSet()
            val present = files.mapTo(HashSet()) { it.name }
            val added = files.filter { it.name !in known }.map { file ->
                RecordingEntity(
                    fileName = file.name,
                    title = null,
                    createdAt = createdAt(file),
                    durationMs = runCatching { readDurationMs(file) }
                        .onFailure { Log.w(TAG, "Could not read duration of ${file.name}", it) }
                        .getOrDefault(0L),
                    sizeBytes = file.length()
                )
            }
            dao.reconcile(add = added, remove = (known - present).toList())
        }
    }

    /** Sets a title by file name, for files that have no [Recording] yet (just imported). Null shows the date. */
    suspend fun setTitle(fileName: String, title: String?) = withContext(ioDispatcher + NonCancellable) {
        dao.setTitle(fileName, title)
    }

    /** A blank title resets it to the date. */
    suspend fun rename(recording: Recording, title: String) = withContext(ioDispatcher + NonCancellable) {
        dao.setTitle(recording.id, title.trim().ifEmpty { null })
    }

    /**
     * User-initiated, after confirmation. Returns false if the file couldn't be deleted (the row then stays).
     * Not cancellable: leaving the screen between deleting the file and its row would leave a ghost row.
     */
    suspend fun delete(recording: Recording): Boolean = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val file = storage.fileNamed(recording.id)
            val gone = !file.exists() || file.delete()
            if (gone) dao.deleteAll(listOf(recording.id)) else Log.w(TAG, "Could not delete ${file.name}")
            gone
        }
    }

    /**
     * Creates a tag, or returns the existing one if the name is already taken ignoring case. [name] must already
     * be trimmed and validated ([TagNames.validate]).
     */
    suspend fun createTag(name: String): Tag = withContext(ioDispatcher + NonCancellable) {
        // Serialized so two quick creates don't both pick their hue against the same previous tag.
        tagMutex.withLock {
            val key = TagNames.key(name)
            dao.tagWithKey(key)?.let { return@withLock it.toTag() }
            val hue = TagHues.pick(previous = dao.newestTag()?.hue, random = random)
            val id = dao.insertTag(TagEntity(name = name, key = key, hue = hue))
            if (id != -1L) Tag(id, name, hue) else dao.tagWithKey(key)!!.toTag()
        }
    }

    /**
     * Renames [tag] to [name] (validated). Returns false if another tag already has that name ignoring case.
     * Renaming to a different case of its own name ("work" to "Work") is allowed.
     */
    suspend fun renameTag(tag: Tag, name: String): Boolean = withContext(ioDispatcher + NonCancellable) {
        try {
            dao.renameTag(tag.id, name, TagNames.key(name))
            true
        } catch (e: SQLiteConstraintException) {
            false
        }
    }

    /** Removes the tag from every recording. No recording is deleted. */
    suspend fun deleteTag(tag: Tag) = withContext(ioDispatcher + NonCancellable) { dao.deleteTag(tag.id) }

    /** Gives [recording] the tag [tag], replacing any other, or clears it when [tag] is null. */
    suspend fun setTag(recording: Recording, tag: Tag?) = setTag(recording.id, tag?.id)

    /** Tags a file by name, replacing any tag it had. Its row must exist (sync first), or this is a logged no-op. */
    suspend fun tagFile(fileName: String, tagId: Long) = setTag(fileName, tagId)

    private suspend fun setTag(fileName: String, tagId: Long?) = withContext(ioDispatcher + NonCancellable) {
        try {
            if (tagId != null) {
                dao.setRecordingTag(RecordingTagEntity(fileName, tagId))
            } else {
                dao.clearRecordingTag(fileName)
            }
        } catch (e: SQLiteConstraintException) {
            // The recording or tag was deleted a moment ago. Nothing left to tag.
            Log.w(TAG, "Could not tag $fileName", e)
        }
    }

    private fun createdAt(file: File): Long =
        storage.startedAt(file)?.atZone(zone())?.toInstant()?.toEpochMilli() ?: file.lastModified()

    private fun TagEntity.toTag() = Tag(id, name, hue)

    private fun RecordingEntity.toRecording(tagId: Long?) = Recording(
        file = storage.fileNamed(fileName),
        title = title,
        createdAt = Instant.ofEpochMilli(createdAt),
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        tagId = tagId
    )

    private companion object {
        const val TAG = "RecordingRepository"
    }
}
