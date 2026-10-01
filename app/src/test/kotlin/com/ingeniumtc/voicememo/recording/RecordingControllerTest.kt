package com.ingeniumtc.voicememo.recording

import app.cash.turbine.test
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val recorders = mutableListOf<FakeRecorder>()
    private var nowMs = 1_000L

    private fun TestScope.controller(configure: FakeRecorder.() -> Unit = {}): Pair<RecordingController, File> {
        val dir = tmp.newFolder("recordings")
        val controller = RecordingController(
            storage = RecordingStorage(dir),
            recorderFactory = { FakeRecorder().apply(configure).also { recorders += it } },
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { nowMs },
            now = { LocalDateTime.of(2026, 10, 1, 11, 30, 5) }
        )
        return controller to dir
    }

    @Test
    fun `start then stop saves an m4a with the elapsed duration`() = runTest {
        val (controller, dir) = controller()
        controller.events.test {
            controller.start()
            runCurrent()
            assertEquals(
                RecordingState.Active(isPaused = false, accumulatedMs = 0, resumedAt = 1_000),
                controller.state.value
            )
            assertTrue(File(dir, "2026-10-01_11-30-05.m4a.part").exists())

            nowMs += 4_200
            controller.stop()
            runCurrent()

            val saved = awaitItem() as RecordingEvent.Saved
            assertEquals("2026-10-01_11-30-05.m4a", saved.file.name)
            assertEquals(4_200, saved.durationMs)
            assertTrue(saved.file.exists())
            assertFalse(File(dir, "2026-10-01_11-30-05.m4a.part").exists())
        }
        assertEquals(RecordingState.Idle, controller.state.value)
        assertTrue(recorders.single().released)
    }

    @Test
    fun `paused time is excluded from duration`() = runTest {
        val (controller, _) = controller()
        controller.events.test {
            controller.start()
            runCurrent()
            nowMs += 2_000
            controller.pause()
            runCurrent()
            assertEquals(true, (controller.state.value as RecordingState.Active).isPaused)

            nowMs += 60_000 // paused, should not count
            controller.resume()
            runCurrent()
            nowMs += 3_000
            controller.stop()
            runCurrent()

            assertEquals(5_000, (awaitItem() as RecordingEvent.Saved).durationMs)
        }
        assertEquals(listOf("start", "pause", "resume", "stop", "release"), recorders.single().calls)
    }

    @Test
    fun `second start while recording is ignored`() = runTest {
        val (controller, _) = controller()
        controller.start()
        controller.start()
        runCurrent()
        assertEquals(1, recorders.size)
    }

    @Test
    fun `failure to start releases the recorder and deletes the file`() = runTest {
        val (controller, dir) = controller { failOnStart = true }
        controller.events.test {
            controller.start()
            runCurrent()
            assertEquals(RecordingEvent.Failed(RecordingEvent.Reason.CouldNotStart), awaitItem())
        }
        assertEquals(RecordingState.Idle, controller.state.value)
        assertTrue(recorders.single().released)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `stop with no captured audio discards the file`() = runTest {
        val (controller, dir) = controller { failOnStop = true }
        controller.events.test {
            controller.start()
            runCurrent()
            controller.stop()
            runCurrent()
            assertEquals(RecordingEvent.Failed(RecordingEvent.Reason.TooShort), awaitItem())
        }
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `recorder error mid-recording still saves when the file can be finalized`() = runTest {
        val (controller, _) = controller()
        controller.events.test {
            controller.start()
            runCurrent()
            nowMs += 1_500
            recorders.single().onError?.invoke()
            runCurrent()
            assertEquals(1_500, (awaitItem() as RecordingEvent.Saved).durationMs)
        }
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    @Test
    fun `recorder error that corrupts the file reports RecorderError`() = runTest {
        val (controller, _) = controller { failOnStop = true }
        controller.events.test {
            controller.start()
            runCurrent()
            recorders.single().onError?.invoke()
            runCurrent()
            assertEquals(RecordingEvent.Failed(RecordingEvent.Reason.RecorderError), awaitItem())
        }
    }

    @Test
    fun `pause on a recorder in its error state ends the session instead of crashing`() = runTest {
        val (controller, _) = controller { failOnPause = true }
        controller.events.test {
            controller.start()
            runCurrent()
            controller.pause()
            runCurrent()
            assertTrue(awaitItem() is RecordingEvent.Saved)
        }
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    @Test
    fun `meter survives a throwing recorder`() = runTest {
        val (controller, _) = controller { failOnAmplitude = true }
        controller.start()
        runCurrent()
        advanceTimeBy(RecordingController.METER_INTERVAL_MS * 3)
        assertEquals(0f, controller.amplitude.value)
        assertTrue(controller.state.value is RecordingState.Active)
    }

    @Test
    fun `stale error from a previous recorder does not end the next session`() = runTest {
        val (controller, _) = controller()
        controller.start()
        runCurrent()
        controller.stop()
        runCurrent()
        controller.start()
        runCurrent()
        recorders.first().onError?.invoke()
        runCurrent()
        assertTrue(controller.state.value is RecordingState.Active)
    }

    @Test
    fun `amplitude is metered while recording and zeroed on pause`() = runTest {
        val (controller, _) = controller { amplitude = 32_767 }
        controller.start()
        runCurrent()
        advanceTimeBy(RecordingController.METER_INTERVAL_MS + 1)
        assertEquals(1f, controller.amplitude.value)

        controller.pause()
        runCurrent()
        assertEquals(0f, controller.amplitude.value)
        val readsWhilePaused = recorders.single().amplitudeReads
        advanceTimeBy(1_000)
        assertEquals(readsWhilePaused, recorders.single().amplitudeReads)
    }

    @Test
    fun `abandoned partial files are cleaned up but finished recordings are kept`() = runTest {
        val (controller, dir) = controller()
        File(dir, "old.m4a.part").writeText("x")
        File(dir, "kept.m4a").writeText("x")
        controller.deleteAbandonedRecordings()
        runCurrent()
        assertEquals(listOf("kept.m4a"), dir.list()!!.toList())
    }

    @Test
    fun `normalize maps silence to 0 and full scale to 1 on a dB curve`() {
        assertEquals(0f, RecordingController.normalize(0))
        assertEquals(1f, RecordingController.normalize(32_767))
        // -20 dB is 60% of the way up a 50 dB range.
        assertEquals(0.6f, RecordingController.normalize(3_277), 0.01f)
    }

    private class FakeRecorder : AudioRecorder {
        var failOnStart = false
        var failOnStop = false
        var failOnPause = false
        var failOnAmplitude = false
        var amplitude = 0
        var amplitudeReads = 0
        var released = false
        val calls = mutableListOf<String>()

        override var onError: (() -> Unit)? = null

        override fun start(output: File) {
            if (failOnStart) throw IllegalStateException("mic busy")
            output.writeText("audio") // MediaRecorder creates the file on start.
            calls += "start"
        }

        override fun pause() {
            if (failOnPause) throw IllegalStateException("recorder in error state")
            calls += "pause"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun stop() {
            if (failOnStop) throw RuntimeException("stop failed")
            calls += "stop"
        }

        override fun release() {
            calls += "release"
            released = true
        }

        override fun maxAmplitude(): Int {
            amplitudeReads++
            if (failOnAmplitude) throw IllegalStateException("recorder in error state")
            return amplitude
        }
    }
}
