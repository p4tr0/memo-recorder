package com.ingeniumtc.voicememo.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-created label. Names are unique ignoring case, so "Work" and "work" are the same tag. Uniqueness is on
 * [key] ([TagNames.key]) rather than SQLite's NOCASE, which only folds ASCII and would let "Łódź" and "łódź" be two.
 */
@Entity(tableName = "tags", indices = [Index(value = ["key"], unique = true)])
data class TagEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val key: String)

/**
 * Which recordings carry which tags. Both sides cascade, so deleting a recording's row (its file is gone) or a
 * tag removes the links and never the other side.
 */
@Entity(
    tableName = "recording_tags",
    primaryKeys = ["fileName", "tagId"],
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
data class RecordingTagEntity(val fileName: String, val tagId: Long)
