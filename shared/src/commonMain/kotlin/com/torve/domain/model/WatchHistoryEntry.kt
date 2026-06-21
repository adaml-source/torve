package com.torve.domain.model

data class WatchHistoryEntry(
    val id: String,
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val watchedAt: Long,
    val durationWatchedMs: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val showTitle: String?,
    val isContentPlaceholder: Boolean = false,
)
