package com.torve.domain.recommendation

import com.torve.domain.model.MediaItem

data class TasteProfile(
    val genreScores: Map<Int, Double>,
    val genreNames: Map<Int, String>,
    val actorScores: Map<String, Double>,
    val avgRating: Double,
    val avgRuntime: Int,
)

data class ScoredMediaItem(
    val item: MediaItem,
    val score: Double,
    val reason: String,
)
