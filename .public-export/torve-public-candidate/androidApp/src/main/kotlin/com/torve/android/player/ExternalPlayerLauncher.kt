package com.torve.android.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Launches external video players via Android Intents.
 */
object ExternalPlayerLauncher {

    enum class ExternalPlayer(
        val label: String,
        val packageName: String?,
    ) {
        VLC("VLC", "org.videolan.vlc"),
        MX_PLAYER("MX Player", "com.mxtech.videoplayer.ad"),
        MX_PLAYER_PRO("MX Player Pro", "com.mxtech.videoplayer.pro"),
        NPLAYER("nPlayer", "com.newin.nplayer.pro"),
        OUTPLAYER("Outplayer", null), // iOS only
        INFUSE("Infuse", null), // iOS only
        JUST_PLAYER("Just Player", "com.brouken.player"),
        NOVA("Nova Player", "org.courville.nova"),
        ;

        companion object {
            val androidPlayers = listOf(VLC, MX_PLAYER, MX_PLAYER_PRO, NPLAYER, JUST_PLAYER, NOVA)
        }
    }

    /**
     * Play a URL in a specific external player.
     * Returns true if the player was launched, false if not installed.
     */
    fun playInExternalPlayer(
        context: Context,
        url: String,
        title: String,
        player: ExternalPlayer,
    ): Boolean {
        val pkg = player.packageName ?: return false
        if (!isPlayerInstalled(context, pkg)) return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            setPackage(pkg)
            putExtra("title", title)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Play a URL using the system's default video player chooser.
     */
    fun playWithChooser(context: Context, url: String, title: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            putExtra("title", title)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Play with...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Get list of installed external players.
     */
    fun getInstalledPlayers(context: Context): List<ExternalPlayer> {
        return ExternalPlayer.androidPlayers.filter { player ->
            player.packageName != null && isPlayerInstalled(context, player.packageName)
        }
    }

    /**
     * Copy a URL to the clipboard.
     */
    fun copyUrl(context: Context, url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
    }

    /**
     * Share a URL via Android share sheet.
     */
    fun shareUrl(context: Context, url: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share stream")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun isPlayerInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
