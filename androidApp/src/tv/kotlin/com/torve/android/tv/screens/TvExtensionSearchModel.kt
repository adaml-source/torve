package com.torve.android.tv.screens

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.torve.domain.model.*

internal data class TvExtensionSearchSource(val addon: InstalledAddon, val catalog: AddonCatalog) {
    val key get() = "${addon.manifest.id}:${catalog.type}:${catalog.id}"
    val isAi get() = Regex("(?i)(\\bai\\b|gemini|semantic|natural.language)")
        .containsMatchIn("${catalog.id} ${catalog.name.orEmpty()}")
}

internal fun tvExtensionSearchSources(addons: List<InstalledAddon>): List<TvExtensionSearchSource> = addons
    .filter { it.isEnabled }.sortedBy { it.priority }.flatMap { addon ->
        addon.manifest.catalogs.filter { it.type in setOf("movie", "tv", "series") &&
            it.extra.any { extra -> extra.name == "search" } &&
            !AiRecommendationCatalogPolicy.isRemoved(it.id, it.name.orEmpty()) }
            .map { TvExtensionSearchSource(addon, it) }
    }.distinctBy { it.key }

internal data class TvExtensionSearchFilters(
    val type: String? = null,
    val genre: String? = null,
    val fromYear: Int? = null,
    val throughYear: Int? = null,
    val minImdb: Float? = null,
    val runtime: String? = null,
    val sort: String = "relevance",
) {
    val count get() = listOf(genre, fromYear, throughYear, minImdb, runtime).count { it != null } + if (sort != "relevance") 1 else 0
}

internal fun filterTvExtensionSearch(items: List<MediaItem>, filters: TvExtensionSearchFilters): List<MediaItem> {
    val filtered = items.filter { item ->
        item.catalogueNotice == null && !item.isContentPlaceholder &&
            (filters.type == null || item.type == if (filters.type == "movie") MediaType.MOVIE else MediaType.SERIES) &&
            (filters.genre == null || item.genres.any {
                it.name.equals(filters.genre, true) || it.name.split(" & ", "/").any { part -> part.equals(filters.genre, true) } ||
                    (filters.genre == "Science Fiction" && it.name.contains("Sci-Fi", true))
            }) &&
            (filters.fromYear == null || (item.year ?: Int.MIN_VALUE) >= filters.fromYear) &&
            (filters.throughYear == null || (item.year ?: Int.MAX_VALUE) <= filters.throughYear) &&
            (filters.minImdb == null || (item.ratings?.imdbScore ?: -1f) >= filters.minImdb) &&
            when (filters.runtime) {
                "short" -> item.runtime?.let { it < 90 } == true
                "medium" -> item.runtime?.let { it in 90..120 } == true
                "long" -> item.runtime?.let { it > 120 } == true
                else -> true
            }
    }
    return when (filters.sort) {
        "imdb" -> filtered.sortedByDescending { it.ratings?.imdbScore ?: -1f }
        "newest" -> filtered.sortedByDescending { it.year ?: 0 }
        "oldest" -> filtered.sortedBy { it.year ?: Int.MAX_VALUE }
        "title" -> filtered.sortedBy { it.title.lowercase() }
        else -> filtered
    }
}

internal class TvExtensionSearchState : ViewModel() {
    var query by mutableStateOf("")
    var aiMode by mutableStateOf(false)
    var filters by mutableStateOf(TvExtensionSearchFilters())
    var items by mutableStateOf<List<MediaItem>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var hasAi by mutableStateOf(false)
    var sourceCount by mutableIntStateOf(0)
    var revision by mutableIntStateOf(0)
    var loadedRequest: String? = null
}
