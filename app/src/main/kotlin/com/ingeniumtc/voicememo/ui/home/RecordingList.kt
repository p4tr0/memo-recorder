package com.ingeniumtc.voicememo.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ingeniumtc.voicememo.R
import com.ingeniumtc.voicememo.data.Recording
import com.ingeniumtc.voicememo.data.Tag
import com.ingeniumtc.voicememo.data.TagNames
import com.ingeniumtc.voicememo.playback.PlaybackState
import com.ingeniumtc.voicememo.ui.theme.tagColor
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Newest first. The current recording expands in place with a seek bar. */
@Composable
internal fun RecordingList(
    recordings: List<Recording>,
    playback: PlaybackState,
    playbackPositionMs: () -> Long,
    onPlayClick: (Recording, String) -> Unit,
    onSeek: (Long) -> Unit,
    onRename: (Recording, String) -> Unit,
    onDelete: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    modifier: Modifier = Modifier,
    tags: List<Tag> = emptyList(),
    /** Unfiltered, so the tags dialog stays open when unticking the tag the list is filtered by. */
    allRecordings: List<Recording> = recordings,
    onSetTag: (Recording, Tag?) -> Unit = { _, _ -> },
    onAddTagTo: (Recording, String) -> TagNames.Result? = { _, _ -> null },
    zone: ZoneId = ZoneId.systemDefault()
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var tagging by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    // Fades the bottom edge while more rows are below, so the list visibly runs under the record controls
    // instead of being cut off by them.
    val edge by animateFloatAsState(
        targetValue = if (listState.canScrollForward) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "edgeFade"
    )
    val edgeColor = MaterialTheme.colorScheme.background
    LazyColumn(
        state = listState,
        modifier = modifier.drawWithContent {
            drawContent()
            val height = EDGE_FADE.toPx().coerceAtMost(size.height)
            if (edge > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(edgeColor.copy(alpha = 0f), edgeColor),
                        startY = size.height - height,
                        endY = size.height
                    ),
                    topLeft = Offset(0f, size.height - height),
                    size = Size(size.width, height),
                    alpha = edge
                )
            }
        },
        // The bottom padding lets the last row scroll fully clear of the fade.
        contentPadding = PaddingValues(top = 8.dp, bottom = EDGE_FADE),
        // Keeps the tinted current row from touching its neighbors.
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(recordings, key = { it.id }) { recording ->
            val title = displayTitle(recording, zone)
            val tag = recording.tagId?.let { id -> tags.firstOrNull { it.id == id } }
            RecordingRow(
                recording = recording,
                tagColor = tag?.let { tagColor(it.hue) },
                title = title,
                subtitle = subtitle(recording, zone),
                playback = playback.takeIf { it.currentId == recording.id },
                playbackPositionMs = playbackPositionMs,
                onPlayClick = { onPlayClick(recording, title) },
                onSeek = onSeek,
                onRenameClick = { renaming = recording.id },
                onTagsClick = { tagging = recording.id },
                onShareClick = { onShare(recording) },
                onDeleteClick = { deleting = recording.id },
                modifier = Modifier.animateItem()
            )
        }
    }

    recordings.firstOrNull { it.id == renaming }?.let { recording ->
        RenameDialog(
            initial = recording.title.orEmpty(),
            placeholder = displayTitle(recording.copy(title = null), zone),
            onDismiss = { renaming = null },
            onConfirm = {
                onRename(recording, it)
                renaming = null
            }
        )
    }
    allRecordings.firstOrNull { it.id == tagging }?.let { recording ->
        RecordingTagsDialog(
            recording = recording,
            title = displayTitle(recording, zone),
            tags = tags,
            onSelect = { onSetTag(recording, it) },
            onAddTag = { onAddTagTo(recording, it) },
            onDismiss = { tagging = null }
        )
    }
    recordings.firstOrNull { it.id == deleting }?.let { recording ->
        DeleteDialog(
            title = displayTitle(recording, zone),
            unprocessed = recording.isRawAac,
            onDismiss = { deleting = null },
            onConfirm = {
                onDelete(recording)
                deleting = null
            }
        )
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    /** Its tag's color, drawn as a stripe on the start edge, or null when untagged. */
    tagColor: Color?,
    title: String,
    subtitle: String,
    playback: PlaybackState?,
    playbackPositionMs: () -> Long,
    onPlayClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onRenameClick: () -> Unit,
    onTagsClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playing = playback?.isPlaying == true
    val current = playback != null
    // A raw copy can share its date (and so its default title) with a processed one; its buttons must say which.
    val spokenName = if (recording.isRawAac) stringResource(R.string.recording_name_unprocessed, title) else title
    val playLabel =
        stringResource(if (playing) R.string.action_pause_playback else R.string.action_play, spokenName)
    // The current row sits on a subtle tint, so it reads as one unit with its seek bar.
    val tint = MaterialTheme.colorScheme.surfaceContainer
    val container by animateColorAsState(
        targetValue = if (current) tint else tint.copy(alpha = 0f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "rowContainer"
    )
    // At large font scales one line can't hold a date and time, so text wraps instead of hiding the time.
    val maxLines = if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) 2 else 1
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(ROW_SHAPE)
            .drawBehind {
                drawRect(container)
                if (tagColor != null) {
                    // Inset from the row's rounded corners, which would otherwise clip its ends into slivers.
                    val width = TAG_STRIPE_WIDTH.toPx()
                    val inset = TAG_STRIPE_INSET.toPx()
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                    drawRoundRect(
                        tagColor,
                        topLeft = Offset(x, inset),
                        size = Size(width, size.height - 2 * inset),
                        cornerRadius = CornerRadius(width / 2)
                    )
                }
            }
    ) {
        Box {
            // The whole row is a tap target for play, but TalkBack gets it once, from the labeled play button.
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics {}
                    .clickable(onClick = onPlayClick)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FilledTonalIconButton(
                    onClick = onPlayClick,
                    colors = if (current) {
                        IconButtonDefaults.filledIconButtonColors()
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    }
                ) {
                    Icon(
                        painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = playLabel
                    )
                }
                // One TalkBack stop for title and subtitle.
                Column(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {}
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Leading, so ellipsizing never hides it.
                        if (recording.isRawAac) UnprocessedTag()
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = maxLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                OverflowMenu(spokenName, onRenameClick, onTagsClick, onShareClick, onDeleteClick)
            }
        }
        AnimatedVisibility(
            visible = current,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            // Falls back to the stored duration until the player has read the file's own.
            val durationMs = playback?.durationMs?.takeIf { it > 0 } ?: recording.durationMs
            SeekBar(positionMs = playbackPositionMs, durationMs = durationMs, onSeek = onSeek)
        }
    }
}

