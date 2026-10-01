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

    @Transaction
    suspend fun reconcile(add: List<RecordingEntity>, remove: List<String>) {
        if (remove.isNotEmpty()) deleteAll(remove)
        if (add.isNotEmpty()) insertAll(add)
    }
}
