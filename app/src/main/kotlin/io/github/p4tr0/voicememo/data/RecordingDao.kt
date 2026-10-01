package io.github.p4tr0.voicememo.data

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

    /** Returns the new id, or -1 if a tag with that key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE key = :key")
    suspend fun tagWithKey(key: String): TagEntity?

    /** Throws SQLiteConstraintException if another tag already has [key]. */
    @Query("UPDATE tags SET name = :name, `key` = :key WHERE id = :id")
    suspend fun renameTag(id: Long, name: String, key: String)

    /** Its links go with it (cascade); recordings stay. */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("SELECT * FROM recording_tags")
    fun observeRecordingTags(): Flow<List<RecordingTagEntity>>

    /** Replaces the recording's tag. Safe here: nothing references recording_tags, so REPLACE can't cascade. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setRecordingTag(link: RecordingTagEntity)

    @Query("DELETE FROM recording_tags WHERE fileName = :fileName")
    suspend fun clearRecordingTag(fileName: String)

    @Query("SELECT * FROM tags ORDER BY id DESC LIMIT 1")
    suspend fun newestTag(): TagEntity?

    @Transaction
    suspend fun reconcile(add: List<RecordingEntity>, remove: List<String>) {
        if (remove.isNotEmpty()) deleteAll(remove)
        if (add.isNotEmpty()) insertAll(add)
    }
}
