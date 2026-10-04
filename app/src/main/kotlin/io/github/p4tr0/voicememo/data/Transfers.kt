package io.github.p4tr0.voicememo.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How an import or export ended, for the snackbar. */
sealed interface TransferResult {
    data class Imported(val summary: ImportSummary) : TransferResult

    data class Exported(val summary: ExportSummary) : TransferResult

    /** The picked folder couldn't be listed (access lost, the storage went away, or it never finished loading). */
    data object FolderUnreadable : TransferResult

    /** Files shared into the app couldn't be read at all. */
    data object ShareFailed : TransferResult
}

/** Copying recordings in from, or out to, a folder the user picked. */
interface FolderTransfers {
    /** True while any import or export runs. */
    val busy: StateFlow<Boolean>

    /** One per finished import or export. Buffered, since a share can cold-start the app before the UI exists. */
    val results: Flow<TransferResult>

    /** Call right away from the picker's result, while its grant to [folder] is still held. */
    fun importFolder(folder: Uri)

    /**
     * Call right away from the picker's result, while its grant to [folder] is still held. [recordings] is read
     * once the export has started, so it may wait for the library to load.
     */
    fun exportTo(folder: Uri, recordings: suspend () -> List<Recording>)
}

/**
 * Runs transfers on the app scope and off the main thread, so they finish even if the screen goes away.
 *
 * The picker's grant to a folder belongs to the activity and ends when it finishes, so for as long as a transfer
 * uses the folder the grant is made persistable, and released again when the transfer ends. Grants left behind by
 * a process killed mid-transfer are released at the next launch: the app never keeps folder access.
 */
class Transfers(
    private val resolver: ContentResolver,
    private val importer: Importer,
    private val exporter: Exporter,
    private val scope: CoroutineScope
) : FolderTransfers {
    private val running = MutableStateFlow(0)
    override val busy: StateFlow<Boolean> = running.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _results = Channel<TransferResult>(Channel.BUFFERED)
    override val results: Flow<TransferResult> = _results.receiveAsFlow()

    // How many running transfers use each folder, so one finishing doesn't release another's grant.
    private val held = mutableMapOf<Uri, Int>()

    init {
        scope.launch(Dispatchers.IO) {
            synchronized(held) {
                (resolver.persistedUriPermissions.map { it.uri } - held.keys).forEach { uri ->
                    runCatching { resolver.releasePersistableUriPermission(uri, ACCESS) }
                }
            }
        }
    }

    /** Recordings shared from another app. [describe] queries the sender, so it runs off the main thread too. */
    fun importShared(describe: () -> List<AudioImport>) = run(folder = null, onError = TransferResult.ShareFailed) {
        val items = describe()
        if (items.isEmpty()) null else TransferResult.Imported(importer.import(items))
    }

    override fun importFolder(folder: Uri) = run(folder, onError = TransferResult.FolderUnreadable) {
        val items = DocumentTree(resolver, folder).audioFiles()
        if (items == null) TransferResult.FolderUnreadable else TransferResult.Imported(importer.import(items))
    }

    override fun exportTo(folder: Uri, recordings: suspend () -> List<Recording>) =
        run(folder, onError = TransferResult.FolderUnreadable) {
            exporter.export(recordings(), DocumentTree(resolver, folder).exportTarget)
                ?.let { TransferResult.Exported(it) }
                ?: TransferResult.FolderUnreadable
        }

    private fun run(folder: Uri?, onError: TransferResult, block: suspend () -> TransferResult?) {
        folder?.let(::hold)
        running.update { it + 1 }
        scope.launch(Dispatchers.IO) {
            try {
                val result = try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // E.g. a picker result that isn't a folder this app can open.
                    Log.w(TAG, "Transfer failed", e)
                    onError
                }
                result?.let { _results.send(it) }
            } finally {
                folder?.let(::release)
                running.update { it - 1 }
            }
        }
    }

    /** On the caller's thread: it must happen before the picker's activity can finish and take its grant along. */
    private fun hold(folder: Uri) = synchronized(held) {
        val count = held[folder] ?: 0
        if (count == 0) {
            // Without it the transfer still runs on the activity's grant, as long as that lasts.
            runCatching { resolver.takePersistableUriPermission(folder, ACCESS) }
                .onFailure { Log.w(TAG, "Could not hold the folder grant", it) }
        }
        held[folder] = count + 1
    }

    private fun release(folder: Uri) = synchronized(held) {
        val count = (held[folder] ?: 1) - 1
        if (count > 0) {
            held[folder] = count
        } else {
            held -= folder
            runCatching { resolver.releasePersistableUriPermission(folder, ACCESS) }
        }
    }

    private companion object {
        const val TAG = "Transfers"
        const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
