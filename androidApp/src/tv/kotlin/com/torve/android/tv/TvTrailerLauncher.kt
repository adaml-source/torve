package com.torve.android.tv

import android.content.Context
import android.content.Intent
import android.net.Uri

private val singleVideoTrailerPlayers = listOf("org.smarttube.stable", "org.smarttube.beta")

/** Play one trailer, then close the external player instead of playing recommendations. */
fun launchTvTrailer(context: Context, youtubeKey: String): Boolean {
    if (youtubeKey.isBlank()) return false

    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.Builder()
            .scheme("https")
            .authority("www.youtube.com")
            .path("watch")
            .appendQueryParameter("v", youtubeKey.trim())
            .build(),
    )
        // SmartTube reads this Boolean extra and overrides autoplay for this video only.
        // https://github.com/yuliskov/SmartTube/blob/master/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/utils/IntentExtractor.java
        .putExtra("finish_on_ended", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val packageManager = context.packageManager
    val defaultPackage = intent.resolveActivity(packageManager)?.packageName
    val preferredPackage = (listOfNotNull(defaultPackage) + singleVideoTrailerPlayers)
        .distinct()
        .firstOrNull { packageName ->
            packageName in singleVideoTrailerPlayers &&
                Intent(intent).setPackage(packageName).resolveActivity(packageManager) != null
        }
    // Prefer a player that supports closing at the end, including when Android would
    // otherwise show a chooser. Keep the existing YouTube-link fallback on other TVs.
    preferredPackage?.let(intent::setPackage)

    return runCatching { context.startActivity(intent) }.isSuccess
}
