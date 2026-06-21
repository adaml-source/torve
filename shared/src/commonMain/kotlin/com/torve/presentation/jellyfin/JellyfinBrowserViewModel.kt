package com.torve.presentation.jellyfin

import com.torve.data.integrations.JellyfinBrowseItem
import com.torve.data.integrations.JellyfinLibraryOverlayService
import com.torve.data.integrations.JellyfinLibrarySection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JellyfinBrowserUiState(
    val isLoading: Boolean = false,
    val sections: List<JellyfinLibrarySection> = emptyList(),
    val sectionItems: Map<String, List<JellyfinBrowseItem>> = emptyMap(),
    val error: String? = null,
)

class JellyfinBrowserViewModel(
    private val jellyfinService: JellyfinLibraryOverlayService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(JellyfinBrowserUiState())
    val state: StateFlow<JellyfinBrowserUiState> = _state.asStateFlow()

    private var loaded = false

    suspend fun isConnected(): Boolean = jellyfinService.isConnected()

    fun loadLibrary() {
        if (loaded) return
        loaded = true
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                println("JELLYFIN: loadLibrary start")
                val sections = jellyfinService.getLibrarySectionsOrThrow()
                println("JELLYFIN: loadLibrary sections=${sections.size}")
                if (sections.isEmpty()) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Your Jellyfin server has no video libraries.",
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(sections = sections) }
                for (section in sections) {
                    val (items, _) = jellyfinService.getLibraryItems(section.id, 0, 30)
                    _state.update { current ->
                        current.copy(sectionItems = current.sectionItems + (section.id to items))
                    }
                }
                _state.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Jellyfin error: ${e.message}") }
            }
        }
    }

    fun reload() {
        loaded = false
        _state.value = JellyfinBrowserUiState()
        loadLibrary()
    }

    suspend fun buildImageUrl(itemId: String): String? = jellyfinService.buildImageUrl(itemId)

    suspend fun buildStreamUrl(itemId: String): String? = jellyfinService.buildStreamUrl(itemId)
}
