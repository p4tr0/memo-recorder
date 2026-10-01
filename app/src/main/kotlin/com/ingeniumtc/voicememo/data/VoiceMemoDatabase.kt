package com.ingeniumtc.voicememo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecordingEntity::class], version = 1, exportSchema = true)
abstract class VoiceMemoDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao

    companion object {
        fun create(context: Context): VoiceMemoDatabase =
            Room.databaseBuilder(context.applicationContext, VoiceMemoDatabase::class.java, "voicememo.db").build()
    }
}
