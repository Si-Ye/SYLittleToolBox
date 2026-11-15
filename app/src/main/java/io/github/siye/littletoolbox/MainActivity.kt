package io.github.siye.littletoolbox

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat

private const val OPEN_QUICK_NOTE = "io.github.siye.littletoolbox.OPEN_QUICK_NOTE"
internal const val QUICK_NOTE_ID = "quick-note"
internal const val TEXT_STATS_ID = "text-stats"

class MainActivity : ComponentActivity() {
    private val toolbox: ToolboxState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ToolboxApp(toolbox) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            OPEN_QUICK_NOTE -> toolbox.openQuickNote()
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> handleShare(intent)
        }
    }

    private fun handleShare(intent: Intent) {
        val type = intent.type.orEmpty()
        if (type == "text/plain") {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            intent.removeExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrEmpty()) toolbox.shareText(text)
            return
        }

        val tool = when {
            type.startsWith("image/") -> "image"
            type.startsWith("video/") -> "audio"
            else -> return
        }
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        val clips = intent.clipData?.let { data ->
            List(data.itemCount) { index -> data.getItemAt(index).uri }
        }.orEmpty()
        val uris = (streams + clips).filterNotNull().filter { it.scheme == "content" }.distinct()
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.clipData = null
        if (uris.isNotEmpty()) toolbox.shareMedia(tool, uris)
    }
}

internal fun quickNoteIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(OPEN_QUICK_NOTE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun pinQuickNote(context: Context): Boolean {
    val manager = context.getSystemService(ShortcutManager::class.java)
    if (manager?.isRequestPinShortcutSupported != true) return false
    val shortcut = ShortcutInfo.Builder(context, QUICK_NOTE_ID)
        .setShortLabel(context.getString(R.string.quick_note))
        .setLongLabel(context.getString(R.string.quick_note_long))
        .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
        .setIntent(quickNoteIntent(context))
        .build()
    return manager.requestPinShortcut(shortcut, null)
}

class QuickNoteTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val launch = quickNoteIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            launchOnLegacyAndroid(launch)
        }
    }

    // The PendingIntent overload was introduced in Android 14.
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchOnLegacyAndroid(intent: Intent) {
        startActivityAndCollapse(intent)
    }
}
