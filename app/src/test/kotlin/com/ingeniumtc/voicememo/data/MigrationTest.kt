package com.ingeniumtc.voicememo.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), VoiceMemoDatabase::class.java)

    @Test
    fun `1 to 2 keeps recordings and their titles, and starts with no tags`() = runTest {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO recordings (fileName, title, createdAt, durationMs, sizeBytes) " +
                    "VALUES ('2026-10-01_09-05-00.m4a', 'Standup', 1000, 4200, 512)"
            )
        }
        helper.runMigrationsAndValidate(DB, 2, true).close()

        // Open through Room itself, so the auto-migration it generated is the one exercised.
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VoiceMemoDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = room.recordings().observeAll().first()
            assertEquals(listOf("Standup"), rows.map { it.title })
            assertTrue(room.recordings().observeTags().first().isEmpty())
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
