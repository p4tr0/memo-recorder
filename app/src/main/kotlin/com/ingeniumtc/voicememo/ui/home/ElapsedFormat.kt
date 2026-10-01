package com.ingeniumtc.voicememo.ui.home

import java.util.Locale

/** 0:07, 12:34, 1:02:03. Locale.ROOT keeps ASCII digits so the timer reads the same everywhere. */
internal fun formatElapsed(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
