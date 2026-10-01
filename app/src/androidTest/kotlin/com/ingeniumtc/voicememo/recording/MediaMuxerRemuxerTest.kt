package com.ingeniumtc.voicememo.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real MediaExtractor and MediaMuxer, which Robolectric can't. The fixture is 20s of AAC-LC in
 * ADTS (no CRC), the same framing MediaRecorder writes.
 */
@RunWith(AndroidJUnit4::class)
class MediaMuxerRemuxerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var dir: File
    private lateinit var storage: RecordingStorage
    private lateinit var adts: ByteArray

    @Before
    fun setUp() {
        dir = File(instrumentation.targetContext.cacheDir, "remux-test").apply {
            deleteRecursively()
            mkdirs()
        }
        storage = RecordingStorage(dir, MediaMuxerRemuxer())
        adts = instrumentation.context.assets.open("tone.aac").use { it.readBytes() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun remuxesAdtsToM4aWithMatchingDurationAndDeletesTheSource() {
        val m4a = storage.commit(partial(adts))!!
        assertEquals("2026-10-01_09-05-00.m4a", m4a.name)
        assertEquals(listOf(m4a.name), dir.list()!!.toList())
        assertEquals(20_000_000.0, durationUs(m4a).toDouble(), 100_000.0)
    }

    @Test
    fun truncatedLastFrameIsTreatedAsComplete() {
        // A kill mid-write leaves half a frame at the end.
        val m4a = storage.commit(partial(adts.copyOf(adts.size - 100)))!!
        assertEquals(listOf(m4a.name), dir.list()!!.toList())
    }

    @Test
    fun corruptFrameMidStreamKeepsTheRawAacNextToTheM4a() {
        val corrupt = adts.copyOf()
        val frame = nextFrameStart(corrupt, corrupt.size / 3)
        corrupt[frame] = 0
        corrupt[frame + 1] = 0
        val m4a = storage.commit(partial(corrupt))!!
        // The extractor reports a clean end of stream at the bad frame, so the m4a alone would lose the rest.
        assertEquals(listOf("2026-10-01_09-05-00.aac", m4a.name), dir.list()!!.sorted())
        assertEquals(corrupt.size.toLong(), File(dir, "2026-10-01_09-05-00.aac").length())
    }

    @Test
    fun emptyFileIsDiscarded() {
        assertNull(storage.commit(partial(ByteArray(0))))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun garbageIsKeptAsRawAacRatherThanDeleted() {
        // The extractor can't open it, which is a generic failure, not proof of no audio.
        val garbage = ByteArray(4_096) { (it * 31).toByte() }
        val kept = storage.commit(partial(garbage))!!
        assertEquals("2026-10-01_09-05-00.aac", kept.name)
        assertEquals(garbage.size.toLong(), kept.length())
    }

    private fun partial(bytes: ByteArray): File =
        storage.newPartialFile(LocalDateTime.of(2026, 10, 1, 9, 5, 0)).apply { writeBytes(bytes) }

    private fun durationUs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)
        } finally {
            extractor.release()
        }
    }

    /** Walks ADTS frame lengths from the start to the first frame boundary at or after [from]. */
    private fun nextFrameStart(bytes: ByteArray, from: Int): Int {
        var pos = 0
        while (pos < from) {
            val length = ((bytes[pos + 3].toInt() and 0x03) shl 11) or
                ((bytes[pos + 4].toInt() and 0xFF) shl 3) or
                ((bytes[pos + 5].toInt() and 0xE0) ushr 5)
            pos += length
        }
        return pos
    }
}
