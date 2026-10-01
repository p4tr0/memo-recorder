package com.ingeniumtc.voicememo.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ingeniumtc.voicememo.R
import com.ingeniumtc.voicememo.recording.RecordingEvent
import kotlinx.coroutines.launch

/** Wires [HomeScreen] to the ViewModel and owns the permission flow. */
@Composable
fun HomeRoute(viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val amplitude = viewModel.amplitude.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.startRecording()
            return@rememberLauncherForActivityResult
        }
        // No rationale after a denial means "don't ask again": only Settings can grant it now.
        val permanentlyDenied = activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.message_mic_permission_needed),
                actionLabel = if (permanentlyDenied) resources.getString(R.string.action_settings) else null,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
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
                }
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    HomeScreen(
        recordingState = recordingState,
        amplitude = { amplitude.value },
        snackbarHostState = snackbarHostState,
        onRecordClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.startRecording()
            } else {
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

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
