package com.ingeniumtc.voicememo.ui.home

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ingeniumtc.voicememo.R
import com.ingeniumtc.voicememo.recording.RecordingState
import com.ingeniumtc.voicememo.ui.theme.RecordRed
import com.ingeniumtc.voicememo.ui.theme.VoiceMemoTheme
import kotlinx.coroutines.delay

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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    clock: () -> Long = SystemClock::elapsedRealtime
) {
    val active = recordingState as? RecordingState.Active
    Scaffold(
        modifier = modifier,
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Crossfade(targetState = active != null, label = "center", modifier = Modifier.align(Alignment.Center)) {
                if (it && active != null) RecordingStatus(active, clock) else EmptyState()
            }
            RecordingControls(
                active = active,
                amplitude = amplitude,
                onRecordClick = onRecordClick,
                onStopClick = onStopClick,
                onPauseClick = onPauseClick,
                onResumeClick = onResumeClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 48.dp),
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
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RecordingStatus(state: RecordingState.Active, clock: () -> Long, modifier: Modifier = Modifier) {
    // Ticks locally while recording; the controller only emits on pause/resume.
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(state) {
        now = clock()
        while (!state.isPaused) {
            delay(TIMER_TICK_MS)
            now = clock()
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatElapsed(state.elapsedMs(now)),
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Light
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.isPaused) MaterialTheme.colorScheme.onSurfaceVariant else RecordRed)
            )
            Text(
                text = stringResource(if (state.isPaused) R.string.status_paused else R.string.status_recording),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecordingControls(
    active: RecordingState.Active?,
    amplitude: () -> Float,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Fixed-size side slots keep the record button centered whether or not pause is shown.
        PauseResumeButton(active, onPauseClick, onResumeClick, Modifier.size(SIDE_BUTTON_SIZE))
        RecordButton(
            isRecording = active != null,
            isPaused = active?.isPaused == true,
            amplitude = amplitude,
            onClick = if (active != null) onStopClick else onRecordClick
        )
        Spacer(Modifier.size(SIDE_BUTTON_SIZE))
    }
}

@Composable
private fun PauseResumeButton(
    active: RecordingState.Active?,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = active != null, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            val paused = active?.isPaused == true
            FilledTonalIconButton(
                onClick = if (paused) onResumeClick else onPauseClick,
                modifier = Modifier.size(SIDE_BUTTON_SIZE)
            ) {
                Icon(
                    painter = painterResource(if (paused) R.drawable.ic_mic else R.drawable.ic_pause),
                    contentDescription = stringResource(
                        if (paused) R.string.action_resume_recording else R.string.action_pause_recording
                    )
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
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    val innerSize by animateDpAsState(if (isRecording) 32.dp else 68.dp, morph, label = "innerSize")
    val innerCorner by animateDpAsState(if (isRecording) 8.dp else 34.dp, morph, label = "innerCorner")
    val ringColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val haloColor = RecordRed.copy(alpha = 0.22f)

    Box(
        modifier = modifier
            .size(RECORD_BUTTON_SIZE)
            // Amplitude is read only during drawing, so level changes redraw without recomposing.
            .drawBehind {
                if (isRecording && !isPaused) {
                    drawCircle(haloColor, radius = size.minDimension / 2f * (1f + 0.4f * amplitude()))
                }
            }
            .clip(CircleShape)
            .border(4.dp, ringColor, CircleShape)
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
private const val TIMER_TICK_MS = 200L

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
