package io.github.p4tr0.voicememo.recording

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.p4tr0.voicememo.R

internal object RecordingNotifications {
    const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "recording"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_recording))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** [state] null means the service is starting and the recorder isn't running yet. */
    fun build(context: Context, state: RecordingState.Active?, elapsedMs: Long): Notification {
        val paused = state?.isPaused == true
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(if (paused) R.string.status_paused else R.string.status_recording))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state != null && !paused) {
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsedMs)
        } else {
            builder.setShowWhen(false)
        }

        if (state != null) {
            if (paused) {
                builder.addAction(
                    0,
                    context.getString(R.string.action_resume),
                    serviceIntent(context, RecordingService.ACTION_RESUME)
                )
            } else {
                builder.addAction(
                    0,
                    context.getString(R.string.action_pause),
                    serviceIntent(context, RecordingService.ACTION_PAUSE)
                )
            }
            builder.addAction(
                0,
                context.getString(R.string.action_stop),
                serviceIntent(context, RecordingService.ACTION_STOP)
            )
        }
        return builder.build()
    }

    private fun openAppIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun serviceIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
