package io.github.p4tr0.voicememo.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.p4tr0.voicememo.R
import io.github.p4tr0.voicememo.data.ExportSummary
import io.github.p4tr0.voicememo.data.ImportSummary
import io.github.p4tr0.voicememo.data.Recording
import io.github.p4tr0.voicememo.data.TransferResult
import io.github.p4tr0.voicememo.recording.RecordingEvent
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Wires [HomeScreen] to the ViewModel and owns the permission flow. */
@Composable
fun HomeRoute(viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val amplitude = viewModel.amplitude.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    // Read only inside the seek bar, like amplitude, so ticks don't recompose the screen.
    val playbackPosition = viewModel.playbackPositionMs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The rationale flag alone can't tell "don't ask again" from a dismissed first dialog: both read false.
    var rationaleBeforeRequest by rememberSaveable { mutableStateOf(false) }
    var deniedBefore by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.startRecording()
            return@rememberLauncherForActivityResult
        }
        val rationaleNow = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        // Only Settings can grant it once the rationale is gone after the user had already been asked: either it
        // was showing before this request, or an earlier request this session was denied too.
        val permanentlyDenied = !rationaleNow && (rationaleBeforeRequest || deniedBefore)
        deniedBefore = true
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.message_mic_permission_needed),
                actionLabel = if (permanentlyDenied) resources.getString(R.string.action_settings) else null,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
        }
    }

    val transferBusy by viewModel.transferBusy.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        folder?.let(viewModel::importFrom)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        folder?.let(viewModel::exportTo)
    }
    val pickFolder = { launcher: ActivityResultLauncher<Uri?> ->
        try {
            launcher.launch(null)
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.message_no_folder_picker)) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.transferResults.collect { result ->
            val message = when (result) {
                is TransferResult.Imported -> importMessage(resources, result.summary)
                is TransferResult.Exported -> exportMessage(resources, result.summary)
                TransferResult.FolderUnreadable -> resources.getString(R.string.message_folder_unreadable)
                TransferResult.ShareFailed -> resources.getString(R.string.message_share_failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is RecordingEvent.Saved -> resources.getString(R.string.message_saved, formatElapsed(event.durationMs))

                is RecordingEvent.Failed -> when (event.reason) {
                    RecordingEvent.Reason.CouldNotStart -> resources.getString(R.string.message_could_not_start)
                    RecordingEvent.Reason.TooShort -> resources.getString(R.string.message_too_short)
                    RecordingEvent.Reason.RecorderError -> resources.getString(R.string.message_recorder_error)
                    RecordingEvent.Reason.SaveFailed -> resources.getString(R.string.message_save_failed)
                }
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.deleteFailures.collect { recording ->
            snackbarHostState.showSnackbar(
                resources.getString(R.string.message_delete_failed, displayTitle(recording, ZoneId.systemDefault()))
            )
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.renameFailures.collect {
            snackbarHostState.showSnackbar(resources.getString(R.string.message_rename_tag_taken))
        }
    }

    HomeScreen(
        recordingState = recordingState,
        recordings = library?.recordings,
        allRecordings = library?.all,
        tags = library?.tags.orEmpty(),
        selectedTag = library?.selectedTag,
        onTagSelected = viewModel::selectTag,
        onAddTag = viewModel::addTag,
        onSetTag = viewModel::setTag,
        onAddTagTo = viewModel::addTagTo,
        onRenameTag = viewModel::renameTag,
        onDeleteTag = viewModel::deleteTag,
        onImportClick = { pickFolder(importLauncher) },
        onExportClick = { pickFolder(exportLauncher) },
        transferBusy = transferBusy,
        search = library?.search,
        onSearchOpen = viewModel::openSearch,
        onSearchChange = viewModel::setSearch,
        onSearchClose = viewModel::closeSearch,
        playback = playback,
        playbackPositionMs = { playbackPosition.value },
        onPlayClick = viewModel::togglePlayback,
        onSeek = viewModel::seekTo,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onShare = { shareRecording(context, it) },
        amplitude = { amplitude.value },
        snackbarHostState = snackbarHostState,
        onRecordClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.startRecording()
            } else {
                rationaleBeforeRequest = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
                permissionLauncher.launch(recordingPermissions())
            }
        },
        onStopClick = viewModel::stop,
        onPauseClick = viewModel::pause,
        onResumeClick = viewModel::resume
    )
}

/** Notifications are optional (recording works without them) so they're requested alongside, not required. */
private fun recordingPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** "Imported 3 recordings", plus what was skipped or failed, if anything. */
private fun importMessage(resources: Resources, summary: ImportSummary): String = buildList {
    // Only a folder import can find nothing: a share always hands over at least one file.
    if (summary == ImportSummary(0, 0, 0)) return resources.getString(R.string.message_import_none)
    if (summary.imported > 0 || (summary.duplicates == 0 && summary.failed == 0)) {
        add(resources.getQuantityString(R.plurals.message_imported, summary.imported, summary.imported))
    }
    if (summary.duplicates > 0) {
        add(resources.getQuantityString(R.plurals.message_import_duplicates, summary.duplicates, summary.duplicates))
    }
    if (summary.failed > 0) {
        add(resources.getQuantityString(R.plurals.message_import_failed, summary.failed, summary.failed))
    }
}.joinToString(" ")

/** "Exported 3 recordings", plus what was already there or failed, if anything. */
private fun exportMessage(resources: Resources, summary: ExportSummary): String = buildList {
    if (summary.exported > 0 || (summary.alreadyThere == 0 && summary.failed == 0)) {
        add(resources.getQuantityString(R.plurals.message_exported, summary.exported, summary.exported))
    }
    if (summary.alreadyThere > 0) {
        add(
            resources.getQuantityString(
                R.plurals.message_export_already_there,
                summary.alreadyThere,
                summary.alreadyThere
            )
        )
    }
    if (summary.failed > 0) {
        add(resources.getQuantityString(R.plurals.message_export_failed, summary.failed, summary.failed))
    }
}.joinToString(" ")

private fun mimeTypeOf(extension: String): String = when (extension.lowercase()) {
    "aac" -> "audio/aac"
    "mp3" -> "audio/mpeg"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    "amr" -> "audio/amr"
    "3gp" -> "audio/3gpp"
    else -> "audio/mp4"
}

/** Hands a read-only content URI to the share sheet. The grant lasts only as long as the receiving activity. */
private fun shareRecording(context: Context, recording: Recording) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", recording.file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeTypeOf(recording.file.extension))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser_title)))
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
