package io.github.p4tr0.voicememo.ui.home

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.p4tr0.voicememo.R
import io.github.p4tr0.voicememo.data.Recording
import io.github.p4tr0.voicememo.data.Tag
import io.github.p4tr0.voicememo.data.TagNames
import io.github.p4tr0.voicememo.ui.theme.tagColor
import kotlinx.coroutines.flow.first

/**
 * "All", then each tag, then "Add tag". Selecting a chip filters the list; long-pressing a tag offers Rename and
 * Delete.
 */
@Composable
internal fun TagBar(
    tags: List<Tag>,
    selectedTag: Tag?,
    onTagSelected: (Tag?) -> Unit,
    onAddTag: (String) -> TagNames.Result?,
    modifier: Modifier = Modifier,
    onRenameTag: (Tag, String) -> TagNames.Result? = { _, _ -> null },
    onDeleteTag: (Tag) -> Unit = {}
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    // Ids, not tags, so a tag that disappears (deleted elsewhere) closes its menu or dialog.
    var menuFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val haptics = LocalHapticFeedback.current
    val rowState = rememberLazyListState()
    // A lone selected "All" filters nothing, so until there's a tag the bar only offers to add one.
    val showAll = tags.isNotEmpty()
    val selectedIndex = selectedTag?.let { tag -> tags.indexOfFirst { it.id == tag.id } + 1 } ?: 0
    // Keeps the selected chip in view, e.g. a remembered tag far along the row on launch.
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    LaunchedEffect(selectedIndex) {
        // Waits for the first layout; before it nothing counts as visible.
        val info = snapshotFlow { rowState.layoutInfo }.first { it.totalItemsCount > 0 }
        val item = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val fullyVisible = item != null &&
            item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
        if (!fullyVisible && selectedIndex > 0) {
            if (reducedMotion) rowState.scrollToItem(selectedIndex) else rowState.animateScrollToItem(selectedIndex)
        }
    }
    val startFade by animateFloatAsState(
        targetValue = if (rowState.canScrollBackward) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tagStartFade"
    )
    val endFade by animateFloatAsState(
        targetValue = if (rowState.canScrollForward) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tagEndFade"
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LazyRow(
        state = rowState,
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .horizontalEdgeFade({ startFade }, { endFade }, rtl, MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showAll) {
            item(key = "all") {
                TagChip(
                    label = stringResource(R.string.tag_all),
                    selected = selectedTag == null,
                    onClick = { onTagSelected(null) }
                )
            }
        }
        items(tags, key = { it.id }) { tag ->
            Box {
                TagChip(
                    label = tag.name,
                    dot = tagColor(tag.hue),
                    selected = tag.id == selectedTag?.id,
                    onClick = { onTagSelected(tag) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuFor = tag.id
                    },
                    onLongClickLabel = stringResource(R.string.action_tag_options, tag.name)
                )
                DropdownMenu(expanded = menuFor == tag.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menuFor = null
                            renaming = tag.id
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menuFor = null
                            deleting = tag.id
                        }
                    )
                }
            }
        }
        item(key = "add") {
            AssistChip(
                onClick = { adding = true },
                label = { Text(stringResource(R.string.action_add_tag)) },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                },
                // Quieter than the filters: an action at the end of the row, not another accent.
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
    if (adding) {
        TagNameDialog(
            title = stringResource(R.string.add_tag_title),
            confirmLabel = stringResource(R.string.action_add),
            onDismiss = { adding = false },
            onConfirm = { name -> onAddTag(name).also { if (it == null) adding = false } }
        )
    }
    tags.firstOrNull { it.id == renaming }?.let { tag ->
        TagNameDialog(
            title = stringResource(R.string.rename_tag_title),
            confirmLabel = stringResource(R.string.action_save),
            initial = tag.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> onRenameTag(tag, name).also { if (it == null) renaming = null } }
        )
    }
    tags.firstOrNull { it.id == deleting }?.let { tag ->
        DeleteTagDialog(
            name = tag.name,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                onDeleteTag(tag)
            }
        )
    }
}

/**
 * A filter chip that can also be long-pressed, which Material's FilterChip can't. Selected is inverted rather
 * than tinted: clearly the active filter in both themes, and neutral, so it never competes with the red record
 * button or the coral of the playing row.
 */
@Composable
private fun TagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The tag's color, so the chip can be matched to the stripes on its recordings. */
    dot: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null
) {
    val colors = MaterialTheme.colorScheme
    val spec = spring<Color>(stiffness = Spring.StiffnessMediumLow)
    val container by animateColorAsState(
        if (selected) colors.inverseSurface else colors.inverseSurface.copy(alpha = 0f),
        spec,
        label = "chipContainer"
    )
    val content by animateColorAsState(
        if (selected) colors.inverseOnSurface else colors.onSurfaceVariant,
        spec,
        label = "chipContent"
    )
    val border by animateColorAsState(
        if (selected) colors.inverseSurface else colors.outlineVariant,
        spec,
        label = "chipBorder"
    )
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = CHIP_HEIGHT)
            .clip(CHIP_SHAPE)
            .drawBehind { drawRect(container) }
            .border(1.dp, border, CHIP_SHAPE)
            .combinedClickable(
                role = Role.RadioButton,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dot != null) TagDot(dot)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A 30-character name stays a chip, not most of the row.
                modifier = Modifier.widthIn(max = TAG_CHIP_MAX_WIDTH)
            )
        }
    }
}

