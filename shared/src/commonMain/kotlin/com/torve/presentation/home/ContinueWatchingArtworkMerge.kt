package com.torve.presentation.home

import com.torve.domain.model.WatchProgress

internal fun List<WatchProgress>.newestWithArtwork(): WatchProgress? {
    val newest = maxByOrNull { it.updatedAt } ?: return null
    return newest.copy(
        posterUrl = newest.posterUrl?.takeIf { it.isNotBlank() }
            ?: firstNotNullOfOrNull { it.posterUrl?.takeIf(String::isNotBlank) },
        backdropUrl = newest.backdropUrl?.takeIf { it.isNotBlank() }
            ?: firstNotNullOfOrNull { it.backdropUrl?.takeIf(String::isNotBlank) },
    )
}
