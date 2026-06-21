package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaFavorite(
    val mediaKey: String,
    val mediaType: MediaType,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val addedAt: String? = null,
    val updatedAt: String? = null,
)

fun MediaItem.favoriteMediaKey(): String {
    val stableId = stableTmdbIdString() ?: id
    return "${type.toMediaFavoriteWireValue()}:$stableId"
}

fun MediaItem.legacyFavoriteMediaKey(): String {
    val stableId = stableTmdbIdString() ?: id
    return "${type.name}:$stableId"
}

fun MediaFavorite.canonicalMediaKey(): String {
    val idPart = tmdbId?.toString() ?: mediaKey.extractTmdbIdFromMediaId() ?: mediaKey.substringAfter(":", mediaKey)
    return "${mediaType.toMediaFavoriteWireValue()}:$idPart"
}

fun MediaFavorite.matchesMediaItemFavorite(item: MediaItem): Boolean {
    val itemKeys = setOf(item.favoriteMediaKey(), item.legacyFavoriteMediaKey())
    return mediaKey in itemKeys || canonicalMediaKey() in itemKeys
}

fun MediaItem.toMediaFavorite(
    addedAt: String? = null,
    updatedAt: String? = null,
): MediaFavorite {
    return MediaFavorite(
        mediaKey = favoriteMediaKey(),
        mediaType = type,
        tmdbId = tmdbId ?: id.extractTmdbIdFromMediaId()?.toIntOrNull(),
        imdbId = imdbId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
        year = year,
        addedAt = addedAt,
        updatedAt = updatedAt,
    )
}

fun MediaFavorite.toMediaItem(): MediaItem {
    val idPart = tmdbId?.toString() ?: mediaKey.substringAfter(":", mediaKey)
    return MediaItem(
        id = idPart,
        tmdbId = tmdbId,
        imdbId = imdbId,
        type = mediaType,
        title = title,
        year = year,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
    )
}

fun MediaType.toMediaFavoriteWireValue(): String {
    return when (this) {
        MediaType.MOVIE -> "movie"
        MediaType.SERIES -> "series"
    }
}

internal fun MediaItem.stableTmdbIdString(): String? {
    return tmdbId?.toString() ?: id.extractTmdbIdFromMediaId()
}

internal fun String.extractTmdbIdFromMediaId(): String? {
    val parts = split(":")
    if (parts.firstOrNull()?.lowercase() != "tmdb") return null
    return parts.lastOrNull()?.takeIf { part ->
        part.isNotBlank() && part.all { it in '0'..'9' }
    }
}
