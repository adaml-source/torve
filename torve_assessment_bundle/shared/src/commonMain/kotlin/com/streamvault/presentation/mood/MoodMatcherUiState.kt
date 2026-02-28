package com.streamvault.presentation.mood

import com.streamvault.domain.recommendation.Mood
import com.streamvault.domain.recommendation.MoodResult

data class MoodMatcherUiState(
    val selectedMood: Mood? = null,
    val results: List<MoodResult> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
