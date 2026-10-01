package com.ingeniumtc.voicememo.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ingeniumtc.voicememo.data.Recording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What is loaded in the player. [currentId] is a [Recording.id], or null when nothing is loaded. The position is
 * separate ([Playback.positionMs]) because it changes every tick and only the seek bar needs it.
 */
data class PlaybackState(val currentId: String? = null, val isPlaying: Boolean = false, val durationMs: Long = 0)

interface Playback {
    val state: StateFlow<PlaybackState>

    /** Updates every few hundred ms while playing. */
    val positionMs: StateFlow<Long>

    /** Plays [recording], or pauses/resumes it if it is already the current one. [title] shows in the notification. */
    fun toggle(recording: Recording, title: String)

    fun seekTo(positionMs: Long)

    fun pause()

    /** Unloads [recordingId] if it is current, e.g. before deleting its file. */
    fun stop(recordingId: String)

    fun release()
}

/**
 * Drives [PlaybackService] through a [MediaController]. Must be created and used on the main thread, which is
 * the controller's application thread; [scope] must dispatch there too.
 */
class MediaControllerPlayback(context: Context, private val scope: CoroutineScope) : Playback {
    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))
    ).buildAsync()

    // Null if the connection failed (service crashed or refused): playback is then unavailable, not a crash.
    private val controller = scope.async {
        try {
            future.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not connect to the playback service", e)
            null
        }
    }

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private var ticker: Job? = null

    init {
        scope.launch {
            val c = controller.await() ?: return@launch
            c.addListener(
                object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        publish(player)
                        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) updateTicker(player)
                    }
                }
            )
            publish(c)
            updateTicker(c)
        }
    }

    override fun toggle(recording: Recording, title: String) = command { c ->
        if (c.currentMediaItem?.mediaId == recording.id) {
            when {
                c.isPlaying -> c.pause()

                c.playbackState == Player.STATE_ENDED -> {
                    c.seekTo(0)
                    c.play()
                }

                else -> {
                    if (c.playbackState == Player.STATE_IDLE) c.prepare() // After an error.
                    c.play()
                }
            }
        } else {
            c.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(recording.id)
                    .setUri(Uri.fromFile(recording.file))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                    .build()
            )
            c.prepare()
            c.play()
        }
    }

    override fun seekTo(positionMs: Long) = command { c ->
        c.seekTo(positionMs)
        publish(c)
    }

    override fun pause() = command { it.pause() }

    override fun stop(recordingId: String) = command { c ->
        if (c.currentMediaItem?.mediaId == recordingId) {
            c.stop()
            c.clearMediaItems()
        }
    }

    override fun release() {
        ticker?.cancel()
        MediaController.releaseFuture(future)
    }

    private fun command(block: (MediaController) -> Unit) {
        scope.launch { controller.await()?.let(block) }
    }

    /** Position only changes continuously while playing, so poll it then and not otherwise. */
    private fun updateTicker(player: Player) {
        ticker?.cancel()
        if (!player.isPlaying) return
        ticker = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                publish(player)
            }
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        // StateFlow skips equal values, so ticks only reach position collectors.
        _state.value = PlaybackState(
            currentId = item?.mediaId,
            isPlaying = player.isPlaying,
            durationMs = player.duration.takeIf { it > 0 } ?: 0
        )
        _positionMs.value = player.currentPosition.coerceAtLeast(0)
    }

    private companion object {
        const val TAG = "Playback"
        const val POSITION_TICK_MS = 200L
    }
}
