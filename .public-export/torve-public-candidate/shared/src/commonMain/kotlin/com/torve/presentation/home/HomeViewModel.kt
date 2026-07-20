package com.torve.presentation.home

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.CustomSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.HomeSection
import com.torve.domain.model.CardStylePreset
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.ParentalFilter
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.ShelfConfig
import com.torve.domain.model.WatchlistItem
import com.torve.domain.model.AddonPolicyFlags
import com.torve.domain.model.collectStableKeys
import com.torve.domain.model.dedupeAcrossShelves
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.ratingEnrichmentLookupKeys
import com.torve.domain.model.stableKey
import com.torve.domain.model.updateSectionPresetId
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.home.HomeLayoutSourceSelector
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
import com.torve.data.contentpolicy.AddonPolicyRepository
import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.MdbListRepository
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.network.homeContentLoadErrorMessage
import com.torve.data.network.sanitizeNetworkDiagnosticText
import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.data.trakt.TraktNetworkException
import com.torve.data.trakt.TraktRateLimitedException
import com.torve.data.trakt.TraktServerException
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.api.TraktAuthorizationRequiredException
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.platform.torveVerboseLog
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(FlowPreview::class)
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
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
    private val addonPolicyRepository: AddonPolicyRepository? = null,
    private val traktApi: TraktAuthorizedApi? = null,
    invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator? = null,
    private val layoutSourceSelector: HomeLayoutSourceSelector = HomeLayoutSourceSelector(prefsRepo),
) {
    private data class ArtworkBackfillRequest(
        val type: MediaType,
        val tmdbId: Int,
    )

    private data class HomeLoadInputs(
        val shelves: List<CatalogShelf>,
        val installedAddons: List<InstalledAddon>,
        val continueWatching: List<com.torve.domain.model.WatchProgress>,
        val overlayContinue: List<com.torve.domain.model.WatchProgress>,
        val recommendations: List<ScoredMediaItem>,
        val watchlistItems: List<WatchlistItem>,
        val recentHistory: List<com.torve.domain.model.WatchHistoryEntry>,
        val hiddenGems: List<MediaItem>,
        val recentlyWatched: List<MediaItem>,
        val upcomingSchedule: UpcomingScheduleLoadResult,
        val popularPeople: List<PersonSummary>,
        val addonShelves: List<CatalogShelf>,
        val mdbListShelves: List<CatalogShelf>,
    )

    private data class UpcomingScheduleLoadResult(
        val items: List<MediaItem>,
        val status: UpcomingScheduleStatus,
    )

    @Serializable
    private data class HomeSnapshot(
        val savedAtMs: Long,
        val shelves: List<CatalogShelf> = emptyList(),
        val heroItem: MediaItem? = null,
        val continueWatching: List<com.torve.domain.model.WatchProgress> = emptyList(),
        val watchlistShelf: CatalogShelf? = null,
        val watchlistItems: List<MediaItem> = emptyList(),
        val becauseYouWatched: List<CatalogShelf> = emptyList(),
        val hiddenGemsShelf: CatalogShelf? = null,
        val recentlyWatched: List<MediaItem> = emptyList(),
        val upcomingSchedule: List<MediaItem> = emptyList(),
        val customShelves: Map<String, List<MediaItem>> = emptyMap(),
        val addonShelves: List<CatalogShelf> = emptyList(),
        val addonShelfVisibility: Map<String, Boolean> = emptyMap(),
        val mdbListShelves: List<CatalogShelf> = emptyList(),
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
    private var homeLoadJob: Job? = null
    private var homeAutoRetryCount = 0
    private var upcomingScheduleRefreshJob: Job? = null
    private var lastTraktScheduleRefreshAtMs = 0L
    private var lastTraktConnectedForSchedule: Boolean? = null

    init {
        scope.launch {
            _sectionConfigs.value = loadSectionConfigs()
            _enabledServiceIds.value = loadEnabledServiceIds()
            _customSections.value = loadCustomSections()
            _addonShelfVisibility.value = loadAddonShelfVisibility()
            _homeLayoutOrder.value = ensureAllSectionsInLayoutOrder(loadHomeLayoutOrder())
            restoreFreshHomeSnapshot()
            loadHomeScreen()
            scheduleUpcomingScheduleRefresh("startup")
        }
        scope.launch {
            // Debounce: on app start the settings-refresh notifier fires
            // 3-5 times in quick succession (account coordinator, panda
            // onboarding check, addon sync). Without debouncing, each one
            // re-runs loadHomeScreen() → re-enriches ratings → hits the
            // MDBList 429 rate limit → pills vanish for a minute.
            settingsRefreshNotifier.events
                .debounce(500L)
                .collect {
                    _sectionConfigs.value = loadSectionConfigs()
                    _homeLayoutOrder.value = ensureAllSectionsInLayoutOrder(loadHomeLayoutOrder())
                    loadHomeScreen()
                    scheduleUpcomingScheduleRefresh("settings_refresh")
                }
        }
        if (invalidationCoordinator != null) {
            scope.launch {
                invalidationCoordinator.events
                    .debounce(500L)
                    .collectLatest {
                        _state.value = HomeUiState(isLoading = true)
                        loadHomeScreen()
                    }
            }
        }
        // Relock hardening: observe content policy state directly. When policy
        // transitions to locked, immediately clear all visible content so that
        // sensitive material disappears without waiting for the full reload.
        if (contentPolicyRepository != null) {
            scope.launch {
                var wasLocked = contentPolicyRepository.state.value.isLocked
                contentPolicyRepository.state.collectLatest { policy ->
                    val nowLocked = policy.isLocked
                    if (nowLocked && !wasLocked) {
                        // Transition to locked — clear all content immediately.
                        // This guarantees no sensitive artwork remains visible while
                        // the full reload (triggered by cache invalidation) is pending.
                        _state.update { it.copy(
                            shelves = emptyList(),
                            heroItem = null,
                            continueWatching = emptyList(),
                            continueWatchingRatings = emptyMap(),
                            recommendedItems = emptyList(),
                            upcomingSchedule = emptyList(),
                            upcomingScheduleStatus = UpcomingScheduleStatus.LOADING,
                            watchlistShelf = null,
                            watchlistItems = emptyList(),
                            customShelves = emptyMap(),
                            addonShelves = emptyList(),
                            mdbListShelves = emptyList(),
                            becauseYouWatched = emptyList(),
                            hiddenGemsShelf = null,
                            recentlyWatched = emptyList(),
                            searchResults = emptyList(),
                            isLoading = true,
                        ) }
                    }
                    wasLocked = nowLocked
                }
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
        val key = layoutSourceSelector.sectionConfigsKey()
        val saved = try { prefsRepo.getString(key) } catch (_: Exception) { null }
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
        HomeSection.entries.map { section ->
            // Continue Watching ships pre-bound to the built-in
            // "landscape-default" preset so freshly-installed users see
            // backdrops there (which are designed for the in-progress
            // strip) while every other shelf shows portrait posters via
            // the global default.
            val presetId = if (section == HomeSection.CONTINUE_WATCHING) "landscape-default" else null
            HomeSectionConfig(section, section.defaultEnabled, section.defaultOrder, presetId = presetId)
        }

    fun updateSectionOrder(configs: List<HomeSectionConfig>) {
        _sectionConfigs.value = configs
        saveSectionConfigs(configs)
        // Sync the layout order so TvHomeScreen picks up the new ordering.
        val order = configs.sortedBy { it.order }.map { "section:${it.section.name}" }
        updateHomeLayoutOrder(order)
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
        updateHomeLayoutOrder(emptyList())
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
                val key = layoutSourceSelector.sectionConfigsKey()
                prefsRepo.setString(key, json.encodeToString(configs))
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
        val key = layoutSourceSelector.homeLayoutOrderKey()
        val saved = try { prefsRepo.getString(key) } catch (_: Exception) { null }
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
            try {
                val key = layoutSourceSelector.homeLayoutOrderKey()
                prefsRepo.setString(key, json.encodeToString(order))
            } catch (_: Exception) { /* ignore */ }
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

    private suspend fun restoreFreshHomeSnapshot(): Boolean {
        val saved = try { prefsRepo.getString(HOME_SNAPSHOT_KEY) } catch (_: Exception) { null }
        if (saved.isNullOrBlank()) return false
        val snapshot = try {
            json.decodeFromString<HomeSnapshot>(saved)
        } catch (_: Exception) {
            return false
        }
        if (currentTimeMillis() - snapshot.savedAtMs > HOME_SNAPSHOT_MAX_AGE_MS) return false
        val restoredAddonShelves = snapshot.addonShelves.withoutProviderOwnedHomeCatalogs()
        if (
            snapshot.shelves.isEmpty() &&
            snapshot.continueWatching.isEmpty() &&
            snapshot.upcomingSchedule.isEmpty() &&
            snapshot.watchlistItems.isEmpty() &&
            restoredAddonShelves.isEmpty() &&
            snapshot.mdbListShelves.isEmpty()
        ) {
            return false
        }
        val hydratedContinueWatching = hydrateContinueWatchingFromCache(snapshot.continueWatching)
        _state.value = _state.value.copy(
            shelves = snapshot.shelves,
            heroItem = snapshot.heroItem ?: snapshot.shelves.firstOrNull()?.items?.firstOrNull(),
            continueWatching = snapshot.continueWatching,
            upcomingSchedule = snapshot.upcomingSchedule,
            upcomingScheduleStatus = if (snapshot.upcomingSchedule.isNotEmpty()) {
                UpcomingScheduleStatus.STALE
            } else {
                UpcomingScheduleStatus.LOADING
            },
            continueWatchingRatings = buildRatingsLookup(
                hydratedContinueWatching +
                    snapshot.shelves.flatMap { it.items } +
                    snapshot.upcomingSchedule +
                    snapshot.watchlistItems +
                    snapshot.becauseYouWatched.flatMap { it.items } +
                    (snapshot.hiddenGemsShelf?.items ?: emptyList()) +
                    snapshot.recentlyWatched +
                    snapshot.customShelves.values.flatten() +
                    restoredAddonShelves.flatMap { it.items } +
                    snapshot.mdbListShelves.flatMap { it.items },
            ),
            watchlistShelf = snapshot.watchlistShelf,
            watchlistItems = snapshot.watchlistItems,
            becauseYouWatched = snapshot.becauseYouWatched,
            hiddenGemsShelf = snapshot.hiddenGemsShelf,
            recentlyWatched = snapshot.recentlyWatched,
            customShelves = snapshot.customShelves,
            addonShelves = restoredAddonShelves,
            addonShelfVisibility = snapshot.addonShelfVisibility,
            mdbListShelves = snapshot.mdbListShelves,
            isLoading = false,
            error = null,
        )
        torveVerboseLog { "HOME_TAB restored persisted snapshot shelves=${snapshot.shelves.size}" }
        return true
    }

    private suspend fun persistHomeSnapshot(state: HomeUiState) {
        if (!state.hasRenderableContent()) return
        val snapshot = HomeSnapshot(
            savedAtMs = currentTimeMillis(),
            shelves = state.shelves,
            heroItem = state.heroItem,
            continueWatching = state.continueWatching,
            upcomingSchedule = state.upcomingSchedule,
            watchlistShelf = state.watchlistShelf,
            watchlistItems = state.watchlistItems,
            becauseYouWatched = state.becauseYouWatched,
            hiddenGemsShelf = state.hiddenGemsShelf,
            recentlyWatched = state.recentlyWatched,
            customShelves = state.customShelves,
            addonShelves = state.addonShelves.withoutProviderOwnedHomeCatalogs(),
            addonShelfVisibility = state.addonShelfVisibility,
            mdbListShelves = state.mdbListShelves,
        )
        runCatching { prefsRepo.setString(HOME_SNAPSHOT_KEY, json.encodeToString(snapshot)) }
    }

    private fun currentTimeMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    private fun List<CatalogShelf>.withoutProviderOwnedHomeCatalogs(): List<CatalogShelf> =
        filterNot { it.isProviderOwnedHomeCatalog() }

    private fun CatalogShelf.isProviderOwnedHomeCatalog(): Boolean {
        val titleKey = title.normalizedHomeCatalogLabel()
        val idKey = id.normalizedHomeCatalogLabel()
        return titleKey in HIDDEN_PROVIDER_HOME_CATALOG_LABELS ||
            idKey in HIDDEN_PROVIDER_HOME_CATALOG_LABELS ||
            titleKey.startsWith("yourmedia") ||
            idKey.contains("yourmedia")
    }

    private fun String.normalizedHomeCatalogLabel(): String =
        lowercase().filter { it.isLetterOrDigit() }

    fun loadHomeScreen() {
        val keepContentVisible = _state.value.hasRenderableContent()
        homeLoadJob?.cancel()
        homeLoadJob = scope.launch {
            torveVerboseLog { "HOME_TAB bootstrap_start" }
            if (keepContentVisible) {
                _state.update { it.copy(isLoading = false, error = null) }
            } else {
                _state.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val dedupe = shouldDedupe()
                val loadInputs = supervisorScope {
                    torveVerboseLog { "HOME_TAB repository_fetch_start source=home_shelves" }
                    val shelvesDeferred = async { metadataRepo.getHomeShelves() }
                    val installedAddonsDeferred = async {
                        try {
                            addonRepo.getInstalledAddons()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
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
                            // Pull a wide window so dedup-by-show has material to
                            // collapse across. The Because-You-Watched shelf caps
                            // at one rail after dedup (see below); the window also
                            // feeds the recently-watched dedup loop.
                            watchHistoryRepo.getRecent(40)
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
                            val seen = mutableSetOf<String>()
                            val out = mutableListOf<MediaItem>()
                            for (entry in watchHistoryRepo.getRecent(80)) {
                                // Synthetic mediaIds from openPreResolvedStream
                                // (NZB-sourced playbacks) carry no catalog
                                // metadata; Adult/Sports are also excluded
                                // by product policy. Drop them from history.
                                if (entry.mediaId.startsWith("direct:")) continue
                                // DB stores "series" / "movie" (lowercase) — match
                                // case-insensitively, also tolerate legacy "tv".
                                val mt = entry.mediaType.lowercase()
                                val isSeries = mt == "series" || mt == "tv" ||
                                    entry.seasonNumber != null || entry.episodeNumber != null
                                val tmdb = entry.mediaId.extractTmdbIdOrNull()
                                val imdb = entry.mediaId.extractImdbIdOrNull()
                                // Stable show-level key so multiple episodes of the
                                // same series collapse into a single card. Never
                                // surface individual episode posters or titles.
                                val showKey = tmdb?.toString() ?: imdb ?: entry.mediaId
                                if (showKey in seen) continue
                                seen += showKey
                                // For series, the card MUST reference the show
                                // (show title + show poster) — never the played
                                // episode — so "The Boys" is one card, not one per
                                // episode.
                                val displayTitle = if (isSeries) {
                                    entry.showTitle?.takeIf { it.isNotBlank() } ?: entry.title
                                } else {
                                    entry.title
                                }
                                out += MediaItem(
                                    id = showKey,
                                    tmdbId = tmdb,
                                    imdbId = imdb,
                                    type = if (isSeries) MediaType.SERIES else MediaType.MOVIE,
                                    title = displayTitle,
                                    posterUrl = entry.posterUrl,
                                    backdropUrl = entry.backdropUrl,
                                )
                            }
                            resolveImdbToTmdb(out)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    val upcomingScheduleDeferred = async {
                        loadUpcomingSchedule()
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
                            val addons = installedAddonsDeferred.await()
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
                            val apiKey = integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                                ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                                ?: ""
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
                        installedAddons = installedAddonsDeferred.await(),
                        continueWatching = continueWatchingDeferred.await(),
                        overlayContinue = overlayContinueDeferred.await(),
                        recommendations = recommendationsDeferred.await(),
                        watchlistItems = watchlistDeferred.await(),
                        recentHistory = historyDeferred.await(),
                        hiddenGems = hiddenGemsDeferred.await(),
                        recentlyWatched = recentlyWatchedDeferred.await(),
                        upcomingSchedule = upcomingScheduleDeferred.await(),
                        popularPeople = popularPeopleDeferred.await(),
                        addonShelves = addonShelvesDeferred.await(),
                        mdbListShelves = mdbListShelvesDeferred.await(),
                    )
                }

                val shelves = loadInputs.shelves
                val continueWatching = loadInputs.continueWatching
                val overlayContinue = loadInputs.overlayContinue
                val mergedContinueWatching = (continueWatching + overlayContinue)
                    // Synthetic mediaIds emitted by openPreResolvedStream
                    // (NZB-sourced playbacks from the Adult / Sports /
                    // Movies-via-Usenet tabs) carry no catalog metadata
                    // and would render as posterless rows. Adult & Sports
                    // are also excluded by product policy. Drop all
                    // `direct:*` entries from the rail.
                    .filterNot { it.mediaId.startsWith("direct:") }
                    .groupBy { "${it.mediaType.name}:${it.mediaId}" }
                    .mapNotNull { (_, entries) -> entries.maxByOrNull { it.updatedAt } }
                    .sortedByDescending { it.updatedAt }
                    .take(20)
                val recommendations = loadInputs.recommendations
                val watchlistItems = loadInputs.watchlistItems
                val recentHistory = loadInputs.recentHistory
                val hiddenGems = loadInputs.hiddenGems
                val recentlyWatched = loadInputs.recentlyWatched
                val upcomingScheduleResult = loadInputs.upcomingSchedule
                val upcomingSchedule = upcomingScheduleResult.items
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
                val installedAddons = loadInputs.installedAddons
                val addonShelves = loadInputs.addonShelves.withoutProviderOwnedHomeCatalogs()
                val mdbListShelves = loadInputs.mdbListShelves
                val policy = currentPolicy()
                val addonFlagsByShelfId = addonShelfPolicyFlags(installedAddons)

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

                // Build a single "Because You Watched" shelf from the most
                // recently watched show/movie. Deduping by show means binging
                // three episodes of one series no longer stacks three near-
                // identical rails — we pick the first distinct title and stop.
                // For series, the display name and TMDB lookup both target the
                // show (not the individual episode), matching the recently-
                // watched card policy a few blocks above.
                val becauseYouWatched = run {
                    val seenShowKeys = mutableSetOf<String>()
                    for (entry in recentHistory) {
                        val mt = entry.mediaType.lowercase()
                        val isSeries = mt == "series" || mt == "tv" ||
                            entry.seasonNumber != null || entry.episodeNumber != null
                        val tmdbId = entry.mediaId.extractTmdbIdOrNull() ?: continue
                        val showKey = tmdbId.toString()
                        if (!seenShowKeys.add(showKey)) continue
                        val displayTitle = if (isSeries) {
                            entry.showTitle?.takeIf { it.isNotBlank() } ?: entry.title
                        } else {
                            entry.title
                        }
                        val type = if (isSeries) "tv" else "movie"
                        val similar = try {
                            metadataRepo.getSimilar(type, tmdbId).take(20)
                        } catch (_: Exception) {
                            continue
                        }
                        if (similar.isEmpty()) continue
                        return@run listOf(
                            CatalogShelf(
                                id = "because_$showKey",
                                title = "Because You Watched $displayTitle",
                                items = similar,
                            ),
                        )
                    }
                    emptyList<CatalogShelf>()
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

                val policyFilteredShelves = filterShelves(
                    policy = policy,
                    context = ContentAccessContext.DEFAULT_DISCOVERY,
                    shelves = parentalFilteredShelves,
                    sourceType = ContentSourceType.TMDB,
                )
                val policyFilteredRecommendations = filterRecommendations(
                    policy = policy,
                    context = ContentAccessContext.GLOBAL_RECOMMENDATION,
                    items = filteredRecommendations,
                    sourceType = ContentSourceType.TMDB,
                )
                val policyFilteredWatchProgress = contentPolicyFilter.filterWatchProgress(
                    policy = policy,
                    context = ContentAccessContext.HISTORY_DERIVED,
                    items = mergedContinueWatching,
                ).items
                val policyFilteredWatchlist = contentPolicyFilter.filterItems(
                    policy = policy,
                    context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
                    items = watchlistMediaItems,
                    sourceType = ContentSourceType.LOCAL_LIBRARY,
                ).items
                val policyFilteredWatchlistShelf = watchlistShelf
                    ?.copy(items = policyFilteredWatchlist)
                    ?.takeIf { it.items.isNotEmpty() }
                val policyFilteredUpcomingSchedule = contentPolicyFilter.filterItems(
                    policy = policy,
                    context = ContentAccessContext.HISTORY_DERIVED,
                    items = upcomingSchedule,
                    sourceType = ContentSourceType.LOCAL_LIBRARY,
                ).items
                val policyFilteredByw = filterShelves(
                    policy = policy,
                    context = ContentAccessContext.HISTORY_DERIVED,
                    shelves = becauseYouWatched,
                    sourceType = ContentSourceType.TMDB,
                )
                val policyFilteredHiddenGems = hiddenGemsShelf?.copy(
                    items = contentPolicyFilter.filterItems(
                        policy = policy,
                        context = ContentAccessContext.DEFAULT_DISCOVERY,
                        items = hiddenGemsShelf.items,
                        sourceType = ContentSourceType.TMDB,
                    ).items,
                )?.takeIf { it.items.isNotEmpty() }
                val policyFilteredRecents = contentPolicyFilter.filterItems(
                    policy = policy,
                    context = ContentAccessContext.HISTORY_DERIVED,
                    items = recentlyWatched,
                    sourceType = ContentSourceType.LOCAL_LIBRARY,
                ).items
                val policyFilteredCustom = customShelves
                    .mapValues { (_, items) ->
                        contentPolicyFilter.filterItems(
                            policy = policy,
                            context = ContentAccessContext.DEFAULT_DISCOVERY,
                            items = items,
                            sourceType = ContentSourceType.TMDB,
                        ).items
                    }
                    .filterValues { it.isNotEmpty() }
                    .toMutableMap()
                val policyFilteredAddonShelves = addonShelves
                    .filter { shelf -> addonFlagsByShelfId[shelf.id]?.shelfEligible != false }
                    .mapNotNull { shelf ->
                        val filteredItems = contentPolicyFilter.filterItems(
                            policy = policy,
                            context = ContentAccessContext.ADDON_SHELF,
                            items = shelf.items,
                            sourceType = ContentSourceType.ADDON,
                            addonPolicyFlags = addonFlagsByShelfId[shelf.id],
                        ).items
                        shelf.copy(items = filteredItems).takeIf { filteredItems.isNotEmpty() }
                    }
                val policyFilteredMdbListShelves = filterShelves(
                    policy = policy,
                    context = ContentAccessContext.DEFAULT_DISCOVERY,
                    shelves = mdbListShelves,
                    sourceType = ContentSourceType.MDBLIST,
                )

                // Within-shelf dedup first
                val withinDedupedWatchlist = if (dedupe) policyFilteredWatchlist.dedupeByStableKey() else policyFilteredWatchlist
                val withinDedupedUpcomingSchedule = if (dedupe) policyFilteredUpcomingSchedule.dedupeByStableKey() else policyFilteredUpcomingSchedule
                val withinDedupedRecents = if (dedupe) policyFilteredRecents.dedupeByStableKey() else policyFilteredRecents
                val withinDedupedHiddenGems = policyFilteredHiddenGems?.let { shelf ->
                    shelf.copy(items = if (dedupe) shelf.items.dedupeByStableKey() else shelf.items)
                }
                val withinDedupedByw = if (dedupe) {
                    policyFilteredByw.map { shelf -> shelf.copy(items = shelf.items.dedupeByStableKey()) }
                } else policyFilteredByw
                val withinDedupedAddons = if (dedupe) {
                    policyFilteredAddonShelves.map { shelf -> shelf.copy(items = shelf.items.dedupeByStableKey()) }
                } else policyFilteredAddonShelves
                val withinDedupedMdbList = if (dedupe) {
                    policyFilteredMdbListShelves.map { shelf -> shelf.copy(items = shelf.items.dedupeByStableKey()) }
                } else policyFilteredMdbListShelves
                val withinDedupedCustom = if (dedupe) {
                    policyFilteredCustom.mapValues { (_, items) -> items.dedupeByStableKey() }.toMutableMap()
                } else policyFilteredCustom

                // ── Cross-shelf dedup: each item appears in ONLY the first shelf ──
                if (dedupe) {
                    // 1. Seed global seen set from protected sources (keep their items intact)
                    val globalSeen = mutableSetOf<String>()
                    policyFilteredWatchProgress.forEach { wp ->
                        // WatchProgress items: track by mediaId
                        val key = wp.mediaId
                        if (key.isNotBlank()) globalSeen.add("id:$key")
                        // Also try to extract tmdbId from mediaId (format: "type_tmdbId")
                        val tmdb = key.extractTmdbIdOrNull()
                        if (tmdb != null && tmdb > 0) {
                            globalSeen.add("${wp.mediaType.name}:$tmdb")
                        }
                    }
                    withinDedupedWatchlist.collectStableKeys(globalSeen)
                    withinDedupedUpcomingSchedule.collectStableKeys(globalSeen)
                    withinDedupedRecents.collectStableKeys(globalSeen)

                    // 2. Cross-dedup main TMDB shelves (Popular, Now Playing, Trending, etc.)
                    val finalShelves = backfillDiscoveryShelves(
                        shelves = policyFilteredShelves.dedupeAcrossShelves(globalSeen),
                        globalSeen = globalSeen,
                        policy = policy,
                    )

                    // 3. Cross-dedup recommendations against seen items
                    val finalRecommendations = run {
                        val map = LinkedHashMap<String, ScoredMediaItem>()
                        for (scored in policyFilteredRecommendations) {
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
                    val hydratedShelves = hydrateShelvesFromCache(finalShelves)
                    val hydratedRecommendations = hydrateRecommendationsFromCache(finalRecommendations)
                    val hydratedWatchlistItems = hydrateItemsFromCache(withinDedupedWatchlist)
                    val hydratedUpcomingSchedule = hydrateItemsFromCache(withinDedupedUpcomingSchedule)
                        .mergeScheduleVisualsFrom(_state.value.upcomingSchedule)
                    val hydratedWatchlistShelf = policyFilteredWatchlistShelf?.copy(items = hydratedWatchlistItems)
                    val effectiveUpcomingStatus = effectiveUpcomingScheduleStatus(
                        originalStatus = upcomingScheduleResult.status,
                        visibleItems = hydratedUpcomingSchedule,
                    )
                    val hydratedByw = hydrateShelvesFromCache(finalByw)
                    val hydratedHiddenGems = hydrateShelfFromCache(finalHiddenGems)
                    val hydratedRecents = hydrateItemsFromCache(withinDedupedRecents)
                    val hydratedCustom = hydrateCustomShelvesFromCache(finalCustom)
                    val hydratedAddons = hydrateShelvesFromCache(finalAddons)
                    val hydratedMdbList = hydrateShelvesFromCache(finalMdbList)
                    val hydratedContinueWatching = hydrateContinueWatchingFromCache(policyFilteredWatchProgress)
                    val continueWatchingRatings = buildRatingsLookup(
                        hydratedContinueWatching +
                        hydratedShelves.flatMap { shelf -> shelf.items } +
                            hydratedRecommendations.map { scored -> scored.item } +
                            hydratedWatchlistItems +
                            hydratedUpcomingSchedule +
                            hydratedByw.flatMap { shelf -> shelf.items } +
                            (hydratedHiddenGems?.items ?: emptyList()) +
                            hydratedRecents +
                            hydratedCustom.values.flatten() +
                            hydratedAddons.flatMap { shelf -> shelf.items } +
                            hydratedMdbList.flatMap { shelf -> shelf.items },
                    )

                    _state.update {
                        it.copy(
                            shelves = hydratedShelves,
                            heroItem = hydratedShelves.firstOrNull()?.items?.firstOrNull(),
                            continueWatching = policyFilteredWatchProgress,
                            continueWatchingRatings = continueWatchingRatings,
                            recommendedItems = hydratedRecommendations,
                            upcomingSchedule = hydratedUpcomingSchedule,
                            upcomingScheduleStatus = effectiveUpcomingStatus,
                            watchlistShelf = hydratedWatchlistShelf,
                            watchlistItems = hydratedWatchlistItems,
                            becauseYouWatched = hydratedByw,
                            hiddenGemsShelf = hydratedHiddenGems,
                            recentlyWatched = hydratedRecents,
                            popularActors = popularActors,
                            popularDirectors = popularDirectors,
                            customShelves = hydratedCustom,
                            addonShelves = hydratedAddons,
                            addonShelfVisibility = _addonShelfVisibility.value,
                            mdbListShelves = hydratedMdbList,
                            isLoading = false,
                        )
                    }
                    torveVerboseLog {
                        "HOME_TAB state_transition state=success shelves=${_state.value.shelves.size} recommendations=${_state.value.recommendedItems.size}"
                    }
                } else {
                    // No dedup — pass through as-is
                    val hydratedShelves = hydrateShelvesFromCache(policyFilteredShelves)
                    val hydratedRecommendations = hydrateRecommendationsFromCache(policyFilteredRecommendations)
                    val hydratedWatchlistItems = hydrateItemsFromCache(policyFilteredWatchlist)
                    val hydratedUpcomingSchedule = hydrateItemsFromCache(policyFilteredUpcomingSchedule)
                        .mergeScheduleVisualsFrom(_state.value.upcomingSchedule)
                    val hydratedWatchlistShelf = policyFilteredWatchlistShelf?.copy(items = hydratedWatchlistItems)
                    val effectiveUpcomingStatus = effectiveUpcomingScheduleStatus(
                        originalStatus = upcomingScheduleResult.status,
                        visibleItems = hydratedUpcomingSchedule,
                    )
                    val hydratedByw = hydrateShelvesFromCache(policyFilteredByw)
                    val hydratedHiddenGems = hydrateShelfFromCache(policyFilteredHiddenGems)
                    val hydratedRecents = hydrateItemsFromCache(policyFilteredRecents)
                    val hydratedCustom = hydrateCustomShelvesFromCache(policyFilteredCustom)
                    val hydratedAddons = hydrateShelvesFromCache(policyFilteredAddonShelves)
                    val hydratedMdbList = hydrateShelvesFromCache(policyFilteredMdbListShelves)
                    val hydratedContinueWatching = hydrateContinueWatchingFromCache(policyFilteredWatchProgress)
                    val continueWatchingRatings = buildRatingsLookup(
                        hydratedContinueWatching +
                        hydratedShelves.flatMap { shelf -> shelf.items } +
                            hydratedRecommendations.map { scored -> scored.item } +
                            hydratedWatchlistItems +
                            hydratedUpcomingSchedule +
                            hydratedByw.flatMap { shelf -> shelf.items } +
                            (hydratedHiddenGems?.items ?: emptyList()) +
                            hydratedRecents +
                            hydratedCustom.values.flatten() +
                            hydratedAddons.flatMap { shelf -> shelf.items } +
                            hydratedMdbList.flatMap { shelf -> shelf.items },
                    )
                    _state.update {
                        it.copy(
                            shelves = hydratedShelves,
                            heroItem = hydratedShelves.firstOrNull()?.items?.firstOrNull(),
                            continueWatching = policyFilteredWatchProgress,
                            continueWatchingRatings = continueWatchingRatings,
                            recommendedItems = hydratedRecommendations,
                            upcomingSchedule = hydratedUpcomingSchedule,
                            upcomingScheduleStatus = effectiveUpcomingStatus,
                            watchlistShelf = hydratedWatchlistShelf,
                            watchlistItems = hydratedWatchlistItems,
                            becauseYouWatched = hydratedByw,
                            hiddenGemsShelf = hydratedHiddenGems,
                            recentlyWatched = hydratedRecents,
                            popularActors = popularActors,
                            popularDirectors = popularDirectors,
                            customShelves = hydratedCustom,
                            addonShelves = hydratedAddons,
                            addonShelfVisibility = _addonShelfVisibility.value,
                            mdbListShelves = hydratedMdbList,
                            isLoading = false,
                        )
                    }
                    torveVerboseLog {
                        "HOME_TAB state_transition state=success shelves=${_state.value.shelves.size} recommendations=${_state.value.recommendedItems.size}"
                    }
                }

                persistHomeSnapshot(_state.value)
                homeAutoRetryCount = 0
                loadProviderLogos()

                launchRatingsEnrichment()
                launchArtworkBackfill()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                torveVerboseLog {
                    "HOME_TAB state_transition state=error ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
                }
                if (!keepContentVisible) {
                    _state.update { it.copy(isLoading = false, error = homeContentLoadErrorMessage(e)) }
                    scheduleHomeRetry()
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun scheduleHomeRetry() {
        if (homeAutoRetryCount >= HOME_LOAD_AUTO_RETRY_DELAYS_MS.size) return
        val retryDelayMs = HOME_LOAD_AUTO_RETRY_DELAYS_MS[homeAutoRetryCount]
        homeAutoRetryCount += 1
        scope.launch {
            delay(retryDelayMs)
            val current = _state.value
            if (!current.hasRenderableContent() && current.error != null && homeLoadJob?.isActive != true) {
                loadHomeScreen()
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
                .debounce(400)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.length < 2) {
                        _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
                        return@collect
                    }
                    // Only show loading spinner when there are no existing results,
                    // otherwise keep current results visible to avoid flicker.
                    if (_state.value.searchResults.isEmpty()) {
                        _state.update { it.copy(isSearching = true) }
                    }
                    try {
                        val result = metadataRepo.searchMultiPaged(query, page = 1, type = null)
                        val filtered = contentPolicyFilter.filterItems(
                            policy = currentPolicy(),
                            context = ContentAccessContext.SEARCH_SUGGESTION,
                            items = result.items,
                            sourceType = ContentSourceType.TMDB,
                        )
                        _state.update { it.copy(searchResults = filtered.items, isSearching = false) }
                    } catch (_: Exception) {
                        _state.update { it.copy(isSearching = false) }
                    }
                }
        }
    }

    fun refresh() {
        loadHomeScreen()
        scheduleUpcomingScheduleRefresh("manual_refresh", force = true)
    }

    fun refreshUpcomingSchedule() {
        scheduleUpcomingScheduleRefresh("explicit_upcoming_refresh", force = true)
    }

    private suspend fun shouldDedupe(): Boolean {
        return prefsRepo.getString(SettingsViewModel.KEY_DEDUPE_RESULTS)?.toBooleanStrictOrNull() ?: true
    }

    private fun scheduleUpcomingScheduleRefresh(reason: String, force: Boolean = false) {
        if (upcomingScheduleRefreshJob?.isActive == true) return
        upcomingScheduleRefreshJob = scope.launch {
            delay(600L)
            if (!force && homeLoadJob?.isActive == true) return@launch
            val connected = runCatching { traktApi?.hasConnection() == true }.getOrDefault(false)
            val becameConnected = connected && lastTraktConnectedForSchedule != true
            lastTraktConnectedForSchedule = connected
            if (!connected) return@launch
            val existingSchedule = _state.value.upcomingSchedule
            if (
                !force &&
                !becameConnected &&
                existingSchedule.isNotEmpty() &&
                !existingSchedule.needsUpcomingScheduleRefresh()
            ) {
                return@launch
            }

            val now = currentTimeMillis()
            if (!force && !becameConnected && now - lastTraktScheduleRefreshAtMs < TRAKT_SCHEDULE_REFRESH_THROTTLE_MS) {
                return@launch
            }
            lastTraktScheduleRefreshAtMs = now
            torveVerboseLog { "HOME_TAB upcoming_schedule_refresh reason=$reason connected=true" }

            val scheduleResult = loadUpcomingSchedule()
            val schedule = scheduleResult.items
            val filteredSchedule = contentPolicyFilter.filterItems(
                policy = currentPolicy(),
                context = ContentAccessContext.HISTORY_DERIVED,
                items = schedule,
                sourceType = ContentSourceType.LOCAL_LIBRARY,
            ).items
            val hydratedSchedule = hydrateItemsFromCache(filteredSchedule)
                .mergeScheduleVisualsFrom(existingSchedule)
            val effectiveStatus = effectiveUpcomingScheduleStatus(
                originalStatus = scheduleResult.status,
                visibleItems = hydratedSchedule,
            )

            _state.update { state ->
                state.copy(
                    upcomingSchedule = hydratedSchedule,
                    upcomingScheduleStatus = effectiveStatus,
                    continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(hydratedSchedule),
                )
            }
            persistHomeSnapshot(_state.value)
        }
    }

    private suspend fun loadUpcomingSchedule(): UpcomingScheduleLoadResult {
        val api = traktApi ?: return UpcomingScheduleLoadResult(
            items = emptyList(),
            status = UpcomingScheduleStatus.DISCONNECTED,
        )
        val connected = runCatching { api.hasConnection() }.getOrDefault(false)
        if (!connected) {
            return UpcomingScheduleLoadResult(
                items = emptyList(),
                status = UpcomingScheduleStatus.DISCONNECTED,
            )
        }
        val result = try {
            api.getCalendarCached(days = 33)
        } catch (_: TraktAuthorizationRequiredException) {
            return UpcomingScheduleLoadResult(
                items = emptyList(),
                status = UpcomingScheduleStatus.DISCONNECTED,
            )
        } catch (_: TraktRateLimitedException) {
            return UpcomingScheduleLoadResult(
                items = emptyList(),
                status = UpcomingScheduleStatus.RATE_LIMITED,
            )
        } catch (_: TraktNetworkException) {
            return UpcomingScheduleLoadResult(
                items = emptyList(),
                status = UpcomingScheduleStatus.ERROR,
            )
        } catch (_: TraktServerException) {
            return UpcomingScheduleLoadResult(
                items = emptyList(),
                status = UpcomingScheduleStatus.ERROR,
            )
        } catch (_: Exception) {
            return UpcomingScheduleLoadResult(
                items = emptyList(),
                status = UpcomingScheduleStatus.ERROR,
            )
        }
        val episodes = result.episodes
        if (episodes.isEmpty()) {
            return UpcomingScheduleLoadResult(
                items = emptyList(),
                status = resolveUpcomingScheduleStatus(
                    connected = true,
                    itemCount = 0,
                    isStale = result.isStale,
                    isRateLimited = result.refreshError is TraktRateLimitedException,
                ),
            )
        }

        val distinctEpisodes = episodes
            .sortedBy { it.firstAired }
            .distinctBy { episode ->
                "${episode.showTmdbId ?: episode.showTitle}:${episode.season}:${episode.episode}:${episode.firstAired}"
            }
            .take(24)

        val items = distinctEpisodes.map { episode -> episode.toUpcomingScheduleItem() }
        return UpcomingScheduleLoadResult(
            items = items,
            status = resolveUpcomingScheduleStatus(
                connected = true,
                itemCount = items.size,
                isStale = result.isStale,
                isRateLimited = result.refreshError is TraktRateLimitedException,
            ),
        )
    }

    private fun TraktCalendarEpisode.toUpcomingScheduleItem(): MediaItem {
        val airDateTime = firstAired.takeIf { it.isNotBlank() }
        val episodeCode = "S${season}E${episode}"
        val title = buildString {
            append(showTitle)
            append(" - ")
            append(episodeCode)
            if (episodeTitle.isNotBlank()) {
                append(" - ")
                append(episodeTitle)
            }
        }
        val fallbackId = buildString {
            append("trakt-calendar:")
            append(showTmdbId ?: showTitle)
            append(':')
            append(season)
            append(':')
            append(episode)
            append(':')
            append(firstAired)
        }
        return MediaItem(
            id = fallbackId,
            tmdbId = showTmdbId,
            type = MediaType.SERIES,
            title = title,
            year = null,
            releaseDate = airDateTime,
        )
    }

    private fun List<MediaItem>.needsUpcomingScheduleRefresh(): Boolean =
        any { item ->
            item.id.startsWith("trakt-calendar:") &&
                item.releaseDate.isNullOrBlank() &&
                item.id.split(":", limit = 5).getOrNull(4).isNullOrBlank()
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
            persistHomeSnapshot(_state.value)
        }
    }

    private fun launchRatingsEnrichment() {
        scope.launch(Dispatchers.Default) {
            // Purge expired cache entries (older than 30 days)
            try { ratingsEnricher.clearExpiredCache() } catch (_: Exception) { }

            // One-time cache invalidation when enrichment semantics change.
            // Bump RATINGS_CACHE_VERSION in the companion object when the
            // enricher starts sourcing a new provider, changes which pills
            // are populated for a given input, or otherwise would leave
            // stale pre-change entries visually inconsistent with freshly
            // enriched items. Users upgrading to the new build pay one
            // re-enrichment storm; after that, the cache behaves normally.
            try {
                val stored = prefsRepo.getString(KEY_RATINGS_CACHE_VERSION)?.toIntOrNull() ?: 0
                if (stored < RATINGS_CACHE_VERSION) {
                    ratingsEnricher.clearPersistentCache()
                    prefsRepo.setString(KEY_RATINGS_CACHE_VERSION, RATINGS_CACHE_VERSION.toString())
                }
            } catch (_: Exception) { }

            val apiKey = try {
                integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }

            // Run enrichment; if MDBList hit its rate limit mid-pass, wait
            // out the cooldown and run again so the items that got skipped
            // can pick up their MDBList-exclusive fields (Letterboxd, MAL,
            // RT-audience, MDBList score). enrichSingle leaves those items
            // uncached on the first pass so the retry actually re-runs.
            // Capped at 5 iterations to avoid indefinite loops if MDBList
            // is returning 429s for an unrelated reason.
            var iteration = 0
            while (iteration < MAX_RATINGS_ENRICHMENT_ITERATIONS) {
                iteration++
                doRefreshRatings(apiKey)
                val remainingMs = ratingsEnricher.rateLimitRemainingMs()
                if (remainingMs <= 0L) break
                // + buffer so we're comfortably past the cooldown when we retry
                kotlinx.coroutines.delay(remainingMs + 2_000L)
            }
        }
    }

    fun refreshRatings(apiKey: String) {
        scope.launch(Dispatchers.Default) { doRefreshRatings(apiKey) }
    }

    private suspend fun doRefreshRatings(apiKey: String) {
        // Even without an MDBList key, the enricher still resolves IMDB + RT +
        // Metacritic via OMDB and falls back to Trakt for IMDB scores. Bailing
        // early on a blank key meant NO rail ever got rating pills unless the
        // user had configured MDBList.
        //
        // Rails are processed sequentially so state updates (one per rail)
        // arrive paced rather than in a burst. We also skip the _state.update
        // entirely when enrichment didn't change any ratings (e.g. every item
        // hit the cache) — emitting a new list reference forces a LazyRow
        // recomposition that can cancel an in-flight `pointerInput` coroutine
        // on a poster card, causing taps to be dropped.
        val current = _state.value

        // Catalog-shelf groups: shelves, addonShelves, mdbListShelves, becauseYouWatched
        current.shelves.forEach { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                state.copy(shelves = state.shelves.replaceById(updated))
            }
        }
        current.addonShelves.forEach { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                state.copy(addonShelves = state.addonShelves.replaceById(updated))
            }
        }
        current.mdbListShelves.forEach { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                state.copy(mdbListShelves = state.mdbListShelves.replaceById(updated))
            }
        }
        current.becauseYouWatched.forEach { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                state.copy(becauseYouWatched = state.becauseYouWatched.replaceById(updated))
            }
        }

        // Optional shelf
        current.hiddenGemsShelf?.let { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                if (state.hiddenGemsShelf?.id == updated.id) {
                    state.copy(hiddenGemsShelf = updated)
                } else state
            }
        }

        // Custom shelves keyed by section id
        current.customShelves.forEach { (sectionId, items) ->
            runCatching {
                val enriched = ratingsEnricher.enrichList(items, apiKey)
                if (!ratingsChanged(items, enriched)) return@runCatching
                _state.update { state ->
                    if (!state.customShelves.containsKey(sectionId)) return@update state
                    state.copy(
                        customShelves = state.customShelves + (sectionId to enriched),
                        continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(enriched),
                    )
                }
            }
        }

        // Loose lists (no shelf id — just replace whole list)
        runCatching {
            val enriched = ratingsEnricher.enrichList(current.watchlistItems, apiKey)
            if (!ratingsChanged(current.watchlistItems, enriched)) return@runCatching
            _state.update { state ->
                state.copy(
                    watchlistItems = enriched,
                    continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(enriched),
                )
            }
        }
        runCatching {
            val enriched = ratingsEnricher.enrichList(current.recentlyWatched, apiKey)
            if (!ratingsChanged(current.recentlyWatched, enriched)) return@runCatching
            _state.update { state ->
                state.copy(
                    recentlyWatched = enriched,
                    continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(enriched),
                )
            }
        }
        runCatching {
            val baseItems = current.recommendedItems.map { it.item }
            val enriched = ratingsEnricher.enrichList(baseItems, apiKey)
            if (!ratingsChanged(baseItems, enriched)) return@runCatching
            val byKey = enriched.associateBy { it.id }
            _state.update { state ->
                val merged = state.recommendedItems.map { scored ->
                    byKey[scored.item.id]?.let { scored.copy(item = it) } ?: scored
                }
                state.copy(
                    recommendedItems = merged,
                    continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(enriched),
                )
            }
        }
    }

    private suspend fun enrichShelfAndApply(
        apiKey: String,
        shelf: CatalogShelf,
        applyToState: (HomeUiState, CatalogShelf) -> HomeUiState,
    ) {
        runCatching {
            val enriched = ratingsEnricher.enrichList(shelf.items, apiKey)
            if (!ratingsChanged(shelf.items, enriched)) return@runCatching
            val updated = shelf.copy(items = enriched)
            _state.update { state ->
                val next = applyToState(state, updated)
                next.copy(
                    continueWatchingRatings = next.continueWatchingRatings.mergeRatingsFor(enriched),
                )
            }
        }
    }

    /**
     * Returns true iff enrichment actually produced new rating data vs the
     * input list. When false, updating state would only force a LazyRow to
     * recompose with structurally-equal items — wasted work that can cancel
     * in-flight pointer-input coroutines on poster cards (dropping taps).
     */
    private fun ratingsChanged(before: List<MediaItem>, after: List<MediaItem>): Boolean {
        if (before.size != after.size) return true
        for (i in before.indices) {
            if (before[i].ratings != after[i].ratings) return true
            if (before[i].imdbId != after[i].imdbId) return true
        }
        return false
    }

    private fun List<CatalogShelf>.replaceById(updated: CatalogShelf): List<CatalogShelf> {
        val idx = indexOfFirst { it.id == updated.id }
        if (idx < 0) return this
        return toMutableList().apply { this[idx] = updated }
    }

    private fun Map<String, MediaRatings>.mergeRatingsFor(items: List<MediaItem>): Map<String, MediaRatings> {
        if (items.isEmpty()) return this
        val out = toMutableMap()
        items.forEach { item ->
            val r = item.ratings ?: return@forEach
            item.ratingEnrichmentLookupKeys().forEach { key ->
                if (key !in out) out[key] = r
            }
        }
        return out
    }

    private fun collectArtworkBackfillRequests(state: HomeUiState): List<ArtworkBackfillRequest> {
        return buildList {
            addAll(state.shelves.flatMap { it.items })
            addAll(state.watchlistItems)
            addAll(state.upcomingSchedule.take(24))
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
        val updatedUpcomingSchedule = upcomingSchedule.map { it.applyArtworkBackfill(backfilledArtwork) }
        return copy(
            shelves = updatedShelves,
            heroItem = heroItem?.applyArtworkBackfill(backfilledArtwork)
                ?: updatedShelves.firstOrNull()?.items?.firstOrNull(),
            recommendedItems = recommendedItems.map { scored ->
                scored.copy(item = scored.item.applyArtworkBackfill(backfilledArtwork))
            },
            watchlistItems = updatedWatchlistItems,
            upcomingSchedule = updatedUpcomingSchedule,
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
        if (isContentPlaceholder || isStubDetail) return this
        if (!needsArtworkBackfill()) return this
        val detail = backfilledArtwork[artworkBackfillKey(type, tmdbId)] ?: return this
        return copy(
            posterUrl = posterUrl.takeUnless { it.isNullOrBlank() } ?: detail.posterUrl,
            backdropUrl = backdropUrl.takeUnless { it.isNullOrBlank() } ?: detail.backdropUrl,
            logoUrl = logoUrl.takeUnless { it.isNullOrBlank() } ?: detail.logoUrl,
            overview = overview.takeUnless { it.isNullOrBlank() } ?: detail.overview,
            rating = rating ?: detail.rating,
            ratings = ratings ?: detail.ratings,
            genres = genres.ifEmpty { detail.genres },
            runtime = runtime ?: detail.runtime,
        )
    }

    private fun List<MediaItem>.mergeScheduleVisualsFrom(previous: List<MediaItem>): List<MediaItem> {
        if (isEmpty() || previous.isEmpty()) return this
        val previousById = previous.associateBy { it.id }
        val previousByTmdb = previous.mapNotNull { item ->
            item.tmdbId?.let { tmdbId -> tmdbId to item }
        }.toMap()
        return map { item ->
            if (!item.id.startsWith("trakt-calendar:")) return@map item
            val cached = previousById[item.id]
                ?: item.tmdbId?.let { previousByTmdb[it] }
                ?: return@map item
            item.copy(
                posterUrl = item.posterUrl.takeUnless { it.isNullOrBlank() } ?: cached.posterUrl,
                backdropUrl = item.backdropUrl.takeUnless { it.isNullOrBlank() } ?: cached.backdropUrl,
                logoUrl = item.logoUrl.takeUnless { it.isNullOrBlank() } ?: cached.logoUrl,
                overview = item.overview.takeUnless { it.isNullOrBlank() } ?: cached.overview,
                rating = item.rating ?: cached.rating,
                ratings = item.ratings ?: cached.ratings,
                genres = item.genres.ifEmpty { cached.genres },
                runtime = item.runtime ?: cached.runtime,
            )
        }
    }

    private fun MediaItem.needsArtworkBackfill(): Boolean {
        return !isContentPlaceholder &&
            !isStubDetail &&
            tmdbId != null &&
            (posterUrl.isNullOrBlank() || backdropUrl.isNullOrBlank() || overview.isNullOrBlank())
    }

    private fun hydrateItemsFromCache(items: List<MediaItem>): List<MediaItem> {
        return ratingsEnricher.hydrateListFromCache(items)
    }

    private fun hydrateShelvesFromCache(shelves: List<CatalogShelf>): List<CatalogShelf> {
        return shelves.map { shelf -> shelf.copy(items = hydrateItemsFromCache(shelf.items)) }
    }

    private fun hydrateShelfFromCache(shelf: CatalogShelf?): CatalogShelf? {
        return shelf?.copy(items = hydrateItemsFromCache(shelf.items))
    }

    private fun hydrateCustomShelvesFromCache(
        shelves: MutableMap<String, List<MediaItem>>,
    ): MutableMap<String, List<MediaItem>> {
        return shelves.mapValues { (_, items) -> hydrateItemsFromCache(items) }.toMutableMap()
    }

    private fun hydrateRecommendationsFromCache(
        items: List<ScoredMediaItem>,
    ): List<ScoredMediaItem> {
        return items.map { scored -> scored.copy(item = ratingsEnricher.hydrateFromCache(scored.item)) }
    }

    private fun hydrateContinueWatchingFromCache(
        items: List<com.torve.domain.model.WatchProgress>,
    ): List<MediaItem> {
        return items.mapNotNull { progress ->
            val tmdbId = progress.mediaId.extractTmdbIdOrNull() ?: return@mapNotNull null
            ratingsEnricher.hydrateFromCache(
                MediaItem(
                    id = progress.mediaId,
                    tmdbId = tmdbId,
                    type = progress.mediaType,
                    title = progress.showTitle ?: progress.title,
                    posterUrl = progress.posterUrl,
                    backdropUrl = progress.backdropUrl,
                ),
            )
        }
    }

    private suspend fun resolveImdbToTmdb(items: List<MediaItem>): List<MediaItem> {
        val needsResolve = items.filter { it.tmdbId == null && !it.imdbId.isNullOrBlank() }
        if (needsResolve.isEmpty()) return items
        val resolved = coroutineScope {
            needsResolve.map { item ->
                async {
                    val imdb = item.imdbId ?: return@async item
                    runCatching {
                        metadataRepo.findByImdbId(
                            imdbId = imdb,
                            preferredType = if (item.type == MediaType.SERIES) "tv" else "movie",
                        )
                    }.getOrNull()?.let { found ->
                        item.copy(
                            id = found.tmdbId?.toString() ?: item.id,
                            tmdbId = found.tmdbId ?: item.tmdbId,
                            posterUrl = item.posterUrl ?: found.posterUrl,
                            backdropUrl = item.backdropUrl ?: found.backdropUrl,
                            year = item.year ?: found.year,
                            rating = item.rating ?: found.rating,
                            ratings = item.ratings ?: found.ratings,
                        )
                    } ?: item
                }
            }.map { it.await() }
        }
        val byStableId = resolved.associateBy { it.imdbId ?: it.id }
        return items.map { item -> byStableId[item.imdbId ?: item.id] ?: item }
    }

    private fun buildRatingsLookup(items: List<MediaItem>): Map<String, MediaRatings> {
        val ratingsMap = mutableMapOf<String, MediaRatings>()
        items.forEach { item ->
            val ratings = item.ratings ?: return@forEach
            item.ratingEnrichmentLookupKeys().forEach { key ->
                if (key !in ratingsMap) ratingsMap[key] = ratings
            }
        }
        return ratingsMap
    }

    private fun currentPolicy(): ContentPolicyState {
        return contentPolicyRepository?.state?.value ?: ContentPolicyState.unrestricted()
    }

    private fun filterShelves(
        policy: ContentPolicyState,
        context: ContentAccessContext,
        shelves: List<CatalogShelf>,
        sourceType: ContentSourceType,
    ): List<CatalogShelf> {
        return shelves.mapNotNull { shelf ->
            val filteredItems = contentPolicyFilter.filterItems(
                policy = policy,
                context = context,
                items = shelf.items,
                sourceType = sourceType,
            ).items
            shelf.copy(items = filteredItems).takeIf { filteredItems.isNotEmpty() }
        }
    }

    private suspend fun backfillDiscoveryShelves(
        shelves: List<CatalogShelf>,
        globalSeen: MutableSet<String>,
        policy: ContentPolicyState,
        minItemsPerShelf: Int = 20,
        maxPage: Int = 20,
    ): List<CatalogShelf> {
        return shelves.mapNotNull { shelf ->
            if (!shelf.isBackfillableDiscoveryShelf()) {
                shelf.takeIf { it.items.isNotEmpty() }
            } else if (shelf.items.size >= minItemsPerShelf) {
                shelf
            } else {
                val localSeen = shelf.items.mapTo(mutableSetOf()) { it.stableKey() }
                val filled = shelf.items.toMutableList()
                var page = 3

                while (filled.size < minItemsPerShelf && page <= maxPage) {
                    val candidates = runCatching {
                        fetchDiscoveryShelfPage(shelf.id, page)
                    }.getOrDefault(emptyList())

                    val policyFiltered = contentPolicyFilter.filterItems(
                        policy = policy,
                        context = ContentAccessContext.DEFAULT_DISCOVERY,
                        items = candidates,
                        sourceType = ContentSourceType.TMDB,
                    ).items.dedupeByStableKey()

                    for (item in policyFiltered) {
                        val key = item.stableKey()
                        if (key in globalSeen || key in localSeen) continue
                        filled += item
                        localSeen += key
                        globalSeen += key
                        if (filled.size >= minItemsPerShelf) break
                    }
                    page++
                }

                shelf.copy(items = filled).takeIf { it.items.size >= minItemsPerShelf }
            }
        }
    }

    private fun CatalogShelf.isBackfillableDiscoveryShelf(): Boolean =
        id in setOf(
            "trending-movies",
            "trending-tv",
            "popular-movies",
            "popular-tv",
            "now-playing",
            "upcoming",
            "top-rated",
            "airing-today",
        )

    private suspend fun fetchDiscoveryShelfPage(shelfId: String, page: Int): List<MediaItem> {
        return when (shelfId) {
            "trending-movies" -> metadataRepo.getTrending("movie", page)
            "trending-tv" -> metadataRepo.getTrending("tv", page)
            "popular-movies" -> metadataRepo.getPopular("movie", page)
            "popular-tv" -> metadataRepo.getPopular("tv", page)
            "now-playing" -> metadataRepo.getNowPlaying(page)
            "upcoming" -> metadataRepo.getUpcoming(page).filterFutureHomeReleases()
            "top-rated" -> metadataRepo.getTopRated("movie", page)
            "airing-today" -> metadataRepo.getAiringToday(page)
            else -> emptyList()
        }
    }

    private fun List<MediaItem>.filterFutureHomeReleases(): List<MediaItem> {
        val today = kotlinx.datetime.Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toString()
        return filter { item ->
            val releaseDate = item.releaseDate?.take(10)?.takeIf { it.length == 10 }
            releaseDate != null && releaseDate >= today
        }
    }

    private fun filterRecommendations(
        policy: ContentPolicyState,
        context: ContentAccessContext,
        items: List<ScoredMediaItem>,
        sourceType: ContentSourceType,
    ): List<ScoredMediaItem> {
        return items.mapNotNull { scored ->
            val filteredItem = contentPolicyFilter.filterItems(
                policy = policy,
                context = context,
                items = listOf(scored.item),
                sourceType = sourceType,
            ).items.firstOrNull()
            filteredItem?.let { scored.copy(item = it) }
        }
    }

    private suspend fun addonShelfPolicyFlags(addons: List<InstalledAddon>): Map<String, AddonPolicyFlags?> {
        return addons.flatMap { addon ->
            val flags = addon.policyFlags ?: addonPolicyRepository?.getFlags(addon.manifestUrl)
            addon.manifest.catalogs.map { catalog ->
                "${addon.manifest.id}-${catalog.id}" to flags
            }
        }.toMap()
    }

    private fun HomeUiState.hasRenderableContent(): Boolean {
        return heroItem != null ||
            shelves.isNotEmpty() ||
            continueWatching.isNotEmpty() ||
            recommendedItems.isNotEmpty() ||
            upcomingSchedule.isNotEmpty() ||
            watchlistItems.isNotEmpty() ||
            becauseYouWatched.isNotEmpty() ||
            hiddenGemsShelf != null ||
            recentlyWatched.isNotEmpty() ||
            popularActors.isNotEmpty() ||
            popularDirectors.isNotEmpty() ||
            customShelves.isNotEmpty() ||
            addonShelves.isNotEmpty() ||
            mdbListShelves.isNotEmpty()
    }

    private fun effectiveUpcomingScheduleStatus(
        originalStatus: UpcomingScheduleStatus,
        visibleItems: List<MediaItem>,
    ): UpcomingScheduleStatus =
        if (originalStatus == UpcomingScheduleStatus.HAS_DATA && visibleItems.isEmpty()) {
            UpcomingScheduleStatus.EMPTY_CONNECTED
        } else {
            originalStatus
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

    companion object {
        private const val HOME_SNAPSHOT_KEY = "home_snapshot_v2"
        private const val HOME_SNAPSHOT_MAX_AGE_MS = 6L * 60L * 60L * 1000L
        private val HOME_LOAD_AUTO_RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L)
        private const val KEY_RATINGS_CACHE_VERSION = "ratings_cache_version"
        private const val TRAKT_SCHEDULE_REFRESH_THROTTLE_MS = 15_000L
        private val HIDDEN_PROVIDER_HOME_CATALOG_LABELS = setOf(
            "yourmedia",
            "lastvideos",
        )

        // Bump when enrichment semantics change in a way that should
        // invalidate the persistent ratings cache on existing installs.
        //  v1: initial release
        //  v2: Trakt augments MDBList-sourced entries that were missing a
        //      trakt score; need to flush v1 entries so Trakt pill appears.
        //  v3: MDBList-rate-limited items are no longer cached as partial
        //      OMDB-only. Retry loop in launchRatingsEnrichment fills them
        //      in after cooldown. Existing v2 entries may have been cached
        //      during rate-limit storms and lack MDBList-exclusive fields.
        private const val RATINGS_CACHE_VERSION = 3

        // Hard cap on the rate-limit retry loop so a broken MDBList endpoint
        // can't keep the coroutine alive forever. 5 × 60s = 5 min worst case.
        private const val MAX_RATINGS_ENRICHMENT_ITERATIONS = 5
    }
}
