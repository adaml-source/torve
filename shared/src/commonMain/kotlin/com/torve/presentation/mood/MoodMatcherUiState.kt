package com.torve.presentation.mood

import com.torve.domain.recommendation.Mood
import com.torve.domain.recommendation.MoodResult

data class MoodMatcherUiState(
    val selectedMood: Mood? = null,
    val results: List<MoodResult> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
