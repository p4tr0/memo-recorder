package com.ingeniumtc.voicememo.data

import android.media.MediaMetadataRetriever
import java.io.File

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
