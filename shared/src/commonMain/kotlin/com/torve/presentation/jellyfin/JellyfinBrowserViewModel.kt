package com.torve.presentation.jellyfin

import com.torve.data.integrations.JellyfinBrowseItem
import com.torve.data.integrations.JellyfinLibraryOverlayService
import com.torve.data.integrations.JellyfinLibrarySection
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JellyfinBrowserUiState(
    val isLoading: Boolean = false,
    val sections: List<JellyfinLibrarySection> = emptyList(),
    val sectionItems: Map<String, List<JellyfinBrowseItem>> = emptyMap(),
    val sectionTotals: Map<String, Int> = emptyMap(),
    val error: String? = null,
)

class JellyfinBrowserViewModel(
    private val jellyfinService: JellyfinLibraryOverlayService,
    private val metadataRepository: MetadataRepository,
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
                    val (items, total) = loadAllSectionItems(section.id)
                    val enrichedItems = enrichLibraryArtwork(items.filterNot(JellyfinBrowseItem::isEpisode))
                    _state.update { current ->
                        current.copy(
                            sectionItems = current.sectionItems + (section.id to enrichedItems),
                            sectionTotals = current.sectionTotals + (section.id to total),
                        )
                    }
                }
                _state.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Jellyfin error: ${e.message}") }
            }
        }
    }

    fun reload(preserveContent: Boolean = false) {
        loaded = false
        if (!preserveContent) {
            _state.value = JellyfinBrowserUiState()
        }
        loadLibrary()
    }

    suspend fun buildImageUrl(itemId: String): String? = jellyfinService.buildImageUrl(itemId)

    suspend fun buildBackdropImageUrl(itemId: String): String? = jellyfinService.buildBackdropImageUrl(itemId)

    suspend fun buildStreamUrl(itemId: String): String? = jellyfinService.buildStreamUrl(itemId)

    private suspend fun loadAllSectionItems(sectionId: String): Pair<List<JellyfinBrowseItem>, Int> {
        val pageSize = 200
        val items = mutableListOf<JellyfinBrowseItem>()
        var total = Int.MAX_VALUE
        var offset = 0
        while (offset < total) {
            val (page, reportedTotal) = jellyfinService.getLibraryItems(sectionId, offset, pageSize)
            total = reportedTotal
            if (page.isEmpty()) break
            items += page
            offset += page.size
        }
        return items.distinctBy(JellyfinBrowseItem::id) to
            total.takeUnless { it == Int.MAX_VALUE }?.coerceAtLeast(items.size).orZero(items.size)
    }

    private suspend fun enrichLibraryArtwork(items: List<JellyfinBrowseItem>): List<JellyfinBrowseItem> {
        val enrichedParents = coroutineScope {
            items.map { item ->
                async {
                    if (item.isEpisode) return@async item
                    if (item.resolvedPrimaryImageTag != null) return@async item
                    val tmdbId = item.providerIds?.tmdb?.toIntOrNull() ?: return@async item
                    val mediaType = if (item.type.equals("Movie", ignoreCase = true)) {
                        MediaType.MOVIE
                    } else {
                        MediaType.SERIES
                    }
                    val detail = runCatching {
                        metadataRepository.getDetail(
                            type = if (mediaType == MediaType.MOVIE) "movie" else "tv",
                            id = tmdbId,
                        )
                    }.getOrNull() ?: return@async item
                    item.copy(
                        fallbackPosterUrl = detail.posterUrl,
                        fallbackBackdropUrl = detail.backdropUrl,
                    )
                }
            }.awaitAll()
        }
        val parentById = enrichedParents.associateBy(JellyfinBrowseItem::id)
        return enrichedParents.map { item ->
            if (!item.isEpisode) return@map item
            val parent = item.seriesId?.let(parentById::get)
            item.copy(
                seriesName = item.seriesName ?: parent?.name,
                fallbackPosterUrl = parent?.fallbackPosterUrl,
                fallbackBackdropUrl = parent?.fallbackBackdropUrl,
            )
        }
    }
}

private fun Int?.orZero(fallback: Int): Int = this ?: fallback
