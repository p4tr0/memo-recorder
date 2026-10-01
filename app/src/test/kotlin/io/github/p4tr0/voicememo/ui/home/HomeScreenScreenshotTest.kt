package io.github.p4tr0.voicememo.ui.home

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.p4tr0.voicememo.R
import io.github.p4tr0.voicememo.data.Recording
import io.github.p4tr0.voicememo.data.Tag
import io.github.p4tr0.voicememo.data.TagNames
import io.github.p4tr0.voicememo.playback.PlaybackState
import io.github.p4tr0.voicememo.recording.RecordingState
import io.github.p4tr0.voicememo.ui.theme.VoiceMemoTheme
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
    fun recordingLandscapeLight() =
        capture("home_recording_landscape_light", darkTheme = false, RECORDING, amplitude = 0.7f)

    @Test
    @Config(qualifiers = "+land")
    fun pausedLandscapeLight() = capture("home_paused_landscape_light", darkTheme = false, PAUSED, snackbar = true)

    @Test
    @Config(qualifiers = "+land")
    fun pausedLandscapeDark() = capture("home_paused_landscape_dark", darkTheme = true, PAUSED)

    @Test
    @Config(qualifiers = "+land")
    fun emptyLandscapeLight() = capture("home_empty_landscape_light", darkTheme = false, IDLE)

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

    @Test
    fun libraryUnprocessedLight() =
        capture("home_library_unprocessed_light", darkTheme = false, IDLE, recordings = WITH_UNPROCESSED)

    @Test
    fun libraryUnprocessedDark() =
        capture("home_library_unprocessed_dark", darkTheme = true, IDLE, recordings = WITH_UNPROCESSED)

    @Test
    fun libraryUnprocessedFontScale2xLight() = capture(
        "home_library_unprocessed_font2x_light",
        darkTheme = false,
        IDLE,
        recordings = WITH_UNPROCESSED,
        fontScale = 2f
    )

    @Test
    fun deleteUnprocessedDialogLight() = capture(
        "home_delete_unprocessed_dialog_light",
        darkTheme = false,
        IDLE,
        WITH_UNPROCESSED,
        open = UNPROCESSED_MENU + "Delete"
    )

    @Test
    fun deleteUnprocessedDialogDark() = capture(
        "home_delete_unprocessed_dialog_dark",
        darkTheme = true,
        IDLE,
        WITH_UNPROCESSED,
        open = UNPROCESSED_MENU + "Delete"
    )

    @Test
    fun tagsAllLight() = captureTags("home_tags_all_light", darkTheme = false)

    @Test
    fun tagsAllDark() = captureTags("home_tags_all_dark", darkTheme = true)

    @Test
    fun tagsFilteredLight() = captureTags("home_tags_filtered_light", darkTheme = false, selectedTag = WORK)

    @Test
    fun tagsFilteredDark() = captureTags("home_tags_filtered_dark", darkTheme = true, selectedTag = WORK)

    @Test
    fun tagsNoneTaggedLight() = captureTags("home_tags_none_tagged_light", darkTheme = false, selectedTag = UNUSED)

    @Test
    fun tagsNoneTaggedDark() = captureTags("home_tags_none_tagged_dark", darkTheme = true, selectedTag = UNUSED)

    @Test
    fun tagsManyLight() = captureTags("home_tags_many_light", darkTheme = false, tags = MANY_TAGS, selectedTag = WORK)

    @Test
    fun tagsManyDark() = captureTags("home_tags_many_dark", darkTheme = true, tags = MANY_TAGS, selectedTag = WORK)

    @Test
    fun addTagDialogLight() = captureTags("home_add_tag_dialog_light", darkTheme = false, open = ADD_TAG)

    @Test
    fun addTagDialogDark() = captureTags("home_add_tag_dialog_dark", darkTheme = true, open = ADD_TAG)

    @Test
    fun addTagDialogErrorLight() = captureTags(
        "home_add_tag_dialog_error_light",
        darkTheme = false,
        open = ADD_TAG,
        type = TOO_LONG_NAME,
        thenClick = "Add"
    )

    @Test
    fun addTagDialogErrorDark() = captureTags(
        "home_add_tag_dialog_error_dark",
        darkTheme = true,
        open = ADD_TAG,
        type = TOO_LONG_NAME,
        thenClick = "Add"
    )

    @Test
    fun recordingTagsDialogLight() =
        captureTags("home_recording_tags_dialog_light", darkTheme = false, open = MENU + "Tag")

    @Test
    fun recordingTagsDialogDark() =
        captureTags("home_recording_tags_dialog_dark", darkTheme = true, open = MENU + "Tag")

    @Test
    fun recordingTagsDialogManyLight() = captureTags(
        "home_recording_tags_dialog_many_light",
        darkTheme = false,
        tags = MANY_TAGS,
        open = MENU + "Tag"
    )

    @Test
    fun recordingTagsDialogNoTagsLight() = captureTags(
        "home_recording_tags_dialog_no_tags_light",
        darkTheme = false,
        tags = emptyList(),
        open =
            MENU + "Tag"
    )

    @Test
    fun recordingTagsDialogNoTagsDark() = captureTags(
        "home_recording_tags_dialog_no_tags_dark",
        darkTheme = true,
        tags = emptyList(),
        open =
            MENU + "Tag"
    )

    @Test
    @Config(qualifiers = "+land")
    fun tagsLandscapeLight() =
        captureTags("home_tags_landscape_light", darkTheme = false, tags = MANY_TAGS, playback = PLAYING)

    @Test
    @Config(qualifiers = "+land")
    fun tagsLandscapeDark() =
        captureTags("home_tags_landscape_dark", darkTheme = true, tags = MANY_TAGS, playback = PLAYING)

    @Test
    fun tagsFontScale2xLight() =
        captureTags("home_tags_font2x_light", darkTheme = false, selectedTag = WORK, playback = PLAYING, fontScale = 2f)

    @Test
    fun tagsFontScale2xDark() =
        captureTags("home_tags_font2x_dark", darkTheme = true, selectedTag = WORK, playback = PLAYING, fontScale = 2f)

    @Test
    fun tagMenuLight() = captureTags("home_tag_menu_light", darkTheme = false, open = WORK_CHIP, longPress = true)

    @Test
    fun tagMenuDark() = captureTags("home_tag_menu_dark", darkTheme = true, open = WORK_CHIP, longPress = true)

    @Test
    fun renameTagDialogLight() =
        captureTags("home_rename_tag_dialog_light", darkTheme = false, open = WORK_CHIP + "Rename", longPress = true)

    @Test
    fun renameTagDialogDark() =
        captureTags("home_rename_tag_dialog_dark", darkTheme = true, open = WORK_CHIP + "Rename", longPress = true)

    @Test
    fun renameTagTakenLight() = captureTags(
        "home_rename_tag_taken_light",
        darkTheme = false,
        open = WORK_CHIP + "Rename",
        longPress = true,
        type = "ideas",
        thenClick = "Save"
    )

    @Test
    fun renameTagTakenDark() = captureTags(
        "home_rename_tag_taken_dark",
        darkTheme = true,
        open = WORK_CHIP + "Rename",
        longPress = true,
        type = "ideas",
        thenClick = "Save"
    )

    @Test
    fun deleteTagDialogLight() =
        captureTags("home_delete_tag_dialog_light", darkTheme = false, open = WORK_CHIP + "Delete", longPress = true)

    @Test
    fun deleteTagDialogDark() =
        captureTags("home_delete_tag_dialog_dark", darkTheme = true, open = WORK_CHIP + "Delete", longPress = true)

    /** The library with [TAGS] on some recordings, filtered by [selectedTag] like the ViewModel does. */
    private fun captureTags(
        name: String,
        darkTheme: Boolean,
        tags: List<Tag> = TAGS,
        selectedTag: Tag? = null,
        playback: PlaybackState = PlaybackState(),
        fontScale: Float = 1f,
        open: List<String> = emptyList(),
        type: String? = null,
        thenClick: String? = null,
        longPress: Boolean = false
    ) = capture(
        name,
        darkTheme,
        IDLE,
        recordings = TAGGED_LIBRARY.filter { selectedTag == null || it.tagId == selectedTag.id },
        playback = playback,
        fontScale = fontScale,
        open = open,
        allRecordings = TAGGED_LIBRARY,
        tags = tags,
        selectedTag = selectedTag,
        type = type,
        thenClick = thenClick,
        longPress = longPress
    )

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
        open: List<String> = emptyList(),
        allRecordings: List<Recording>? = recordings,
        tags: List<Tag> = emptyList(),
        selectedTag: Tag? = null,
        /** Typed into the dialog's text field after [open]. */
        type: String? = null,
        /** Clicked by text after [type]. */
        thenClick: String? = null,
        /** The first of [open] is long-pressed by its text instead of clicked. */
        longPress: Boolean = false
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
                        allRecordings = allRecordings,
                        tags = tags,
                        selectedTag = selectedTag,
                        onAddTag = ::rejectInvalid,
                        onAddTagTo = { _, tag -> rejectInvalid(tag) },
                        onRenameTag = { tag, input -> rejectRename(tags, tag, input) },
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
        val first = composeRule.onAllNodes(contentDescriptionIgnoringNarrowSpaces(open.first()))
        if (longPress) {
            composeRule.onNodeWithText(open.first()).performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
        } else if (first.fetchSemanticsNodes().isNotEmpty()) {
            first[0].performClick()
            composeRule.waitForIdle()
        } else {
            // A chip at the end of the tag bar may be scrolled out of composition; the bar is the first scroller.
            composeRule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(open.first()))
            // It opens a dialog with a focused field right away, so the clock is stepped by hand from here.
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithText(open.first()).performSemanticsAction(SemanticsActions.OnClick)
        }
        // A focused text field's cursor blinks forever, so from here frames are stepped by hand instead of waiting
        // for idle, draining the looper each frame so new popup and dialog windows get attached and drawn.
        composeRule.mainClock.autoAdvance = false
        // Touches injected into popup windows miss under Robolectric, so menu items are clicked semantically.
        open.drop(1).forEach { composeRule.onNodeWithText(it).performSemanticsAction(SemanticsActions.OnClick) }
        // Records the dialog's root as its window is created. Chained after the rule's own callback and restored
        // before the rule is used again, since the rule checks the callback is its own.
        val ruleCallback = ViewRootForTest.onViewCreatedCallback
        ViewRootForTest.onViewCreatedCallback = {
            ruleCallback?.invoke(it)
            roots += it
        }
        repeat(SETTLE_FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper()
        }
        ViewRootForTest.onViewCreatedCallback = ruleCallback
        // With a focused field's cursor blinking, Compose never goes idle, so these find the dialog's nodes without
        // the rule, which would wait for idle.
        type?.let { text ->
            val setText = nodesNow().last { SemanticsActions.SetText in it.config }.config[SemanticsActions.SetText]
            composeRule.runOnUiThread { setText.action?.invoke(AnnotatedString(text)) }
            repeat(SETTLE_FRAMES) {
                composeRule.mainClock.advanceTimeByFrame()
                ShadowLooper.idleMainLooper()
            }
        }
        thenClick?.let { label ->
            val click = nodesNow().last { node ->
                SemanticsActions.OnClick in node.config &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
            }.config[SemanticsActions.OnClick]
            composeRule.runOnUiThread { click.action?.invoke() }
        }
        repeat(SETTLE_FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper()
        }
        captureScreenRoboImage(path)
    }

    private val roots = mutableListOf<ViewRootForTest>()

    /** Every merged semantics node in the windows recorded in [roots], read without waiting for idle. */
    private fun nodesNow(): List<SemanticsNode> {
        var nodes = emptyList<SemanticsNode>()
        composeRule.runOnUiThread {
            nodes = roots.filter { it.view.isAttachedToWindow }.flatMap {
                it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            }
        }
        return nodes
    }

    /** Localized times put a narrow no-break space before AM/PM; matches it as a plain space. */
    private fun contentDescriptionIgnoringNarrowSpaces(value: String) =
        SemanticsMatcher("ContentDescription = '$value'") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .any { it.replace('\u202F', ' ') == value }
        }

    /** Like the ViewModel: invalid names come back as the error, valid ones are accepted. */
    private fun rejectInvalid(name: String): TagNames.Result? =
        TagNames.validate(name).takeIf { it !is TagNames.Result.Valid }

    /** Like the ViewModel: also refuses a name another tag has, ignoring case. */
    private fun rejectRename(tags: List<Tag>, tag: Tag, input: String): TagNames.Result? =
        when (val result = TagNames.validate(input)) {
            is TagNames.Result.Valid -> TagNames.Result.Taken.takeIf {
                tags.any { it.id != tag.id && TagNames.key(it.name) == TagNames.key(result.name) }
            }

            else -> result
        }

    private companion object {
        const val SETTLE_FRAMES = 60
        val IDLE = RecordingState.Idle
        val RECORDING = RecordingState.Active(isPaused = false, accumulatedMs = 0, resumedAt = 0)
        val PAUSED = RecordingState.Active(isPaused = true, accumulatedMs = 754_000, resumedAt = 0)

        // 1:02:03 at the fixed test clock (83s): the widest timer the screen has to fit.
        val RECORDING_LONG = RecordingState.Active(isPaused = false, accumulatedMs = 3_640_000, resumedAt = 0)

        /** [createdAt] is ISO-8601 UTC, e.g. 2026-10-01T09:05:00Z; the file is named after it like real ones. */
        private fun recording(createdAt: String, title: String?, durationMs: Long, extension: String = "m4a") =
            Recording(
                file = File(createdAt.removeSuffix("Z").replace('T', '_').replace(':', '-') + ".$extension"),
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

        // A remux that dropped audio: the short .m4a and the complete raw .aac share the date and default title.
        val WITH_UNPROCESSED = listOf(
            recording("2026-10-01T09:05:00Z", null, 168_000),
            recording("2026-10-01T09:05:00Z", null, 195_000, extension = "aac")
        ) + LIBRARY.drop(1)

        val WORK = Tag(1, "Work", hue = 210)
        val IDEAS = Tag(2, "Ideas", hue = 45)
        val ERRANDS = Tag(3, "Errands", hue = 140)
        val UNUSED = Tag(4, "Travel", hue = 300)
        val TAGS = listOf(WORK, IDEAS, ERRANDS, UNUSED)

        // Sorted by name like the repository; one at the 30-character limit.
        val MANY_TAGS = listOf(
            Tag(10, "Building management and heat", hue = 20),
            ERRANDS,
            Tag(11, "Family", hue = 330),
            IDEAS,
            Tag(12, "Lectures", hue = 175),
            Tag(13, "Podcast drafts", hue = 265),
            UNUSED,
            WORK
        )

        // Grocery list: Errands. Interview: Work. The two untitled ones after it: Work. Song idea: Ideas.
        val TAGGED_LIBRARY = LIBRARY.mapIndexed { index, recording ->
            recording.copy(
                tagId = when (index) {
                    1 -> ERRANDS.id
                    2, 3, 4 -> WORK.id
                    5 -> IDEAS.id
                    else -> null
                }
            )
        }

        val ADD_TAG = listOf("Add tag")
        val WORK_CHIP = listOf("Work")
        const val TOO_LONG_NAME = "Weekly planning with the whole team"

        val UNPROCESSED_MENU = listOf("More options for Oct 1, 2026, 9:05 AM, unprocessed original")
    }
}
