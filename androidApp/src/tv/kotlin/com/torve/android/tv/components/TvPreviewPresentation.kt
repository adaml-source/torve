package com.torve.android.tv.components

import com.torve.android.tv.withTvHeroDetail
import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.CancellationException

internal fun MediaItem.isTvPreviewTitle() = !id.startsWith("person:") &&
    !id.startsWith("web-replay") && familySourceKey != "web-replays" && catalogueNotice == null && !isContentPlaceholder

internal fun MediaItem.needsTvPreviewDetails() = isTvPreviewTitle() &&
    (overview.isNullOrBlank() || cast.isEmpty() || logoUrl.isNullOrBlank() || runtime == null || year == null)

/** A provider's numeric suffix (for example trakt:123) is not a TMDB identity. */
internal fun MediaItem.tvPreviewTmdbId(): Int? = tmdbId ?: id.trim().let { raw ->
    when {
        raw.all(Char::isDigit) -> raw.toIntOrNull()
        raw.matches(Regex("tmdb:[1-9][0-9]*")) -> raw.substringAfter(':').toIntOrNull()
        else -> null
    }
}?.takeIf { it > 0 }

internal fun tvRailDisplayTitle(rail: TvContentRail): String {
    // Movies/Shows tabs already establish the media type, as on iOS. Home
    // and extension shelves still need their disambiguating content suffixes.
    if (rail.key in setOf("trending_movie", "popular_movie", "top_rated_movie",
            "trending_tv", "popular_tv", "top_rated_tv")) return rail.title
    val base = rail.title.trim()
    if (base.lowercase() !in setOf("popular", "trending", "top rated")) return rail.title
    val kind = when {
        rail.key.contains("director", true) -> "Directors"
        rail.key.contains("actor", true) || rail.items.all { it.id.startsWith("person:") } -> "Actors"
        rail.items.all { it.type == MediaType.SERIES } -> "TV Shows"
        rail.items.all { it.type == MediaType.MOVIE } -> "Movies"
        else -> "Movies & TV Shows"
    }
    return "$base $kind"
}

internal interface TvPreviewLookup {
    suspend fun find(imdb: String, type: String): MediaItem?
    suspend fun search(title: String): List<MediaItem>
    suspend fun detail(type: String, id: Int): MediaItem
    suspend fun logo(type: String, id: Int): String?
}

internal class RepositoryTvPreviewLookup(private val repository: MetadataRepository) : TvPreviewLookup {
    override suspend fun find(imdb: String, type: String) = repository.findByImdbId(imdb, type)
    override suspend fun search(title: String) = repository.searchMulti(title)
    override suspend fun detail(type: String, id: Int) = repository.getDetail(type, id)
    override suspend fun logo(type: String, id: Int) = repository.getLogoUrl(type, id)
}

/** Sparse saved/extension rows can have ratings without an identity or full details. */
internal suspend fun loadTvPreviewDetails(item: MediaItem, source: TvPreviewLookup): MediaItem? {
    if (!item.isTvPreviewTitle()) return null
    val type = if (item.type == MediaType.MOVIE) "movie" else "tv"
    val direct = item.tvPreviewTmdbId()
    val imdb = item.imdbId ?: item.id.extractImdbIdOrNull()
    val match = if (direct != null) null else if (imdb != null) source.find(imdb, type) else {
        val title = item.tvPreviewShowTitle()
        // Never attach another film's cast/logo just because its title is similar.
        source.search(title).filter { it.type == item.type && it.title.trim().equals(title.trim(), true) &&
            (item.year == null || it.year == item.year) }.distinctBy { it.tmdbId ?: it.id }.singleOrNull()
    }
    val id = direct ?: match?.tvPreviewTmdbId() ?: return null
    val detail = source.detail(type, id)
    var result = item.withTvHeroDetail(detail).copy(tmdbId = id, isStubDetail = false)
    if (result.logoUrl.isNullOrBlank()) {
        val logo = try { source.logo(type, id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        result = result.copy(logoUrl = logo)
    }
    return result
}
