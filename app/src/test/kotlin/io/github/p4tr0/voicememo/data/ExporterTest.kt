package io.github.p4tr0.voicememo.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Robolectric for android.util.Log, which the exporter logs failures with.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ExporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var library: File
    private lateinit var folder: File
    private val exporter = Exporter(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        library = tmp.newFolder("recordings")
        folder = tmp.newFolder("export")
    }

    @Test
    fun `files are named after their title, or keep their date name when untitled`() = runTest {
        val titled = recording("2026-10-01_09-05-00.m4a", "Grocery list")
        val untitled = recording("2026-10-02_09-05-00.aac", null)

        val summary = exporter.export(listOf(titled, untitled), FolderTarget(folder))

        assertEquals(ExportSummary(exported = 2, alreadyThere = 0, failed = 0), summary)
        assertEquals(setOf("Grocery list.m4a", "2026-10-02_09-05-00.aac"), names())
        assertEquals(titled.file.readText(), File(folder, "Grocery list.m4a").readText())
    }

    @Test
    fun `characters folders refuse are replaced, and a title with nothing usable falls back to the date`() {
        assertEquals(
            "Notes_ part 1_2 _draft_.m4a",
            Exporter.exportName(recording("2026-10-01_09-05-00.m4a", "Notes: part 1/2 \"draft\""))
        )
        assertEquals("2026-10-01_09-05-00.m4a", Exporter.exportName(recording("2026-10-01_09-05-00.m4a", " .. ")))
    }

    @Test
    fun `a long title is cut to fit a file name without splitting a character`() {
        val name = Exporter.exportName(recording("2026-10-01_09-05-00.m4a", "żółw🐢".repeat(40)))
        assertTrue(name.endsWith(".m4a"))
        assertTrue(name.removeSuffix(".m4a").toByteArray().size <= 200)
        assertTrue(name.none { it == '�' } && !name.removeSuffix(".m4a").last().isHighSurrogate())
    }

    @Test
    fun `exporting again skips what is already there and adds only what's new`() = runTest {
        val first = recording("2026-10-01_09-05-00.m4a", "Song idea")
        exporter.export(listOf(first), FolderTarget(folder))
        val second = recording("2026-10-02_09-05-00.m4a", "Grocery list")

        val summary = exporter.export(listOf(first, second), FolderTarget(folder))

        assertEquals(ExportSummary(exported = 1, alreadyThere = 1, failed = 0), summary)
        assertEquals(setOf("Song idea.m4a", "Grocery list.m4a"), names())
    }

    @Test
    fun `a copy the folder renamed, or one in another case, is still recognized as already there`() = runTest {
        val a = recording("2026-10-01_09-05-00.m4a", "Meeting", content = "first meeting")
        val b = recording("2026-10-02_09-05-00.m4a", "Meeting", content = "the second meeting")
        val c = recording("2026-10-03_09-05-00.m4a", "Notes", content = "notes")
        exporter.export(listOf(a, b), FolderTarget(folder))
        // A FAT SD card keeps whatever case it was written in.
        c.file.copyTo(File(folder, "NOTES.M4A"))

        val summary = exporter.export(listOf(a, b, c), FolderTarget(folder))

        assertEquals(ExportSummary(exported = 0, alreadyThere = 3, failed = 0), summary)
        assertEquals(setOf("Meeting.m4a", "Meeting (1).m4a", "NOTES.M4A"), names())
    }

    @Test
    fun `a different file under the same name is kept and the export gets another name`() = runTest {
        File(folder, "Song idea.m4a").writeText("someone else's song")
        val recording = recording("2026-10-01_09-05-00.m4a", "Song idea")

        val summary = exporter.export(listOf(recording), FolderTarget(folder))

        assertEquals(1, summary?.exported)
        assertEquals("someone else's song", File(folder, "Song idea.m4a").readText())
        assertEquals(recording.file.readText(), File(folder, "Song idea (1).m4a").readText())
    }

    @Test
    fun `two recordings with the same title and size in one export are both exported`() = runTest {
        val a = recording("2026-10-01_09-05-00.m4a", "Memo", content = "aaaa")
        val b = recording("2026-10-02_09-05-00.m4a", "Memo", content = "bbbb")

        val summary = exporter.export(listOf(a, b), FolderTarget(folder))

        assertEquals(2, summary?.exported)
        assertEquals(setOf("Memo.m4a", "Memo (1).m4a"), names())
    }

    @Test
    fun `a failed copy leaves no partial file and the rest still export`() = runTest {
        val broken = recording("2026-10-01_09-05-00.m4a", "Broken")
        val fine = recording("2026-10-02_09-05-00.m4a", "Fine")
        val missing = Recording(File(library, "gone.m4a"), "Gone", Instant.EPOCH, 1_000, 10)

        val summary = exporter.export(listOf(broken, missing, fine), FolderTarget(folder, failWriting = "Broken.m4a"))

        assertEquals(ExportSummary(exported = 1, alreadyThere = 0, failed = 2), summary)
        assertEquals(setOf("Fine.m4a"), names())
        assertTrue(broken.file.exists())
    }

    @Test
    fun `an unreadable folder exports nothing`() = runTest {
        val summary = exporter.export(listOf(recording("2026-10-01_09-05-00.m4a", "Memo")), FolderTarget(null))
        assertNull(summary)
    }

    private fun names() = folder.list().orEmpty().toSet()

    private fun recording(fileName: String, title: String?, content: String = "audio of $fileName"): Recording {
        val file = File(library, fileName).apply { writeText(content) }
        return Recording(file, title, Instant.EPOCH, 1_000, file.length())
    }

    /** Behaves like the storage provider: a taken name gets " (1)", " (2)" before the extension. */
    private class FolderTarget(private val dir: File?, private val failWriting: String? = null) : ExportTarget {
        override fun existingFiles(): Map<String, Long>? = dir?.listFiles()?.associate { it.name to it.length() }

        override fun create(name: String): ExportFile {
            val base = name.substringBeforeLast('.')
            val extension = name.substringAfterLast('.')
            var file = File(dir, name)
            var n = 1
            while (file.exists()) file = File(dir, "$base (${n++}).$extension")
            file.createNewFile()
            return object : ExportFile {
                override fun write(source: File) {
                    if (name == failWriting) {
                        file.writeText("half")
                        throw IOException("Disk full")
                    }
                    source.copyTo(file, overwrite = true)
                }

                override fun delete() {
                    file.delete()
                }
            }
        }
    }
}
