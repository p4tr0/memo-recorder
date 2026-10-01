package io.github.p4tr0.voicememo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-created label. Names are unique ignoring case, so "Work" and "work" are the same tag. Uniqueness is on
 * [key] ([TagNames.key]) rather than SQLite's NOCASE, which only folds ASCII and would let "Łódź" and "łódź" be two.
 */
@Entity(tableName = "tags", indices = [Index(value = ["key"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val key: String,
    /** 0 until 359; the UI turns it into a pastel for the current theme ([TagHues]). */
    @ColumnInfo(defaultValue = "0") val hue: Int
)

/**
 * The one tag a recording carries, if any: [fileName] is the primary key. Both sides cascade, so deleting a
 * recording's row (its file is gone) or a tag removes the link and never the other side.
 */
@Entity(
    tableName = "recording_tags",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["fileName"],
            childColumns = ["fileName"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class RecordingTagEntity(@PrimaryKey val fileName: String, val tagId: Long)
