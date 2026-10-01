package com.ingeniumtc.voicememo.playback

import android.app.PendingIntent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the player so playback continues with the screen off, with lock screen and notification controls.
 * Media3 manages the foreground state and the media notification.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    // The extractor flag is @UnstableApi: it may change between Media3 releases, so recheck it when upgrading.
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Raw .aac recordings (kept when remuxing failed) are ADTS, which has no index: without constant
        // bitrate seeking they can't be seeked and report no duration.
        val extractors = DefaultExtractorsFactory()
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        val player = ExoPlayer.Builder(this, DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val builder = MediaSession.Builder(this, player).setCallback(TrustedControllersOnly())
        openAppIntent()?.let(builder::setSessionActivity)
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * The service is exported so system UI (lock screen, quick settings player, Bluetooth) can connect. Recording
     * titles are personal, so other apps are turned away. Trusted means this app, the system, or an app holding
     * MEDIA_CONTENT_CONTROL.
     */
    private class TrustedControllersOnly : MediaSession.Callback {
        @OptIn(UnstableApi::class) // ControllerInfo.isTrusted.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult = if (controller.isTrusted) {
            super.onConnect(session, controller)
        } else {
            MediaSession.ConnectionResult.reject()
        }
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
