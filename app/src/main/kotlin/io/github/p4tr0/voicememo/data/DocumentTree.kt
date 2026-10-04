package io.github.p4tr0.voicememo.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A folder picked with the system file picker (`ACTION_OPEN_DOCUMENT_TREE`). [Transfers] holds the grant only
 * while it uses the folder. All calls do I/O and may block; never on the main thread.
 */
class DocumentTree(private val resolver: ContentResolver, private val treeUri: Uri) {
    private val rootId = DocumentsContract.getTreeDocumentId(treeUri)

    /** Every audio file the app can play in the folder and its subfolders, or null if the folder can't be read. */
    fun audioFiles(): List<AudioImport>? {
        val found = mutableListOf<AudioImport>()
        val folders = ArrayDeque(listOf(rootId))
        val seen = mutableSetOf(rootId)
        while (folders.isNotEmpty()) {
            val folderId = folders.removeFirst()
            // An incomplete subfolder would make a partial import look complete, so it fails the whole import.
            val children = children(folderId) ?: return null
            for (child in children) {
                if (child.mimeType == Document.MIME_TYPE_DIR) {
                    if (seen.add(child.id)) folders += child.id
                    continue
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                val item = AudioImport(
                    displayName = child.name,
                    mimeType = child.mimeType,
                    lastModified = child.lastModified,
                    open = { resolver.openInputStream(uri) }
                )
                if (Importer.extensionOf(item) != null) found += item
            }
        }
        return found
    }

    val exportTarget: ExportTarget = object : ExportTarget {
        /** The documents there before the export: a provider handing one back from [create] must not touch it. */
        private var existingIds = emptySet<String>()

        override fun existingFiles(): Map<String, Long>? {
            val files = children(rootId)?.filter { it.mimeType != Document.MIME_TYPE_DIR } ?: return null
            existingIds = files.mapTo(HashSet()) { it.id }
            return files.filter { it.name != null }.associate { it.name!! to (it.size ?: -1L) }
        }

        override fun create(name: String): ExportFile? {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            val uri = DocumentsContract.createDocument(resolver, parent, mimeTypeOf(name), name) ?: return null
            if (DocumentsContract.getDocumentId(uri) in existingIds) {
                // createDocument should always make a new document; writing here would overwrite the user's file.
                Log.w(TAG, "Folder returned an existing document for $name, skipping it")
                return null
            }
            return object : ExportFile {
                override fun write(source: File) {
                    val descriptor = resolver.openFileDescriptor(uri, "w") ?: throw IOException("Could not open $name")
                    descriptor.use {
                        FileOutputStream(it.fileDescriptor).use { output ->
                            source.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
                            // Not every provider is backed by a local file (a pipe can't be synced).
                            runCatching { output.fd.sync() }
                        }
                        // A provider streaming through a pipe reports its own write errors only here.
                        it.checkError()
                    }
                    val written = size(uri)
                    if (written != null && written != source.length()) {
                        throw IOException("$name has $written of ${source.length()} bytes")
                    }
                }

                override fun delete() {
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                        .onFailure { Log.w(TAG, "Could not remove a failed export", it) }
                }
            }
        }
    }

    private class Child(
        val id: String,
        val name: String?,
        val mimeType: String?,
        val lastModified: Long?,
        val size: Long?
    )

    /** Null if the folder can't be listed, or its provider is still loading it (cloud folders) after a wait. */
    private fun children(folderId: String): List<Child>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        repeat(LOADING_ATTEMPTS) {
            try {
                val result: List<Child>? = resolver.query(uri, PROJECTION, null, null, null)?.use { c ->
                    if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) return@use null
                    buildList<Child> {
                        while (c.moveToNext()) {
                            add(
                                Child(
                                    id = c.getString(0),
                                    name = c.getString(1),
                                    mimeType = c.getString(2),
                                    lastModified = if (c.isNull(3)) null else c.getLong(3),
                                    size = if (c.isNull(4)) null else c.getLong(4)
                                )
                            )
                        }
                    }
                }
                if (result != null) return result
            } catch (e: Exception) {
                // Revoked grants and providers that went away (an unmounted SD card) throw here.
                Log.w(TAG, "Could not list folder", e)
                return null
            }
            Thread.sleep(LOADING_WAIT_MS)
        }
        Log.w(TAG, "Folder was still loading, giving up")
        return null
    }

    private fun size(document: Uri): Long? = runCatching {
        resolver.query(document, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private companion object {
        const val TAG = "DocumentTree"
        const val BUFFER_SIZE = 64 * 1024

        // Up to 10 seconds for a cloud provider to fill a folder in.
        const val LOADING_ATTEMPTS = 20
        const val LOADING_WAIT_MS = 500L

        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE
        )

        /**
         * From the same table providers check names against, so they keep the name as it is instead of adding an
         * extension they think the type needs.
         */
        fun mimeTypeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
    }
}
