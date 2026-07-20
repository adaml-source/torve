package com.torve.presentation.mood

import com.torve.domain.recommendation.Mood
import com.torve.domain.recommendation.MoodMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MoodMatcherViewModel(
    private val moodMatcher: MoodMatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(MoodMatcherUiState())
    val state: StateFlow<MoodMatcherUiState> = _state.asStateFlow()

    fun selectMood(mood: Mood) {
        _state.update { it.copy(selectedMood = mood, isLoading = true, error = null, results = emptyList()) }
        scope.launch {
            try {
                val results = moodMatcher.getRecommendations(mood)
                _state.update { it.copy(results = results, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.CONTENT_LOAD_FAILED.messageKey) }
            }
        }
    }

    fun clearMood() {
        _state.update { MoodMatcherUiState() }
    }
}
