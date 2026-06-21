package com.torve.domain.model

data class WatchlistItem(
    val mediaId: String,
    val mediaType: MediaType,
    val tmdbId: Int,
    val imdbId: String? = null,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val genres: String? = null,
    val addedAt: Long,
    val sortOrder: Int = 0,
)
