package com.streamvault.presentation.home

import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.CustomSection
import com.streamvault.domain.model.HomeSectionConfig
import com.streamvault.domain.model.HomeSection
import com.streamvault.domain.model.PosterOrientation
import com.streamvault.domain.model.PosterSize
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.ParentalFilter
import com.streamvault.domain.model.ShelfConfig
import com.streamvault.domain.recommendation.GetRecommendationsUseCase
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.ProfileRepository
import com.streamvault.domain.repository.ShelfConfigRepository
import com.streamvault.domain.repository.AddonRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.domain.repository.WatchlistRepository
import com.streamvault.data.addon.CatalogAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

class HomeViewModel(
    private val metadataRepo: MetadataRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val recommendationsUseCase: GetRecommendationsUseCase,
    private val profileRepo: ProfileRepository,
    private val shelfConfigRepo: ShelfConfigRepository,
    private val watchlistRepo: WatchlistRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
    private val prefsRepo: PreferencesRepository,
    private val addonRepo: AddonRepository,
    private val catalogAggregator: CatalogAggregator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    // Section configuration state
    private val _sectionConfigs = MutableStateFlow(emptyList<HomeSectionConfig>())
    val sectionConfigs: StateFlow<List<HomeSectionConfig>> = _sectionConfigs.asStateFlow()

    // Streaming service selection
    private val _enabledServiceIds = MutableStateFlow<Set<Int>>(setOf(8, 9, 337, 350, 1899, 15))
    val enabledServiceIds: StateFlow<Set<Int>> = _enabledServiceIds.asStateFlow()

    // Custom sections
    private val _customSections = MutableStateFlow(emptyList<CustomSection>())
    val customSections: StateFlow<List<CustomSection>> = _customSections.asStateFlow()

    // Provider logos
    private val _providerLogos = MutableStateFlow<Map<Int, String>>(emptyMap())
    val providerLogos: StateFlow<Map<Int, String>> = _providerLogos.asStateFlow()

    init {
        scope.launch {
            _sectionConfigs.value = loadSectionConfigs()
            _enabledServiceIds.value = loadEnabledServiceIds()
            _customSections.value = loadCustomSections()
            loadHomeScreen()
        }
        loadProviderLogos()
    }

    private fun loadProviderLogos() {
        scope.launch {
            try {
                _providerLogos.value = metadataRepo.getWatchProviderLogos()
            } catch (_: Exception) { }
        }
    }

    private suspend fun loadSectionConfigs(): List<HomeSectionConfig> {
        val saved = try { prefsRepo.getString("home_section_configs") } catch (_: Exception) { null }
        return if (saved != null) {
            try {
                json.decodeFromString<List<HomeSectionConfig>>(saved)
            } catch (_: Exception) {
                defaultSectionConfigs()
            }
        } else {
            defaultSectionConfigs()
        }
    }

    private fun defaultSectionConfigs(): List<HomeSectionConfig> =
        HomeSection.entries.map { HomeSectionConfig(it, it.defaultEnabled, it.defaultOrder) }

    fun updateSectionOrder(configs: List<HomeSectionConfig>) {
        _sectionConfigs.value = configs
        saveSectionConfigs(configs)
    }

    fun toggleSection(section: HomeSection, enabled: Boolean) {
        val updated = _sectionConfigs.value.map {
            if (it.section == section) it.copy(enabled = enabled) else it
        }
        _sectionConfigs.value = updated
        saveSectionConfigs(updated)
    }

    fun resetSections() {
        val defaults = defaultSectionConfigs()
        _sectionConfigs.value = defaults
        saveSectionConfigs(defaults)
    }

    private fun saveSectionConfigs(configs: List<HomeSectionConfig>) {
        scope.launch {
            try {
                prefsRepo.setString("home_section_configs", json.encodeToString(configs))
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private suspend fun loadEnabledServiceIds(): Set<Int> {
        val saved = try { prefsRepo.getString("enabled_streaming_services") } catch (_: Exception) { null }
        return if (saved != null) {
            try {
                json.decodeFromString(SetSerializer(Int.serializer()), saved)
            } catch (_: Exception) {
                setOf(8, 9, 337, 350, 1899, 15)
            }
        } else {
            setOf(8, 9, 337, 350, 1899, 15)
        }
    }

    fun toggleStreamingService(providerId: Int, enabled: Boolean) {
        val updated = if (enabled) _enabledServiceIds.value + providerId else _enabledServiceIds.value - providerId
        _enabledServiceIds.value = updated
        scope.launch {
            prefsRepo.setString("enabled_streaming_services", json.encodeToString(SetSerializer(Int.serializer()), updated))
        }
    }

    // Custom sections
    private suspend fun loadCustomSections(): List<CustomSection> {
        val saved = try { prefsRepo.getString("custom_sections") } catch (_: Exception) { null }
        println("StreamVault: loadCustomSections raw = ${saved?.take(200)}")
        return if (saved != null) {
            try {
                val result = json.decodeFromString<List<CustomSection>>(saved)
                println("StreamVault: loadCustomSections parsed ${result.size} sections")
                result
            } catch (e: Exception) {
                println("StreamVault: loadCustomSections parse failed: ${e.message}")
                emptyList()
            }
        } else {
            println("StreamVault: loadCustomSections — no saved data")
            emptyList()
        }
    }

    private fun saveCustomSections(sections: List<CustomSection>) {
        scope.launch {
            try { prefsRepo.setString("custom_sections", json.encodeToString(sections)) }
            catch (_: Exception) { /* ignore */ }
        }
    }

    fun addCustomSection(section: CustomSection) {
        val updated = _customSections.value + section
        _customSections.value = updated
        saveCustomSections(updated)
        loadHomeScreen()
    }

    fun updateCustomSection(section: CustomSection) {
        val updated = _customSections.value.map { if (it.id == section.id) section else it }
        _customSections.value = updated
        saveCustomSections(updated)
        loadHomeScreen()
    }

    fun deleteCustomSection(sectionId: String) {
        val updated = _customSections.value.filter { it.id != sectionId }
        _customSections.value = updated
        saveCustomSections(updated)
        loadHomeScreen()
    }

    fun moveCustomSection(sectionId: String, direction: Int) {
        val sections = _customSections.value.sortedBy { it.order }.toMutableList()
        val index = sections.indexOfFirst { it.id == sectionId }
        if (index < 0) return
        val newIndex = index + direction
        if (newIndex < 0 || newIndex >= sections.size) return
        val item = sections.removeAt(index)
        sections.add(newIndex, item)
        val reordered = sections.mapIndexed { i, s -> s.copy(order = i) }
        _customSections.value = reordered
        saveCustomSections(reordered)
        loadHomeScreen()
    }

    fun updateSectionLayout(section: HomeSection, orientation: PosterOrientation, size: PosterSize) {
        val updated = _sectionConfigs.value.map {
            if (it.section == section) it.copy(orientation = orientation, size = size) else it
        }
        _sectionConfigs.value = updated
        saveSectionConfigs(updated)
    }

    fun loadHomeScreen() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val shelvesDeferred = async { metadataRepo.getHomeShelves() }
                val continueWatchingDeferred = async { watchProgressRepo.getInProgress(20) }
                val recommendationsDeferred = async {
                    try {
                        recommendationsUseCase.execute()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val watchlistDeferred = async {
                    try {
                        watchlistRepo.getAll().take(20)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val historyDeferred = async {
                    try {
                        watchHistoryRepo.getRecent(3)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val hiddenGemsDeferred = async {
                    try {
                        metadataRepo.discover(
                            type = "movie",
                            sortBy = "vote_average.desc",
                            minRating = 7.5f,
                            page = 1,
                        ).items.take(20)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val recentlyWatchedDeferred = async {
                    try {
                        watchHistoryRepo.getRecent(20).map { entry ->
                            MediaItem(
                                id = entry.mediaId,
                                type = if (entry.mediaType == MediaType.SERIES.name || entry.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
                                title = entry.title,
                                posterUrl = entry.posterUrl,
                                backdropUrl = entry.backdropUrl,
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val popularPeopleDeferred = async {
                    try {
                        metadataRepo.getPopularPeople()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val addonShelvesDeferred = async {
                    try {
                        val addons = addonRepo.getInstalledAddons()
                        if (addons.isEmpty()) emptyList()
                        else {
                            val movieShelves = catalogAggregator.fetchCatalogs(addons, "movie")
                            val seriesShelves = catalogAggregator.fetchCatalogs(addons, "series")
                            (movieShelves + seriesShelves).distinctBy { it.id }
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val shelves = shelvesDeferred.await()
                val continueWatching = continueWatchingDeferred.await()
                val recommendations = recommendationsDeferred.await()
                val watchlistItems = watchlistDeferred.await()
                val recentHistory = historyDeferred.await()
                val hiddenGems = hiddenGemsDeferred.await()
                val recentlyWatched = recentlyWatchedDeferred.await()
                val popularPeople = popularPeopleDeferred.await()
                val popularActors = popularPeople.filter { it.knownForDepartment == "Acting" }.take(20)
                val popularDirectors = popularPeople.filter { it.knownForDepartment == "Directing" }.take(20)
                val addonShelves = addonShelvesDeferred.await()

                // Load custom section content (parallel)
                val allCustomSections = _customSections.value
                val enabledCustomSections = allCustomSections.filter { it.enabled }
                val customShelfDeferreds = enabledCustomSections.map { section ->
                    async {
                        try {
                            val f = section.filters
                            val items = if (f.specificTmdbIds.isNotEmpty()) {
                                // Specific mode: fetch each item by TMDB ID
                                f.specificTmdbIds.mapNotNull { spec ->
                                    try {
                                        metadataRepo.getDetail(spec.mediaType, spec.tmdbId)
                                    } catch (_: Exception) { null }
                                }
                            } else {
                                // Discover mode: use TMDB discover API
                                val castIds = f.withCast.takeIf { it.isNotEmpty() }?.joinToString(",") { it.id.toString() }
                                val crewIds = f.withCrew.takeIf { it.isNotEmpty() }?.joinToString(",") { it.id.toString() }
                                val genres = f.genreIds.takeIf { it.isNotEmpty() }?.joinToString(",")
                                val providers = f.withWatchProviders.takeIf { it.isNotEmpty() }?.joinToString(",")
                                val keywords = f.withKeywords.takeIf { it.isNotEmpty() }?.joinToString("|")
                                val types = if (section.mediaType == "both") listOf("movie", "tv") else listOf(section.mediaType)
                                types.flatMap { type ->
                                    metadataRepo.discover(
                                        type = type,
                                        sortBy = f.sortBy,
                                        withGenres = genres,
                                        minRating = f.minRating,
                                        year = f.yearFrom,
                                        yearTo = f.yearTo,
                                        withCast = castIds,
                                        withCrew = crewIds,
                                        withWatchProviders = providers,
                                        watchRegion = f.watchRegion,
                                        withKeywords = keywords,
                                    ).items
                                }.distinctBy { it.id }.take(20)
                            }
                            section.id to items
                        } catch (_: Exception) {
                            section.id to emptyList()
                        }
                    }
                }
                val customShelves = customShelfDeferreds
                    .map { it.await() }
                    .filter { it.second.isNotEmpty() }
                    .toMap()
                    .toMutableMap()

                // Build watchlist shelf
                val watchlistMediaItems = watchlistItems.map { wl ->
                    MediaItem(
                        id = wl.mediaId,
                        tmdbId = wl.tmdbId,
                        title = wl.title,
                        posterUrl = wl.posterUrl,
                        backdropUrl = wl.backdropUrl,
                        rating = wl.rating,
                        year = wl.year,
                        type = wl.mediaType,
                    )
                }
                val watchlistShelf = if (watchlistMediaItems.isNotEmpty()) {
                    CatalogShelf(
                        id = "your_watchlist",
                        title = "Your Watchlist",
                        items = watchlistMediaItems,
                    )
                } else null

                // Build "Because You Watched" shelves from recent history
                val becauseYouWatched = recentHistory.mapNotNull { entry ->
                    try {
                        val type = if (entry.mediaType == MediaType.SERIES.name || entry.mediaType == "tv") "tv" else "movie"
                        val tmdbId = entry.mediaId.substringAfterLast("_", entry.mediaId).toIntOrNull() ?: return@mapNotNull null
                        val similar = metadataRepo.getSimilar(type, tmdbId).take(15)
                        if (similar.isNotEmpty()) {
                            CatalogShelf(
                                id = "because_${entry.mediaId}",
                                title = "Because You Watched ${entry.title}",
                                items = similar,
                            )
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }

                // Build hidden gems shelf
                val hiddenGemsShelf = if (hiddenGems.isNotEmpty()) {
                    CatalogShelf(
                        id = "hidden_gems",
                        title = "Hidden Gems",
                        items = hiddenGems,
                    )
                } else null

                // Apply shelf visibility and ordering
                val shelfConfigs = try { shelfConfigRepo.getAllConfigs() } catch (_: Exception) { emptyList() }
                val configMap = shelfConfigs.associateBy { it.shelfId }
                val orderedShelves = shelves
                    .filter { shelf -> configMap[shelf.id]?.isVisible != false }
                    .sortedBy { shelf -> configMap[shelf.id]?.sortOrder ?: Int.MAX_VALUE }

                // Apply parental content filtering
                val activeProfile = try { profileRepo.getActiveProfile() } catch (_: Exception) { null }
                val maxRating = activeProfile?.maxContentRating
                val filteredShelves = orderedShelves.map { shelf ->
                    shelf.copy(items = ParentalFilter.filter(shelf.items, maxRating))
                }
                val filteredRecommendations = if (maxRating != null) {
                    recommendations.filter { scored ->
                        ParentalFilter.filter(listOf(scored.item), maxRating).isNotEmpty()
                    }
                } else recommendations

                _state.update {
                    it.copy(
                        shelves = filteredShelves,
                        heroItem = filteredShelves.firstOrNull()?.items?.firstOrNull(),
                        continueWatching = continueWatching,
                        recommendedItems = filteredRecommendations,
                        watchlistShelf = watchlistShelf,
                        watchlistItems = watchlistMediaItems,
                        becauseYouWatched = becauseYouWatched,
                        hiddenGemsShelf = hiddenGemsShelf,
                        recentlyWatched = recentlyWatched,
                        popularActors = popularActors,
                        popularDirectors = popularDirectors,
                        customShelves = customShelves,
                        addonShelves = addonShelves,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun refresh() {
        loadHomeScreen()
    }

    fun toggleShelfVisibility(shelfId: String) {
        scope.launch {
            val existing = shelfConfigRepo.getConfig(shelfId)
            val config = existing?.copy(isVisible = !existing.isVisible)
                ?: ShelfConfig(shelfId = shelfId, isVisible = false, sortOrder = 0)
            shelfConfigRepo.upsertConfig(config)
            loadHomeScreen()
        }
    }

    fun reorderShelf(shelfId: String, newOrder: Int) {
        scope.launch {
            val existing = shelfConfigRepo.getConfig(shelfId)
            val config = existing?.copy(sortOrder = newOrder)
                ?: ShelfConfig(shelfId = shelfId, isVisible = true, sortOrder = newOrder)
            shelfConfigRepo.upsertConfig(config)
            loadHomeScreen()
        }
    }
}
