package com.torve.android.ui.panda

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.torve.android.R

/**
 * Human-readable labels for the stable ids returned by GET /api/v1/schema.
 *
 * The server returns plain id strings (e.g. "nzbgeek", "best_quality"); labels are
 * owned by the client so i18n stays client-side. New server-side ids degrade
 * gracefully — unknown ids render as a prettified version of the id itself.
 */

@Composable
fun labelForNzbIndexer(id: String): String = when (id) {
    "none" -> stringResource(R.string.panda_setup_usenet_indexer_none)
    "nzbgeek" -> "NZBgeek"
    "scenenzbs" -> "SceneNZBs"
    "dognzb" -> "DogNZB"
    "nzbplanet" -> "NZBPlanet"
    "custom" -> stringResource(R.string.panda_setup_usenet_indexer_custom)
    else -> prettifyId(id)
}

@Composable
fun labelForDownloadClient(id: String): String = when (id) {
    "none" -> stringResource(R.string.panda_setup_usenet_client_none)
    "nzbget" -> "NZBget"
    "sabnzbd" -> "SABnzbd"
    "premiumize" -> "Premiumize"
    "torbox" -> "TorBox"
    "alldebrid" -> "AllDebrid"
    else -> prettifyId(id)
}

@Composable
fun labelForUsenetProvider(id: String): String = when (id) {
    "none" -> stringResource(R.string.panda_setup_usenet_indexer_none)
    "easynews" -> stringResource(R.string.panda_setup_usenet_easynews)
    "generic" -> stringResource(R.string.panda_setup_usenet_generic)
    else -> prettifyId(id)
}

@Composable
fun labelForQuality(id: String): String = when (id) {
    "2160p" -> "4K (2160p)"
    "1080p" -> "1080p"
    "720p" -> "720p"
    "480p" -> "480p"
    else -> id
}

@Composable
fun labelForQualityProfile(id: String): String = when (id) {
    "balanced" -> stringResource(R.string.panda_quality_balanced)
    "best_quality" -> stringResource(R.string.panda_quality_best)
    "fast_start" -> stringResource(R.string.panda_quality_fast)
    "data_saver" -> stringResource(R.string.panda_quality_saver)
    else -> prettifyId(id)
}

@Composable
fun labelForLanguage(id: String): String = when (id) {
    "any" -> stringResource(R.string.panda_language_any)
    "english" -> "English"
    "german" -> "Deutsch"
    "spanish" -> "Español"
    "french" -> "Français"
    "italian" -> "Italiano"
    "portuguese" -> "Português"
    "turkish" -> "Türkçe"
    "japanese" -> "日本語"
    "korean" -> "한국어"
    "chinese" -> "中文"
    "hindi" -> "हिन्दी"
    "multi" -> "Multi"
    else -> prettifyId(id)
}

private fun prettifyId(id: String): String =
    id.replace("_", " ").replaceFirstChar { it.uppercase() }
