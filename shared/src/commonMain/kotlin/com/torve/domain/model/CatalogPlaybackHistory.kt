package com.torve.domain.model

private val nonCatalogPlaybackPrefixes = listOf("direct:", "sports:", "webdav:")

/** Check before extracting IDs: the numeric part of a sports/file key is not a TMDB ID. */
fun String.isCatalogPlaybackIdentity(): Boolean {
    val value = trim()
    return value.isNotBlank() && !value.contains("://") &&
        nonCatalogPlaybackPrefixes.none { value.startsWith(it, ignoreCase = true) }
}

/** Movie/show cards only; keep file and event bookmarks in their original playback surfaces. */
fun WatchHistoryEntry.toCatalogHistoryItemOrNull(): MediaItem? {
    if (!mediaId.isCatalogPlaybackIdentity()) return null
    val kind = mediaType.trim().lowercase()
    if (kind !in listOf("movie", "series", "tv")) return null
    val series = kind == "series" || kind == "tv" || seasonNumber != null || episodeNumber != null
    return MediaItem(
        id = mediaId,
        tmdbId = mediaId.extractTmdbIdOrNull(),
        imdbId = mediaId.extractImdbIdOrNull(),
        type = if (series) MediaType.SERIES else MediaType.MOVIE,
        title = if (series) showTitle?.takeIf { it.isNotBlank() } ?: title else title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
    )
}
