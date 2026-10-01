package com.ingeniumtc.voicememo.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
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
    fun emptyLight() = capture("home_empty_light", darkTheme = false, RecordingState.Idle)

    @Test
    fun emptyDark() = capture("home_empty_dark", darkTheme = true, RecordingState.Idle)

    @Test
    fun recordingDark() = capture(
        "home_recording_dark",
        darkTheme = true,
        RecordingState.Active(isPaused = false, accumulatedMs = 0, resumedAt = 0),
        amplitude = 0.7f
    )

    @Test
    fun pausedLight() = capture(
        "home_paused_light",
        darkTheme = false,
        RecordingState.Active(isPaused = true, accumulatedMs = 754_000, resumedAt = 0)
    )

    private fun capture(name: String, darkTheme: Boolean, state: RecordingState, amplitude: Float = 0f) {
        composeRule.setContent {
            VoiceMemoTheme(darkTheme = darkTheme, dynamicColor = false) {
                HomeScreen(
                    recordingState = state,
                    amplitude = { amplitude },
                    onRecordClick = {},
                    onStopClick = {},
                    onPauseClick = {},
                    onResumeClick = {},
                    clock = { 83_000 }
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
