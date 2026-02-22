package com.streamvault.presentation.catalog

import com.streamvault.domain.repository.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shared catalog ViewModel used by both Movies and TV Shows screens.
 * The [mediaType] determines whether we query "movie" or "tv".
 */
class CatalogViewModel(
    private val metadataRepo: MetadataRepository,
    private val mediaType: String, // "movie" or "tv"
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")

    init {
        loadCatalog()
        observeSearch()
    }

    fun loadCatalog() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val items = when (_state.value.selectedCategory) {
                    CatalogCategory.TRENDING -> metadataRepo.getTrending(mediaType)
                    CatalogCategory.POPULAR -> metadataRepo.getPopular(mediaType)
                    CatalogCategory.TOP_RATED -> metadataRepo.getTopRated(mediaType)
                }
                val filtered = _state.value.selectedGenreId?.let { genreId ->
                    items.filter { it.genreIds.contains(genreId) }
                } ?: items
                _state.update { it.copy(items = filtered, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectCategory(category: CatalogCategory) {
        _state.update { it.copy(selectedCategory = category) }
        loadCatalog()
    }

    fun selectGenre(genreId: Int?) {
        _state.update { it.copy(selectedGenreId = genreId) }
        loadCatalog()
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchQueryFlow.value = query
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        scope.launch {
            searchQueryFlow
                .debounce(300)
                .distinctUntilChanged()
                .filter { it.length >= 2 }
                .collect { query ->
                    _state.update { it.copy(isSearching = true) }
                    try {
                        val results = metadataRepo.searchMulti(query)
                        // Filter to only the relevant media type
                        val filtered = results.filter {
                            when (mediaType) {
                                "movie" -> it.type == com.streamvault.domain.model.MediaType.MOVIE
                                "tv" -> it.type == com.streamvault.domain.model.MediaType.SERIES
                                else -> true
                            }
                        }
                        _state.update { it.copy(searchResults = filtered, isSearching = false) }
                    } catch (_: Exception) {
                        _state.update { it.copy(isSearching = false) }
                    }
                }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList()) }
        searchQueryFlow.value = ""
    }

    fun refresh() {
        loadCatalog()
    }
}
