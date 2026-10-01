package io.github.p4tr0.voicememo.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 3: tags get a color, and a recording carries at most one tag.
 *
 * Existing tags get hues spread by the golden angle (137 degrees), so neighbours differ the way new random
 * ones do. A recording that had several tags keeps its oldest one. The recordings table, and so every title, is
 * untouched.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tags` ADD COLUMN `hue` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `tags` SET `hue` = (`id` * 137) % 360")

        db.execSQL(
            "CREATE TABLE `recording_tags_new` (`fileName` TEXT NOT NULL, `tagId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`fileName`), " +
                "FOREIGN KEY(`fileName`) REFERENCES `recordings`(`fileName`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "INSERT INTO `recording_tags_new` (`fileName`, `tagId`) " +
                "SELECT `fileName`, MIN(`tagId`) FROM `recording_tags` GROUP BY `fileName`"
        )
        db.execSQL("DROP TABLE `recording_tags`")
        db.execSQL("ALTER TABLE `recording_tags_new` RENAME TO `recording_tags`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_tags_tagId` ON `recording_tags` (`tagId`)")
    }
}
