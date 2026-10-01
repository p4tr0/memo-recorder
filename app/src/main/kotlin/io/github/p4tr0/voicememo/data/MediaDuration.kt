package io.github.p4tr0.voicememo.data

import android.media.MediaMetadataRetriever
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Duration from the container. For a raw `.aac` (ADTS) the platform estimates it from the bitrate. */
internal fun readMediaDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.path)
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }
}

/**
 * When the audio says it was recorded, from its metadata date (e.g. "20250301T164012.000Z", UTC), or null.
 * Many recorders don't write one.
 */
internal fun readRecordedAt(file: File, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.path)
        val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
        val utc = LocalDateTime.parse(raw.take(METADATA_DATE_LENGTH), METADATA_DATE)
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime()
    } catch (e: Exception) {
        return null
    } finally {
        retriever.release()
    }
}

private val METADATA_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private const val METADATA_DATE_LENGTH = 15
