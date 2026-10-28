package com.torve.presentation.home

import com.torve.data.metadata.tmdbExtensionSelection

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.withoutAiRecommendations
import com.torve.domain.model.CustomSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.HomeCatalogueRowConfig
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
import com.torve.domain.model.configurableHomeSections
import com.torve.domain.model.dedupeAcrossShelves
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.isCatalogPlaybackIdentity
import com.torve.domain.model.toCatalogHistoryItemOrNull
import com.torve.domain.model.migrateLegacyBecauseYouWatchedConfigs
import com.torve.domain.model.migrateLegacyBecauseYouWatchedLayoutOrder
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
import com.torve.data.addon.homeCatalogPlan
import com.torve.data.addon.CatalogAggregator
import com.torve.data.addon.catalogueShelfId
import com.torve.data.addon.legacyAddonShelfId
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
import com.torve.presentation.util.needsSchedulePresentationRefresh
import com.torve.presentation.util.mergeScheduleVisualsFrom
import com.torve.presentation.util.hydrateUpcomingScheduleArtwork
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
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
    private val searchLists: com.torve.data.trakt.SearchListsRepository? = null,
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
        val profileOwner: String? = null,
        val shelves: List<CatalogShelf> = emptyList(),
        val heroItem: MediaItem? = null,
        val continueWatching: List<com.torve.domain.model.WatchProgress> = emptyList(),
        val continueWatchingRatings: Map<String, MediaRatings> = emptyMap(),
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
    private val _state = contentPolicyFilter.profileState(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    // Section configuration state
    private val _sectionConfigs = MutableStateFlow(emptyList<HomeSectionConfig>())
    val sectionConfigs: StateFlow<List<HomeSectionConfig>> = _sectionConfigs.asStateFlow()

    // Streaming service selection
    private val _enabledServiceIds = MutableStateFlow<Set<Int>>(setOf(8, 9, 337, 350, 1899, 15))
    val enabledServiceIds: StateFlow<Set<Int>> = _enabledServiceIds.asStateFlow()

    private val streamingServiceChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val streamingServiceWrites = kotlinx.coroutines.sync.Mutex()
    val nowStreaming: NowStreamingCatalogue by lazy {
        NowStreamingCatalogue(metadataRepo, configuration = {
            contentPolicyFilter.awaitProfileBoundary()
            NowStreamingConfiguration(
                contentPolicyFilter.profileOwnerKey(),
                prefsRepo.getString(SettingsViewModel.KEY_REGION_CODE)?.trim()?.uppercase()
                    ?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US",
                prefsRepo.getString("app_language").orEmpty(), loadEnabledServiceIds(),
                prefsRepo.tmdbExtensionSelection().catalogue,
                profileRepo.getActiveProfile()?.maxContentRating, currentPolicy(),
            )
        }, owner = { contentPolicyFilter.profileOwnerKey() }, policyFilter = contentPolicyFilter,
            changes = kotlinx.coroutines.flow.merge(
                streamingServiceChanges,
                settingsRefreshNotifier.events.map { Unit },
                contentPolicyFilter.profileOwners().map { Unit },
                contentPolicyFilter.discoveryChanges(),
                contentPolicyRepository?.state?.map { Unit }
                    ?: kotlinx.coroutines.flow.emptyFlow(),
            ),
            hydrateRatings = ratingsEnricher::hydrateListFromCache,
            enrichRatings = { items ->
                val key = try {
                    integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                        ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                        ?: MdbListApi.DEFAULT_API_KEY
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }
                ratingsEnricher.enrichList(items, key)
            })
    }

    // Custom sections
    private val _customSections = contentPolicyFilter.profileState(emptyList<CustomSection>())
    val customSections: StateFlow<List<CustomSection>> = _customSections.asStateFlow()

    // Provider logos
    private val _providerLogos = MutableStateFlow<Map<Int, String>>(emptyMap())
    val providerLogos: StateFlow<Map<Int, String>> = _providerLogos.asStateFlow()

    // Combined home layout ordering (built-in + custom)
    private val _homeLayoutOrder = MutableStateFlow<List<String>>(emptyList())
    val homeLayoutOrder: StateFlow<List<String>> = _homeLayoutOrder.asStateFlow()

    private val _showPhoneResumeBar = MutableStateFlow(true)
    val showPhoneResumeBar: StateFlow<Boolean> = _showPhoneResumeBar.asStateFlow()

    fun setShowPhoneResumeBar(enabled: Boolean) {
        _showPhoneResumeBar.value = enabled
        scope.launch { prefsRepo.setString("phone_show_resume_bar", enabled.toString()) }
    }

    private val _returningViewerHome = MutableStateFlow(true)
    val returningViewerHome: StateFlow<Boolean> = _returningViewerHome.asStateFlow()

    fun setReturningViewerHome(enabled: Boolean) {
        _returningViewerHome.value = enabled
        scope.launch { prefsRepo.setString("phone_returning_viewer_home", enabled.toString()) }
    }

    fun dismissRecommendation(item: MediaItem) {
        scope.launch {
            try {
                recommendationsUseCase.dismiss(item)
                _state.update { current -> current.copy(recommendedItems = current.recommendedItems.filterNot {
                    it.item.stableKey() == item.stableKey()
                }) }
            } catch (error: Exception) { if (error is CancellationException) throw error }
        }
    }

    fun resetRecommendationFeedback() {
        scope.launch {
            try { recommendationsUseCase.resetFeedback(); loadHomeScreen() }
            catch (error: Exception) { if (error is CancellationException) throw error }
        }
    }

    // Addon shelf visibility (persisted)
    private val _addonShelfVisibility = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val addonShelfVisibility: StateFlow<Map<String, Boolean>> = _addonShelfVisibility.asStateFlow()

    // Search
    private val searchQueryFlow = MutableStateFlow("")
    private var homeLoadJob: Job? = null
    private var discoverySnapshotJob: Job? = null
    private var continueWatchingRefreshJob: Job? = null
    private var artworkBackfillJob: Job? = null
    private var ratingsEnrichmentJob: Job? = null
    private val artworkBackfillAttempts = mutableMapOf<String, Long>()
    private var homeAutoRetryCount = 0
    private var upcomingScheduleRefreshJob: Job? = null
    private var lastTraktScheduleRefreshAtMs = 0L
    private var lastTraktConnectedForSchedule: Boolean? = null

    init {
        scope.launch {
            contentPolicyFilter.discoveryChanges().collectLatest {
                // Certification of an encountered watched show can update the
                // filter repeatedly. Let the running load apply its latest
                // policy instead of cancelling every remaining Home source.
                if (homeLoadJob?.isActive != true) loadHomeScreen()
            }
        }
        scope.launch {
            contentPolicyFilter.profileOwners().distinctUntilChanged().drop(1).collect {
                loadHomeScreen()
            }
        }
        scope.launch {
            contentPolicyFilter.awaitProfileBoundary()
            _sectionConfigs.value = loadSectionConfigs()
            _addonShelfVisibility.value = loadAddonShelfVisibility()
            _homeLayoutOrder.value = ensureAllSectionsInLayoutOrder(loadHomeLayoutOrder())
            withContext(Dispatchers.Default) { restoreHomeSnapshot() }
            loadProviderLogos()
            _enabledServiceIds.value = loadEnabledServiceIds()
            _customSections.value = loadCustomSections()
            _addonShelfVisibility.value = loadAddonShelfVisibility()
            _homeLayoutOrder.value = ensureAllSectionsInLayoutOrder(loadHomeLayoutOrder())
            _returningViewerHome.value = prefsRepo.getString("phone_returning_viewer_home") != "false"
            _showPhoneResumeBar.value = prefsRepo.getString("phone_show_resume_bar") != "false"
            loadHomeScreen()
            scheduleUpcomingScheduleRefresh("startup")
        }
        scope.launch {
            watchProgressRepo.progressChanges
                .debounce(250L)
                .collectLatest {
                    refreshContinueWatching()
                }
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
                    _returningViewerHome.value = prefsRepo.getString("phone_returning_viewer_home") != "false"
                    _showPhoneResumeBar.value = prefsRepo.getString("phone_show_resume_bar") != "false"
                    _customSections.value = loadCustomSections()
                    _enabledServiceIds.value = loadEnabledServiceIds()
                    _addonShelfVisibility.value = loadAddonShelfVisibility()
                    loadHomeScreen()
                    scheduleUpcomingScheduleRefresh("settings_refresh")
                }
        }
        if (invalidationCoordinator != null) {
            scope.launch {
                invalidationCoordinator.events
                    .debounce(500L)
                    .collectLatest {
                        _state.update { current ->
                            if (current.hasRenderableContent()) {
                                current.copy(isLoading = false, error = null)
                            } else {
                                HomeUiState(isLoading = true)
                            }
                        }
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
                        ratingsEnrichmentJob?.cancel()
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
        val result = completeHomeLayoutOrder(saved, _sectionConfigs.value)
        if (result != saved) updateHomeLayoutOrder(result)
        return result
    }

    private fun loadProviderLogos() {
        scope.launch {
            try {
                val owner = contentPolicyFilter.profileOwnerKey()
                val region = prefsRepo.getString(SettingsViewModel.KEY_REGION_CODE)?.trim()?.uppercase()
                    ?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
                val logos = metadataRepo.getWatchProviderLogos(region = region)
                val names = metadataRepo.getWatchProviderNames(region = region)
                val resolved = streamingServiceNames.mapNotNull { (id, name) ->
                    val logo = logos[id] ?: com.torve.domain.model.resolveStreamingProviderIds(name, id, region,
                        names.map { com.torve.domain.model.StreamingProviderCandidate(it.key, it.value) })
                        .firstNotNullOfOrNull { logos[it] }
                    logo?.let { id to it }
                }.toMap()
                if (owner == contentPolicyFilter.profileOwnerKey()) _providerLogos.value = logos + resolved
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
                val migrated = migrateLegacyBecauseYouWatchedConfigs(decoded)
                val defaults = defaultSectionConfigs()
                val bySection = migrated.associateBy { it.section }
                val presetIds = loadCardStylePresetIds()
                val resolvedConfigs = defaults.map { def ->
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
                if (resolvedConfigs != decoded) {
                    prefsRepo.setString(key, json.encodeToString(resolvedConfigs))
                }
                resolvedConfigs
            } catch (_: Exception) {
                defaultSectionConfigs()
            }
        } else {
            defaultSectionConfigs()
        }
    }

    private fun defaultSectionConfigs(): List<HomeSectionConfig> =
        configurableHomeSections.map { section ->
            // Continue Watching ships pre-bound to the built-in
            // "landscape-default" preset so freshly-installed users see
            // backdrops there (which are designed for the in-progress
            // strip) while every other shelf shows portrait posters via
            // the global default.
            val presetId = if (section == HomeSection.CONTINUE_WATCHING) "landscape-default" else null
            HomeSectionConfig(section, section.defaultEnabled, section.defaultOrder, presetId = presetId)
        }

    fun updateSectionOrder(configs: List<HomeSectionConfig>) {
        updateHomeLayout(configs, mergeBuiltInHomeLayoutOrder(_homeLayoutOrder.value, configs))
    }

    fun updateHomeLayout(configs: List<HomeSectionConfig>, layoutOrder: List<String>) {
        _sectionConfigs.value = configs
        saveSectionConfigs(configs)
        // Keep custom and addon rails in the combined order. Reordering the
        // built-in rows must not silently discard entries that this editor
        // does not own.
        updateHomeLayoutOrder(layoutOrder)
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
        val owner = contentPolicyFilter.profileOwnerKey()
        val updated = if (enabled) _enabledServiceIds.value + providerId else _enabledServiceIds.value - providerId
        _enabledServiceIds.value = updated
        scope.launch {
            streamingServiceWrites.lock()
            try {
                if (owner == contentPolicyFilter.profileOwnerKey()) {
                    prefsRepo.setString("enabled_streaming_services", json.encodeToString(SetSerializer(Int.serializer()), updated))
                    streamingServiceChanges.emit(Unit)
                }
            } finally { streamingServiceWrites.unlock() }
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
                val decoded = json.decodeFromString<List<String>>(saved)
                val migrated = migrateLegacyBecauseYouWatchedLayoutOrder(decoded)
                if (migrated != decoded) {
                    prefsRepo.setString(key, json.encodeToString(migrated))
                }
                migrated
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
        val options = _sectionConfigs.value.firstOrNull { it.section == HomeSection.ADDON_SHELVES }
            ?.catalogueRows?.get(shelfId)
        setAddonShelfVisible(shelfId, !(options?.enabled ?: _addonShelfVisibility.value[shelfId] ?: true))
    }

    fun setAddonShelfVisible(shelfId: String, visible: Boolean) {
        val updated = _addonShelfVisibility.value + (shelfId to visible)
        _addonShelfVisibility.value = updated
        _state.update { it.copy(addonShelfVisibility = updated) }
        saveAddonShelfVisibility(updated)
        updateCatalogueRow(shelfId) { it.copy(enabled = visible) }
    }

    fun updateAddonShelfAppearance(shelfId: String, title: String?, presetId: String?) {
        updateCatalogueRow(shelfId) { it.copy(
            customTitle = title?.trim()?.take(100)?.takeIf(String::isNotEmpty),
            presetId = presetId?.trim()?.take(120)?.takeUnless { id -> id.isEmpty() || id == "inherit" },
        ) }
    }

    private fun updateCatalogueRow(shelfId: String, update: (HomeCatalogueRowConfig) -> HomeCatalogueRowConfig) {
        val updated = _sectionConfigs.value.map { config ->
            if (config.section == HomeSection.ADDON_SHELVES) config.copy(
                catalogueRows = config.catalogueRows + (shelfId to update(config.catalogueRows[shelfId] ?: HomeCatalogueRowConfig())),
            ) else config
        }
        _sectionConfigs.value = updated
        saveSectionConfigs(updated)
    }

    /**
     * Register each addon shelf in the layout order if not already present.
     * Called after addon shelves are loaded so they appear in Home Layout.
     */
    private fun ensureAddonShelvesInLayout(addonShelves: List<CatalogShelf>) {
        // Split legacy movie/TV collisions without losing the user's hidden rows,
        // appearance or layout position. Explicit new preferences always win.
        val aliases = addonShelves.filter { it.id != it.id.legacyAddonShelfId() }
            .groupBy { it.id.legacyAddonShelfId() }
        if (aliases.isNotEmpty()) {
            val visibility = _addonShelfVisibility.value.toMutableMap()
            aliases.forEach { (legacy, shelves) ->
                visibility[legacy]?.let { value -> shelves.forEach { if (it.id !in visibility) visibility[it.id] = value } }
            }
            if (visibility != _addonShelfVisibility.value) {
                _addonShelfVisibility.value = visibility
                saveAddonShelfVisibility(visibility)
            }
            val configs = _sectionConfigs.value.map { config ->
                if (config.section != HomeSection.ADDON_SHELVES) config else {
                    val options = config.catalogueRows.toMutableMap()
                    aliases.forEach { (legacy, shelves) ->
                        options[legacy]?.let { value -> shelves.forEach { if (it.id !in options) options[it.id] = value } }
                    }
                    config.copy(catalogueRows = options)
                }
            }
            if (configs != _sectionConfigs.value) {
                _sectionConfigs.value = configs
                saveSectionConfigs(configs)
            }
            val expanded = expandLegacyAddonLayoutKeys(_homeLayoutOrder.value,
                aliases.mapValues { (_, shelves) -> shelves.map { it.id } })
            if (expanded != _homeLayoutOrder.value) updateHomeLayoutOrder(expanded)
        }
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
    fun updateCustomSectionPreset(sectionId: String, presetId: String?) {
        val updated = _customSections.value.map { if (it.id == sectionId) it.copy(presetId = presetId) else it }
        _customSections.value = updated
        saveCustomSections(updated)
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

    private suspend fun restoreHomeSnapshot(): Boolean {
        val restoringOwner = contentPolicyFilter.profileOwnerKey()
        val snapshot = try {
            withContext(ioDispatcher) {
                readHomeStartupSnapshot(prefsRepo)?.let { json.decodeFromString<HomeSnapshot>(it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            null
        } ?: return false
        if (snapshot.profileOwner != null && snapshot.profileOwner != restoringOwner) return false
        val snapshotAgeMs = (currentTimeMillis() - snapshot.savedAtMs).coerceAtLeast(0L)
        if (snapshotAgeMs > HOME_SNAPSHOT_MAX_STALE_AGE_MS) return false
        // A removed/disabled extension must never return via a presentation cache.
        val availableAddonIds = withContext(ioDispatcher) {
            homeCatalogPlan(addonRepo.getInstalledAddons(),
                _sectionConfigs.value.firstOrNull { it.section == HomeSection.ADDON_SHELVES },
                _addonShelfVisibility.value, _homeLayoutOrder.value).map { it.shelfId }.toSet()
        }
        val restoredAddonShelves = snapshot.addonShelves.withoutProviderOwnedHomeCatalogs()
            .filter { it.id in availableAddonIds }
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
        if (restoringOwner != contentPolicyFilter.profileOwnerKey()) return false
        val restored = _state.value.copy(
            shelves = snapshot.shelves,
            heroItem = snapshot.heroItem ?: snapshot.shelves.firstOrNull()?.items?.firstOrNull(),
            continueWatching = snapshot.continueWatching,
            upcomingSchedule = snapshot.upcomingSchedule.mergeScheduleVisualsFrom(
                snapshot.watchlistItems + snapshot.recentlyWatched + restoredAddonShelves.flatMap { it.items }),
            upcomingScheduleStatus = if (snapshot.upcomingSchedule.isNotEmpty()) {
                UpcomingScheduleStatus.STALE
            } else {
                UpcomingScheduleStatus.LOADING
            },
            continueWatchingRatings = buildRatingsLookup(
                    snapshot.shelves.flatMap { it.items } +
                    snapshot.upcomingSchedule +
                    snapshot.watchlistItems +
                    snapshot.becauseYouWatched.flatMap { it.items } +
                    (snapshot.hiddenGemsShelf?.items ?: emptyList()) +
                    snapshot.recentlyWatched +
                    snapshot.customShelves.values.flatten() +
                    restoredAddonShelves.flatMap { it.items } +
                    snapshot.mdbListShelves.flatMap { it.items },
            ) + snapshot.continueWatchingRatings,
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
        ).applyFamilyBoundary(contentPolicyFilter)
        updateHomeFor(restoringOwner) { it.withStartupFallback(restored).applyFamilyBoundary(contentPolicyFilter) }
        // Upgrade the existing presentation cache once, including while offline.
        persistFastHomeSnapshot(_state.value, restoringOwner)
        torveVerboseLog {
            "HOME_TAB restored persisted snapshot shelves=${snapshot.shelves.size} age_ms=$snapshotAgeMs"
        }
        return true
    }

    private suspend fun persistFastHomeSnapshot(state: HomeUiState, owner: String) {
        try {
            withContext(ioDispatcher) {
                if (owner != contentPolicyFilter.profileOwnerKey()) return@withContext
                val encoded = json.encodeToString(homeSnapshot(state.compactStartupSnapshot(), owner))
                // Keep the first presentation comfortably below Fire OS's row limit.
                if (encoded.length <= 196_608 && owner == contentPolicyFilter.profileOwnerKey()) {
                    prefsRepo.setString(HOME_STARTUP_SNAPSHOT_KEY, encoded)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A refresh can still render its retained content. */ }
    }

    private fun homeSnapshot(state: HomeUiState, owner: String) = HomeSnapshot(
            savedAtMs = currentTimeMillis(),
            profileOwner = owner,
            shelves = state.shelves,
            heroItem = state.heroItem,
            continueWatching = state.continueWatching,
            continueWatchingRatings = state.continueWatchingRatings,
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

    private suspend fun persistHomeSnapshot(state: HomeUiState) {
        if (!state.hasRenderableContent()) return
        val owner = contentPolicyFilter.profileOwnerKey()
        persistFastHomeSnapshot(state, owner)
        try {
            withContext(ioDispatcher) {
                if (owner == contentPolicyFilter.profileOwnerKey()) {
                    prefsRepo.setString(HOME_SNAPSHOT_KEY, json.encodeToString(homeSnapshot(state, owner)))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep visible Home content if a cache write fails. */ }
    }

    private fun currentTimeMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    private fun List<CatalogShelf>.withoutProviderOwnedHomeCatalogs(): List<CatalogShelf> =
        withoutAiRecommendations().filterNot { it.isProviderOwnedHomeCatalog() }

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

    /**
     * Publish essential discovery shelves as soon as TMDB returns on a cold
     * start. A cached or already visible catalogue stays on screen until the
     * complete refresh is ready, avoiding a partial/full rail replacement cycle.
     */
    private suspend fun publishInitialHomeShelves(
        shelves: List<CatalogShelf>,
        dedupe: Boolean,
        owner: String,
    ) {
        if (owner != contentPolicyFilter.profileOwnerKey() || shelves.isEmpty()) return

        val shelfConfigs = try { shelfConfigRepo.getAllConfigs() } catch (_: Exception) { emptyList() }
        val configMap = shelfConfigs.associateBy { it.shelfId }
        val maxRating = try { profileRepo.getActiveProfile()?.maxContentRating } catch (_: Exception) { null }
        val policy = currentPolicy()
        val ordered = shelves
            .filter { shelf -> configMap[shelf.id]?.isVisible != false }
            .sortedBy { shelf -> configMap[shelf.id]?.sortOrder ?: Int.MAX_VALUE }
            .map { shelf ->
                val parentalItems = contentPolicyFilter.filterProfileLimit(shelf.items, maxRating)
                shelf.copy(items = if (dedupe) parentalItems.dedupeByStableKey() else parentalItems)
            }
        val policyFiltered = filterShelves(
            policy = policy,
            context = ContentAccessContext.DEFAULT_DISCOVERY,
            shelves = ordered,
            sourceType = ContentSourceType.TMDB,
        )
        val visibleShelves = if (dedupe) {
            deduplicateDiscoveryRows(policyFiltered, mutableSetOf())
        } else {
            policyFiltered
        }.filter { it.items.isNotEmpty() }
        // Rendering an already-fetched row must not wait behind rating-cache
        // reads or snapshot writes contending with account restoration.
        val context = currentCoroutineContext()
        updateHomeFor(owner) { current ->
            context.ensureActive()
            current.withInitialDiscoveryShelves(visibleShelves)
        }
        val hydratedShelves = withContext(ioDispatcher) { hydrateShelvesFromCache(visibleShelves) }
        if (hydratedShelves.isEmpty()) return

        updateHomeFor(owner) { current ->
            context.ensureActive()
            current.withInitialDiscoveryShelves(hydratedShelves)
        }
        // A short visit still leaves a useful local Home. Optional providers,
        // recommendations and schedules must not delay the first saved snapshot.
        discoverySnapshotJob?.cancel()
        discoverySnapshotJob = scope.launch(Dispatchers.Default) {
            delay(350)
            if (owner == contentPolicyFilter.profileOwnerKey()) persistHomeSnapshot(_state.value)
        }
        torveVerboseLog {
            "HOME_TAB state_transition state=essential shelves=${hydratedShelves.size}"
        }
    }

    /** Personal rows are local reads; optional discovery/add-ons must not hold them back. */
    private suspend fun publishInitialPersonalRows(
        progress: List<com.torve.domain.model.WatchProgress>,
        watchlist: List<WatchlistItem>,
    ) {
        val policy = currentPolicy()
        val maxRating = withContext(ioDispatcher) { profileRepo.getActiveProfile()?.maxContentRating }
        val watching = contentPolicyFilter.filterWatchProgress(policy = policy,
            context = ContentAccessContext.HISTORY_DERIVED,
            items = progress.filter { it.mediaId.isCatalogPlaybackIdentity() }).items
        val saved = watchlist.map { it.toHomeWatchlistItem() }
        val visibleWatchlist = withContext(ioDispatcher) { hydrateItemsFromCache(contentPolicyFilter.filterItems(policy = policy,
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = contentPolicyFilter.filterProfileLimit(saved, maxRating), sourceType = ContentSourceType.LOCAL_LIBRARY).items) }
        val ratings = buildRatingsLookup(withContext(ioDispatcher) { hydrateContinueWatchingFromCache(watching) } + visibleWatchlist)
        _state.update { current -> current.copy(
            continueWatching = watching.withHomeArtworkFrom(current.continueWatching),
            continueWatchingRatings = current.continueWatchingRatings + ratings,
            watchlistItems = visibleWatchlist,
            watchlistShelf = visibleWatchlist.takeIf { it.isNotEmpty() }?.let {
                CatalogShelf(id = "your_watchlist", title = "Your Watchlist", items = it)
            },
            isLoading = if (watching.isNotEmpty() || visibleWatchlist.isNotEmpty()) false else current.isLoading,
        ) }
        persistHomeSnapshot(_state.value)
    }

    private inline fun updateHomeFor(owner: String, transform: (HomeUiState) -> HomeUiState) {
        if (owner != contentPolicyFilter.profileOwnerKey()) return
        _state.update { if (owner == contentPolicyFilter.profileOwnerKey()) transform(it) else it }
    }

    private suspend fun visibleOptionalHomeItems(items: List<MediaItem>, context: ContentAccessContext,
        source: ContentSourceType): List<MediaItem> {
        val maxRating = profileRepo.getActiveProfile()?.maxContentRating
        return contentPolicyFilter.filterItems(currentPolicy(), context,
            contentPolicyFilter.filterProfileLimit(items, maxRating), source).items
    }

    private suspend fun publishWatchedSourceRows(items: List<MediaItem>, owner: String) {
        val visible = visibleOptionalHomeItems(items, ContentAccessContext.HISTORY_DERIVED, ContentSourceType.LOCAL_LIBRARY)
        if (visible.isNotEmpty()) updateHomeFor(owner) { it.copy(
            recentlyWatched = visible.take(MAX_RECENTLY_WATCHED_ITEMS),
            becauseYouWatched = buildBecauseYouWatchedShelves(visible),
        ) }
    }

    private suspend fun publishOptionalAddonRows(rows: List<CatalogShelf>, addons: List<InstalledAddon>, owner: String) {
        val flags = addonShelfPolicyFlags(addons)
        val visible = rows.withoutProviderOwnedHomeCatalogs().mapNotNull { row ->
            if (flags[row.id]?.shelfEligible == false) return@mapNotNull null
            val items = contentPolicyFilter.filterItems(currentPolicy(), ContentAccessContext.ADDON_SHELF,
                row.items, ContentSourceType.ADDON, addonPolicyFlags = flags[row.id]).items
            row.copy(items = items).takeIf { items.isNotEmpty() }
        }
        if (visible.isNotEmpty()) updateHomeFor(owner) { it.copy(addonShelves = visible,
            addonShelfVisibility = _addonShelfVisibility.value, isLoading = false) }
    }

    fun loadHomeScreen() {
        val keepContentVisible = _state.value.hasRenderableContent()
        homeLoadJob?.cancel()
        ratingsEnrichmentJob?.cancel()
        homeLoadJob = scope.launch(Dispatchers.Default) {
            contentPolicyFilter.awaitProfileBoundary()
            val loadingOwner = contentPolicyFilter.profileOwnerKey()
            torveVerboseLog { "HOME_TAB bootstrap_start" }
            if (keepContentVisible) {
                updateHomeFor(loadingOwner) { it.copy(isLoading = false, error = null) }
            } else {
                updateHomeFor(loadingOwner) { it.copy(isLoading = true, error = null) }
            }
            try {
                val dedupe = shouldDedupe()
                val tmdbCatalogue = prefsRepo.tmdbExtensionSelection().catalogue
                val loadInputs = withContext(Dispatchers.Default) { supervisorScope {
                    torveVerboseLog { "HOME_TAB repository_fetch_start source=home_shelves" }
                    // Optional TMDB must not make personal/extension rows fail to load.
                    val firstDiscoveryReady = CompletableDeferred<Unit>()
                    if (keepContentVisible || !tmdbCatalogue) firstDiscoveryReady.complete(Unit)
                    val shelvesDeferred = async {
                        try {
                            if (!tmdbCatalogue) emptyList() else metadataRepo.homeShelves().onEach { shelves ->
                                publishInitialHomeShelves(shelves, dedupe, loadingOwner)
                                firstDiscoveryReady.complete(Unit)
                            }.last()
                        } finally { firstDiscoveryReady.complete(Unit) }
                    }
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
                        firstDiscoveryReady.await()
                        try {
                            recommendationsUseCase.execute().also { items ->
                                val policy = currentPolicy()
                                val maxRating = profileRepo.getActiveProfile()?.maxContentRating
                                val visible = filterRecommendations(policy, ContentAccessContext.GLOBAL_RECOMMENDATION,
                                    items.filter { contentPolicyFilter.filterProfileLimit(listOf(it.item), maxRating).isNotEmpty() }, ContentSourceType.TMDB)
                                if (visible.isNotEmpty()) updateHomeFor(loadingOwner) { it.copy(recommendedItems = visible, isLoading = false) }
                            }
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
                    launch {
                        try {
                            val initialContinue = continueWatchingDeferred.await()
                            val initialWatchlist = watchlistDeferred.await()
                            withContext(Dispatchers.Main) { publishInitialPersonalRows(initialContinue, initialWatchlist) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* The normal load can still publish these rows. */ }
                    }
                    val historyDeferred = async {
                        try {
                            // Pull a wide window so dedup-by-show has material to
                            // collapse across. This also feeds the watched-source
                            // Movies and TV Shows recommendation entry rails.
                            watchHistoryRepo.getRecent(40)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    val hiddenGemsDeferred = async {
                        firstDiscoveryReady.await()
                        try {
                            metadataRepo.discover(
                                type = "movie",
                                sortBy = "vote_average.desc",
                                minRating = 7.5f,
                                page = 1,
                            ).items.take(20).also { items ->
                                val visible = visibleOptionalHomeItems(items, ContentAccessContext.DEFAULT_DISCOVERY, ContentSourceType.TMDB)
                                if (visible.isNotEmpty()) updateHomeFor(loadingOwner) { it.copy(hiddenGemsShelf = CatalogShelf("hidden_gems", "Hidden Gems", visible)) }
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    val recentlyWatchedDeferred = async {
                        try {
                            val seen = mutableSetOf<String>()
                            val out = mutableListOf<MediaItem>()
                            val sourceCountByType = mutableMapOf<MediaType, Int>()
                            // Read the complete local history for source rails.
                            // A raw 80-row window can contain 80 episodes of one
                            // show and incorrectly crowd every watched movie out.
                            for (entry in watchHistoryRepo.getAll()) {
                                // Exclude direct files/events before their numeric
                                // playback keys can be mistaken for catalog IDs.
                                val item = entry.toCatalogHistoryItemOrNull() ?: continue
                                // Stable show-level key so multiple episodes of the
                                // same series collapse into a single card. Never
                                // surface individual episode posters or titles.
                                val showKey = item.tmdbId?.toString() ?: item.imdbId ?: item.id
                                val mediaType = item.type
                                val historyKey = "${mediaType.name}:$showKey"
                                if (historyKey in seen) continue
                                seen += historyKey
                                val typeCount = sourceCountByType[mediaType] ?: 0
                                if (typeCount >= MAX_WATCHED_SOURCES_PER_TYPE) continue
                                sourceCountByType[mediaType] = typeCount + 1
                                out += item.copy(id = showKey)
                            }
                            publishWatchedSourceRows(out, loadingOwner)
                            // Details resolve an IMDb ID when opened. Resolving
                            // 160 history sources here floods TMDB's three lanes.
                            out
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    val upcomingScheduleDeferred = async {
                        loadUpcomingSchedule().also { result ->
                            val visible = contentPolicyFilter.filterItems(policy = currentPolicy(),
                                context = ContentAccessContext.HISTORY_DERIVED,
                                items = result.items, sourceType = ContentSourceType.LOCAL_LIBRARY).items
                            withContext(Dispatchers.Main) { updateHomeFor(loadingOwner) { current -> current.copy(
                                upcomingSchedule = visible.mergeScheduleVisualsFrom(current.upcomingSchedule),
                                upcomingScheduleStatus = effectiveUpcomingScheduleStatus(result.status, visible),
                            ) } }
                            persistHomeSnapshot(_state.value)
                        }
                    }
                    val popularPeopleDeferred = async {
                        firstDiscoveryReady.await()
                        if (contentPolicyFilter.familyKidsProfile) emptyList() else try {
                            metadataRepo.getPopularPeople().also { people ->
                                if (!contentPolicyFilter.familyKidsProfile) updateHomeFor(loadingOwner) { it.copy(
                                    popularActors = people.filter { it.knownForDepartment == "Acting" }.take(20),
                                    popularDirectors = people.filter { it.knownForDepartment in homeDirectorDepartments }.take(20),
                                ) }
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    val addonShelvesDeferred = async {
                        try {
                            val addons = installedAddonsDeferred.await()
                            if (addons.isEmpty()) emptyList()
                            else {
                                val plan = homeCatalogPlan(addons,
                                    _sectionConfigs.value.firstOrNull { it.section == HomeSection.ADDON_SHELVES },
                                    _addonShelfVisibility.value, _homeLayoutOrder.value)
                                catalogAggregator.fetchHomeCatalogs(plan).also { publishOptionalAddonRows(it, addons, loadingOwner) }
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
                                }.also { rows ->
                                    val visible = filterShelves(currentPolicy(), ContentAccessContext.DEFAULT_DISCOVERY, rows, ContentSourceType.MDBLIST)
                                    if (visible.isNotEmpty()) updateHomeFor(loadingOwner) { it.copy(mdbListShelves = visible) }
                                }
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    val initialShelves = shelvesDeferred.await()
                    withContext(Dispatchers.Main) { publishInitialHomeShelves(initialShelves, dedupe, loadingOwner) }
                    HomeLoadInputs(
                        shelves = initialShelves,
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
                } }

                val shelves = loadInputs.shelves
                val continueWatching = loadInputs.continueWatching
                val overlayContinue = loadInputs.overlayContinue
                val mergedContinueWatching = (continueWatching + overlayContinue)
                    // Direct files and sports events have their own playback
                    // bookmarks; they are not movie/show catalog cards.
                    .filter { it.mediaId.isCatalogPlaybackIdentity() }
                    .groupBy { "${it.mediaType.name}:${it.mediaId}" }
                    .mapNotNull { (_, entries) -> entries.newestWithArtwork() }
                    .sortedByDescending { it.updatedAt }
                    .take(20)
                val recommendations = loadInputs.recommendations
                val watchlistItems = loadInputs.watchlistItems
                val recentHistory = loadInputs.recentHistory
                val hiddenGems = loadInputs.hiddenGems
                val allWatchedSources = loadInputs.recentlyWatched
                val recentlyWatched = allWatchedSources.take(MAX_RECENTLY_WATCHED_ITEMS)
                val upcomingScheduleResult = loadInputs.upcomingSchedule
                val upcomingSchedule = upcomingScheduleResult.items
                val popularPeople = if (contentPolicyFilter.familyKidsProfile) emptyList() else fillHomePeopleRows(loadInputs.popularPeople,
                    actors = _sectionConfigs.value.any { it.enabled && it.section == HomeSection.ACTORS },
                    directors = _sectionConfigs.value.any { it.enabled && it.section == HomeSection.DIRECTORS },
                    maxPages = 2,
                    fetch = { metadataRepo.getPopularPeople(it) })
                val popularActors = popularPeople.filter { it.knownForDepartment == "Acting" }.take(20)
                val directorDepartments = homeDirectorDepartments
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
                            val items = if (f.traktListId != null) {
                                searchLists?.homeItems(f.traktListId, f.traktListOwned).orEmpty()
                            } else if (f.specificTmdbIds.isNotEmpty()) {
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
                val watchlistMediaItems = watchlistItems.map { it.toHomeWatchlistItem() }
                val watchlistShelf = if (watchlistMediaItems.isNotEmpty()) {
                    CatalogShelf(
                        id = "your_watchlist",
                        title = "Your Watchlist",
                        items = watchlistMediaItems,
                    )
                } else null

                // These are watched-source rails, not recommendation-preview
                // rails. Selecting a source poster opens its own paginated
                // More Like collection; Home never mixes suggestions into it.
                val becauseYouWatched = buildBecauseYouWatchedShelves(allWatchedSources)

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
                val orderedShelves = discoveryRowsWithMissingSources(shelves, _sectionConfigs.value)
                    .filter { shelf -> configMap[shelf.id]?.isVisible != false }
                    .sortedBy { shelf -> configMap[shelf.id]?.sortOrder ?: Int.MAX_VALUE }

                // Apply parental content filtering
                val activeProfile = try { profileRepo.getActiveProfile() } catch (_: Exception) { null }
                val maxRating = activeProfile?.maxContentRating
                val parentalFilteredShelves = orderedShelves.map { shelf ->
                    val items = contentPolicyFilter.filterProfileLimit(shelf.items, maxRating)
                    shelf.copy(items = if (dedupe) items.dedupeByStableKey() else items)
                }
                val filteredRecommendations = if (maxRating != null) {
                    recommendations.filter { scored ->
                        contentPolicyFilter.filterProfileLimit(listOf(scored.item), maxRating).isNotEmpty()
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
                    shelves = becauseYouWatched.mapNotNull { shelf ->
                        shelf.copy(items = contentPolicyFilter.filterProfileLimit(shelf.items, maxRating))
                            .takeIf { it.items.isNotEmpty() }
                    },
                    sourceType = ContentSourceType.LOCAL_LIBRARY,
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
                        shelves = policyFilteredShelves,
                        globalSeen = globalSeen,
                        policy = policy,
                        maxRating = maxRating,
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
                        keepingAvailableRowMinimum(map.values.toList(), policyFilteredRecommendations) { it.item.stableKey() }
                    }

                    // 4. Cross-dedup hidden gems
                    val finalHiddenGems = withinDedupedHiddenGems?.let { shelf ->
                        val filtered = shelf.items.filter { item ->
                            val key = item.stableKey()
                            if (key in globalSeen) false
                            else { globalSeen.add(key); true }
                        }
                        shelf.copy(items = keepingAvailableRowMinimum(filtered, shelf.items) { it.stableKey() })
                    }

                    // 5. Watched-source rails intentionally repeat items from
                    // Recently Watched because they navigate to recommendation
                    // collections rather than details. Other shelves still dedupe.
                    val finalByw = withinDedupedByw
                    val finalAddons = keepAvailableShelfMinimum(withinDedupedAddons, globalSeen)
                    val finalMdbList = keepAvailableShelfMinimum(withinDedupedMdbList, globalSeen)

                    // 6. Cross-dedup custom shelves
                    val finalCustom = withinDedupedCustom.mapValues { (_, items) ->
                        val selected = items.filter { item ->
                            val key = item.stableKey()
                            if (key in globalSeen) false
                            else { globalSeen.add(key); true }
                        }
                        keepingAvailableRowMinimum(selected, items) { it.stableKey() }
                    }.filter { it.value.isNotEmpty() }.toMutableMap()
                    val hydratedShelves = withContext(ioDispatcher) { hydrateShelvesFromCache(finalShelves) }
                    val hydratedRecommendations = withContext(ioDispatcher) { hydrateRecommendationsFromCache(finalRecommendations) }
                    val hydratedWatchlistItems = withContext(ioDispatcher) { hydrateItemsFromCache(withinDedupedWatchlist) }
                    val hydratedUpcomingSchedule = withContext(ioDispatcher) { hydrateItemsFromCache(withinDedupedUpcomingSchedule) }
                        .mergeScheduleVisualsFrom(_state.value.upcomingSchedule)
                    val hydratedWatchlistShelf = policyFilteredWatchlistShelf?.copy(items = hydratedWatchlistItems)
                    val effectiveUpcomingStatus = effectiveUpcomingScheduleStatus(
                        originalStatus = upcomingScheduleResult.status,
                        visibleItems = hydratedUpcomingSchedule,
                    )
                    val hydratedByw = withContext(ioDispatcher) { hydrateShelvesFromCache(finalByw) }
                    val hydratedHiddenGems = withContext(ioDispatcher) { hydrateShelfFromCache(finalHiddenGems) }
                    val hydratedRecents = withContext(ioDispatcher) { hydrateItemsFromCache(withinDedupedRecents) }
                    val hydratedCustom = withContext(ioDispatcher) { hydrateCustomShelvesFromCache(finalCustom) }
                    val hydratedAddons = withContext(ioDispatcher) { hydrateShelvesFromCache(finalAddons) }
                    val hydratedMdbList = withContext(ioDispatcher) { hydrateShelvesFromCache(finalMdbList) }
                    val hydratedContinueWatching = withContext(ioDispatcher) { hydrateContinueWatchingFromCache(policyFilteredWatchProgress) }
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

                    updateHomeFor(loadingOwner) {
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
                    val hydratedShelves = withContext(ioDispatcher) { hydrateShelvesFromCache(backfillDiscoveryShelves(
                        policyFilteredShelves, mutableSetOf(), policy, maxRating = maxRating, deduplicate = false)) }
                    val hydratedRecommendations = withContext(ioDispatcher) { hydrateRecommendationsFromCache(policyFilteredRecommendations) }
                    val hydratedWatchlistItems = withContext(ioDispatcher) { hydrateItemsFromCache(policyFilteredWatchlist) }
                    val hydratedUpcomingSchedule = withContext(ioDispatcher) { hydrateItemsFromCache(policyFilteredUpcomingSchedule) }
                        .mergeScheduleVisualsFrom(_state.value.upcomingSchedule)
                    val hydratedWatchlistShelf = policyFilteredWatchlistShelf?.copy(items = hydratedWatchlistItems)
                    val effectiveUpcomingStatus = effectiveUpcomingScheduleStatus(
                        originalStatus = upcomingScheduleResult.status,
                        visibleItems = hydratedUpcomingSchedule,
                    )
                    val hydratedByw = withContext(ioDispatcher) { hydrateShelvesFromCache(policyFilteredByw) }
                    val hydratedHiddenGems = withContext(ioDispatcher) { hydrateShelfFromCache(policyFilteredHiddenGems) }
                    val hydratedRecents = withContext(ioDispatcher) { hydrateItemsFromCache(policyFilteredRecents) }
                    val hydratedCustom = withContext(ioDispatcher) { hydrateCustomShelvesFromCache(policyFilteredCustom) }
                    val hydratedAddons = withContext(ioDispatcher) { hydrateShelvesFromCache(policyFilteredAddonShelves) }
                    val hydratedMdbList = withContext(ioDispatcher) { hydrateShelvesFromCache(policyFilteredMdbListShelves) }
                    val hydratedContinueWatching = withContext(ioDispatcher) { hydrateContinueWatchingFromCache(policyFilteredWatchProgress) }
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
                    updateHomeFor(loadingOwner) {
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

                currentCoroutineContext().ensureActive()
                launchRatingsEnrichment()
                launchArtworkBackfill()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                torveVerboseLog {
                    "HOME_TAB state_transition state=error ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
                }
                if (!keepContentVisible) {
                    updateHomeFor(loadingOwner) { it.copy(isLoading = false, error = homeContentLoadErrorMessage(e)) }
                    scheduleHomeRetry()
                } else {
                    updateHomeFor(loadingOwner) { it.copy(isLoading = false) }
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

    fun refreshForProfile() {
        homeLoadJob?.cancel(); continueWatchingRefreshJob?.cancel(); upcomingScheduleRefreshJob?.cancel()
        artworkBackfillJob?.cancel()
        artworkBackfillAttempts.clear()
        _state.value = HomeUiState()
        scope.launch {
            _sectionConfigs.value = loadSectionConfigs()
            _customSections.value = loadCustomSections()
            _enabledServiceIds.value = loadEnabledServiceIds()
            _addonShelfVisibility.value = loadAddonShelfVisibility()
            _homeLayoutOrder.value = ensureAllSectionsInLayoutOrder(loadHomeLayoutOrder())
            loadHomeScreen()
        }
    }

    /**
     * Refresh only the progress-derived rail after playback writes. A full Home
     * reload would restart unrelated network-backed shelves every ten seconds.
     */
    fun refreshContinueWatching() {
        continueWatchingRefreshJob?.cancel()
        continueWatchingRefreshJob = scope.launch {
            val (local, overlay) = withContext(ioDispatcher) {
                val localItems = runCatching { watchProgressRepo.getInProgress(20) }.getOrDefault(emptyList())
                val overlayItems = runCatching { libraryOverlayService.getContinueWatching(20) }.getOrDefault(emptyList())
                localItems to overlayItems
            }
            val merged = (local + overlay)
                .filter { it.mediaId.isCatalogPlaybackIdentity() }
                .groupBy { "${it.mediaType.name}:${it.mediaId}" }
                .mapNotNull { (_, entries) -> entries.newestWithArtwork() }
                .sortedByDescending { it.updatedAt }
                .take(20)
            val filtered = contentPolicyFilter.filterWatchProgress(
                policy = currentPolicy(),
                context = ContentAccessContext.HISTORY_DERIVED,
                items = merged,
            ).items
            val hydrated = withContext(ioDispatcher) { hydrateContinueWatchingFromCache(filtered) }
            val ratings = buildRatingsLookup(hydrated)
            _state.update { current ->
                current.copy(
                    continueWatching = filtered.withHomeArtworkFrom(current.continueWatching),
                    continueWatchingRatings = current.continueWatchingRatings + ratings,
                )
            }
            // Synced or newly started titles can arrive after the initial home load.
            launchArtworkBackfill()
        }
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
                !existingSchedule.needsSchedulePresentationRefresh()
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
            val hydratedSchedule = withContext(ioDispatcher) { hydrateItemsFromCache(filteredSchedule) }
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

        val items = hydrateUpcomingScheduleArtwork(
            entries = distinctEpisodes.map { episode -> episode to episode.toUpcomingScheduleItem() }
                .let { entries ->
                    val known = _state.value
                    val artwork = (known.watchlistItems + known.upcomingSchedule +
                        known.recentlyWatched + known.shelves.flatMap { it.items } + known.addonShelves.flatMap { it.items })
                        .filter { it.type == MediaType.SERIES }
                    val repaired = entries.map { it.second }.mergeScheduleVisualsFrom(artwork)
                    entries.zip(repaired) { (episode, _), item -> episode to item }
                },
            metadataRepo = metadataRepo,
            detailLookupLimit = 24,
        )
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

    private fun launchArtworkBackfill() {
        if (artworkBackfillJob?.isActive == true) return
        artworkBackfillJob = scope.launch {
            val now = currentTimeMillis()
            val requests = collectArtworkBackfillRequests(_state.value).filter { request ->
                val previous = artworkBackfillAttempts[artworkBackfillKey(request.type, request.tmdbId)]
                previous == null || now - previous >= 300_000L
            }
            if (requests.isEmpty()) return@launch
            requests.forEach { artworkBackfillAttempts[artworkBackfillKey(it.type, it.tmdbId)] = now }

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
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
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
        ratingsEnrichmentJob?.cancel()
        ratingsEnrichmentJob = scope.launch(Dispatchers.Default) {
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
                currentCoroutineContext().ensureActive()
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
        ratingsEnrichmentJob?.cancel()
        ratingsEnrichmentJob = scope.launch(Dispatchers.Default) { doRefreshRatings(apiKey) }
    }

    private suspend fun doRefreshRatings(apiKey: String) {
        // Even without an MDBList key, the enricher still resolves IMDB + RT +
        // Metacritic via OMDB and falls back to Trakt for IMDB scores. Bailing
        // early on a blank key meant NO rail ever got rating pills unless the
        // user had configured MDBList.
        //
        // Rails are processed sequentially so state updates (one per rail)
        // arrive paced rather than in a burst. Merges retain unchanged items
        // and lists; equal state is suppressed by StateFlow. In particular,
        // delayed responses cannot replace a newly loaded rail.
        val current = _state.value

        // Catalog-shelf groups: shelves, addonShelves, mdbListShelves, becauseYouWatched
        current.shelves.forEach { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                state.copy(shelves = state.shelves.mergeHomeRatings(shelf.id, updated))
            }
        }
        current.addonShelves.forEach { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                state.copy(addonShelves = state.addonShelves.mergeHomeRatings(shelf.id, updated))
            }
        }
        current.mdbListShelves.forEach { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                state.copy(mdbListShelves = state.mdbListShelves.mergeHomeRatings(shelf.id, updated))
            }
        }
        current.becauseYouWatched.forEach { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                state.copy(becauseYouWatched = state.becauseYouWatched.mergeHomeRatings(shelf.id, updated))
            }
        }

        // Optional shelf
        current.hiddenGemsShelf?.let { shelf ->
            enrichShelfAndApply(apiKey, shelf) { state, updated ->
                if (state.hiddenGemsShelf?.id == shelf.id) {
                    state.copy(hiddenGemsShelf = updated.apply(state.hiddenGemsShelf).sortTopRatedShelfByExternalRatings())
                } else state
            }
        }

        // Custom shelves keyed by section id
        current.customShelves.forEach { (sectionId, items) ->
            val update = enrichRatings(items, apiKey) ?: return@forEach
            val context = currentCoroutineContext()
            _state.update { state ->
                context.ensureActive()
                val visible = state.customShelves[sectionId] ?: return@update state
                val merged = update.apply(visible)
                state.copy(
                    customShelves = state.customShelves + (sectionId to merged),
                    continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(merged),
                )
            }
        }

        // Merge into current lists: a response must never restore removed items,
        // an old catalogue order, old artwork or another profile's content.
        val context = currentCoroutineContext()
        enrichRatings(current.watchlistItems, apiKey)?.let { update ->
            _state.update { state ->
                context.ensureActive()
                val merged = update.apply(state.watchlistItems)
                state.copy(
                    watchlistItems = merged,
                    continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(merged),
                )
            }
        }
        enrichRatings(current.recentlyWatched, apiKey)?.let { update ->
            _state.update { state ->
                context.ensureActive()
                val merged = update.apply(state.recentlyWatched)
                state.copy(
                    recentlyWatched = merged,
                    continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(merged),
                )
            }
        }
        enrichRatings(current.recommendedItems.map { it.item }, apiKey)?.let { update ->
            _state.update { state ->
                context.ensureActive()
                val merged = state.recommendedItems.map { scored ->
                    val item = update.apply(scored.item)
                    if (item === scored.item) scored else scored.copy(item = item)
                }
                state.copy(
                    recommendedItems = merged,
                    continueWatchingRatings = state.continueWatchingRatings.mergeRatingsFor(merged.map { it.item }),
                )
            }
        }
    }

    private suspend fun enrichShelfAndApply(
        apiKey: String,
        shelf: CatalogShelf,
        applyToState: (HomeUiState, HomeRatingsUpdate) -> HomeUiState,
    ) {
        val update = enrichRatings(shelf.items, apiKey) ?: return
        val context = currentCoroutineContext()
        _state.update { state ->
            context.ensureActive()
            val next = applyToState(state, update)
            val visible = next.shelves + next.addonShelves + next.mdbListShelves + next.becauseYouWatched + listOfNotNull(next.hiddenGemsShelf)
            val mergedItems = visible.firstOrNull { it.id == shelf.id }?.items.orEmpty()
            next.copy(
                heroItem = next.heroItem?.let(update::apply),
                continueWatchingRatings = next.continueWatchingRatings.mergeRatingsFor(mergedItems),
            )
        }
    }

    private suspend fun enrichRatings(items: List<MediaItem>, apiKey: String): HomeRatingsUpdate? {
        currentCoroutineContext().ensureActive()
        if (items.isEmpty()) return null
        return try {
            val enriched = ratingsEnricher.enrichList(items, apiKey)
            currentCoroutineContext().ensureActive()
            HomeRatingsUpdate(items, enriched)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun List<CatalogShelf>.mergeHomeRatings(id: String, update: HomeRatingsUpdate): List<CatalogShelf> {
        val merged = map { shelf ->
            if (shelf.id == id) update.apply(shelf).sortTopRatedShelfByExternalRatings() else shelf
        }
        return if (merged == this) this else merged
    }

    private fun keepAvailableShelfMinimum(shelves: List<CatalogShelf>, seen: MutableSet<String>): List<CatalogShelf> {
        val unique = shelves.dedupeAcrossShelves(seen).associateBy { it.id }
        return shelves.map { shelf -> shelf.copy(items = keepingAvailableRowMinimum(
            unique[shelf.id]?.items.orEmpty(), shelf.items) { it.stableKey() }) }
    }

    private fun CatalogShelf.sortTopRatedShelfByExternalRatings(): CatalogShelf {
        if (id != "top-rated") return this
        val sorted = items.sortedWith(
            compareByDescending<MediaItem> { it.homeTopRatedExternalRating10() ?: -1.0 }
                .thenByDescending { it.ratings?.imdbVotes ?: 0 }
                .thenBy { it.title.lowercase() },
        )
        return if (sorted == items) this else copy(items = sorted)
    }

    private fun MediaItem.homeTopRatedExternalRating10(): Double? {
        val ratings = ratings ?: return null
        val imdb = ratings.imdbScore
            ?.takeIf { it > 0f }
            ?.toDouble()
        val rt = ratings.rottenTomatoesScore
            ?.takeIf { it in 1..100 }
            ?.toDouble()
            ?.div(10.0)
        val values = listOfNotNull(imdb, rt)
        if (values.isEmpty()) return null
        return values.average()
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
            // Saved progress can predate catalogue artwork or arrive from
            // another device without it. Repair personal rows before the
            // bounded batch is filled by generic catalogue items.
            addAll(state.continueWatching.mapNotNull { value ->
                val tmdbId = value.mediaId.extractTmdbIdOrNull() ?: return@mapNotNull null
                MediaItem(value.mediaId, tmdbId = tmdbId, title = value.showTitle ?: value.title,
                    type = value.mediaType, posterUrl = value.posterUrl, backdropUrl = value.backdropUrl)
            })
            addAll(state.watchlistItems)
            addAll(state.recentlyWatched)
            addAll(state.recommendedItems.map { it.item })
            addAll(state.upcomingSchedule.take(24))
            addAll(state.shelves.flatMap { it.items })
            addAll(state.becauseYouWatched.flatMap { it.items })
            state.hiddenGemsShelf?.let { addAll(it.items) }
            addAll(state.customShelves.values.flatten())
            addAll(state.addonShelves.flatMap { it.items })
            addAll(state.mdbListShelves.flatMap { it.items })
        }
            .prioritizeHomeArtwork().asSequence()
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
            continueWatching = continueWatching.map { progress ->
                val id = progress.mediaId.extractTmdbIdOrNull()
                val detail = id?.let { backfilledArtwork[artworkBackfillKey(progress.mediaType, it)] }
                if (detail == null) progress else progress.copy(
                    posterUrl = progress.posterUrl.takeUnless { it.isNullOrBlank() } ?: detail.posterUrl,
                    backdropUrl = progress.backdropUrl.takeUnless { it.isNullOrBlank() } ?: detail.backdropUrl,
                )
            },
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
        return shelves.map { shelf ->
            shelf.copy(items = hydrateItemsFromCache(shelf.items))
                .sortTopRatedShelfByExternalRatings()
        }
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
            shelf.copy(items = filteredItems).takeIf { filteredItems.isNotEmpty() || shelf.id in pagedDiscoveryRows }
        }
    }

    private suspend fun backfillDiscoveryShelves(
        shelves: List<CatalogShelf>, globalSeen: MutableSet<String>, policy: ContentPolicyState,
        maxRating: com.torve.domain.model.ContentRating? = null, deduplicate: Boolean = true,
    ): List<CatalogShelf> = fillHomeDiscoveryRows(shelves, globalSeen, deduplicate,
        // Home previews need a small working set. See All owns deeper paging.
        maxPage = 2,
        filter = { candidates -> contentPolicyFilter.filterItems(policy, ContentAccessContext.DEFAULT_DISCOVERY,
            contentPolicyFilter.filterProfileLimit(candidates, maxRating), ContentSourceType.TMDB).items },
        fetch = { id, page ->
            fun com.torve.domain.model.PagedResult.rowPage() = HomeDiscoveryPage(items, this.page < totalPages, this.page + 1)
            when (id) {
                "trending-movies" -> metadataRepo.getTrendingPaged("movie", page).rowPage()
                "trending-tv" -> metadataRepo.getTrendingPaged("tv", page).rowPage()
                "popular-movies" -> metadataRepo.getPopularPaged("movie", page).rowPage()
                "popular-tv" -> metadataRepo.getPopularPaged("tv", page).rowPage()
                "top-rated" -> metadataRepo.getTopRatedPaged("movie", page).rowPage()
                "upcoming" -> metadataRepo.getUpcomingPaged(page).rowPage()
                "now-playing" -> metadataRepo.getNowPlaying(page).let { HomeDiscoveryPage(it, it.isNotEmpty()) }
                "airing-today" -> metadataRepo.getAiringToday(page).let { HomeDiscoveryPage(it, it.isNotEmpty()) }
                else -> HomeDiscoveryPage(emptyList(), false)
            }
        })

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
                addon.manifest.catalogueShelfId(catalog) to flags
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
        // v4 rebuilds watched-source rails from independently bounded movie
        // and TV history, instead of restoring the old raw 80-row window.
        // v4 could already have converted sports/file hashes into apparent TMDB IDs.
        private const val HOME_SNAPSHOT_KEY = "home_snapshot_v5"
        private const val MAX_RECENTLY_WATCHED_ITEMS = 80
        private const val MAX_WATCHED_SOURCES_PER_TYPE = 80
        private const val HOME_SNAPSHOT_MAX_STALE_AGE_MS = 30L * 24L * 60L * 60L * 1000L
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

internal const val BECAUSE_YOU_WATCHED_MOVIES_ID = "because_you_watched_movies"
internal const val BECAUSE_YOU_WATCHED_TV_ID = "because_you_watched_tv"
internal const val BECAUSE_YOU_WATCHED_MOVIES_TITLE = "Because You Watched (Movies)"
internal const val BECAUSE_YOU_WATCHED_TV_TITLE = "Because You Watched (TV Shows)"

internal fun buildBecauseYouWatchedShelves(watchedItems: List<MediaItem>): List<CatalogShelf> {
    val eligible = watchedItems
        .asSequence()
        .filter { item -> item.tmdbId?.let { it > 0 } == true }
        .distinctBy { it.stableKey() }
        .toList()
    return buildList {
        eligible.filter { it.type == MediaType.MOVIE }
            .takeIf { it.isNotEmpty() }
            ?.let { movies ->
                add(
                    CatalogShelf(
                        id = BECAUSE_YOU_WATCHED_MOVIES_ID,
                        title = BECAUSE_YOU_WATCHED_MOVIES_TITLE,
                        items = movies,
                    ),
                )
            }
        eligible.filter { it.type == MediaType.SERIES }
            .takeIf { it.isNotEmpty() }
            ?.let { shows ->
                add(
                    CatalogShelf(
                        id = BECAUSE_YOU_WATCHED_TV_ID,
                        title = BECAUSE_YOU_WATCHED_TV_TITLE,
                        items = shows,
                    ),
                )
            }
    }
}

internal fun expandLegacyAddonLayoutKeys(saved: List<String>, aliases: Map<String, List<String>>): List<String> =
    saved.flatMap { key ->
        val replacement = if (key.startsWith("addon:")) aliases[key.removePrefix("addon:")] else null
        replacement?.map { "addon:$it" } ?: listOf(key)
    }.distinct()

internal fun completeHomeLayoutOrder(saved: List<String>, configs: List<HomeSectionConfig>): List<String> {
    if (saved.isEmpty()) return saved
    val existing = saved.toSet()
    val result = saved.toMutableList()
    configs.filter { "section:${it.section.name}" !in existing }.sortedBy { it.order }.forEach { config ->
        // SEARCH_BAR has default order -1. A partially saved layout must not
        // pass that negative position to MutableList.add on app startup.
        result.add(config.order.coerceIn(0, result.size), "section:${config.section.name}")
    }
    return result
}

internal fun mergeBuiltInHomeLayoutOrder(
    existingOrder: List<String>,
    configs: List<HomeSectionConfig>,
): List<String> {
    val orderedBuiltIns = configs
        .sortedWith(compareBy<HomeSectionConfig> { it.order }.thenBy { it.section.ordinal })
        .map { "section:${it.section.name}" }
    if (existingOrder.isEmpty()) return orderedBuiltIns

    val builtInIterator = orderedBuiltIns.iterator()
    val merged = existingOrder.mapNotNull { key ->
        if (key.startsWith("section:")) {
            if (builtInIterator.hasNext()) builtInIterator.next() else null
        } else {
            key
        }
    }.toMutableList()
    while (builtInIterator.hasNext()) merged += builtInIterator.next()
    return merged.distinct()
}
