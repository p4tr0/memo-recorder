package com.ingeniumtc.voicememo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Everything here except titles can be rebuilt from the files, but titles can't, so never use a destructive
 * fallback. For version 2+, add `autoMigrations = [AutoMigration(from = 1, to = 2)]` (schemas are exported to
 * app/schemas/) or a hand-written Migration, and test it.
 */
@Database(entities = [RecordingEntity::class], version = 1, exportSchema = true)
abstract class VoiceMemoDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao

    companion object {
        fun create(context: Context): VoiceMemoDatabase =
            Room.databaseBuilder(context.applicationContext, VoiceMemoDatabase::class.java, "voicememo.db").build()
    }
}
