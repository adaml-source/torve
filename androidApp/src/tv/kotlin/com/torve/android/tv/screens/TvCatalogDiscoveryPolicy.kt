package com.torve.android.tv.screens

import com.torve.android.tv.components.TvContentRail
import com.torve.data.metadata.TmdbGenres

internal data class GenreSpec(val id: Int, val label: String)

internal data class CatalogRailSpec(
    val key: String,
    val title: String,
    val genreId: Int? = null,
    val special: String? = null,
)

/** The same genre vocabulary and sequence used by iOS CatalogScreen. */
internal fun catalogDiscoveryGenres(mediaType: String): List<GenreSpec> =
    (if (mediaType == "tv") TmdbGenres.TV_GENRES else TmdbGenres.MOVIE_GENRES)
        .map { (id, name) -> GenreSpec(id, name) }

internal fun catalogRailSpecs(
    mediaType: String,
    genreSpecs: List<GenreSpec>,
    trendingLabel: String,
    popularLabel: String,
    topRatedLabel: String,
    includeAdditionalRails: Boolean = true,
): List<CatalogRailSpec> = buildList {
    add(CatalogRailSpec("trending_$mediaType", trendingLabel, special = "trending"))
    add(CatalogRailSpec("popular_$mediaType", popularLabel, special = "popular"))
    add(CatalogRailSpec("top_rated_$mediaType", topRatedLabel, special = "top_rated"))
    // iOS keeps the calendar on Home; discovery has Upcoming movies and genres.
    if (includeAdditionalRails) {
        if (mediaType == "movie") add(CatalogRailSpec("upcoming", "Upcoming", special = "upcoming"))
        genreSpecs.forEach { genre ->
            add(CatalogRailSpec("genre_${mediaType}_${genre.id}", genre.label, genreId = genre.id))
        }
    }
}

/** Cached and asynchronously arriving rows must obey the current page's structure. */
internal fun alignCatalogDiscoveryRails(
    rails: List<TvContentRail>,
    specs: List<CatalogRailSpec>,
): List<TvContentRail> {
    val byKey = rails.associateBy { it.key }
    return specs.mapNotNull { spec ->
        byKey[spec.key]?.takeIf { it.items.isNotEmpty() }?.copy(title = spec.title)
    }
}
