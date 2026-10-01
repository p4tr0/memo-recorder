package io.github.p4tr0.voicememo.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RecordingGuardPlayerTest {
    private val exoPlayer = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
    private var recording = false
    private val player = RecordingGuardPlayer(exoPlayer) { recording }

    @After
    fun tearDown() {
        exoPlayer.release()
    }

    @Test
    fun `play is refused while recording, whichever way it is asked for`() {
        recording = true
        player.play()
        assertFalse(exoPlayer.playWhenReady)
        player.setPlayWhenReady(true)
        assertFalse(exoPlayer.playWhenReady)
    }

    @Test
    fun `pausing still works while recording`() {
        player.play()
        recording = true
        player.pause()
        assertFalse(exoPlayer.playWhenReady)
    }

    @Test
    fun `play works when not recording`() {
        player.play()
        assertTrue(exoPlayer.playWhenReady)
    }
}
