package com.torve.data.metadata

import com.torve.domain.model.MediaRatings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackendCatalogRatings(val items: List<BackendCatalogRating> = emptyList())

@Serializable
data class BackendCatalogRating(
    val id: Int,
    @SerialName("media_type") val mediaType: String,
    @SerialName("imdb_id") val imdbId: String? = null,
    val ratings: MediaRatings = MediaRatings(),
)
