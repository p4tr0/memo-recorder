package io.github.p4tr0.voicememo.data

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
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            // On the migrated database itself: foreign keys are declared, so a link to a missing recording fails.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO tags (name, `key`) VALUES ('Work', 'work')")
            val rejected = runCatching {
                db.execSQL("INSERT INTO recording_tags (fileName, tagId) VALUES ('missing.m4a', 1)")
            }
            assertTrue(rejected.isFailure)
            // And the key is unique.
            assertTrue(runCatching { db.execSQL("INSERT INTO tags (name, `key`) VALUES ('WORK', 'work')") }.isFailure)
            db.execSQL("DELETE FROM tags")
        }

        // Open through Room itself, so the auto-migration it generated is the one exercised.
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VoiceMemoDatabase::class.java, DB)
            .addMigrations(MIGRATION_2_3)
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

    @Test
    fun `2 to 3 keeps titles, gives tags spread colors, and keeps a recording's oldest tag`() = runTest {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO recordings (fileName, title, createdAt, durationMs, sizeBytes) VALUES " +
                    "('a.m4a', 'Standup', 1000, 4200, 512), ('b.m4a', NULL, 2000, 1000, 128)"
            )
            db.execSQL("INSERT INTO tags (id, name, `key`) VALUES (1, 'Work', 'work'), (2, 'Ideas', 'ideas')")
            // a.m4a had two tags in v2; b.m4a had none.
            db.execSQL("INSERT INTO recording_tags (fileName, tagId) VALUES ('a.m4a', 2), ('a.m4a', 1)")
        }
        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT fileName, tagId FROM recording_tags").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("a.m4a", c.getString(0))
                assertEquals(1L, c.getLong(1))
            }
            db.query("SELECT hue FROM tags ORDER BY id").use { c ->
                val hues = buildList { while (c.moveToNext()) add(c.getInt(0)) }
                assertEquals(listOf(137, 274), hues)
            }
            db.query("SELECT title FROM recordings WHERE fileName = 'a.m4a'").use { c ->
                c.moveToFirst()
                assertEquals("Standup", c.getString(0))
            }
            // The new primary key allows one tag per recording.
            db.execSQL("PRAGMA foreign_keys = ON")
            assertTrue(
                runCatching { db.execSQL("INSERT INTO recording_tags (fileName, tagId) VALUES ('a.m4a', 2)") }.isFailure
            )
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
