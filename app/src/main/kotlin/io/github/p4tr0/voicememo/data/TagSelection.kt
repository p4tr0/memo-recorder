package io.github.p4tr0.voicememo.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tag the library is filtered by, remembered across launches. Null means "All". */
interface TagSelection {
    suspend fun load(): Long?

    suspend fun save(tagId: Long?)
}

/** SharedPreferences, only touched off the main thread: the first read loads the file from disk. */
class SharedPreferencesTagSelection(context: Context, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
    TagSelection,
    PendingTag {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override suspend fun load(): Long? = withContext(ioDispatcher) {
        prefs.getLong(KEY, ALL).takeIf { it != ALL }
    }

    override suspend fun save(tagId: Long?) = withContext(ioDispatcher) {
        prefs.edit { putLong(KEY, tagId ?: ALL) }
    }

    override suspend fun loadPending(): Pair<String, Long>? = withContext(ioDispatcher) {
        val baseName = prefs.getString(PENDING_NAME, null) ?: return@withContext null
        baseName to prefs.getLong(PENDING_TAG, ALL)
    }

    // commit, not apply: the process may die right after, and surviving that is the point.
    override suspend fun savePending(baseName: String, tagId: Long) = withContext(ioDispatcher) {
        prefs.edit(commit = true) {
            putString(PENDING_NAME, baseName)
            putLong(PENDING_TAG, tagId)
        }
    }

    override suspend fun clearPending() = withContext(ioDispatcher) {
        prefs.edit(commit = true) {
            remove(PENDING_NAME)
            remove(PENDING_TAG)
        }
    }

    private companion object {
        const val FILE = "library"
        const val KEY = "selected_tag_id"
        const val ALL = -1L
        const val PENDING_NAME = "pending_tag_recording"
        const val PENDING_TAG = "pending_tag_id"
    }
}
