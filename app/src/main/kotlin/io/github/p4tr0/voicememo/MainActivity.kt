package io.github.p4tr0.voicememo

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.p4tr0.voicememo.data.AudioImport
import io.github.p4tr0.voicememo.ui.home.HomeRoute
import io.github.p4tr0.voicememo.ui.theme.VoiceMemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again after rotation: the share was already handled by the first creation.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            VoiceMemoTheme {
                HomeRoute()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Recordings shared from another app (e.g. a recorder's "Share" on several files). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = sharedUris(intent)
        // Reads start right away, while the sender's read grant (held by this app) is fresh.
        val resolver = applicationContext.contentResolver
        (application as VoiceMemoApp).container.importShared { uris.map { describe(resolver, it) } }
        // Handled: a later recreation (e.g. from recents) must not import the same files again.
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val fromExtras = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
        } else {
            listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            )
        }
        // Some senders only fill ClipData.
        val fromClip = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
        return (fromExtras + fromClip.orEmpty()).distinct()
    }

    private fun describe(contentResolver: ContentResolver, uri: Uri): AudioImport {
        var name: String? = null
        var modified: Long? = null
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                        ?.let { i -> if (!c.isNull(i)) modified = c.getLong(i) }
                }
            }
        }
        if (name == null && uri.scheme == "file") name = uri.lastPathSegment
        return AudioImport(
            displayName = name,
            mimeType = contentResolver.getType(uri),
            lastModified = modified,
            open = { contentResolver.openInputStream(uri) }
        )
    }
}
