package io.github.p4tr0.voicememo.ui.home

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.p4tr0.voicememo.R
import io.github.p4tr0.voicememo.data.Recording
import io.github.p4tr0.voicememo.data.Tag
import io.github.p4tr0.voicememo.data.TagNames
import io.github.p4tr0.voicememo.playback.PlaybackState
import io.github.p4tr0.voicememo.recording.RecordingState
import io.github.p4tr0.voicememo.ui.theme.RecordRed
import io.github.p4tr0.voicememo.ui.theme.VoiceMemoTheme
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recordingState: RecordingState,
    amplitude: () -> Float,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null while loading, so the empty state doesn't flash before the first query returns. */
    recordings: List<Recording>? = emptyList(),
    playback: PlaybackState = PlaybackState(),
    playbackPositionMs: () -> Long = { 0 },
    onPlayClick: (Recording, String) -> Unit = { _, _ -> },
    onSeek: (Long) -> Unit = {},
    onRename: (Recording, String) -> Unit = { _, _ -> },
    onDelete: (Recording) -> Unit = {},
    onShare: (Recording) -> Unit = {},
    /** Unfiltered. Empty means there are no recordings at all, as opposed to none with the selected tag. */
    allRecordings: List<Recording>? = recordings,
    tags: List<Tag> = emptyList(),
    /** Null means All. */
    selectedTag: Tag? = null,
    onTagSelected: (Tag?) -> Unit = {},
    onAddTag: (String) -> TagNames.Result? = { null },
    onSetTag: (Recording, Tag?) -> Unit = { _, _ -> },
    onAddTagTo: (Recording, String) -> TagNames.Result? = { _, _ -> null },
    /** Returns the error to show in the rename dialog, or null once the rename is submitted. */
    onRenameTag: (Tag, String) -> TagNames.Result? = { _, _ -> null },
    onDeleteTag: (Tag) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    clock: () -> Long = SystemClock::elapsedRealtime,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val active = recordingState as? RecordingState.Active
    // Landscape phones and split screen: the large bar would eat most of the height.
    val compactHeight = LocalWindowInfo.current.containerDpSize.height < COMPACT_HEIGHT
    Scaffold(
        modifier = modifier,
        topBar = {
            val title = @Composable { Text(stringResource(R.string.home_title)) }
            if (compactHeight) TopAppBar(title = title) else LargeTopAppBar(title = title)
        }
    ) { padding ->
        val content = when {
            active != null -> Content.Recording
            recordings == null || allRecordings == null -> Content.Loading
            allRecordings.isEmpty() -> Content.Empty
            else -> Content.Library
        }
        val center = @Composable { centerModifier: Modifier ->
            Crossfade(
                targetState = content,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "center",
                modifier = centerModifier
            ) { shown ->
                when (shown) {
                    Content.Recording -> active?.let { Centered { RecordingStatus(it, clock) } }

                    Content.Empty -> Centered { EmptyState(controlsBeside = compactHeight) }

                    Content.Loading -> Unit

                    // Recordings is non-null here except for a frame while fading out of the library.
                    Content.Library -> Column(Modifier.fillMaxSize()) {
                        // Fixed above the list, so the filter stays in reach however far the list is scrolled.
                        TagBar(
                            tags = tags,
                            selectedTag = selectedTag,
                            onTagSelected = onTagSelected,
                            onAddTag = onAddTag,
                            onRenameTag = onRenameTag,
                            onDeleteTag = onDeleteTag,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        val shown = recordings.orEmpty()
                        if (shown.isEmpty() && selectedTag != null) {
                            Centered { NoRecordingsTagged(selectedTag) }
                        } else {
                            RecordingList(
                                recordings = shown,
                                playback = playback,
                                playbackPositionMs = playbackPositionMs,
                                onPlayClick = onPlayClick,
                                onSeek = onSeek,
                                onRename = onRename,
                                onDelete = onDelete,
                                onShare = onShare,
                                tags = tags,
                                allRecordings = allRecordings.orEmpty(),
                                onSetTag = onSetTag,
                                onAddTagTo = onAddTagTo,
                                zone = zone,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
        // In the content column, not the Scaffold slot, so a snackbar sits above the controls instead of on them.
        val snackbar = @Composable {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            )
        }
        val controls = @Composable { vertical: Boolean, controlsModifier: Modifier ->
            RecordingControls(
                active = active,
                amplitude = amplitude,
                onRecordClick = onRecordClick,
                onStopClick = onStopClick,
                onPauseClick = onPauseClick,
                onResumeClick = onResumeClick,
                vertical = vertical,
                modifier = controlsModifier
            )
        }
        val body = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        if (compactHeight) {
            // Landscape phones and split screen: controls in a column on the end side, so the list keeps the
            // height. HALO_CLEARANCE on both sides keeps the amplitude halo (up to 0.4 x radius) off the content
            // and the screen edge.
            Row(body, verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    center(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    snackbar()
                }
                controls(true, Modifier.padding(horizontal = HALO_CLEARANCE))
            }
        } else {
            Column(body, horizontalAlignment = Alignment.CenterHorizontally) {
                center(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                snackbar()
                // HALO_CLEARANCE keeps the amplitude halo off the snackbar.
                controls(false, Modifier.padding(top = HALO_CLEARANCE, bottom = 32.dp))
            }
        }
    }
}

private enum class Content { Loading, Empty, Library, Recording }

/** Scrolls only when the content can't fit (landscape, 2x font); otherwise stays centered. */
@Composable
private fun Centered(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun EmptyState(controlsBeside: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(if (controlsBeside) R.string.home_empty_body_beside else R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RecordingStatus(state: RecordingState.Active, clock: () -> Long, modifier: Modifier = Modifier) {
    // Ticks locally while recording, waking on each whole second; the controller only emits on pause/resume.
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(state) {
        now = clock()
        while (!state.isPaused) {
            val intoSecond = state.elapsedMs(now).coerceAtLeast(0) % 1000
            delay(1000 - intoSecond)
            now = clock()
        }
    }
    val elapsedMs = state.elapsedMs(now)
    val status = stringResource(if (state.isPaused) R.string.status_paused else R.string.status_recording)
    // Two TalkBack nodes: the timer speaks the live time when focused, and only the status is a polite live
    // region, so pause/resume is announced without chattering every second. Compose drops live regions on
    // merged children, so the status can't be merged into the timer.
    val spokenElapsed = spokenDuration(elapsedMs)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatElapsed(elapsedMs),
            modifier = Modifier.clearAndSetSemantics { contentDescription = spokenElapsed },
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Light,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = TIMER_MIN_FONT_SIZE,
                maxFontSize = MaterialTheme.typography.displayLarge.fontSize
            )
        )
        Row(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = status
                liveRegion = LiveRegionMode.Polite
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.isPaused) MaterialTheme.colorScheme.onSurfaceVariant else RecordRed)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "1 hour 2 minutes 3 seconds", skipping leading zero units. */
@Composable
private fun spokenDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = (totalSeconds / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return buildList {
        if (hours > 0) add(pluralStringResource(R.plurals.duration_hours, hours, hours))
        if (hours > 0 || minutes > 0) add(pluralStringResource(R.plurals.duration_minutes, minutes, minutes))
        add(pluralStringResource(R.plurals.duration_seconds, seconds, seconds))
    }.joinToString(" ")
}

@Composable
private fun RecordingControls(
    active: RecordingState.Active?,
    amplitude: () -> Float,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Stacked top to bottom instead of side by side, for the landscape side column. */
    vertical: Boolean = false
) {
    // Fixed-size slots either side keep the record button centered whether or not pause is shown.
    val slots = @Composable {
        PauseResumeButton(active, onPauseClick, onResumeClick, Modifier.size(SIDE_BUTTON_SIZE))
        RecordButton(
            isRecording = active != null,
            isPaused = active?.isPaused == true,
            amplitude = amplitude,
            onClick = if (active != null) onStopClick else onRecordClick
        )
        Spacer(Modifier.size(SIDE_BUTTON_SIZE))
    }
    if (vertical) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VERTICAL_CONTROLS_GAP)
        ) { slots() }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) { slots() }
    }
}

@Composable
private fun PauseResumeButton(
    active: RecordingState.Active?,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = active != null, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            val paused = active?.isPaused == true
            // Outlined rather than tonal: the red resume dot needs the plain surface behind it for 3:1 contrast.
            OutlinedIconButton(
                onClick = {
                    haptics.performHapticFeedback(
                        if (paused) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                    )
                    if (paused) onResumeClick() else onPauseClick()
                },
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = RING_ALPHA)),
                modifier = Modifier.size(SIDE_BUTTON_SIZE)
            ) {
                Icon(
                    painter = painterResource(if (paused) R.drawable.ic_resume else R.drawable.ic_pause),
                    contentDescription = stringResource(
                        if (paused) R.string.action_resume_recording else R.string.action_pause_recording
                    ),
                    tint = if (paused) RecordRed else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    isPaused: Boolean,
    amplitude: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val label = stringResource(if (isRecording) R.string.action_stop_recording else R.string.action_record)
    val morph =
        spring<Dp>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    val innerSize by animateDpAsState(if (isRecording) 32.dp else 68.dp, morph, label = "innerSize")
    val innerCorner by animateDpAsState(if (isRecording) 8.dp else 34.dp, morph, label = "innerCorner")
    val ringColor = MaterialTheme.colorScheme.onSurface.copy(alpha = RING_ALPHA)
    val haloColor = RecordRed.copy(alpha = 0.22f)
    val showHalo = isRecording && !isPaused
    // Animator duration scale 0 ("Remove animations"): keep a static halo instead of a pulsing one.
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    val halo = remember { Animatable(0f) }
    LaunchedEffect(showHalo, reducedMotion) {
        if (!showHalo || reducedMotion) {
            halo.snapTo(if (showHalo) STATIC_HALO_LEVEL else 0f)
            return@LaunchedEffect
        }
        // Each new level cancels the running spring and retargets it, keeping velocity, so the halo follows the
        // 20 Hz meter and breathes instead of jittering.
        snapshotFlow { amplitude().coerceIn(0f, 1f) }.collectLatest { level ->
            halo.animateTo(level, spring(stiffness = Spring.StiffnessMedium))
        }
    }

    Box(
        modifier = modifier
            .size(RECORD_BUTTON_SIZE)
            // The halo level is read only during drawing, so it redraws without recomposing.
            .drawBehind {
                if (showHalo) drawCircle(haloColor, radius = size.minDimension / 2f * (1f + 0.4f * halo.value))
            }
            .clip(CircleShape)
            .border(4.dp, ringColor, CircleShape)
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(
                    if (isRecording) HapticFeedbackType.Confirm else HapticFeedbackType.ToggleOn
                )
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(RecordRed)
        )
    }
}

private val RECORD_BUTTON_SIZE = 88.dp
private val SIDE_BUTTON_SIZE = 56.dp
private val COMPACT_HEIGHT = 480.dp
private val HALO_CLEARANCE = 24.dp

// Tighter than the 32dp row gap to fit short windows; still clears the halo's 0.4 x 44dp reach.
private val VERTICAL_CONTROLS_GAP = 24.dp
private val TIMER_MIN_FONT_SIZE = 32.sp
private const val RING_ALPHA = 0.12f
private const val STATIC_HALO_LEVEL = 0.3f

@Preview
@Composable
private fun HomeScreenIdlePreview() {
    VoiceMemoTheme(dynamicColor = false) {
        HomeScreen(RecordingState.Idle, { 0f }, {}, {}, {}, {})
    }
}

@Preview
@Composable
private fun HomeScreenRecordingPreview() {
    VoiceMemoTheme(dynamicColor = false) {
        HomeScreen(
            recordingState = RecordingState.Active(isPaused = false, accumulatedMs = 0, resumedAt = 0),
            amplitude = { 0.6f },
            onRecordClick = {},
            onStopClick = {},
            onPauseClick = {},
            onResumeClick = {},
            clock = { 83_000 }
        )
    }
}
