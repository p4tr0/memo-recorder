package io.github.p4tr0.voicememo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Coral,
    onPrimary = Ink,
    background = Ink,
    onBackground = Color.White,
    surface = Ink,
    onSurface = Color.White,
    surfaceVariant = InkHigh,
    surfaceContainerLowest = InkLowest,
    surfaceContainerLow = InkLow,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkHigh,
    surfaceContainerHighest = InkHighest,
    secondaryContainer = InkHigh,
    onSecondaryContainer = Color.White,
    onSurfaceVariant = Mist,
    outline = Pewter,
    outlineVariant = InkLine,
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    inversePrimary = CoralDeep,
    error = DangerSoft,
    onError = Ink
)

private val LightColors = lightColorScheme(
    primary = CoralDeep,
    onPrimary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperSunken,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperRaised,
    // Dialogs sit on this. Brighter than the background, not darker, so CoralDeep buttons keep 4.5:1.
    surfaceContainerHigh = PaperBright,
    surfaceContainerHighest = PaperSunken,
    secondaryContainer = PaperSunken,
    onSecondaryContainer = Ink,
    onSurfaceVariant = Slate,
    outline = Stone,
    outlineVariant = PaperLine,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Coral,
    error = Danger,
    onError = Color.White
)

@Composable
fun VoiceMemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors

        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun ColorScheme.isDark() = background.luminance() < 0.5f

/**
 * What the recording list sits on: a step brighter than the page, so the list reads as its own area that
 * scrolls. White on paper in light, a raised ink in dark. From the scheme's roles, so dynamic color works too.
 */
val ColorScheme.listPanel: Color
    get() = if (isDark()) surfaceContainer else surfaceContainerLowest

/** The current row's tint: a step above [listPanel] in both themes, so it still stands out on the panel. */
val ColorScheme.listRowHighlight: Color
    get() = if (isDark()) surfaceContainerHigh else surfaceContainer