/** Quiet outlined tag for a kept raw recording. Rare, so it informs without competing with the title. */
@Composable
private fun UnprocessedTag() {
    Text(
        text = stringResource(R.string.label_unprocessed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, TAG_SHAPE)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun SeekBar(positionMs: () -> Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val position = positionMs()
    // While dragging, the thumb follows the finger; the player is only told on release.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = dragFraction?.let { (it * durationMs).toLong() } ?: position
    val label = stringResource(R.string.seek_position)
    val state = stringResource(R.string.seek_state, formatElapsed(shownMs), formatElapsed(durationMs))
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            enabled = durationMs > 0,
            // The default inactive track (secondaryContainer) nearly vanishes on the row tint.
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = state
            }
        )
        // Already spoken as the slider's state.
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            Text(formatElapsed(shownMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatElapsed(durationMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverflowMenu(
    title: String,
    onRename: () -> Unit,
    onTags: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more, title)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                onClick = {
                    expanded = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_tags)) },
                onClick = {
                    expanded = false
                    onTags()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                onClick = {
                    expanded = false
                    onShare()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun RenameDialog(initial: String, placeholder: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    // Focused with the old name selected, so typing replaces it right away.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.rename_label)) },
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingText = { Text(stringResource(R.string.rename_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value.text) }),
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun DeleteDialog(title: String, unprocessed: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Text(stringResource(if (unprocessed) R.string.delete_body_unprocessed else R.string.delete_body, title))
        },
        confirmButton = {
            // Filled, so the irreversible action outweighs Cancel (both would otherwise be reddish text).
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** The user's title, or the start date and time in the user's locale. */
internal fun displayTitle(recording: Recording, zone: ZoneId): String =
    recording.title ?: dateTime().format(recording.createdAt.atZone(zone))

@Composable
private fun subtitle(recording: Recording, zone: ZoneId): String {
    val duration = formatElapsed(recording.durationMs)
    // A renamed recording would otherwise lose its date.
    return if (recording.title == null) {
        duration
    } else {
        stringResource(R.string.recording_subtitle, dateTime().format(recording.createdAt.atZone(zone)), duration)
    }
}

// Built per call: a localized formatter captures the default locale when it's created.
private fun dateTime(): DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private val ROW_SHAPE = RoundedCornerShape(20.dp)
private val TAG_STRIPE_WIDTH = 3.dp
private val TAG_STRIPE_INSET = 12.dp
private val TAG_SHAPE = RoundedCornerShape(6.dp)
private val EDGE_FADE = 24.dp
private const val LARGE_FONT_SCALE = 1.5f
