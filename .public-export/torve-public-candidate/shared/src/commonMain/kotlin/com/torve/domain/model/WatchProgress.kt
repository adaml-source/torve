package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WatchProgress(
    val mediaId: String,
    val mediaType: MediaType,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val showTitle: String? = null,
    val updatedAt: Long = 0,
    val isContentPlaceholder: Boolean = false,
) {
    val progressPercent: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs) else 0f
}
