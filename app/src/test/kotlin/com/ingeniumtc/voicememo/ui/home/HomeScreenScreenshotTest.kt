package com.ingeniumtc.voicememo.ui.home

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.ingeniumtc.voicememo.R
import com.ingeniumtc.voicememo.recording.RecordingState
import com.ingeniumtc.voicememo.ui.theme.VoiceMemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class HomeScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyLight() = capture("home_empty_light", darkTheme = false, IDLE)

    @Test
    fun emptyDark() = capture("home_empty_dark", darkTheme = true, IDLE)

    @Test
    fun recordingLight() = capture("home_recording_light", darkTheme = false, RECORDING, amplitude = 0.7f)

    @Test
    fun recordingDark() = capture("home_recording_dark", darkTheme = true, RECORDING, amplitude = 0.7f)

    @Test
    fun pausedLight() = capture("home_paused_light", darkTheme = false, PAUSED)

    @Test
    fun pausedDark() = capture("home_paused_dark", darkTheme = true, PAUSED)

    @Test
    @Config(qualifiers = "+land")
    fun recordingLandscapeDark() =
        capture("home_recording_landscape_dark", darkTheme = true, RECORDING, amplitude = 0.7f)

    @Test
    @Config(qualifiers = "+land")
    fun pausedLandscapeLight() = capture("home_paused_landscape_light", darkTheme = false, PAUSED, snackbar = true)

    @Test
    fun recordingFontScale2xLight() =
        capture("home_recording_font2x_light", darkTheme = false, RECORDING_LONG, amplitude = 0.7f, fontScale = 2f)

    @Test
    fun emptyFontScale2xDark() = capture("home_empty_font2x_dark", darkTheme = true, IDLE, fontScale = 2f)

    @Test
    fun recordingSnackbarDark() =
        capture("home_recording_snackbar_dark", darkTheme = true, RECORDING, amplitude = 0.7f, snackbar = true)

    private fun capture(
        name: String,
        darkTheme: Boolean,
        state: RecordingState,
        amplitude: Float = 0f,
        fontScale: Float = 1f,
        snackbar: Boolean = false
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                VoiceMemoTheme(darkTheme = darkTheme, dynamicColor = false) {
                    val snackbarHostState = remember { SnackbarHostState() }
                    if (snackbar) {
                        val message = stringResource(R.string.message_saved, "0:23")
                        LaunchedEffect(Unit) {
                            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Indefinite)
                        }
                    }
                    HomeScreen(
                        recordingState = state,
                        amplitude = { amplitude },
                        onRecordClick = {},
                        onStopClick = {},
                        onPauseClick = {},
                        onResumeClick = {},
                        snackbarHostState = snackbarHostState,
                        clock = { 83_000 }
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private companion object {
        val IDLE = RecordingState.Idle
        val RECORDING = RecordingState.Active(isPaused = false, accumulatedMs = 0, resumedAt = 0)
        val PAUSED = RecordingState.Active(isPaused = true, accumulatedMs = 754_000, resumedAt = 0)

        // 1:02:03 at the fixed test clock (83s): the widest timer the screen has to fit.
        val RECORDING_LONG = RecordingState.Active(isPaused = false, accumulatedMs = 3_640_000, resumedAt = 0)
    }
}
