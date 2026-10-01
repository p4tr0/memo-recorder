package com.ingeniumtc.voicememo.recording

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ingeniumtc.voicememo.VoiceMemoApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the microphone accessible while recording with the screen off or the app
 * in the background. All recording logic lives in [RecordingController]; this only mirrors its state
 * into the notification and stops itself when the session ends.
 *
 * Android 14+ only allows starting a microphone foreground service while the app is visible, so
 * [start] must be called from a user action in the UI.
 */
class RecordingService : Service() {

    private val controller by lazy { (application as VoiceMemoApp).container.recordingController }
    private val scope = MainScope()
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        RecordingNotifications.ensureChannel(this)
        scope.launch {
            controller.state.collect { state ->
                if (state is RecordingState.Active) updateNotification(state)
            }
        }
        scope.launch {
            // Every session, and every failed start, ends with exactly one event. Ending on events rather than
            // on Idle also covers a session so short that the conflated state flow never showed it as Active.
            controller.events.collect {
                if (controller.state.value == RecordingState.Idle) shutDown()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val current = controller.state.value as? RecordingState.Active
        when (intent?.action) {
            ACTION_START -> if (current != null) {
                // Double tap: every startForegroundService needs a startForeground, but keep the live session.
                promoteToForeground(current)
            } else if (promoteToForeground(null)) {
                controller.start()
            }

            // Actions from a notification that outlived its session (or a system restart) have nothing to do.
            ACTION_PAUSE -> if (current != null) controller.pause() else shutDown()

            ACTION_RESUME -> if (current != null) controller.resume() else shutDown()

            ACTION_STOP -> if (current != null) controller.stop() else shutDown()

            else -> if (current == null) shutDown()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** [state] is the live session when re-promoting after a duplicate start, or null for a new one. */
    private fun promoteToForeground(state: RecordingState.Active?): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            val elapsed = state?.elapsedMs(SystemClock.elapsedRealtime()) ?: 0
            val notification = RecordingNotifications.build(this, state, elapsed)
            ServiceCompat.startForeground(this, RecordingNotifications.NOTIFICATION_ID, notification, type)
            true
        } catch (e: Exception) {
            // SecurityException (permission revoked) or ForegroundServiceStartNotAllowedException (app not visible).
            // Only a new session is abandoned here. A live one is already in the foreground.
            if (state == null) {
                controller.reportStartFailure()
                shutDown()
            }
            false
        }
    }

    private fun updateNotification(state: RecordingState.Active) {
        val notification = RecordingNotifications.build(this, state, state.elapsedMs(SystemClock.elapsedRealtime()))
        // Posting can fail silently if notifications are denied. The service keeps running regardless.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(this).notify(RecordingNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // By start ID, so a start command still in flight keeps the service alive to call startForeground.
        stopSelf(lastStartId)
    }

    companion object {
        private const val TAG = "RecordingService"
        internal const val ACTION_START = "com.ingeniumtc.voicememo.action.START"
        internal const val ACTION_PAUSE = "com.ingeniumtc.voicememo.action.PAUSE"
        internal const val ACTION_RESUME = "com.ingeniumtc.voicememo.action.RESUME"
        internal const val ACTION_STOP = "com.ingeniumtc.voicememo.action.STOP"

        /** Returns false if the system refused to start the service. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_START)
            )
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) or a background start limit on 26+.
            Log.w(TAG, "Could not start recording service", e)
            false
        }
    }
}
