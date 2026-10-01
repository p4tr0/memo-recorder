package com.ingeniumtc.voicememo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC, fileName DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT fileName FROM recordings")
    suspend fun allFileNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<RecordingEntity>)

    @Query("DELETE FROM recordings WHERE fileName IN (:fileNames)")
    suspend fun deleteAll(fileNames: List<String>)

    @Query("UPDATE recordings SET title = :title WHERE fileName = :fileName")
    suspend fun setTitle(fileName: String, title: String?)

    // Tags are listed in the order they were created, so a new one appears next to "Add tag".
    @Query("SELECT * FROM tags ORDER BY id")
    fun observeTags(): Flow<List<TagEntity>>

    /** Returns the new id, or -1 if a tag with that name (ignoring case) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    /** Case-insensitive, through the column's NOCASE collation. */
    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun tagNamed(name: String): TagEntity?

    @Query("SELECT * FROM recording_tags")
    fun observeRecordingTags(): Flow<List<RecordingTagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRecordingTag(link: RecordingTagEntity)

    @Query("DELETE FROM recording_tags WHERE fileName = :fileName AND tagId = :tagId")
    suspend fun removeRecordingTag(fileName: String, tagId: Long)

    @Transaction
    suspend fun reconcile(add: List<RecordingEntity>, remove: List<String>) {
        if (remove.isNotEmpty()) deleteAll(remove)
        if (add.isNotEmpty()) insertAll(add)
    }
}
