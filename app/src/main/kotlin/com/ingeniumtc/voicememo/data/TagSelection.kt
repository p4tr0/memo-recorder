package com.ingeniumtc.voicememo.data

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
    TagSelection {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override suspend fun load(): Long? = withContext(ioDispatcher) {
        prefs.getLong(KEY, ALL).takeIf { it != ALL }
    }

    override suspend fun save(tagId: Long?) = withContext(ioDispatcher) {
        prefs.edit { putLong(KEY, tagId ?: ALL) }
    }

    private companion object {
        const val FILE = "library"
        const val KEY = "selected_tag_id"
        const val ALL = -1L
    }
}
