package com.ingeniumtc.voicememo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ingeniumtc.voicememo.R
import com.ingeniumtc.voicememo.data.Recording
import com.ingeniumtc.voicememo.data.Tag
import com.ingeniumtc.voicememo.data.TagNames

/** "All", then each tag, then "Add tag". Selecting a chip filters the list. */
@Composable
internal fun TagBar(
    tags: List<Tag>,
    selectedTag: Tag?,
    onTagSelected: (Tag?) -> Unit,
    onAddTag: (String) -> TagNames.Result?,
    modifier: Modifier = Modifier
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "all") {
            FilterChip(
                selected = selectedTag == null,
                onClick = { onTagSelected(null) },
                label = { Text(stringResource(R.string.tag_all)) }
            )
        }
        items(tags, key = { it.id }) { tag ->
            FilterChip(
                selected = tag.id == selectedTag?.id,
                onClick = { onTagSelected(tag) },
                label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
        item(key = "add") {
            AssistChip(
                onClick = { adding = true },
                label = { Text(stringResource(R.string.action_add_tag)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) }
            )
        }
    }
    if (adding) {
        TagNameDialog(
            title = stringResource(R.string.add_tag_title),
            onDismiss = { adding = false },
            onConfirm = { name -> onAddTag(name).also { if (it == null) adding = false } }
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

/** Checkboxes for every tag on one recording, plus a field to create a new tag and put it on the recording. */
@Composable
internal fun RecordingTagsDialog(
    recording: Recording,
    title: String,
    tags: List<Tag>,
    onToggle: (Tag, Boolean) -> Unit,
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
                    LazyColumn(Modifier.heightIn(max = TAG_LIST_MAX_HEIGHT)) {
                        items(tags, key = { it.id }) { tag ->
                            val checked = tag.id in recording.tagIds
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .toggleable(
                                        value = checked,
                                        role = Role.Checkbox,
                                        onValueChange = { onToggle(tag, it) }
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // The row is the toggle, so the checkbox itself takes no clicks or focus.
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    tag.name,
                                    modifier = Modifier.padding(start = 16.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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

@Composable
private fun TagNameDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> TagNames.Result?) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<TagNames.Result?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { error = onConfirm(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
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
            TextButton(onClick = { submit() }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun tagNameError(result: TagNames.Result): String = when (result) {
    TagNames.Result.TooLong ->
        pluralStringResource(R.plurals.tag_error_too_long, TagNames.MAX_LENGTH, TagNames.MAX_LENGTH)

    else -> stringResource(R.string.tag_error_blank)
}

private val TAG_LIST_MAX_HEIGHT = 240.dp
