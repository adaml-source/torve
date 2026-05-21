package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaType {
    MOVIE,
    SERIES;

    companion object {
        fun fromString(s: String): MediaType = when (s.lowercase()) {
            "movie" -> MOVIE
            "tv", "series" -> SERIES
            else -> MOVIE
        }
    }
}

@Serializable
data class MediaItem(
    val id: String,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val type: MediaType,
    val title: String,
    val adult: Boolean? = null,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val rating: Double? = null,
    val voteCount: Int? = null,
    val runtime: Int? = null,
    val genres: List<Genre> = emptyList(),
    val genreIds: List<Int> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val director: String? = null,
    val directorId: Int? = null,
    val directorProfileUrl: String? = null,
    val studios: List<MediaCompany> = emptyList(),
    val releaseDate: String? = null,
    val status: String? = null,
    val trailerKey: String? = null,
    val seasons: List<Season> = emptyList(),
    val tagline: String? = null,
    val popularity: Double? = null,
    val ratings: MediaRatings? = null,
    val isContentPlaceholder: Boolean = false,
    val isStubDetail: Boolean = false,
)

@Serializable
data class Genre(
    val id: Int,
    val name: String,
)

@Serializable
data class MediaCompany(
    val id: Int,
    val name: String,
    val logoUrl: String? = null,
)

@Serializable
data class CastMember(
    val id: Int,
    val name: String,
    val character: String? = null,
    val profileUrl: String? = null,
)

@Serializable
data class Season(
    val seasonNumber: Int,
    val episodeCount: Int,
    val name: String? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
    val airDate: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class Episode(
    val episodeNumber: Int,
    val name: String = "",
    val overview: String = "",
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val rating: Double = 0.0,
)

@Serializable
data class PersonSummary(
    val id: Int,
    val name: String,
    val profileUrl: String? = null,
    val knownForDepartment: String? = null,
)

data class PagedResult(
    val items: List<MediaItem>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int,
)

internal fun MediaItem.stableKey(): String {
    val idPart = tmdbId?.toString() ?: id
    return "${type.name}:$idPart"
}

private fun mergeMediaItems(primary: MediaItem, other: MediaItem): MediaItem {
    fun preferString(a: String?, b: String?): String? =
        if (!a.isNullOrBlank()) a else b
    fun preferInt(a: Int?, b: Int?): Int? = a ?: b
    fun preferDouble(a: Double?, b: Double?): Double? = a ?: b

    return primary.copy(
        tmdbId = primary.tmdbId ?: other.tmdbId,
        imdbId = preferString(primary.imdbId, other.imdbId),
        title = if (primary.title.isNotBlank()) primary.title else other.title,
        adult = primary.adult ?: other.adult,
        year = preferInt(primary.year, other.year),
        overview = preferString(primary.overview, other.overview),
        posterUrl = preferString(primary.posterUrl, other.posterUrl),
        backdropUrl = preferString(primary.backdropUrl, other.backdropUrl),
        logoUrl = preferString(primary.logoUrl, other.logoUrl),
        rating = preferDouble(primary.rating, other.rating),
        voteCount = preferInt(primary.voteCount, other.voteCount),
        runtime = preferInt(primary.runtime, other.runtime),
        genres = if (primary.genres.isNotEmpty()) primary.genres else other.genres,
        genreIds = if (primary.genreIds.isNotEmpty()) primary.genreIds else other.genreIds,
        cast = if (primary.cast.isNotEmpty()) primary.cast else other.cast,
        director = preferString(primary.director, other.director),
        directorId = preferInt(primary.directorId, other.directorId),
        directorProfileUrl = preferString(primary.directorProfileUrl, other.directorProfileUrl),
        studios = if (primary.studios.isNotEmpty()) primary.studios else other.studios,
        releaseDate = preferString(primary.releaseDate, other.releaseDate),
        status = preferString(primary.status, other.status),
        trailerKey = preferString(primary.trailerKey, other.trailerKey),
        seasons = if (primary.seasons.isNotEmpty()) primary.seasons else other.seasons,
        tagline = preferString(primary.tagline, other.tagline),
        popularity = preferDouble(primary.popularity, other.popularity),
        ratings = primary.ratings ?: other.ratings,
        isContentPlaceholder = primary.isContentPlaceholder || other.isContentPlaceholder,
        isStubDetail = primary.isStubDetail || other.isStubDetail,
    )
}

fun List<MediaItem>.dedupeByStableKey(): List<MediaItem> {
    val map = LinkedHashMap<String, MediaItem>()
    for (item in this) {
        val key = item.stableKey()
        val existing = map[key]
        if (existing == null) {
            map[key] = item
        } else {
            val primary = if (existing.tmdbId != null || item.tmdbId == null) existing else item
            val secondary = if (primary === existing) item else existing
            map[key] = mergeMediaItems(primary, secondary)
        }
    }
    return map.values.toList()
}

/**
 * Cross-shelf deduplication: each movie/show appears in only the FIRST shelf
 * that contains it (by list order). Empty shelves are removed after filtering.
 * [globalSeen] is a mutable set pre-populated with keys from protected sources
 * (continue watching, watchlist, recently watched) so those items are excluded
 * from later shelves but the protected sources themselves are untouched.
 */
fun List<CatalogShelf>.dedupeAcrossShelves(
    globalSeen: MutableSet<String> = mutableSetOf(),
    minItemsPerShelf: Int = 20,
): List<CatalogShelf> {
    return mapNotNull { shelf ->
        val localSeen = mutableSetOf<String>()
        val filtered = shelf.items.filter { item ->
            val key = item.stableKey()
            if (key in globalSeen || key in localSeen) {
                false
            } else {
                localSeen.add(key)
                true
            }
        }
        val result = filtered
        result.collectStableKeys(globalSeen)
        if (result.isEmpty()) null else shelf.copy(items = result)
    }
}

/**
 * Collect stable keys from a list of media items into the given mutable set.
 * Used to seed the global seen set from protected sources.
 */
fun List<MediaItem>.collectStableKeys(into: MutableSet<String>) {
    for (item in this) {
        into.add(item.stableKey())
    }
}
