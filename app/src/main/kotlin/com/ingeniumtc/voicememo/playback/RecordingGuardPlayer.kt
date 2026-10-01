package com.ingeniumtc.voicememo.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Refuses to start playback while a recording is running, or the microphone would pick it up. Lives in the
 * player rather than the UI because play can also come from the media notification, the lock screen, a headset
 * button or Bluetooth, all of which reach the player through the session without touching the app's UI.
 */
@OptIn(UnstableApi::class) // ForwardingPlayer.
class RecordingGuardPlayer(player: Player, private val isRecording: () -> Boolean) : ForwardingPlayer(player) {
    override fun play() {
        if (!isRecording()) super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady || !isRecording()) super.setPlayWhenReady(playWhenReady)
    }
}
