package com.torve.android.ui.community

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.presentation.apple.AppleCatalogueDetail
import com.torve.presentation.settings.SettingsViewModel
import org.koin.mp.KoinPlatform
import java.util.UUID

/** Save the supplied provider identity on the destination, including process restoration. */
fun NavHostController.openExtensionDetailIfNeeded(item: MediaItem, tv: Boolean = false, autoPlay: Boolean = false, season: Int? = null, episode: Int? = null): Boolean {
    val settings = KoinPlatform.getKoin().get<SettingsViewModel>().state.value
    val tmdb = item.tmdbId ?: item.id.extractTmdbIdOrNull()
    val imdb = item.imdbId ?: item.id.extractImdbIdOrNull()
    if (settings.tmdbCatalogueEnabled && (tmdb != null || imdb != null)) return false
    val key = UUID.randomUUID().toString()
    val seed = item.copy(tmdbId = tmdb, imdbId = imdb)
    currentBackStackEntry?.savedStateHandle?.set("extensionSeed:$key", AppleCatalogueDetail.encode(seed)) ?: return false
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    val episodeQuery = "&season=${season ?: -1}&episode=${episode ?: -1}"
    navigate((if (tv) "tv_details/$type/0?extension=$key&autoPlay=$autoPlay" else "detail/$type/0?extension=$key") + episodeQuery)
    return true
}

fun NavHostController.extensionDetailSeed(entry: NavBackStackEntry): String? {
    val key = entry.arguments?.getString("extension").orEmpty()
    if (key.isEmpty()) return null
    entry.savedStateHandle.get<String>("extensionSeed")?.let { return it }
    val seed = previousBackStackEntry?.savedStateHandle?.get<String>("extensionSeed:$key") ?: return null
    entry.savedStateHandle["extensionSeed"] = seed
    previousBackStackEntry?.savedStateHandle?.remove<String>("extensionSeed:$key")
    return seed
}

internal val tmdbHomeSections = setOf(
    com.torve.domain.model.HomeSection.HERO, com.torve.domain.model.HomeSection.ACTORS,
    com.torve.domain.model.HomeSection.DIRECTORS, com.torve.domain.model.HomeSection.STREAMING_SERVICES,
    com.torve.domain.model.HomeSection.TRENDING_MOVIES, com.torve.domain.model.HomeSection.TRENDING_TV,
    com.torve.domain.model.HomeSection.POPULAR_MOVIES, com.torve.domain.model.HomeSection.NOW_PLAYING,
    com.torve.domain.model.HomeSection.RECOMMENDED, com.torve.domain.model.HomeSection.TOP_RATED,
    com.torve.domain.model.HomeSection.HIDDEN_GEMS, com.torve.domain.model.HomeSection.BECAUSE_YOU_WATCHED,
    com.torve.domain.model.HomeSection.BECAUSE_YOU_WATCHED_MOVIES, com.torve.domain.model.HomeSection.BECAUSE_YOU_WATCHED_TV,
    com.torve.domain.model.HomeSection.NEW_RELEASES,
)
