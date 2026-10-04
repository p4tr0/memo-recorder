package io.github.p4tr0.voicememo.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.p4tr0.voicememo.R
import io.github.p4tr0.voicememo.data.Recording
import io.github.p4tr0.voicememo.data.Tag
import java.text.Normalizer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Whether [recording] matches what's typed in the search bar. A blank query matches all. Ignores case, accents
 * and punctuation.
 * - Title: every word appears, in any order ("list grocery" finds "Grocery list").
 * - Date: the query appears as typed, in the date as the list shows it ("sep 30", "6:42 pm") or spelled out
 *   ("september 30 2026"). As a phrase, so "sep 2" finds Sep 20 to 29 but not Sep 18, whose year has a 2.
 */
internal fun matchesSearch(
    recording: Recording,
    query: String,
    zone: ZoneId,
    locale: Locale = Locale.getDefault()
): Boolean = SearchMatcher(query, zone, locale).matches(recording)

/** [matchesSearch] for a whole list: the query and the date formats are prepared once, not per recording. */
internal class SearchMatcher(query: String, private val zone: ZoneId, locale: Locale = Locale.getDefault()) {
    private val phrase = searchKey(query)
    private val words = phrase.split(' ')
    private val dateFormats = listOf(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT),
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
    ).map { it.withLocale(locale) }

    fun matches(recording: Recording): Boolean {
        if (phrase.isEmpty()) return true
        val title = recording.title?.let(::searchKey)
        if (title != null && words.all { it in title }) return true
        val at = recording.createdAt.atZone(zone)
        return dateFormats.any { phrase in searchKey(it.format(at)) }
    }
}

private val COMBINING_MARKS = Regex("\\p{M}+")

// Keeps the colon, so "6:42" is still one thing to type.
private val PUNCTUATION = Regex("[^\\p{L}\\p{N}:]+")

/** "Łódź,  Notes!" to "lodz notes". Ł has no decomposition, so it is mapped by hand. */
internal fun searchKey(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(COMBINING_MARKS, "")
    .replace('ł', 'l')
    .replace(PUNCTUATION, " ")
    .trim()

/** The top bar while searching: back closes the search, and the field is focused as it opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    // The field's own state, so typing never waits on the ViewModel's filtered list to come back round.
    var text by rememberSaveable { mutableStateOf(query) }
    // Only when the bar opens: not again after rotation, which would bring back a keyboard the user put away.
    var focused by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val close = {
        // Otherwise focus moves on to the first chip when the field goes away.
        focusManager.clearFocus()
        onClose()
    }
    val change = { value: String ->
        text = value
        onQueryChange(value)
    }
    BackHandler(onBack = close)
    LaunchedEffect(Unit) {
        if (!focused) {
            focusRequester.requestFocus()
            focused = true
        }
    }
    val hint = stringResource(R.string.search_hint)
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = close) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_close_search)
                )
            }
        },
        title = {
            TextField(
                value = text,
                onValueChange = change,
                placeholder = { Text(hint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    // The placeholder goes once something is typed; TalkBack still says what the field is for.
                    .semantics { contentDescription = hint }
            )
        },
        actions = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { change("") }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_clear_search)
                    )
                }
            }
        }
    )
}

/** Nothing matches. Within a tag, says so and offers to search everything. */
@Composable
internal fun NoSearchResults(query: String, selectedTag: Tag?, onSearchAll: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.search_empty_title, query.trim()),
            // Announced as typing turns up nothing, since the list just goes blank otherwise.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        if (selectedTag != null) {
            Text(
                text = stringResource(R.string.search_empty_in_tag, selectedTag.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onSearchAll) { Text(stringResource(R.string.action_search_all)) }
        }
    }
}
