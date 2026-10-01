package com.ingeniumtc.voicememo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A tag's pastel for the current theme. Tags store only a hue, so the same tag reads as soft on Paper and stays
 * visible on Ink. Follows the theme in use, not the system setting, so forced themes and previews match.
 */
@Composable
fun tagColor(hue: Int): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        Color.hsl(hue.toFloat(), saturation = 0.55f, lightness = 0.72f)
    } else {
        Color.hsl(hue.toFloat(), saturation = 0.70f, lightness = 0.76f)
    }
}
