package com.ingeniumtc.voicememo.ui.home

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ingeniumtc.voicememo.R
import com.ingeniumtc.voicememo.data.Recording
import com.ingeniumtc.voicememo.playback.PlaybackState
import com.ingeniumtc.voicememo.recording.RecordingState
import com.ingeniumtc.voicememo.ui.theme.VoiceMemoTheme
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

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

    @Test
    fun libraryLight() = capture("home_library_light", darkTheme = false, IDLE, recordings = LIBRARY)

    @Test
    fun libraryDark() = capture("home_library_dark", darkTheme = true, IDLE, recordings = LIBRARY)

    @Test
    fun libraryPlayingLight() =
        capture("home_library_playing_light", darkTheme = false, IDLE, recordings = LIBRARY, playback = PLAYING)

    @Test
    fun libraryPlayingDark() =
        capture("home_library_playing_dark", darkTheme = true, IDLE, recordings = LIBRARY, playback = PLAYING)

    @Test
    fun libraryPausedLight() = capture(
        "home_library_paused_light",
        darkTheme = false,
        IDLE,
        recordings = LIBRARY,
        playback = PLAYING.copy(isPlaying = false)
    )

    @Test
    fun libraryPausedDark() = capture(
        "home_library_paused_dark",
        darkTheme = true,
        IDLE,
        recordings = LIBRARY,
        playback = PLAYING.copy(isPlaying = false)
    )

    @Test
    fun libraryFontScale2xLight() = capture(
        "home_library_font2x_light",
        darkTheme = false,
        IDLE,
        recordings = LIBRARY,
        playback = PLAYING,
        fontScale = 2f
    )

    @Test
    fun libraryFontScale2xDark() = capture(
        "home_library_font2x_dark",
        darkTheme = true,
        IDLE,
        recordings = LIBRARY,
        playback = PLAYING,
        fontScale = 2f
    )

    @Test
    @Config(qualifiers = "+land")
    fun libraryLandscapeLight() = capture(
        "home_library_landscape_light",
        darkTheme = false,
        IDLE,
        recordings = LIBRARY,
        playback = PLAYING
    )

    @Test
    @Config(qualifiers = "+land")
    fun libraryLandscapeDark() = capture(
        "home_library_landscape_dark",
        darkTheme = true,
        IDLE,
        recordings = LIBRARY,
        playback = PLAYING
    )

    @Test
    fun libraryMenuLight() = capture("home_library_menu_light", darkTheme = false, IDLE, LIBRARY, open = MENU)

    @Test
    fun libraryMenuDark() = capture("home_library_menu_dark", darkTheme = true, IDLE, LIBRARY, open = MENU)

    @Test
    fun renameDialogLight() =
        capture("home_rename_dialog_light", darkTheme = false, IDLE, LIBRARY, open = MENU + "Rename")

    @Test
    fun renameDialogDark() = capture("home_rename_dialog_dark", darkTheme = true, IDLE, LIBRARY, open = MENU + "Rename")

    @Test
    fun deleteDialogLight() =
        capture("home_delete_dialog_light", darkTheme = false, IDLE, LIBRARY, open = MENU + "Delete")

    @Test
    fun deleteDialogDark() = capture("home_delete_dialog_dark", darkTheme = true, IDLE, LIBRARY, open = MENU + "Delete")

    /**
     * [open] is a list of clicks performed before capturing: the first is a content description, the rest are
     * texts. With clicks, the whole screen is captured so popups (menus, dialogs) are included.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(
        name: String,
        darkTheme: Boolean,
        state: RecordingState,
        recordings: List<Recording>? = emptyList(),
        playback: PlaybackState = PlaybackState(),
        amplitude: Float = 0f,
        fontScale: Float = 1f,
        snackbar: Boolean = false,
        open: List<String> = emptyList()
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
                        recordings = recordings,
                        playback = playback,
                        playbackPositionMs = { PLAYING_POSITION_MS },
                        snackbarHostState = snackbarHostState,
                        clock = { 83_000 },
                        zone = ZoneOffset.UTC
                    )
                }
            }
        }
        val path = "src/test/screenshots/$name.png"
        if (open.isEmpty()) {
            composeRule.onRoot().captureRoboImage(path)
            return
        }
        composeRule.onNodeWithContentDescription(open.first()).performClick()
        composeRule.waitForIdle()
        // A focused text field's cursor blinks forever, so from here frames are stepped by hand instead of waiting
        // for idle, draining the looper each frame so new popup and dialog windows get attached and drawn.
        composeRule.mainClock.autoAdvance = false
        // Touches injected into popup windows miss under Robolectric, so menu items are clicked semantically.
        open.drop(1).forEach { composeRule.onNodeWithText(it).performSemanticsAction(SemanticsActions.OnClick) }
        repeat(SETTLE_FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper()
        }
        captureScreenRoboImage(path)
    }

    private companion object {
        const val SETTLE_FRAMES = 60
        val IDLE = RecordingState.Idle
        val RECORDING = RecordingState.Active(isPaused = false, accumulatedMs = 0, resumedAt = 0)
        val PAUSED = RecordingState.Active(isPaused = true, accumulatedMs = 754_000, resumedAt = 0)

        // 1:02:03 at the fixed test clock (83s): the widest timer the screen has to fit.
        val RECORDING_LONG = RecordingState.Active(isPaused = false, accumulatedMs = 3_640_000, resumedAt = 0)

        /** [createdAt] is ISO-8601 UTC, e.g. 2026-10-01T09:05:00Z; the file is named after it like real ones. */
        private fun recording(createdAt: String, title: String?, durationMs: Long) = Recording(
            file = File(createdAt.removeSuffix("Z").replace('T', '_').replace(':', '-') + ".m4a"),
            title = title,
            createdAt = Instant.parse(createdAt),
            durationMs = durationMs,
            sizeBytes = durationMs * 8
        )

        val LIBRARY = listOf(
            recording("2026-10-01T09:05:00Z", null, 195_000),
            recording("2026-09-30T18:42:00Z", "Grocery list", 23_000),
            recording(
                "2026-09-29T14:10:00Z",
                "Interview with the building manager about the heating schedule for winter",
                1_874_000
            ),
            recording("2026-09-27T07:30:00Z", null, 3_725_000),
            recording("2026-09-25T21:15:00Z", null, 61_000),
            recording("2026-09-21T12:00:00Z", "Song idea", 142_000),
            recording("2026-09-18T08:45:00Z", null, 8_000)
        )

        // The first recording, 0:42 into 3:15.
        val PLAYING = PlaybackState(
            currentId = LIBRARY.first().id,
            isPlaying = true,
            durationMs = 195_000
        )
        const val PLAYING_POSITION_MS = 42_000L

        // The overflow button of the renamed "Grocery list" row.
        val MENU = listOf("More options for Grocery list")
    }
}