@Composable
private fun TagDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(TAG_DOT_SIZE)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Fades whichever edge has more chips beyond it, like the list's bottom edge, so the row reads as scrollable
 * instead of cut off.
 */
private fun Modifier.horizontalEdgeFade(start: () -> Float, end: () -> Float, rtl: Boolean, color: Color) =
    drawWithContent {
        drawContent()
        val width = TAG_EDGE_FADE.toPx().coerceAtMost(size.width / 2)
        val (left, right) = if (rtl) end() to start() else start() to end()
        if (left > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)), startX = 0f, endX = width),
                size = Size(width, size.height),
                alpha = left
            )
        }
        if (right > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(color.copy(alpha = 0f), color),
                    startX = size.width - width,
                    endX = size.width
                ),
                topLeft = Offset(size.width - width, 0f),
                size = Size(width, size.height),
                alpha = right
            )
        }
    }

/** Shown when the selected tag has no recordings, while other recordings exist. */
@Composable
internal fun NoRecordingsTagged(tag: Tag, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.tag_empty_title, tag.name),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.tag_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Picks the one tag for a recording ("No tag" or one of them), plus a field to create a new tag and give it to
 * the recording. A recording carries a single tag, so picking one replaces the other.
 */
@Composable
internal fun RecordingTagsDialog(
    recording: Recording,
    title: String,
    tags: List<Tag>,
    onSelect: (Tag?) -> Unit,
    onAddTag: (String) -> TagNames.Result?,
    onDismiss: () -> Unit
) {
    var newTag by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<TagNames.Result?>(null) }
    val submit = {
        error = onAddTag(newTag)
        if (error == null) newTag = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_title, title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tags.isEmpty()) {
                    Text(
                        stringResource(R.string.tags_none_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Five and a half rows, so a cut-off row shows there are more to scroll to.
                    LazyColumn(Modifier.heightIn(max = TAG_LIST_MAX_HEIGHT).selectableGroup()) {
                        item(key = "none") {
                            TagOption(
                                label = stringResource(R.string.tag_none),
                                dot = null,
                                selected = recording.tagId == null,
                                onClick = { onSelect(null) }
                            )
                        }
                        items(tags, key = { it.id }) { tag ->
                            TagOption(
                                label = tag.name,
                                dot = tagColor(tag.hue),
                                selected = tag.id == recording.tagId,
                                onClick = { onSelect(tag) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newTag,
                    onValueChange = {
                        newTag = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.new_tag_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(tagNameError(it)) } },
                    trailingIcon = {
                        IconButton(onClick = submit, enabled = newTag.isNotBlank()) {
                            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.action_add_tag))
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } }
    )
}

/** Add or rename. [initial] is selected in full, so typing replaces it. */
@Composable
private fun TagOption(label: String, dot: Color?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TAG_ROW_HEIGHT)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The row is the control, so the radio button itself takes no clicks or focus.
        RadioButton(selected = selected, onClick = null)
        Row(
            Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // "No tag" gets an empty slot, so every name lines up.
            if (dot != null) TagDot(dot) else Spacer(Modifier.size(TAG_DOT_SIZE))
            // Options to pick, so full-strength text rather than the dialog's dimmer body color.
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TagNameDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> TagNames.Result?,
    initial: String = ""
) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    var error by remember { mutableStateOf<TagNames.Result?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { error = onConfirm(name.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    if (it.text != name.text) error = null
                    name = it
                },
                label = { Text(stringResource(R.string.tag_name_label)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(tagNameError(it)) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = name.text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun DeleteTagDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_tag_title, name)) },
        text = { Text(stringResource(R.string.delete_tag_body)) },
        confirmButton = {
            // Filled and error-colored like deleting a recording, so it outweighs Cancel.
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

@Composable
private fun tagNameError(result: TagNames.Result): String = when (result) {
    TagNames.Result.TooLong ->
        pluralStringResource(R.plurals.tag_error_too_long, TagNames.MAX_LENGTH, TagNames.MAX_LENGTH)

    TagNames.Result.Taken -> stringResource(R.string.tag_error_taken)

    is TagNames.Result.Blank, is TagNames.Result.Valid -> stringResource(R.string.tag_error_blank)
}

private val CHIP_HEIGHT = 32.dp
private val CHIP_SHAPE = RoundedCornerShape(8.dp)
private val TAG_ROW_HEIGHT = 48.dp
private val TAG_LIST_MAX_HEIGHT = TAG_ROW_HEIGHT * 5.5f
private val TAG_DOT_SIZE = 8.dp
private val TAG_CHIP_MAX_WIDTH = 200.dp
private val TAG_EDGE_FADE = 24.dp
