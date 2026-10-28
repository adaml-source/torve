package com.torve.android.ui.replays

import android.content.Context
import android.content.Intent
import android.net.Uri

private val fullscreenYouTubeTvPlayers = listOf(
    "org.smarttube.stable", "org.smarttube.beta",
    "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
)

/** A TV highlight must open a TV player, never a web browser or embedded popup. */
internal fun replayTvYouTubeIntents(videoId: String, preferredPackage: String? = null): List<Intent> {
    val id = videoId.trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return emptyList()
    val url = Uri.Builder().scheme("https").authority("www.youtube.com").path("watch")
        .appendQueryParameter("v", id).build()
    return (listOfNotNull(preferredPackage?.takeIf { it in fullscreenYouTubeTvPlayers }) + fullscreenYouTubeTvPlayers)
        .distinct().map { player ->
            Intent(Intent.ACTION_VIEW, url).setPackage(player)
                .putExtra("finish_on_ended", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

internal fun launchReplayTvYouTube(context: Context, videoId: String): Boolean {
    val query = replayTvYouTubeIntents(videoId).firstOrNull() ?: return false
    val preferred = Intent(query).setPackage(null).resolveActivity(context.packageManager)?.packageName
    for (intent in replayTvYouTubeIntents(videoId, preferred)) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return true
    }
    return false
}
