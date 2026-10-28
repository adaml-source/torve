package com.torve.data.metadata

import kotlinx.serialization.Serializable

// Only identifiers are consumed. Posters and safety metadata always come from
// the configured catalogue, never from an unclassified completion response.
@Serializable
internal data class TitleCompletionResponse(val d: List<TitleCompletion> = emptyList())

@Serializable
internal data class TitleCompletion(val id: String = "", val qid: String = "") {
    val mediaType: String? get() = when (qid) {
        "movie", "tvMovie", "video", "short" -> "movie"
        "tvSeries", "tvMiniSeries" -> "tv"
        else -> null
    }
}
