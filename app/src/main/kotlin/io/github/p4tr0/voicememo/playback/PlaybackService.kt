package io.github.p4tr0.voicememo.playback

import android.app.PendingIntent
import android.os.Build
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
import io.github.p4tr0.voicememo.VoiceMemoApp
import io.github.p4tr0.voicememo.recording.RecordingState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the player so playback continues with the screen off, with lock screen and notification controls.
 * Media3 manages the foreground state and the media notification.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()
    private val recordingState by lazy { (application as VoiceMemoApp).container.recordingController.state }

    // The extractor flag is @UnstableApi: it may change between Media3 releases, so recheck it when upgrading.
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Raw .aac recordings (kept when remuxing failed) are ADTS, which has no index: without constant
        // bitrate seeking they can't be seeked and report no duration.
        val extractors = DefaultExtractorsFactory()
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        val exoPlayer = ExoPlayer.Builder(this, DefaultMediaSourceFactory(this, extractors))
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
        val guarded = RecordingGuardPlayer(exoPlayer) { recordingState.value is RecordingState.Active }
        // A recording can start while something plays (the UI pauses first, but not every path goes through it).
        scope.launch {
            recordingState.collect { if (it is RecordingState.Active) guarded.pause() }
        }
        val builder = MediaSession.Builder(this, guarded).setCallback(TrustedControllersOnly())
        openAppIntent()?.let(builder::setSessionActivity)
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * The service is exported so system UI (lock screen, quick settings player, Bluetooth) can connect. Recording
     * titles are personal, so other apps can't connect a controller and read them. Trusted means this app, the
     * system, or an app holding MEDIA_CONTENT_CONTROL.
     *
     * This doesn't cover media button intents, which any app can send to play or pause without connecting.
     * [RecordingGuardPlayer] still blocks those while recording.
     */
    private class TrustedControllersOnly : MediaSession.Callback {
        @OptIn(UnstableApi::class) // ControllerInfo.isTrusted.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Before Android 9 every platform MediaController reports this placeholder package and can't be
            // verified, which would also lock out Bluetooth and watch bridges. There, availability wins.
            val legacyPlatform = Build.VERSION.SDK_INT < Build.VERSION_CODES.P &&
                controller.packageName == LEGACY_CONTROLLER_PACKAGE
            return if (controller.isTrusted || legacyPlatform) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.reject()
            }
        }
    }

    private companion object {
        const val LEGACY_CONTROLLER_PACKAGE = "android.media.session.MediaController"
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
