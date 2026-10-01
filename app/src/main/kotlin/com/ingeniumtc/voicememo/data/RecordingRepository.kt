package com.ingeniumtc.voicememo.data

import android.util.Log
import com.ingeniumtc.voicememo.recording.RecordingStorage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
    val sizeBytes: Long
) {
    val id: String get() = file.name
}

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
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    // Serializes sync and delete so a delete can't be undone by a sync that listed the file just before.
    private val mutex = Mutex()

    val recordings: Flow<List<Recording>> = dao.observeAll().map { rows -> rows.map { it.toRecording() } }

    /** Adds rows for files without one and drops rows whose file is gone. */
    suspend fun sync() = withContext(ioDispatcher) {
        mutex.withLock {
            val files = storage.finishedFiles()
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

    /** A blank title resets it to the date. */
    suspend fun rename(recording: Recording, title: String) = withContext(ioDispatcher) {
        dao.setTitle(recording.id, title.trim().ifEmpty { null })
    }

    /** User-initiated, after confirmation. Returns false if the file couldn't be deleted (the row then stays). */
    suspend fun delete(recording: Recording): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val file = storage.fileNamed(recording.id)
            val gone = !file.exists() || file.delete()
            if (gone) dao.deleteAll(listOf(recording.id)) else Log.w(TAG, "Could not delete ${file.name}")
            gone
        }
    }

    private fun createdAt(file: File): Long =
        storage.startedAt(file)?.atZone(zone())?.toInstant()?.toEpochMilli() ?: file.lastModified()

    private fun RecordingEntity.toRecording() = Recording(
        file = storage.fileNamed(fileName),
        title = title,
        createdAt = Instant.ofEpochMilli(createdAt),
        durationMs = durationMs,
        sizeBytes = sizeBytes
    )

    private companion object {
        const val TAG = "RecordingRepository"
    }
}
