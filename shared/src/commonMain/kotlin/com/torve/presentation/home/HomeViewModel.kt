package com.torve.presentation.home

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.CustomSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.HomeSection
import com.torve.domain.model.CardStylePreset
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.ParentalFilter
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.ShelfConfig
import com.torve.domain.model.WatchlistItem
import com.torve.domain.model.collectStableKeys
import com.torve.domain.model.dedupeAcrossShelves
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.stableKey
import com.torve.domain.model.updateSectionPresetId
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.recommendation.ScoredMediaItem
import com.torve.domain.recommendation.GetRecommendationsUseCase
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.ProfileRepository
import com.torve.domain.repository.ShelfConfigRepository
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.data.addon.CatalogAggregator
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.MdbListRepository
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.network.homeContentLoadErrorMessage
import com.torve.data.network.sanitizeNetworkDiagnosticText
import com.torve.platform.torveVerboseLog
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    private val mdbListRepo: MdbListRepository,
    private val ratingsEnricher: RatingsEnricher,
    private val libraryOverlayService: LibraryOverlayService,
    private val integrationSecretStore: IntegrationSecretStore,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
) {
    private data class ArtworkBackfillRequest(
        val type: MediaType,
        val tmdbId: Int,
    )

    private data class HomeLoadInputs(
        val shelves: List<CatalogShelf>,
        val continueWatching: List<com.torve.domain.model.WatchProgress>,
        val overlayContinue: List<com.torve.domain.model.WatchProgress>,
        val recommendations: List<ScoredMediaItem>,
        val watchlistItems: List<WatchlistItem>,
        val recentHistory: List<com.torve.domain.model.WatchHistoryEntry>,
        val hiddenGems: List<MediaItem>,
        val recentlyWatched: List<MediaItem>,
        val popularPeople: List<PersonSummary>,
        val addonShelves: List<CatalogShelf>,
        val mdbListShelves: List<CatalogShelf>,
    )

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

    // Combined home layout ordering (built-in + custom)
    private val _homeLayoutOrder = MutableStateFlow<List<String>>(emptyList())
    val homeLayoutOrder: StateFlow<List<String>> = _homeLayoutOrder.asStateFlow()

    // Addon shelf visibility (persisted)
    private val _addonShelfVisibility = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val addonShelfVisibility: StateFlow<Map<String, Boolean>> = _addonShelfVisibility.asStateFlow()

    // Search
    private val searchQueryFlow = MutableStateFlow("")

    init {
        scope.launch {
            _sectionConfigs.value = loadSectionConfigs()
            _enabledServiceIds.value = loadEnabledServiceIds()
            _customSections.value = loadCustomSections()
            _addonShelfVisibility.value = loadAddonShelfVisibility()
            _homeLayoutOrder.value = ensureAllSectionsInLayoutOrder(loadHomeLayoutOrder())
            loadHomeScreen()
        }
        scope.launch {
            settingsRefreshNotifier.events.collect {
                _sectionConfigs.value = loadSectionConfigs()
                _homeLayoutOrder.value = ensureAllSectionsInLayoutOrder(loadHomeLayoutOrder())
                loadHomeScreen()
            }
        }
        observeSearch()
    }

    /**
     * Ensure every known section key appears in the layout order.
     * New sections added to the enum get appended at their default order position.
     */
    private fun ensureAllSectionsInLayoutOrder(saved: List<String>): List<String> {
        if (saved.isEmpty()) return saved // first-time users — HomeScreen falls back to order field
        val existing = saved.toSet()
        val missing = _sectionConfigs.value
            .filter { "section:${it.section.name}" !in existing }
            .sortedBy { it.order }
            .map { "section:${it.section.name}" }
        if (missing.isEmpty()) return saved
        val result = saved.toMutableList()
        // Insert each missing section at its order position (clamped to list size)
        missing.forEach { key ->
            val section = _sectionConfigs.value.firstOrNull { "section:${it.section.name}" == key }
            val insertAt = (section?.order ?: result.size).coerceAtMost(result.size)
            result.add(insertAt, key)
        }
        // Persist the updated order
        updateHomeLayoutOrder(result)
        return result
    }

    private fun loadProviderLogos() {
        scope.launch {
            try {
                _providerLogos.value = metadataRepo.getWatchProviderLogos()
            } catch (_: Exception) { }
        }
    }

    fun refreshProviderLogos() {
        loadProviderLogos()
    }

    private suspend fun loadSectionConfigs(): List<HomeSectionConfig> {
        val saved = try { prefsRepo.getString("home_section_configs") } catch (_: Exception) { null }
        return if (saved != null) {
            try {
                val decoded = json.decodeFromString<List<HomeSectionConfig>>(saved)
                val defaults = defaultSectionConfigs()
                val bySection = decoded.associateBy { it.section }
                val presetIds = loadCardStylePresetIds()
                defaults.map { def ->
                    val resolved = bySection[def.section] ?: def
                    // Only strip invalid preset IDs if presets are actually loaded.
                    // When presetIds is empty, presets may not be persisted yet —
                    // preserve whatever the user saved.
                    if (presetIds.isNotEmpty() &&
                        resolved.presetId != null &&
                        resolved.presetId != "default" &&
                        resolved.presetId !in presetIds
                    ) {
                        resolved.copy(presetId = null)
                    } else {
                        resolved
                    }
                }
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

    fun resetSectionToDefault(section: HomeSection) {
        val defaults = defaultSectionConfigs().associateBy { it.section }
        val defaultConfig = defaults[section] ?: return
        val updated = _sectionConfigs.value.map {
            if (it.section == section) {
                it.copy(
                    enabled = defaultConfig.enabled,
                    presetId = "default",
                    customTitle = null,
                )
            } else it
        }
        _sectionConfigs.value = updated
        saveSectionConfigs(updated)
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
        return if (saved != null) {
            try {
                json.decodeFromString<List<CustomSection>>(saved)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    private fun saveCustomSections(sections: List<CustomSection>) {
        scope.launch {
            try { prefsRepo.setString("custom_sections", json.encodeToString(sections)) }
            catch (_: Exception) { /* ignore */ }
        }
    }

    private suspend fun loadHomeLayoutOrder(): List<String> {
        val saved = try { prefsRepo.getString("home_layout_order") } catch (_: Exception) { null }
        return if (saved != null) {
            try {
                json.decodeFromString<List<String>>(saved)
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
    }

    fun updateHomeLayoutOrder(order: List<String>) {
        _homeLayoutOrder.value = order
        scope.launch {
            try { prefsRepo.setString("home_layout_order", json.encodeToString(order)) }
            catch (_: Exception) { /* ignore */ }
        }
    }

    // ── Addon shelf visibility ──

    private suspend fun loadAddonShelfVisibility(): Map<String, Boolean> {
        val saved = try { prefsRepo.getString("addon_shelf_visibility") } catch (_: Exception) { null }
        return if (saved != null) {
            try {
                json.decodeFromString<Map<String, Boolean>>(saved)
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()
    }

    private fun saveAddonShelfVisibility(visibility: Map<String, Boolean>) {
        scope.launch {
            try { prefsRepo.setString("addon_shelf_visibility", json.encodeToString(visibility)) }
            catch (_: Exception) { /* ignore */ }
        }
    }

    fun toggleAddonShelfVisibility(shelfId: String) {
        val current = _addonShelfVisibility.value
        val isVisible = current[shelfId] ?: true
        val updated = current + (shelfId to !isVisible)
        _addonShelfVisibility.value = updated
        _state.update { it.copy(addonShelfVisibility = updated) }
        saveAddonShelfVisibility(updated)
    }

    /**
     * Register each addon shelf in the layout order if not already present.
     * Called after addon shelves are loaded so they appear in Home Layout.
     */
    private fun ensureAddonShelvesInLayout(addonShelves: List<CatalogShelf>) {
        val current = _homeLayoutOrder.value
        if (current.isEmpty()) return // first-time users — let default ordering apply
        val existing = current.toSet()
        val missing = addonShelves.filter { "addon:${it.id}" !in existing }
        if (missing.isEmpty()) return
        val result = current.toMutableList()
        // Find ADDON_SHELVES section position and insert after it
        val addonSectionIdx = result.indexOfFirst { it == "section:ADDON_SHELVES" }
        var insertAt = if (addonSectionIdx >= 0) addonSectionIdx + 1 else result.size
        missing.forEach { shelf ->
            result.add(insertAt, "addon:${shelf.id}")
            insertAt++
        }
        updateHomeLayoutOrder(result)
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

    fun updateSectionPreset(section: HomeSection, presetId: String?) {
        val updated = updateSectionPresetId(_sectionConfigs.value, section, presetId)
        _sectionConfigs.value = updated
        saveSectionConfigs(updated)
    }

    private suspend fun loadCardStylePresetIds(): Set<String> {
        val saved = try { prefsRepo.getString(SettingsViewModel.KEY_CARD_STYLE_PRESETS) } catch (_: Exception) { null }
        if (saved.isNullOrBlank()) return emptySet()
        return try {
            json.decodeFromString<List<CardStylePreset>>(saved).map { it.presetId }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun loadHomeScreen() {
        scope.launch {
            torveVerboseLog { "HOME_TAB bootstrap_start" }
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val dedupe = shouldDedupe()
                val loadInputs = supervisorScope {
                    torveVerboseLog { "HOME_TAB repository_fetch_start source=home_shelves" }
                    val shelvesDeferred = async { metadataRepo.getHomeShelves() }
                    val continueWatchingDeferred = async { watchProgressRepo.getInProgress(20) }
                    val overlayContinueDeferred = async {
                        try { libraryOverlayService.getContinueWatching(20) } catch (_: Exception) { emptyList() }
                    }
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
                    val mdbListShelvesDeferred = async {
                        try {
                            val apiKey = prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY) ?: ""
                            if (apiKey.isBlank()) emptyList()
                            else {
                                val savedLists = mdbListRepo.getSavedLists().filter { it.enabled }
                                savedLists.mapNotNull { listConfig ->
                                    try {
                                        val items = mdbListRepo.fetchListContent(listConfig.listId, apiKey, listConfig.itemCount)
                                        if (items.isNotEmpty()) {
                                            CatalogShelf(
                                                id = "mdblist_${listConfig.listId}",
                                                title = listConfig.name,
                                                items = items,
                                            )
                                        } else null
                                    } catch (_: Exception) { null }
                                }
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    HomeLoadInputs(
                        shelves = shelvesDeferred.await(),
                        continueWatching = continueWatchingDeferred.await(),
                        overlayContinue = overlayContinueDeferred.await(),
                        recommendations = recommendationsDeferred.await(),
                        watchlistItems = watchlistDeferred.await(),
                        recentHistory = historyDeferred.await(),
                        hiddenGems = hiddenGemsDeferred.await(),
                        recentlyWatched = recentlyWatchedDeferred.await(),
                        popularPeople = popularPeopleDeferred.await(),
                        addonShelves = addonShelvesDeferred.await(),
                        mdbListShelves = mdbListShelvesDeferred.await(),
                    )
                }

                val shelves = loadInputs.shelves
                val continueWatching = loadInputs.continueWatching
                val overlayContinue = loadInputs.overlayContinue
                val mergedContinueWatching = (continueWatching + overlayContinue)
                    .groupBy { "${it.mediaType.name}:${it.mediaId}" }
                    .mapNotNull { (_, entries) -> entries.maxByOrNull { it.updatedAt } }
                    .sortedByDescending { it.updatedAt }
                    .take(20)
                val recommendations = loadInputs.recommendations
                val watchlistItems = loadInputs.watchlistItems
                val recentHistory = loadInputs.recentHistory
                val hiddenGems = loadInputs.hiddenGems
                val recentlyWatched = loadInputs.recentlyWatched
                val popularPeople = loadInputs.popularPeople
                val popularActors = popularPeople.filter { it.knownForDepartment == "Acting" }.take(20)
                val directorDepartments = setOf("Directing", "Production", "Writing")
                val popularDirectors = popularPeople
                    .filter { it.knownForDepartment in directorDepartments }
                    .ifEmpty {
                        popularPeople.filter { it.knownForDepartment != "Acting" }
                    }
                    .ifEmpty { popularPeople }
                    .take(20)
                val addonShelves = loadInputs.addonShelves
                val mdbListShelves = loadInputs.mdbListShelves

                // Register addon shelves in layout order for individual customization
                ensureAddonShelvesInLayout(addonShelves)

                // Load custom section content (parallel)
                val allCustomSections = _customSections.value
                val enabledCustomSections = allCustomSections.filter { it.enabled }
                val customShelfDeferreds = enabledCustomSections.map { section ->
                    async {
                        try {
                            val f = section.filters
                            val customLimit = 40
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
                                    val page1 = metadataRepo.discover(
                                        type = type,
                                        sortBy = f.sortBy,
                                        withGenres = genres,
                                        minRating = f.minRating,
                                        year = f.yearFrom,
                                        yearTo = f.yearTo,
                                        runtimeGte = f.runtimeGte,
                                        runtimeLte = f.runtimeLte,
                                        originCountries = f.originCountries.takeIf { it.isNotEmpty() }?.joinToString("|"),
                                        originalLanguage = f.originalLanguage,
                                        certification = f.certification,
                                        certificationGte = f.certificationGte,
                                        certificationLte = f.certificationLte,
                                        certificationCountry = f.certificationCountry,
                                        withCast = castIds,
                                        withCrew = crewIds,
                                        withWatchProviders = providers,
                                        watchRegion = f.watchRegion,
                                        withKeywords = keywords,
                                        page = 1,
                                    ).items
                                    val page2 = metadataRepo.discover(
                                        type = type,
                                        sortBy = f.sortBy,
                                        withGenres = genres,
                                        minRating = f.minRating,
                                        year = f.yearFrom,
                                        yearTo = f.yearTo,
                                        runtimeGte = f.runtimeGte,
                                        runtimeLte = f.runtimeLte,
                                        originCountries = f.originCountries.takeIf { it.isNotEmpty() }?.joinToString("|"),
                                        originalLanguage = f.originalLanguage,
                                        certification = f.certification,
                                        certificationGte = f.certificationGte,
                                        certificationLte = f.certificationLte,
                                        certificationCountry = f.certificationCountry,
                                        withCast = castIds,
                                        withCrew = crewIds,
                                        withWatchProviders = providers,
                                        watchRegion = f.watchRegion,
                                        withKeywords = keywords,
                                        page = 2,
                                    ).items
                                    page1 + page2
                                }.distinctBy { it.id }.take(customLimit)
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
                val parentalFilteredShelves = orderedShelves.map { shelf ->
                    val items = ParentalFilter.filter(shelf.items, maxRating)
                    shelf.copy(items = if (dedupe) items.dedupeByStableKey() else items)
                }
                val filteredRecommendations = if (maxRating != null) {
                    recommendations.filter { scored ->
                        ParentalFilter.filter(listOf(scored.item), maxRating).isNotEmpty()
                    }
                } else recommendations

                // Within-shelf dedup first
                val withinDedupedWatchlist = if (dedupe) watchlistMediaItems.dedupeByStableKey() else watchlistMediaItems
                val withinDedupedRecents = if (dedupe) recentlyWatched.dedupeByStableKey() else recentlyWatched
                val withinDedupedHiddenGems = hiddenGemsShelf?.let { shelf ->
                    shelf.copy(items = if (dedupe) shelf.items.dedupeByStableKey() else shelf.items)
                }
                val withinDedupedByw = if (dedupe) {
                    becauseYouWatched.map { shelf -> shelf.copy(items = shelf.items.dedupeByStableKey()) }
                } else becauseYouWatched
                val withinDedupedAddons = if (dedupe) {
                    addonShelves.map { shelf -> shelf.copy(items = shelf.items.dedupeByStableKey()) }
                } else addonShelves
                val withinDedupedMdbList = if (dedupe) {
                    mdbListShelves.map { shelf -> shelf.copy(items = shelf.items.dedupeByStableKey()) }
                } else mdbListShelves
                val withinDedupedCustom = if (dedupe) {
                    customShelves.mapValues { (_, items) -> items.dedupeByStableKey() }.toMutableMap()
                } else customShelves

                // ── Cross-shelf dedup: each item appears in ONLY the first shelf ──
                if (dedupe) {
                    // 1. Seed global seen set from protected sources (keep their items intact)
                    val globalSeen = mutableSetOf<String>()
                    mergedContinueWatching.forEach { wp ->
                        // WatchProgress items: track by mediaId
                        val key = wp.mediaId
                        if (key.isNotBlank()) globalSeen.add("id:$key")
                        // Also try to extract tmdbId from mediaId (format: "type_tmdbId")
                        val tmdb = key.substringAfterLast("_", "").toIntOrNull()
                        if (tmdb != null && tmdb > 0) {
                            globalSeen.add("${wp.mediaType.name}:$tmdb")
                        }
                    }
                    withinDedupedWatchlist.collectStableKeys(globalSeen)
                    withinDedupedRecents.collectStableKeys(globalSeen)

                    // 2. Cross-dedup main TMDB shelves (Popular, Now Playing, Trending, etc.)
                    val finalShelves = parentalFilteredShelves.dedupeAcrossShelves(globalSeen)

                    // 3. Cross-dedup recommendations against seen items
                    val finalRecommendations = run {
                        val map = LinkedHashMap<String, ScoredMediaItem>()
                        for (scored in filteredRecommendations) {
                            val key = scored.item.stableKey()
                            if (key !in globalSeen && !map.containsKey(key)) {
                                map[key] = scored
                                globalSeen.add(key)
                            }
                        }
                        map.values.toList()
                    }

                    // 4. Cross-dedup hidden gems
                    val finalHiddenGems = withinDedupedHiddenGems?.let { shelf ->
                        val filtered = shelf.items.filter { item ->
                            val key = item.stableKey()
                            if (key in globalSeen) false
                            else { globalSeen.add(key); true }
                        }
                        if (filtered.isEmpty()) null else shelf.copy(items = filtered)
                    }

                    // 5. Cross-dedup Because You Watched, Addon, MDBList shelves
                    val finalByw = withinDedupedByw.dedupeAcrossShelves(globalSeen)
                    val finalAddons = withinDedupedAddons.dedupeAcrossShelves(globalSeen)
                    val finalMdbList = withinDedupedMdbList.dedupeAcrossShelves(globalSeen)

                    // 6. Cross-dedup custom shelves
                    val finalCustom = withinDedupedCustom.mapValues { (_, items) ->
                        items.filter { item ->
                            val key = item.stableKey()
                            if (key in globalSeen) false
                            else { globalSeen.add(key); true }
                        }
                    }.filter { it.value.isNotEmpty() }.toMutableMap()

                    _state.update {
                        it.copy(
                            shelves = finalShelves,
                            heroItem = finalShelves.firstOrNull()?.items?.firstOrNull(),
                    continueWatching = mergedContinueWatching,
                            recommendedItems = finalRecommendations,
                            watchlistShelf = watchlistShelf,
                            watchlistItems = withinDedupedWatchlist,
                            becauseYouWatched = finalByw,
                            hiddenGemsShelf = finalHiddenGems,
                            recentlyWatched = withinDedupedRecents,
                            popularActors = popularActors,
                            popularDirectors = popularDirectors,
                            customShelves = finalCustom,
                            addonShelves = finalAddons,
                            addonShelfVisibility = _addonShelfVisibility.value,
                            mdbListShelves = finalMdbList,
                            isLoading = false,
                        )
                    }
                    torveVerboseLog {
                        "HOME_TAB state_transition state=success shelves=${_state.value.shelves.size} recommendations=${_state.value.recommendedItems.size}"
                    }
                } else {
                    // No dedup — pass through as-is
                    _state.update {
                        it.copy(
                            shelves = parentalFilteredShelves,
                            heroItem = parentalFilteredShelves.firstOrNull()?.items?.firstOrNull(),
                            continueWatching = mergedContinueWatching,
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
                            addonShelfVisibility = _addonShelfVisibility.value,
                            mdbListShelves = mdbListShelves,
                            isLoading = false,
                        )
                    }
                    torveVerboseLog {
                        "HOME_TAB state_transition state=success shelves=${_state.value.shelves.size} recommendations=${_state.value.recommendedItems.size}"
                    }
                }

                loadProviderLogos()

                // Background enrichment: add MDBList multi-source ratings
                launchRatingsEnrichment()
                launchArtworkBackfill()
            } catch (e: Exception) {
                torveVerboseLog {
                    "HOME_TAB state_transition state=error ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
                }
                _state.update { it.copy(isLoading = false, error = homeContentLoadErrorMessage()) }
            }
        }
    }

    // ── Search ──

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchQueryFlow.value = query
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
        searchQueryFlow.value = ""
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        scope.launch {
            searchQueryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.length < 2) {
                        _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
                        return@collect
                    }
                    _state.update { it.copy(isSearching = true) }
                    try {
                        val result = metadataRepo.searchMultiPaged(query, page = 1, type = null)
                        _state.update { it.copy(searchResults = result.items, isSearching = false) }
                    } catch (_: Exception) {
                        _state.update { it.copy(isSearching = false) }
                    }
                }
        }
    }

    fun refresh() {
        loadHomeScreen()
    }

    private suspend fun shouldDedupe(): Boolean {
        return prefsRepo.getString(SettingsViewModel.KEY_DEDUPE_RESULTS)?.toBooleanStrictOrNull() ?: true
    }

    private fun launchArtworkBackfill() {
        scope.launch {
            val requests = collectArtworkBackfillRequests(_state.value)
            if (requests.isEmpty()) return@launch

            val backfilledArtwork = coroutineScope {
                requests.map { request ->
                    async {
                        try {
                            val detail = metadataRepo.getDetail(request.type.toMetadataType(), request.tmdbId)
                            if (detail.posterUrl.isNullOrBlank() && detail.backdropUrl.isNullOrBlank()) {
                                null
                            } else {
                                artworkBackfillKey(request.type, request.tmdbId) to detail
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.mapNotNull { it.await() }.toMap()
            }

            if (backfilledArtwork.isEmpty()) return@launch

            _state.update { current ->
                current.applyArtworkBackfill(backfilledArtwork)
            }
        }
    }

    private fun launchRatingsEnrichment() {
        scope.launch {
            // Purge expired cache entries (older than 30 days)
            try { ratingsEnricher.clearExpiredCache() } catch (_: Exception) { }

            val apiKey = try {
                integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }
            refreshRatings(apiKey)
        }
    }

    fun refreshRatings(apiKey: String) {
        if (apiKey.isBlank()) return
        scope.launch {
            val current = _state.value
            val enrichedShelves = current.shelves.map { shelf ->
                shelf.copy(items = ratingsEnricher.enrichList(shelf.items, apiKey))
            }
            val enrichedAddonShelves = current.addonShelves.map { shelf ->
                shelf.copy(items = ratingsEnricher.enrichList(shelf.items, apiKey))
            }
            val enrichedMdbListShelves = current.mdbListShelves.map { shelf ->
                shelf.copy(items = ratingsEnricher.enrichList(shelf.items, apiKey))
            }
            val enrichedCustomShelves = current.customShelves.mapValues { (_, items) ->
                ratingsEnricher.enrichList(items, apiKey)
            }.toMutableMap()
            val enrichedHiddenGems = current.hiddenGemsShelf?.let { shelf ->
                shelf.copy(items = ratingsEnricher.enrichList(shelf.items, apiKey))
            }
            val enrichedWatchlist = ratingsEnricher.enrichList(current.watchlistItems, apiKey)
            // Build ratings lookup for continue watching from all enriched items
            val allItems = enrichedShelves.flatMap { it.items } +
                enrichedAddonShelves.flatMap { it.items } +
                enrichedMdbListShelves.flatMap { it.items } +
                enrichedCustomShelves.values.flatten() +
                enrichedWatchlist +
                (enrichedHiddenGems?.items ?: emptyList())
            val ratingsMap = mutableMapOf<String, MediaRatings>()
            allItems.forEach { item ->
                val r = item.ratings ?: return@forEach
                ratingsMap[item.id] = r
                item.tmdbId?.let { ratingsMap[it.toString()] = r }
                item.imdbId?.let { ratingsMap[it] = r }
            }
            _state.update {
                it.copy(
                    shelves = enrichedShelves,
                    addonShelves = enrichedAddonShelves,
                    mdbListShelves = enrichedMdbListShelves,
                    customShelves = enrichedCustomShelves,
                    hiddenGemsShelf = enrichedHiddenGems,
                    watchlistItems = enrichedWatchlist,
                    continueWatchingRatings = ratingsMap,
                )
            }
        }
    }

    private fun collectArtworkBackfillRequests(state: HomeUiState): List<ArtworkBackfillRequest> {
        return buildList {
            addAll(state.shelves.flatMap { it.items })
            addAll(state.watchlistItems)
            addAll(state.becauseYouWatched.flatMap { it.items })
            state.hiddenGemsShelf?.let { addAll(it.items) }
            addAll(state.recentlyWatched)
            addAll(state.customShelves.values.flatten())
            addAll(state.addonShelves.flatMap { it.items })
            addAll(state.mdbListShelves.flatMap { it.items })
            addAll(state.recommendedItems.map { it.item })
        }
            .asSequence()
            .filter { it.needsArtworkBackfill() }
            .mapNotNull { item ->
                item.tmdbId?.let { tmdbId ->
                    ArtworkBackfillRequest(type = item.type, tmdbId = tmdbId)
                }
            }
            .distinctBy { artworkBackfillKey(it.type, it.tmdbId) }
            .take(40)
            .toList()
    }

    private fun HomeUiState.applyArtworkBackfill(backfilledArtwork: Map<String, MediaItem>): HomeUiState {
        val updatedShelves = shelves.map { shelf ->
            shelf.copy(items = shelf.items.map { it.applyArtworkBackfill(backfilledArtwork) })
        }
        val updatedWatchlistItems = watchlistItems.map { it.applyArtworkBackfill(backfilledArtwork) }
        return copy(
            shelves = updatedShelves,
            heroItem = heroItem?.applyArtworkBackfill(backfilledArtwork)
                ?: updatedShelves.firstOrNull()?.items?.firstOrNull(),
            recommendedItems = recommendedItems.map { scored ->
                scored.copy(item = scored.item.applyArtworkBackfill(backfilledArtwork))
            },
            watchlistItems = updatedWatchlistItems,
            watchlistShelf = watchlistShelf?.copy(items = updatedWatchlistItems),
            becauseYouWatched = becauseYouWatched.map { shelf ->
                shelf.copy(items = shelf.items.map { it.applyArtworkBackfill(backfilledArtwork) })
            },
            hiddenGemsShelf = hiddenGemsShelf?.copy(
                items = hiddenGemsShelf.items.map { it.applyArtworkBackfill(backfilledArtwork) }
            ),
            recentlyWatched = recentlyWatched.map { it.applyArtworkBackfill(backfilledArtwork) },
            customShelves = customShelves.mapValues { (_, items) ->
                items.map { it.applyArtworkBackfill(backfilledArtwork) }
            }.toMutableMap(),
            addonShelves = addonShelves.map { shelf ->
                shelf.copy(items = shelf.items.map { it.applyArtworkBackfill(backfilledArtwork) })
            },
            mdbListShelves = mdbListShelves.map { shelf ->
                shelf.copy(items = shelf.items.map { it.applyArtworkBackfill(backfilledArtwork) })
            },
        )
    }

    private fun MediaItem.applyArtworkBackfill(backfilledArtwork: Map<String, MediaItem>): MediaItem {
        val tmdbId = tmdbId ?: return this
        if (!needsArtworkBackfill()) return this
        val detail = backfilledArtwork[artworkBackfillKey(type, tmdbId)] ?: return this
        return copy(
            posterUrl = posterUrl.takeUnless { it.isNullOrBlank() } ?: detail.posterUrl,
            backdropUrl = backdropUrl.takeUnless { it.isNullOrBlank() } ?: detail.backdropUrl,
            logoUrl = logoUrl.takeUnless { it.isNullOrBlank() } ?: detail.logoUrl,
        )
    }

    private fun MediaItem.needsArtworkBackfill(): Boolean {
        return tmdbId != null && posterUrl.isNullOrBlank() && backdropUrl.isNullOrBlank()
    }

    private fun MediaType.toMetadataType(): String = when (this) {
        MediaType.MOVIE -> "movie"
        MediaType.SERIES -> "tv"
    }

    private fun artworkBackfillKey(type: MediaType, tmdbId: Int): String = "${type.name}:$tmdbId"

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
