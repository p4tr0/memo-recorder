package io.github.p4tr0.voicememo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata for one finished recording. The audio file is the source of truth: a row exists only while its
 * file does, and [RecordingRepository.sync] reconciles the two.
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    /** Name within the recordings directory, e.g. `2026-10-01_12-42-33.m4a`. Never a path. */
    @PrimaryKey val fileName: String,
    /** User-chosen title, or null to show the date. */
    val title: String?,
    /** Epoch millis when recording started. */
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long
)
