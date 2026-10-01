package com.ingeniumtc.voicememo.data

import android.util.Log
import com.ingeniumtc.voicememo.recording.RecordingStorage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Gives a recording made while the library is filtered by a tag that tag, so it doesn't vanish from the list it
 * was recorded into.
 *
 * The tag is captured when recording starts, not when it ends: switching chips mid-recording doesn't change it,
 * and it survives process death. It is applied to every finished file of the recording (the raw `.aac` kept
 * next to an incomplete `.m4a` too) once the recording is saved or recovered at the next launch.
 */
class AutoTagger(
    private val storage: RecordingStorage,
    private val repository: RecordingRepository,
    private val selection: TagSelection,
    private val pending: PendingTag,
    private val scope: CoroutineScope,
    // One at a time, in order: a stop right after a start must see the start's pending tag.
    @Suppress("OPT_IN_USAGE")
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) {
    fun sessionStarted(partial: File) = run {
        val tagId = selection.load()
        if (tagId == null) pending.clearPending() else pending.savePending(storage.baseName(partial), tagId)
    }

    /** After a save or recovery. Tags the pending recording if it is finished, and forgets it once handled. */
    fun filesChanged() = run {
        val (baseName, tagId) = pending.loadPending() ?: return@run
        val (finished, unfinished) = storage.filesOf(baseName)
        when {
            finished.isNotEmpty() -> {
                repository.sync()
                finished.forEach { repository.tagFile(it.name, tagId) }
                pending.clearPending()
            }

            // Discarded as too short. While unfinished, wait: it's still recording or awaiting recovery.
            !unfinished -> pending.clearPending()
        }
    }

    private fun run(block: suspend () -> Unit) {
        scope.launch(dispatcher) {
            // One failure (disk full, say) must not stop auto-tagging for the rest of the session.
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Auto-tagging failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "AutoTagger"
    }
}

/** The tag a running recording will get, keyed by its base name. Persisted so it survives process death. */
interface PendingTag {
    suspend fun loadPending(): Pair<String, Long>?

    suspend fun savePending(baseName: String, tagId: Long)

    suspend fun clearPending()
}
