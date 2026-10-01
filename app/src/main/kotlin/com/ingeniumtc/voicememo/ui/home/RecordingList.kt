package com.ingeniumtc.voicememo.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ingeniumtc.voicememo.R
import com.ingeniumtc.voicememo.data.Recording
import com.ingeniumtc.voicememo.playback.PlaybackState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Newest first. The current recording expands in place with a seek bar. */
@Composable
internal fun RecordingList(
    recordings: List<Recording>,
    playback: PlaybackState,
    onPlayClick: (Recording, String) -> Unit,
    onSeek: (Long) -> Unit,
    onRename: (Recording, String) -> Unit,
    onDelete: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(recordings, key = { it.id }) { recording ->
            val title = displayTitle(recording, zone)
            RecordingRow(
                recording = recording,
                title = title,
                subtitle = subtitle(recording, zone),
                playback = playback.takeIf { it.currentId == recording.id },
                onPlayClick = { onPlayClick(recording, title) },
                onSeek = onSeek,
                onRenameClick = { renaming = recording.id },
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
    recordings.firstOrNull { it.id == deleting }?.let { recording ->
        DeleteDialog(
            title = displayTitle(recording, zone),
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
    title: String,
    subtitle: String,
    playback: PlaybackState?,
    onPlayClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onRenameClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playing = playback?.isPlaying == true
    val playLabel = stringResource(if (playing) R.string.action_pause_playback else R.string.action_play, title)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = playLabel, onClick = onPlayClick)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FilledTonalIconButton(
                onClick = onPlayClick,
                colors = if (playback != null) {
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
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OverflowMenu(title, onRenameClick, onShareClick, onDeleteClick)
        }
        AnimatedVisibility(
            visible = playback != null,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            // Falls back to the stored duration until the player has read the file's own.
            val durationMs = playback?.durationMs?.takeIf { it > 0 } ?: recording.durationMs
            SeekBar(positionMs = playback?.positionMs ?: 0, durationMs = durationMs, onSeek = onSeek)
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    // While dragging, the thumb follows the finger; the player is only told on release.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = dragFraction?.let { (it * durationMs).toLong() } ?: positionMs
    val label = stringResource(R.string.seek_position)
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            enabled = durationMs > 0,
            modifier = Modifier.semantics { contentDescription = label }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            Text(formatElapsed(shownMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatElapsed(durationMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverflowMenu(title: String, onRename: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
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
                keyboardActions = KeyboardActions(onDone = { onConfirm(value.text) })
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun DeleteDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_body, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
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
