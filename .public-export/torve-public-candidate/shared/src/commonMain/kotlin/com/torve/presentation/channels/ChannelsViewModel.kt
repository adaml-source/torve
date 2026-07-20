package com.torve.presentation.channels

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.LiveTvEpgResolver
import com.torve.domain.model.PlaylistType
import com.torve.domain.model.channelIdentityCandidates
import com.torve.domain.model.channelMatchesIdentity
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.programmesForEpgChannel
import com.torve.domain.model.stableChannelId
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.data.channels.CatchupResolver
import com.torve.data.auth.AuthClient
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.util.ioDispatcher
import com.torve.util.mainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

private const val KEY_CHANNELS_AUDIO_PASSTHROUGH = "channels_audio_passthrough_enabled"
private const val KEY_CHANNELS_PREFER_SURROUND = "channels_prefer_surround_codecs"
private const val KEY_CHANNELS_AUDIO_OUTPUT_MODE = "channels_live_audio_output_mode"
private const val KEY_CHANNELS_SELECTED_PLAYLIST = "channels_selected_playlist"
private const val KEY_CHANNELS_SELECTED_GROUP_PREFIX = "channels_selected_group_"
private const val KEY_CHANNELS_SELECTED_CHANNEL_PREFIX = "channels_selected_channel_"
private const val KEY_CHANNELS_LAST_WATCHED_CHANNEL_PREFIX = "channels_last_watched_channel_"
private const val KEY_CHANNELS_BOOTSTRAP_SELECTED_PLAYLIST_PREFIX = "channels_bootstrap_selected_playlist_"
private const val KEY_CHANNELS_BOOTSTRAP_CATEGORIES_PREFIX = "channels_bootstrap_categories_"
// Bumped 2026-05-05: was 160, which silently truncated the EPG grid on
// playlists with hundreds of live channels. Desktop has no memory
// constraint that justifies the old phone-era cap. 1000 is generous
// while still defending against pathological 10k-channel lists.
private const val MAX_GUIDE_CHANNELS_IN_STATE = 1000
private const val MAX_FULL_PLAYLIST_LOAD_IN_STATE = 5_000L
private const val EPG_DEBUG_LOG_ENABLED = false

class ChannelsViewModel(
    private val channelRepo: ChannelRepository,
    private val prefsRepo: PreferencesRepository,
    private val localSettingsRepo: DeviceLocalSettingsRepository? = null,
    private val catchupResolver: CatchupResolver = CatchupResolver(),
    private val backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = ioDispatcher,
    private val playlistBackup: com.torve.presentation.session.AccountSessionCoordinator? = null,
    private val settingsRefreshNotifier: com.torve.presentation.settings.SettingsRefreshNotifier? = null,
    /**
     * Optional EPG correction surfaces. When wired (default in
     * production via Koin), guide builds apply the persisted offset +
     * tvg-id remap and the live page surfaces a stale-EPG banner.
     * Null in tests / older platforms keeps the legacy code path
     * byte-equivalent.
     */
    private val epgCorrectionRepository: com.torve.data.recording.EpgCorrectionRepository? = null,
    private val epgCorrectionViewModel: com.torve.presentation.recording.EpgCorrectionViewModel? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val _state = MutableStateFlow(ChannelsUiState())
    val state: StateFlow<ChannelsUiState> = _state.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")
    private var guideJob: Job? = null
    private var epgRefreshJob: Job? = null
    private var catalogLoadJob: Job? = null
    private var startupPhase = "idle"

    init {
        println("CHANNELS_VM_INIT: ChannelsViewModel created")
        // Settings refreshes do not invalidate the channels catalog — the previous
        // `notifier.events.collect { loadPlaylists() }` here caused 3+ rapid CATALOG_LOAD
        // calls on every startup, each cancelling the previous DB query mid-flight.
        // Playlist data is only loaded once by the init coroutine below, plus user-driven
        // refresh actions (the explicit Refresh button).
        // ── TiviMate-style cache-first startup ──
        // Single orchestrated sequence: restore cache → load prefs → load playlists.
        // All DB/prefs IO runs on backgroundDispatcher. Only _state.update touches Main.
        scope.launch {
            val initStartMs = Clock.System.now().toEpochMilliseconds()
            startupPhase = "cache_restore"

            // Phase 1: Restore cached categories from preferences (no DB, instant).
            val bootstrapUserId = withContext(backgroundDispatcher) { currentBootstrapUserId() }
            val cachedPlaylistId = withContext(backgroundDispatcher) {
                prefsRepo.getString(KEY_CHANNELS_SELECTED_PLAYLIST)
                    ?: bootstrapUserId?.let { userId ->
                        localSettingsRepo?.getString(channelsBootstrapSelectedPlaylistKey(userId))
                    }
            }
            val cachedCategoriesJson = withContext(backgroundDispatcher) {
                prefsRepo.getString(KEY_CACHED_CATEGORIES)
                    ?: bootstrapUserId?.let { userId ->
                        localSettingsRepo?.getString(channelsBootstrapCategoriesKey(userId))
                    }
            }
            val cachedCategories = parseCachedCategories(cachedCategoriesJson)
                .filterNot { isVodCategoryName(it.name) }
            val hasCachedCatalog = cachedPlaylistId != null && cachedCategories.isNotEmpty()
            if (hasCachedCatalog) {
                _state.update {
                    it.copy(
                        selectedPlaylistId = cachedPlaylistId,
                        categories = cachedCategories,
                        allCategories = cachedCategories,
                        isLoading = false,
                        isLoadingChannels = false,
                    )
                }
                val cacheMs = Clock.System.now().toEpochMilliseconds() - initStartMs
                println("STARTUP[${cacheMs}ms] cache_restore: ${cachedCategories.size} categories for playlist=$cachedPlaylistId — UI ready")
            } else {
                println("STARTUP[0ms] cache_restore: no cached categories — will query DB")
            }

            // Phase 2: Load prefs (filters, hidden items, audio settings) — serialized on IO.
            startupPhase = "prefs_load"
            withContext(backgroundDispatcher) { migrateOldPreferenceKeys() }
            loadSavedFiltersSync()
            loadHiddenItemsSync()
            loadAudioSettingsSync()
            val prefsMs = Clock.System.now().toEpochMilliseconds() - initStartMs
            println("STARTUP[${prefsMs}ms] prefs_load: complete")

            // Phase 3: Load playlist metadata from DB (fast — single row).
            startupPhase = "playlist_load"
            val playlists = withContext(backgroundDispatcher) { channelRepo.getPlaylists() }
            val selectedPlaylistId = withContext(backgroundDispatcher) { resolvePreferredPlaylistId(playlists) }
            // On cache-first startup, mark EPG as a quiet loaded state so neither
            // the "not configured" nor "loading" banners appear. The EPG grid
            // loads channel data on-demand per category — no full guide build needed.
            val selectedPlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
            val epgSourceUrl = selectedPlaylist.resolveEpgSourceUrl().orEmpty()
            val derivedEpgState = if (epgSourceUrl.isBlank()) {
                EpgState.NotConfigured
            } else {
                EpgState.Loaded(sourceUrl = epgSourceUrl, sourceChannelCount = 0, sourceProgrammeCount = 0, matchedChannelCount = 0, unmatchedChannelCount = 0)
            }
            _state.update {
                it.copy(
                    playlists = playlists,
                    isLoading = false,
                    selectedPlaylistId = selectedPlaylistId ?: it.selectedPlaylistId,
                    epgState = if (it.epgState == EpgState.NotConfigured) derivedEpgState else it.epgState,
                )
            }
            if (selectedPlaylistId != null) {
                withContext(backgroundDispatcher) {
                    persistSelectedPlaylistId(selectedPlaylistId)
                }
            }
            val playlistMs = Clock.System.now().toEpochMilliseconds() - initStartMs
            println("STARTUP[${playlistMs}ms] playlist_load: ${playlists.size} playlists, selected=$selectedPlaylistId")

            // Phase 4: If cache was shown, we're done for blocking startup.
            // Kick off deferred DB category verification (non-blocking).
            // If no cache, do the DB category query now.
            val targetPlaylistId = selectedPlaylistId ?: cachedPlaylistId
            if (targetPlaylistId != null) {
                if (hasCachedCatalog && targetPlaylistId == cachedPlaylistId) {
                    // Cache hit — startup is complete. Defer DB verification.
                    startupPhase = "interactive"
                    println("STARTUP[${Clock.System.now().toEpochMilliseconds() - initStartMs}ms] interactive: cache-first startup complete")
                    // Deferred: silently verify categories from DB (won't block UI).
                    // EPG loading happens lazily when the user opens the channels screen
                    // (via TvIptvScreen's LaunchedEffect calling `ensureEpgLoaded`), not
                    // here — calling it here cascades into `buildGuideChannels` ->
                    // `ensureFullPlaylistLoaded` which loads tens of thousands of channels
                    // into memory and hangs the home screen on launch.
                    hydrateInitialCategorySelection(
                        playlistId = targetPlaylistId,
                        previousPlaylistId = null,
                        categories = cachedCategories,
                        restoreSavedState = true,
                    )
                    deferredCategoryVerification(targetPlaylistId)
                } else {
                    // Cache miss or playlist changed — must query DB for categories.
                    startupPhase = "db_category_load"
                    loadPlaylistCatalog(
                        playlistId = targetPlaylistId,
                        restoreSavedState = true,
                        triggerBackgroundRefresh = false,
                        showLoadingUntilRefresh = false,
                    )
                    println("STARTUP[${Clock.System.now().toEpochMilliseconds() - initStartMs}ms] db_category_load: triggered")
                }
            }

            // Phase 5: Load favorites and recent (non-blocking, fire-and-forget)
            loadFavoritesAsync()
            loadRecentlyViewedAsync()
        }
        observeSearch()
        observeAccountSession()
        observeAccountCatalogClearEvents()
    }

    /**
     * Deferred category verification: after cache-first startup, silently
     * compare cached categories with DB. If different, update state + cache.
     * Runs fully on IO, only touches Main for the final _state.update.
     */
    private fun deferredCategoryVerification(playlistId: String) {
        scope.launch {
            val verifyStart = Clock.System.now().toEpochMilliseconds()
            val dbCategories = withContext(backgroundDispatcher) {
                val counts = liveCategoryCounts(playlistId)
                if (counts.isEmpty()) return@withContext emptyList()
                val allCats = CategoryNameCleaner.processCategoryCountsOnly(counts)
                val hiddenLower = _state.value.hiddenCategories.map { it.lowercase() }.toSet()
                allCats.filter { it.name.lowercase() !in hiddenLower }
            }
            val verifyMs = Clock.System.now().toEpochMilliseconds() - verifyStart
            val currentCategories = _state.value.categories
            if (dbCategories.isNotEmpty() && dbCategories != currentCategories) {
                _state.update { it.copy(categories = dbCategories, allCategories = dbCategories) }
                withContext(backgroundDispatcher) { persistCachedCategoriesNow(dbCategories) }
                println("STARTUP_DEFERRED[${verifyMs}ms] category_verify: updated ${currentCategories.size} → ${dbCategories.size} categories")
            } else {
                println("STARTUP_DEFERRED[${verifyMs}ms] category_verify: cache matches DB (${currentCategories.size} categories)")
            }

            val playlist = _state.value.playlists.firstOrNull { it.id == playlistId }
            val lastUpdated = playlist?.lastUpdated ?: 0L
            val ageMs = Clock.System.now().toEpochMilliseconds() - lastUpdated
            println("REFRESH_GATE: playlistId=$playlistId lastUpdated=$lastUpdated ageMs=$ageMs ageSec=${ageMs / 1000} decision=SKIP_AUTO_STARTUP")
        }
    }

    private fun parseCachedCategories(json: String?): List<ChannelCategory> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            json.split("\n").filter { it.isNotBlank() }.map { line ->
                val parts = line.split("\t")
                ChannelCategory(
                    name = parts[0],
                    channelCount = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    countryCode = parts.getOrNull(2)?.takeIf { it != "null" },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun persistCachedCategoriesNow(categories: List<ChannelCategory>) {
        val serialized = categories.joinToString("\n") { "${it.name}\t${it.channelCount}\t${it.countryCode}" }
        prefsRepo.setString(KEY_CACHED_CATEGORIES, serialized)
        currentBootstrapUserId()?.let { userId ->
            localSettingsRepo?.setString(channelsBootstrapCategoriesKey(userId), serialized)
        }
    }

    private suspend fun currentBootstrapUserId(): String? {
        return localSettingsRepo
            ?.getString(AuthClient.KEY_AUTH_USER_ID)
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun persistSelectedPlaylistId(playlistId: String) {
        prefsRepo.setString(KEY_CHANNELS_SELECTED_PLAYLIST, playlistId)
        currentBootstrapUserId()?.let { userId ->
            localSettingsRepo?.setString(channelsBootstrapSelectedPlaylistKey(userId), playlistId)
        }
    }

    private suspend fun removeSelectedPlaylistId() {
        prefsRepo.remove(KEY_CHANNELS_SELECTED_PLAYLIST)
        currentBootstrapUserId()?.let { userId ->
            localSettingsRepo?.remove(channelsBootstrapSelectedPlaylistKey(userId))
            localSettingsRepo?.remove(channelsBootstrapCategoriesKey(userId))
        }
    }

    private fun channelsBootstrapSelectedPlaylistKey(userId: String): String {
        return "$KEY_CHANNELS_BOOTSTRAP_SELECTED_PLAYLIST_PREFIX$userId"
    }

    private fun channelsBootstrapCategoriesKey(userId: String): String {
        return "$KEY_CHANNELS_BOOTSTRAP_CATEGORIES_PREFIX$userId"
    }

    // Synchronous versions of init loaders — called within the serialized init launch.
    // No separate scope.launch; run sequentially on the init coroutine.

    private suspend fun loadSavedFiltersSync() {
        val (countries, xxx) = withContext(backgroundDispatcher) {
            prefsRepo.getString("channels_country_filter") to
                prefsRepo.getString("channels_xxx_enabled")
        }
        _state.update {
            it.copy(
                selectedCountries = countries?.split(",")?.filter { c -> c.isNotBlank() }?.toSet() ?: emptySet(),
                xxxEnabled = xxx == "true",
            )
        }
    }

    private suspend fun loadHiddenItemsSync() {
        val (cats, chs) = withContext(backgroundDispatcher) {
            prefsRepo.getString("channels_hidden_categories") to
                prefsRepo.getString("channels_hidden_channels")
        }
        val rawHiddenChannels = chs?.split("|||")?.filter { c -> c.isNotBlank() }?.toSet() ?: emptySet()
        withContext(backgroundDispatcher) {
            channelRepo.syncHiddenChannelsToDb(rawHiddenChannels)
        }
        val migratedSet = withContext(backgroundDispatcher) {
            channelRepo.getHiddenChannelIds()
        }
        _state.update {
            it.copy(
                hiddenCategories = cats?.split("|||")?.filter { c -> c.isNotBlank() }?.toSet() ?: emptySet(),
                hiddenChannels = migratedSet,
            )
        }
        if (migratedSet != rawHiddenChannels && migratedSet.isNotEmpty()) {
            withContext(backgroundDispatcher) {
                prefsRepo.setString("channels_hidden_channels", migratedSet.joinToString("|||"))
            }
        }
    }

    private suspend fun loadAudioSettingsSync() {
        val (passthrough, preferSurround, outputMode) = withContext(backgroundDispatcher) {
            Triple(
                prefsRepo.getString(KEY_CHANNELS_AUDIO_PASSTHROUGH)
                    ?.toBooleanStrictOrNull() ?: false,
                prefsRepo.getString(KEY_CHANNELS_PREFER_SURROUND)
                    ?.toBooleanStrictOrNull() ?: true,
                LiveAudioOutputMode.fromStorage(
                    prefsRepo.getString(KEY_CHANNELS_AUDIO_OUTPUT_MODE),
                ),
            )
        }
        _state.update {
            it.copy(
                audioPassthroughEnabled = passthrough,
                preferSurroundCodecs = preferSurround,
                liveAudioOutputMode = outputMode,
            )
        }
    }

    private fun loadFavoritesAsync() {
        scope.launch {
            try {
                val favs = withContext(backgroundDispatcher) { channelRepo.getFavorites() }
                _state.update { it.copy(favorites = favs) }
            } catch (_: Exception) { }
        }
    }

    private fun loadRecentlyViewedAsync() {
        scope.launch {
            try {
                val recent = withContext(backgroundDispatcher) { channelRepo.getRecentlyViewedChannels(20) }
                _state.update { it.copy(recentlyViewedChannels = recent) }
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val KEY_CACHED_CATEGORIES = "channels_cached_categories"
        private const val STALE_THRESHOLD_MS = 60 * 60 * 1000L // 1 hour
    }

    private fun ChannelsUiState.hasUsableCatalogFor(playlistId: String): Boolean {
        val storedCount = playlists.firstOrNull { it.id == playlistId }?.channelCount ?: 0
        return selectedPlaylistId == playlistId &&
            (channels.isNotEmpty() || categories.isNotEmpty() || groupedChannels.isNotEmpty() || storedCount > 0)
    }

    private suspend fun migrateOldPreferenceKeys() {
        val oldKeys = listOf(
            "iptv_country_filter" to "channels_country_filter",
            "iptv_xxx_enabled" to "channels_xxx_enabled",
            "iptv_hidden_categories" to "channels_hidden_categories",
            "iptv_hidden_channels" to "channels_hidden_channels",
        )
        for ((oldKey, newKey) in oldKeys) {
            prefsRepo.getString(oldKey)?.let { value ->
                prefsRepo.setString(newKey, value)
                prefsRepo.remove(oldKey)
            }
        }
    }

    fun loadPlaylists(recoverEmptyCatalog: Boolean = true) {
        scope.launch {
            _state.update { it.copy(isLoading = it.categories.isEmpty(), error = null) }
            try {
                val playlists = withContext(backgroundDispatcher) { channelRepo.getPlaylists() }
                val selectedPlaylistId = withContext(backgroundDispatcher) { resolvePreferredPlaylistId(playlists) }
                _state.update {
                    it.copy(
                        playlists = playlists,
                        isLoading = false,
                        selectedPlaylistId = selectedPlaylistId,
                    )
                }
                if (selectedPlaylistId != null) {
                    withContext(backgroundDispatcher) {
                        persistSelectedPlaylistId(selectedPlaylistId)
                    }
                    loadPlaylistCatalog(
                        playlistId = selectedPlaylistId,
                        restoreSavedState = true,
                        triggerBackgroundRefresh = false,
                        showLoadingUntilRefresh = false,
                        recoverEmptyCatalog = recoverEmptyCatalog,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.CHANNEL_LOAD_FAILED.messageKey) }
            }
        }
    }

    fun selectPlaylist(playlistId: String) {
        scope.launch { withContext(backgroundDispatcher) { persistSelectedPlaylistId(playlistId) } }
        loadPlaylistCatalog(
            playlistId = playlistId,
            restoreSavedState = true,
            triggerBackgroundRefresh = false,
            showLoadingUntilRefresh = false,
        )
        // Prompt 10C: union the persisted EPG-correction hidden-
        // categories into state.hiddenCategories so the existing
        // filters drop them from the rendered channel list. The
        // correction repo is the source of truth for the user's
        // toggles in Settings → EPG Correction; this bridge keeps the
        // two stores aligned without invasive filter rewires.
        epgCorrectionRepository?.let { repo ->
            scope.launch {
                val correction = runCatching { repo.get(playlistId) }.getOrNull() ?: return@launch
                if (correction.hiddenCategories.isEmpty()) return@launch
                val hiddenLower = correction.hiddenCategories.map { it.lowercase() }.toSet()
                val current = _state.value.hiddenCategories
                val toAdd = correction.hiddenCategories.filter { it.lowercase() !in current.map { c -> c.lowercase() } }
                if (toAdd.isEmpty()) return@launch
                val merged = current + toAdd
                val visible = _state.value.allCategories
                    .filter { it.name.lowercase() !in hiddenLower && it.name.lowercase() !in current.map { c -> c.lowercase() } }
                _state.update {
                    it.copy(
                        hiddenCategories = merged,
                        categories = visible,
                    )
                }
            }
        }
    }

    fun selectGroup(group: String?) {
        _state.update { it.copy(selectedGroup = group) }
        val playlistId = _state.value.selectedPlaylistId ?: return
        scope.launch { withContext(backgroundDispatcher) { persistSelectedGroup(playlistId, group) } }
    }

    fun getDisplayChannels(): List<EnrichedChannel> {
        val st = _state.value
        val group = st.selectedGroup
        val base = if (group != null) {
            st.groupedChannels[group] ?: emptyList()
        } else {
            st.channels
        }
        return applyFilters(base)
    }

    private fun applyFilters(channels: List<EnrichedChannel>): List<EnrichedChannel> {
        val st = _state.value
        var result = channels

        // Country filter
        if (st.selectedCountries.isNotEmpty()) {
            val selectedCountriesLower = st.selectedCountries.map { it.lowercase() }.toSet()
            result = result.filter { enriched ->
                val country = enriched.channel.tvgCountry ?: return@filter false
                val channelCountries = country
                    .split(",", ";")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                channelCountries.any { it in selectedCountriesLower }
            }
        }

        // XXX filter — hide adult content unless explicitly enabled
        if (!st.xxxEnabled) {
            val adultKeywords = setOf("xxx", "adult", "18+", "porn", "erotic")
            result = result.filter { enriched ->
                val group = enriched.channel.groupTitle?.lowercase() ?: ""
                val name = enriched.channel.name.lowercase()
                adultKeywords.none { keyword -> group.contains(keyword) || name.contains(keyword) }
            }
        }

        // Quality filter
        when (st.activeFilter) {
            ChannelsFilterType.HD -> result = result.filter { matchesQuality(it.channel.name, "hd") }
            ChannelsFilterType.FHD -> result = result.filter { matchesQuality(it.channel.name, "fhd") }
            ChannelsFilterType.UHD -> result = result.filter { matchesQuality(it.channel.name, "4k", "uhd") }
            ChannelsFilterType.FAVORITES -> {
                val favIds = st.favorites.flatMap(::channelIdentityCandidates).toSet()
                result = result.filter { enriched ->
                    channelIdentityCandidates(enriched.channel).any(favIds::contains)
                }
            }
            ChannelsFilterType.ALL -> { /* no additional filter */ }
        }

        // Sort
        result = when (st.activeSort) {
            ChannelsSortType.NAME_AZ -> result.sortedBy { it.channel.name.lowercase() }
            ChannelsSortType.NAME_ZA -> result.sortedByDescending { it.channel.name.lowercase() }
            ChannelsSortType.RECENTLY_ADDED -> result.reversed()
            ChannelsSortType.DEFAULT -> result
        }

        return result
    }

    private fun isVodUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.contains("/movie/") || path.contains("/series/") ||
            path.contains("/vod/") ||
            path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi") ||
            path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".wmv") ||
            path.endsWith(".webm")
    }

    private fun isVodCategoryName(name: String): Boolean {
        return name.startsWith("VOD:", ignoreCase = true) || name.equals("VOD", ignoreCase = true)
    }

    private fun isLiveBrowseChannel(channel: Channel): Boolean {
        return (channel.contentType == ChannelContentType.LIVE || channel.contentType == ChannelContentType.UNKNOWN) &&
            !isVodCategoryName(channel.groupTitle.orEmpty()) &&
            !isVodUrl(channel.url)
    }

    private fun isVodBrowseChannel(channel: Channel): Boolean {
        return channel.contentType == ChannelContentType.VOD_MOVIE ||
            channel.contentType == ChannelContentType.VOD_SERIES ||
            isVodCategoryName(channel.groupTitle.orEmpty()) ||
            isVodUrl(channel.url)
    }

    private suspend fun liveCategoryCounts(playlistId: String): List<Pair<String, Long>> {
        return channelRepo.getLiveCategoryCounts(playlistId)
            .filterNot { (name, _) -> isVodCategoryName(name) }
    }

    private fun ChannelsUiState.hasGuideData(): Boolean {
        return guideProgrammes.isNotEmpty() ||
            guideChannels.isNotEmpty() ||
            epgState is EpgState.Loaded
    }

    private fun matchesQuality(name: String, vararg tags: String): Boolean {
        val lower = name.lowercase()
        return tags.any { tag ->
            lower.contains(tag) ||
                lower.contains("[$tag]") ||
                lower.contains("($tag)") ||
                lower.contains("|$tag|")
        }
    }

    // --- Sub-tab management ---

    fun selectSubTab(tab: ChannelsSubTab) {
        _state.update { it.copy(selectedSubTab = tab) }
        if (tab == ChannelsSubTab.GUIDE || _state.value.hasGuideData()) {
            buildGuideChannels()
        }
        if (tab == ChannelsSubTab.MOVIES && _state.value.vodCategories.isEmpty()) {
            buildMoviesCategories()
        }
    }

    fun toggleVodCategoryExpanded(categoryName: String) {
        var shouldLoad = false
        _state.update {
            val expanded = it.expandedVodCategories.toMutableSet()
            if (categoryName in expanded) {
                expanded.remove(categoryName)
            } else {
                expanded.add(categoryName)
                shouldLoad = it.vodCategories
                    .firstOrNull { c -> c.name == categoryName }
                    ?.channels?.isEmpty() != false
            }
            it.copy(expandedVodCategories = expanded)
        }
        if (shouldLoad) {
            loadVodCategoryChannels(categoryName)
        }
    }

    private fun loadVodCategoryChannels(categoryName: String) {
        val playlistId = _state.value.selectedPlaylistId ?: return
        scope.launch {
            val channels = withContext(backgroundDispatcher) {
                runCatching { channelRepo.getChannelsForCategory(playlistId, categoryName) }
                    .getOrDefault(emptyList())
                    .filter(::isVodBrowseChannel)
            }
            _state.update { st ->
                st.copy(
                    vodCategories = st.vodCategories.map { cat ->
                        if (cat.name == categoryName) cat.copy(channels = channels.map { EnrichedChannel(it) })
                        else cat
                    },
                )
            }
        }
    }

    private fun buildMoviesCategories() {
        val playlistId = _state.value.selectedPlaylistId ?: return
        scope.launch {
            // Tier 1: SQL-based VOD (XTREAM or M3U with proper content_type / "VOD:" prefix)
            val sqlCounts = withContext(backgroundDispatcher) {
                runCatching { channelRepo.getVodCategoryCounts(playlistId) }.getOrDefault(emptyList())
            }
            val sqlNames = sqlCounts.map { it.first }.toSet()

            // Tier 2: URL-detected VOD from the in-memory channel list — covers M3U playlists
            // where channels arrive as UNKNOWN but have XTREAM-style /movie/ or /series/ paths
            // or video file extensions. Pre-populate channels so no lazy SQL fetch is needed.
            val urlDetected = withContext(backgroundDispatcher) {
                channelRepo.getEnrichedChannels(playlistId)
                    .filter { ch ->
                        ch.channel.contentType == ChannelContentType.UNKNOWN &&
                            isVodUrl(ch.channel.url) &&
                            (ch.channel.groupTitle ?: "") !in sqlNames
                    }
                    .groupBy { it.channel.groupTitle ?: "VOD" }
                    .map { (name, chs) ->
                        ChannelCategory(name = name, channelCount = chs.size, channels = chs)
                    }
            }

            val merged = (sqlCounts.map { (name, count) -> ChannelCategory(name = name, channelCount = count.toInt()) } +
                urlDetected).sortedBy { it.name }
            _state.update { it.copy(vodCategories = merged) }
        }
    }

    fun toggleViewMode() {
        _state.update {
            it.copy(viewMode = if (it.viewMode == ChannelsViewMode.LIST) ChannelsViewMode.GRID else ChannelsViewMode.LIST)
        }
    }

    // --- Category management ---

    fun toggleCategoryExpanded(categoryName: String) {
        var shouldLoadChannels = false
        _state.update {
            val expanded = it.expandedCategories.toMutableSet()
            if (categoryName in expanded) {
                expanded.remove(categoryName)
            } else {
                expanded.add(categoryName)
                shouldLoadChannels = it.categories
                    .firstOrNull { category -> category.name == categoryName }
                    ?.channels
                    ?.isEmpty() != false
            }
            it.copy(expandedCategories = expanded)
        }
        if (shouldLoadChannels) {
            loadCategoryChannels(categoryName)
        }
    }

    private fun buildLiveCategories() {
        val st = _state.value
        val playlistId = st.selectedPlaylistId ?: return
        scope.launch {
            val catStartMs = Clock.System.now().toEpochMilliseconds()
            if (st.channels.isEmpty()) {
                // Full dataset not loaded — rebuild from lightweight DB category counts.
                // This is the normal browse path — no full playlist materialization needed.
                val categoryCounts = withContext(backgroundDispatcher) { liveCategoryCounts(playlistId) }
                val (visibleCategories, allCategories) = withContext(backgroundDispatcher) {
                    val allCats = CategoryNameCleaner.processCategoryCountsOnly(categoryCounts)
                    val hiddenLower = st.hiddenCategories.map { it.lowercase() }.toSet()
                    val visibleCats = allCats.filter { it.name.lowercase() !in hiddenLower }
                    visibleCats to allCats
                }
                val catMs = Clock.System.now().toEpochMilliseconds() - catStartMs
                println("STARTUP_METRIC: buildLiveCategories(lightweight)=${catMs}ms visible=${visibleCategories.size} all=${allCategories.size}")
                _state.update { it.copy(categories = visibleCategories, allCategories = allCategories) }
                withContext(backgroundDispatcher) { persistCachedCategoriesNow(visibleCategories) }
            } else {
                // Full dataset available — use the rich path with channel-level filtering.
                val (visibleCategories, allCategories) = withContext(backgroundDispatcher) {
                    val filtered = applyFilters(st.channels).filter {
                        isLiveBrowseChannel(it.channel)
                    }.filter {
                        channelIdentityCandidates(it.channel).none(st.hiddenChannels::contains)
                    }
                    val grouped = filtered.groupBy { it.channel.groupTitle ?: "Ungrouped" }
                    val allCats = CategoryNameCleaner.processCategories(grouped)
                    val hiddenLower = st.hiddenCategories.map { it.lowercase() }.toSet()
                    val visibleCats = allCats.filter { it.name.lowercase() !in hiddenLower }
                    visibleCats to allCats
                }
                val catMs = Clock.System.now().toEpochMilliseconds() - catStartMs
                println("STARTUP_METRIC: buildLiveCategories(full)=${catMs}ms visible=${visibleCategories.size} all=${allCategories.size} channels=${st.channels.size}")
                _state.update { it.copy(categories = visibleCategories, allCategories = allCategories) }
                withContext(backgroundDispatcher) { persistCachedCategoriesNow(visibleCategories) }
            }
        }
    }

    private fun buildGuideChannels(forceRefreshEpg: Boolean = false) {
        val st = _state.value
        val playlistId = st.selectedPlaylistId ?: return
        // Guide requires enriched channels (with EPG data). If not loaded yet,
        // trigger the full load — guide is an explicit feature activation, not normal browse.
        if (st.channels.isEmpty() && st.categoryChannels.isEmpty()) {
            ensureFullPlaylistLoaded()
            return // Will be called again after applyLoadedPlaylist via rebuildGuide
        }
        // Invariant: guideProgrammes is keyed ONLY by canonical epg_channel_key.
        // No alias keys, no fuzzy matching, no entry scans.
        //
        // Resolve rows from the active live browsing scope instead of the
        // previous guide viewport. Empty EPG rows are kept so a category with
        // many channels does not collapse to the few channels that currently
        // have matched programme entries.
        val guideSource = LiveTvGuideSourceResolver.resolve(st)
        val guide = prioritizeGuideChannels(
            channels = guideSource,
            selectedGroup = st.selectedGroup,
            groupedChannels = st.groupedChannels,
        )

        val selectedPlaylist = st.playlists.firstOrNull { it.id == playlistId }
        val epgSourceUrl = selectedPlaylist.resolveEpgSourceUrl().orEmpty()

        if (epgSourceUrl.isBlank()) {
            println("ChannelsEPG: not configured playlistId=$playlistId source=none")
            _state.update {
                it.copy(
                    guideChannels = guide,
                    guideProgrammes = emptyMap(),
                    isLoadingGuide = false,
                    guideError = null,
                    epgState = EpgState.NotConfigured,
                )
            }
            return
        }

        // Load programme data for guide timeline — local-first, never block on network.
        // Cancel any previous guide build to avoid overlapping state updates.
        guideJob?.cancel()
        guideJob = scope.launch {
            val buildStartedAt = Clock.System.now().toEpochMilliseconds()
            try {
                println(
                    "ChannelsEPG: load start playlistId=$playlistId source=$epgSourceUrl forceRefresh=$forceRefreshEpg",
                )

                // Force refresh requested (manual retry) — fetch from network first.
                if (forceRefreshEpg) {
                    _state.update {
                        it.copy(
                            guideChannels = guide,
                            isLoadingGuide = true,
                            guideError = null,
                            epgState = EpgState.Loading,
                        )
                    }
                    withContext(backgroundDispatcher) { channelRepo.refreshEpg(playlistId, _state.value.hiddenChannels) }
                }

                // Load EPG from local cache/DB — this is fast and never hits network.
                val epgData = withContext(backgroundDispatcher) { channelRepo.getEpg(playlistId) }
                val epgLoadError = withContext(backgroundDispatcher) { channelRepo.getEpgLoadError(playlistId) }

                // If we have local EPG data, render it immediately — even if stale.
                if (epgData.programmesByChannelKey.isNotEmpty()) {
                    val buildResult = buildEpgGuideResult(guide, playlistId, epgData)
                    println(
                        "ChannelsEPG: local-first render playlistId=$playlistId generation=${epgData.generationId ?: -1} " +
                            "guideChannels=${guide.size} matched=${buildResult.matchedChannels} " +
                            "unmatched=${buildResult.unmatchedChannels} " +
                            "buildMs=${Clock.System.now().toEpochMilliseconds() - buildStartedAt}",
                    )
                    _state.update {
                        it.copy(
                            guideChannels = guide,
                            guideProgrammes = buildResult.programmesByKey,
                            isLoadingGuide = false,
                            guideError = null,
                            epgState = EpgState.Loaded(
                                sourceUrl = epgSourceUrl,
                                sourceChannelCount = epgData.channels.size,
                                sourceProgrammeCount = epgData.programmes.size,
                                matchedChannelCount = buildResult.matchedChannels,
                                unmatchedChannelCount = buildResult.unmatchedChannels,
                            ),
                        )
                    }
                    // Background refresh: update EPG without blocking UI.
                    if (!forceRefreshEpg) {
                        refreshEpgInBackground(playlistId, guide, epgSourceUrl)
                    }
                    return@launch
                }

                // No local EPG at all — show loading and fetch from network.
                _state.update {
                    it.copy(
                        guideChannels = guide,
                        isLoadingGuide = true,
                        guideError = null,
                        epgState = EpgState.Loading,
                    )
                }

                if (!forceRefreshEpg) {
                    println("ChannelsEPG: no local EPG for playlistId=$playlistId, fetching from network")
                    try {
                        withContext(backgroundDispatcher) { channelRepo.refreshEpg(playlistId, _state.value.hiddenChannels) }
                    } catch (e: Exception) {
                        println("ChannelsEPG: refresh failed playlistId=$playlistId error=${DiagnosticsRedactor.redact(e.message)}")
                    }
                }

                val freshEpgData = withContext(backgroundDispatcher) { channelRepo.getEpg(playlistId) }
                val freshEpgLoadError = withContext(backgroundDispatcher) { channelRepo.getEpgLoadError(playlistId) }

                if (freshEpgData.programmesByChannelKey.isEmpty() && !freshEpgLoadError.isNullOrBlank()) {
                    throw IllegalStateException(freshEpgLoadError)
                }

                val buildResult = buildEpgGuideResult(guide, playlistId, freshEpgData)
                debugLog(
                    "ChannelsEPG: guide build complete playlistId=$playlistId generation=${freshEpgData.generationId ?: -1} buildMs=${Clock.System.now().toEpochMilliseconds() - buildStartedAt} channels=${guide.size} programmeRows=${freshEpgData.programmes.size} guideMapSize=${buildResult.programmesByKey.size}",
                )

                _state.update {
                    it.copy(
                        guideChannels = guide,
                        guideProgrammes = buildResult.programmesByKey,
                        isLoadingGuide = false,
                        guideError = null,
                        epgState = EpgState.Loaded(
                            sourceUrl = epgSourceUrl,
                            sourceChannelCount = freshEpgData.channels.size,
                            sourceProgrammeCount = freshEpgData.programmes.size,
                            matchedChannelCount = buildResult.matchedChannels,
                            unmatchedChannelCount = buildResult.unmatchedChannels,
                        ),
                    )
                }
            } catch (e: Exception) {
                val message = e.message ?: "Failed to load EPG"
                println("ChannelsEPG: load failed playlistId=$playlistId sourceConfigured=${epgSourceUrl.isNotBlank()} error=${DiagnosticsRedactor.redact(message)}")
                _state.update {
                    it.copy(
                        guideChannels = guide,
                        guideProgrammes = emptyMap(),
                        isLoadingGuide = false,
                        guideError = message,
                        epgState = EpgState.Error(message),
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable.isOutOfMemory()) {
                    val message = "EPG is too large for device memory. Reduce provider EPG days and retry."
                    println("ChannelsEPG: load failed playlistId=$playlistId sourceConfigured=${epgSourceUrl.isNotBlank()} error=$message")
                    _state.update {
                        it.copy(
                            guideChannels = guide,
                            guideProgrammes = emptyMap(),
                            isLoadingGuide = false,
                            guideError = message,
                            epgState = EpgState.Error(message),
                        )
                    }
                } else {
                    throw throwable
                }
            }
        }
    }

    private fun Throwable.isOutOfMemory(): Boolean =
        toString().contains("OutOfMemory", ignoreCase = true)

    private data class GuideBuildResult(
        val programmesByKey: Map<String, List<EpgProgramme>>,
        val matchedChannels: Int,
        val unmatchedChannels: Int,
    )

    private suspend fun buildEpgGuideResult(
        guide: List<EnrichedChannel>,
        playlistId: String,
        epgData: EpgData,
    ): GuideBuildResult = withContext(backgroundDispatcher) {
        // Prompt 10C: apply persisted EPG correction (offset + tvg-id
        // remap) so the rendered guide reflects the user's manual fix
        // for a feed that's slightly off. The applier is a no-op when
        // correction is empty, so legacy paths stay byte-equivalent.
        val correction = epgCorrectionRepository?.let { repo ->
            runCatching { repo.get(playlistId) }.getOrNull()
        }
        if (correction == null || correction.isEmpty) {
            // Legacy path — direct lookup against the input map.
            val programmesByKey = HashMap<String, List<EpgProgramme>>(guide.size)
            var matchedChannels = 0
            var unmatchedChannels = 0
            guide.forEach { enriched ->
                val key = canonicalEpgChannelKey(
                    playlistId = playlistId,
                    channel = enriched.channel,
                )
                if (key.isNullOrBlank()) {
                    unmatchedChannels++
                    return@forEach
                }
                val programmes = programmesForEpgChannel(
                    programmesByChannelKey = epgData.programmesByChannelKey,
                    playlistId = playlistId,
                    channel = enriched.channel,
                )
                programmesByKey[key] = programmes
                if (programmes.isEmpty()) {
                    unmatchedChannels++
                } else {
                    matchedChannels++
                }
            }
            // Even with empty correction we still emit health when the
            // VM is wired so the live page's stale banner stays
            // data-driven.
            epgCorrectionViewModel?.updateHealth(
                matchedChannelCount = matchedChannels,
                unmatchedChannelCount = unmatchedChannels,
                programmes = epgData.programmes,
            )
            return@withContext GuideBuildResult(
                programmesByKey = programmesByKey,
                matchedChannels = matchedChannels,
                unmatchedChannels = unmatchedChannels,
            )
        }
        val applied = com.torve.data.recording.EpgCorrectionApplier.apply(
            playlistId = playlistId,
            channels = guide,
            epgData = epgData,
            correction = correction,
        )
        // Drive the stale-EPG banner from the corrected programme list.
        epgCorrectionViewModel?.updateHealth(
            matchedChannelCount = applied.matchedChannels,
            unmatchedChannelCount = applied.unmatchedChannels,
            programmes = applied.correctedProgrammes,
        )
        GuideBuildResult(
            programmesByKey = applied.programmesByKey,
            matchedChannels = applied.matchedChannels,
            unmatchedChannels = applied.unmatchedChannels,
        )
    }

    private fun refreshEpgInBackground(
        playlistId: String,
        guide: List<EnrichedChannel>,
        epgSourceUrl: String,
    ) {
        // Only one EPG network refresh at a time — skip duplicates.
        if (epgRefreshJob?.isActive == true) {
            println("ChannelsEPG: background refresh already in progress, skipping duplicate")
            return
        }
        epgRefreshJob = scope.launch {
            try {
                println("ChannelsEPG: background refresh start playlistId=$playlistId")
                withContext(backgroundDispatcher) { channelRepo.refreshEpg(playlistId, _state.value.hiddenChannels) }
                if (_state.value.selectedPlaylistId != playlistId) return@launch
                val freshEpg = withContext(backgroundDispatcher) { channelRepo.getEpg(playlistId) }
                if (freshEpg.programmesByChannelKey.isEmpty()) return@launch
                val freshResult = buildEpgGuideResult(guide, playlistId, freshEpg)
                println(
                    "ChannelsEPG: background refresh complete playlistId=$playlistId " +
                        "matched=${freshResult.matchedChannels} unmatched=${freshResult.unmatchedChannels}",
                )
                _state.update {
                    it.copy(
                        guideProgrammes = freshResult.programmesByKey,
                        epgState = EpgState.Loaded(
                            sourceUrl = epgSourceUrl,
                            sourceChannelCount = freshEpg.channels.size,
                            sourceProgrammeCount = freshEpg.programmes.size,
                            matchedChannelCount = freshResult.matchedChannels,
                            unmatchedChannelCount = freshResult.unmatchedChannels,
                        ),
                    )
                }
            } catch (e: Exception) {
                println("ChannelsEPG: background refresh failed playlistId=$playlistId error=${DiagnosticsRedactor.redact(e.message)}")
                // Don't overwrite visible EPG data — stale data is better than no data.
            }
        }
    }

    fun retryGuideLoad() {
        buildGuideChannels(forceRefreshEpg = true)
    }

    /** Update the EPG guide search query (filters channel list by name). */
    fun setGuideSearchQuery(query: String) {
        _state.update { it.copy(guideSearchQuery = query) }
    }

    /** Update the EPG guide sort mode (NUMBER / NAME / EPG_FIRST). */
    fun setGuideSortMode(mode: GuideSortMode) {
        _state.update { it.copy(guideSortMode = mode) }
    }

    /**
     * Trigger a guide build without forcing a network refresh of the EPG XML.
     * Surface that lets the desktop shell resync [ChannelsUiState.guideChannels]
     * when the user opens the Guide tab or changes the active browse scope.
     */
    fun requestGuideBuild() {
        buildGuideChannels(forceRefreshEpg = false)
    }

    // --- Hidden categories/channels management ---

    private fun loadHiddenItems() {
        scope.launch { loadHiddenItemsSync() }
    }

    fun toggleHiddenCategory(categoryName: String) {
        val current = _state.value.hiddenCategories
        val updated = if (categoryName in current) current - categoryName else current + categoryName
        val currentAllCats = _state.value.allCategories
        val hiddenLower = updated.map { it.lowercase() }.toSet()
        val visibleCategories = currentAllCats.filter { it.name.lowercase() !in hiddenLower }
        _state.update {
            it.copy(
                hiddenCategories = updated,
                categories = visibleCategories,
                selectedGroup = if (categoryName.lowercase() in hiddenLower) null else it.selectedGroup,
                categoryChannels = if (categoryName.lowercase() in hiddenLower) emptyList() else it.categoryChannels,
            )
        }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString("channels_hidden_categories", updated.joinToString("|||")) } }
    }

    fun toggleHiddenChannel(channelId: String) {
        val current = _state.value.hiddenChannels
        val updated = if (channelId in current) current - channelId else current + channelId
        _state.update { it.copy(hiddenChannels = updated) }
        scope.launch {
            withContext(backgroundDispatcher) {
                channelRepo.syncHiddenChannelsToDb(updated)
                prefsRepo.setString("channels_hidden_channels", updated.joinToString("|||"))
            }
            buildLiveCategories()
        }
    }

    fun getAllCategoryNames(): List<String> {
        return _state.value.allCategories.map { it.name }
    }

    fun hideAllCategories() {
        val allNames = _state.value.allCategories.map { it.name }.toSet()
        _state.update { it.copy(hiddenCategories = allNames, categories = emptyList()) }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString("channels_hidden_categories", allNames.joinToString("|||")) } }
        buildLiveCategories()
    }

    fun showAllCategories() {
        val currentAllCategories = _state.value.allCategories
        _state.update { it.copy(hiddenCategories = emptySet(), categories = currentAllCategories) }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString("channels_hidden_categories", "") } }
        buildLiveCategories()
    }

    fun hideCountryCategories(countryCode: String) {
        val matching = _state.value.allCategories
            .filter { it.countryCode?.equals(countryCode, ignoreCase = true) == true }
            .map { it.name }
            .toSet()
        val updated = _state.value.hiddenCategories + matching
        _state.update { it.copy(hiddenCategories = updated) }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString("channels_hidden_categories", updated.joinToString("|||")) } }
        buildLiveCategories()
    }

    fun showCountryCategories(countryCode: String) {
        val matching = _state.value.allCategories
            .filter { it.countryCode?.equals(countryCode, ignoreCase = true) == true }
            .map { it.name.lowercase() }
            .toSet()
        val updated = _state.value.hiddenCategories.filter { it.lowercase() !in matching }.toSet()
        _state.update { it.copy(hiddenCategories = updated) }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString("channels_hidden_categories", updated.joinToString("|||")) } }
        buildLiveCategories()
    }

    // --- Recently viewed ---

    fun recordChannelViewed(channel: Channel) {
        scope.launch {
            try {
                withContext(backgroundDispatcher) {
                    channelRepo.recordChannelViewed(channel)
                    persistLastWatchedChannel(channel)
                }
                loadRecentlyViewed()
            } catch (_: Exception) { }
        }
    }

    private fun loadRecentlyViewed() {
        loadRecentlyViewedAsync()
    }

    // --- Filter & sort ---

    fun setFilter(filter: ChannelsFilterType) {
        // Merged: set filter + rebuild in one emission.
        _state.update { it.copy(activeFilter = filter) }
        buildLiveCategories()
        if (_state.value.hasGuideData()) {
            buildGuideChannels()
        }
    }

    fun setSort(sort: ChannelsSortType) {
        // Merged: set sort + rebuild in one emission.
        _state.update { it.copy(activeSort = sort) }
        buildLiveCategories()
        if (_state.value.hasGuideData()) {
            buildGuideChannels()
        }
    }

    fun toggleFilterSheet() {
        _state.update { it.copy(showFilterSheet = !it.showFilterSheet) }
    }

    fun toggleCategoryManager() {
        _state.update { it.copy(showCategoryManager = !it.showCategoryManager) }
    }

    // --- Add playlist ---

    fun showAddPlaylistDialog() {
        _state.update { it.copy(showAddPlaylist = true) }
    }

    fun dismissAddPlaylistDialog() {
        _state.update {
            it.copy(
                showAddPlaylist = false,
                newPlaylistName = "",
                newPlaylistUrl = "",
                newPlaylistEpgUrl = "",
                newPlaylistType = "m3u",
                newXtreamServer = "",
                newXtreamUsername = "",
                newXtreamPassword = "",
                isAddingPlaylist = false,
                addPlaylistProgress = null,
                isCheckingEpg = false,
                epgCheckMessage = null,
                epgCheckSuccess = null,
            )
        }
    }

    fun setNewPlaylistName(name: String) {
        _state.update { it.copy(newPlaylistName = name) }
    }

    fun setNewPlaylistUrl(url: String) {
        _state.update { it.copy(newPlaylistUrl = url) }
    }

    fun setNewPlaylistEpgUrl(url: String) {
        _state.update {
            it.copy(
                newPlaylistEpgUrl = url,
                epgCheckMessage = null,
                epgCheckSuccess = null,
            )
        }
    }

    fun checkNewPlaylistEpgUrl() {
        checkEpgUrl(_state.value.newPlaylistEpgUrl)
    }

    fun checkEpgUrl(epgUrl: String) {
        val normalizedUrl = epgUrl.trim()
        if (normalizedUrl.isBlank()) {
            _state.update {
                it.copy(
                    isCheckingEpg = false,
                    epgCheckMessage = "Enter an EPG URL to check.",
                    epgCheckSuccess = false,
                )
            }
        }
        scope.launch {
            _state.update {
                it.copy(
                    isCheckingEpg = true,
                    epgCheckMessage = null,
                    epgCheckSuccess = null,
                )
            }
            val result = withContext(backgroundDispatcher) {
                playlistBackup?.validatePlaylistEpgUrl(normalizedUrl)
                    ?: com.torve.data.account.EpgValidationResponse(
                        success = false,
                        status = "unavailable",
                        message = "Sign in to check EPG URLs.",
                    )
            }
            _state.update {
                it.copy(
                    isCheckingEpg = false,
                    epgCheckMessage = result.displayMessage(),
                    epgCheckSuccess = result.success,
                )
            }
        }
    }

    fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String) {
        scope.launch {
            try {
                val normalizedUrl = epgUrl.trim().takeIf { it.isNotEmpty() }
                val playlists = withContext(backgroundDispatcher) {
                    channelRepo.updatePlaylistEpgUrl(playlistId, normalizedUrl)
                    channelRepo.getPlaylists()
                }
                _state.update { it.copy(playlists = playlists) }
                val updatedPlaylist = playlists.firstOrNull { it.id == playlistId }
                if (updatedPlaylist != null) {
                    runCatching {
                        playlistBackup?.savePlaylistToBackend(
                            playlistId = updatedPlaylist.id,
                            name = updatedPlaylist.name,
                            url = if (updatedPlaylist.type == PlaylistType.M3U) updatedPlaylist.url else null,
                            epgUrl = normalizedUrl,
                            playlistType = updatedPlaylist.type.name.lowercase(),
                            server = updatedPlaylist.server,
                            username = updatedPlaylist.username,
                            password = if (updatedPlaylist.type == PlaylistType.XTREAM) updatedPlaylist.password else null,
                        )
                    }.onFailure { e ->
                        println("[PlaylistSync] Backend EPG update failed for '${updatedPlaylist.id}': ${DiagnosticsRedactor.redact(e.message)}")
                    }
                }
                if (_state.value.selectedPlaylistId == playlistId) {
                    buildGuideChannels(forceRefreshEpg = true)
                }
            } catch (e: Exception) {
                val message = e.message ?: "Failed to update EPG URL"
                _state.update {
                    it.copy(
                        guideError = message,
                        epgState = EpgState.Error(message),
                    )
                }
            }
        }
    }

    fun setNewPlaylistType(type: String) {
        _state.update {
            it.copy(
                newPlaylistType = type,
                epgCheckMessage = null,
                epgCheckSuccess = null,
            )
        }
    }

    fun setNewXtreamServer(server: String) {
        _state.update { it.copy(newXtreamServer = server) }
    }

    fun setNewXtreamUsername(username: String) {
        _state.update { it.copy(newXtreamUsername = username) }
    }

    fun setNewXtreamPassword(password: String) {
        _state.update { it.copy(newXtreamPassword = password) }
    }

    fun addPlaylist() {
        val st = _state.value
        if (st.newPlaylistType == "xtream") {
            addXtreamPlaylist()
        } else {
            addM3uPlaylist()
        }
    }

    private fun addM3uPlaylist() {
        val st = _state.value
        if (st.newPlaylistName.isBlank() || st.newPlaylistUrl.isBlank()) return

        scope.launch {
            _state.update { it.copy(isAddingPlaylist = true, addPlaylistProgress = null, error = null) }
            try {
                val epg = st.newPlaylistEpgUrl.trim().takeIf { it.isNotEmpty() }
                val playlist = withContext(backgroundDispatcher) {
                    channelRepo.addPlaylist(
                        name = st.newPlaylistName,
                        url = st.newPlaylistUrl,
                        epgUrl = epg,
                        onProgress = { progress ->
                            _state.update { it.copy(addPlaylistProgress = progress) }
                        },
                    )
                }
                // Backup to backend for cross-device restore
                val backendOk = runCatching {
                    playlistBackup?.savePlaylistToBackend(
                        playlistId = playlist.id,
                        name = st.newPlaylistName,
                        url = st.newPlaylistUrl,
                        epgUrl = epg,
                        playlistType = "m3u",
                    ) ?: false
                }.getOrElse { e ->
                    println("[PlaylistSync] Backend save FAILED for M3U '${playlist.id}': ${DiagnosticsRedactor.redact(e.message)}")
                    false
                }
                if (backendOk) println("[PlaylistSync] Backend save OK for M3U '${playlist.id}'")
                else println("[PlaylistSync] Backend save failed for M3U '${playlist.id}'")
                dismissAddPlaylistDialog()
                loadPlaylists()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isAddingPlaylist = false,
                        addPlaylistProgress = null,
                        error = com.torve.presentation.error.UserFacingError.CHANNEL_LOAD_FAILED.messageKey,
                    )
                }
            }
        }
    }

    private fun addXtreamPlaylist() {
        val st = _state.value
        if (st.newPlaylistName.isBlank() || st.newXtreamServer.isBlank() ||
            st.newXtreamUsername.isBlank() || st.newXtreamPassword.isBlank()
        ) return

        scope.launch {
            _state.update { it.copy(isAddingPlaylist = true, error = null) }
            try {
                val playlist = withContext(backgroundDispatcher) {
                    channelRepo.addXtreamPlaylist(
                        name = st.newPlaylistName,
                        server = st.newXtreamServer,
                        username = st.newXtreamUsername,
                        password = st.newXtreamPassword,
                    )
                }
                // Backup to backend for cross-device restore
                val backendOk = runCatching {
                    playlistBackup?.savePlaylistToBackend(
                        playlistId = playlist.id,
                        name = st.newPlaylistName,
                        playlistType = "xtream",
                        server = st.newXtreamServer,
                        username = st.newXtreamUsername,
                        password = st.newXtreamPassword,
                    ) ?: false
                }.getOrElse { e ->
                    println("[PlaylistSync] Backend save FAILED for Xtream '${playlist.id}': ${DiagnosticsRedactor.redact(e.message)}")
                    false
                }
                if (backendOk) println("[PlaylistSync] Backend save OK for Xtream '${playlist.id}'")
                else println("[PlaylistSync] Backend save failed for Xtream '${playlist.id}'")
                dismissAddPlaylistDialog()
                loadPlaylists()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isAddingPlaylist = false,
                        addPlaylistProgress = null,
                        error = com.torve.presentation.error.UserFacingError.CHANNEL_LOAD_FAILED.messageKey,
                    )
                }
            }
        }
    }

    // --- Country filter ---

    fun toggleCountryFilter() {
        _state.update { it.copy(showCountryFilter = !it.showCountryFilter) }
    }

    fun setCountryFilterVisible(visible: Boolean) {
        _state.update { it.copy(showCountryFilter = visible) }
    }

    fun toggleCountry(country: String) {
        val normalized = country.trim()
        if (normalized.isBlank()) return
        val current = _state.value.selectedCountries
        val updated = if (normalized in current) current - normalized else current + normalized
        setCountryFilter(updated)
    }

    fun clearCountryFilter() {
        setCountryFilter(emptySet())
    }

    fun setCountryFilter(countries: Set<String>) {
        val normalized = countries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        _state.update { it.copy(selectedCountries = normalized) }
        scope.launch {
            withContext(backgroundDispatcher) {
                if (normalized.isEmpty()) {
                    prefsRepo.remove("channels_country_filter")
                } else {
                    prefsRepo.setString("channels_country_filter", normalized.joinToString(","))
                }
            }
        }
        buildLiveCategories()
    }

    fun setXxxEnabled(enabled: Boolean) {
        _state.update { it.copy(xxxEnabled = enabled) }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString("channels_xxx_enabled", enabled.toString()) } }
        buildLiveCategories()
    }

    private fun loadAudioSettings() {
        scope.launch { loadAudioSettingsSync() }
    }

    fun clearRecentlyViewed() {
        scope.launch {
            try {
                withContext(backgroundDispatcher) { channelRepo.clearRecentlyViewedChannels() }
                _state.update { it.copy(recentlyViewedChannels = emptyList()) }
            } catch (_: Exception) { }
        }
    }

    fun setAudioPassthroughEnabled(enabled: Boolean) {
        _state.update { it.copy(audioPassthroughEnabled = enabled) }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString(KEY_CHANNELS_AUDIO_PASSTHROUGH, enabled.toString()) } }
    }

    fun setPreferSurroundCodecs(enabled: Boolean) {
        _state.update { it.copy(preferSurroundCodecs = enabled) }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString(KEY_CHANNELS_PREFER_SURROUND, enabled.toString()) } }
    }

    fun setLiveAudioOutputMode(mode: LiveAudioOutputMode) {
        _state.update { it.copy(liveAudioOutputMode = mode) }
        scope.launch { withContext(backgroundDispatcher) { prefsRepo.setString(KEY_CHANNELS_AUDIO_OUTPUT_MODE, mode.storageValue) } }
    }

    fun removePlaylist(playlistId: String) {
        scope.launch {
            try {
                withContext(backgroundDispatcher) {
                    channelRepo.removePlaylist(playlistId)
                }
                // Also delete from backend
                runCatching { playlistBackup?.deletePlaylistFromBackend(playlistId) }
                if (_state.value.selectedPlaylistId == playlistId) {
                    _state.update { it.copy(selectedPlaylistId = null, channels = emptyList(), groupedChannels = emptyMap()) }
                    withContext(backgroundDispatcher) { removeSelectedPlaylistId() }
                }
                loadPlaylists()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.CHANNEL_LOAD_FAILED.messageKey) }
            }
        }
    }

    fun refreshPlaylist() {
        val playlistId = _state.value.selectedPlaylistId ?: return
        scope.launch {
            _state.update { it.copy(isLoadingChannels = true) }
            try {
                withContext(backgroundDispatcher) { channelRepo.refreshPlaylist(playlistId) }
                loadPlaylistCatalog(
                    playlistId = playlistId,
                    restoreSavedState = true,
                    triggerBackgroundRefresh = false,
                    showLoadingUntilRefresh = false,
                )
            } catch (e: Exception) {
                _state.update { current ->
                    current.copy(
                        isLoadingChannels = false,
                        error = if (current.hasUsableCatalogFor(playlistId)) {
                            null
                        } else {
                            com.torve.presentation.error.UserFacingError.CHANNEL_LOAD_FAILED.messageKey
                        },
                    )
                }
            }
        }
    }

    fun deletePlaylist(id: String) {
        scope.launch {
            try {
                withContext(backgroundDispatcher) { channelRepo.removePlaylist(id) }
                runCatching { playlistBackup?.deletePlaylistFromBackend(id) }
                loadPlaylists()
            } catch (_: Exception) { }
        }
    }

    // --- Favorites ---

    fun loadFavorites() {
        scope.launch {
            try {
                val favs = withContext(backgroundDispatcher) { channelRepo.getFavorites() }
                _state.update { it.copy(favorites = favs) }
            } catch (_: Exception) { }
        }
    }

    fun toggleFavorite(channel: Channel) {
        val candidateIds = channelIdentityCandidates(channel)
        scope.launch {
            try {
                withContext(backgroundDispatcher) {
                    val existingId = candidateIds.firstOrNull { channelRepo.isFavorite(it) }
                    if (existingId != null) {
                        channelRepo.removeFavorite(existingId)
                    } else {
                        channelRepo.addFavorite(channel)
                    }
                }
                loadFavorites()
                // Refresh channels to update favorite status
                _state.value.selectedPlaylistId?.let { selectPlaylist(it) }
            } catch (_: Exception) { }
        }
    }

    // --- Search ---

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchQueryFlow.value = query
        if (query.trim().length < 2 && _state.value.hasGuideData()) {
            buildGuideChannels()
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        scope.launch {
            searchQueryFlow
                .debounce(300)
                .distinctUntilChanged()
                .filter { it.length >= 2 }
                .collect { query ->
                    try {
                        val results = channelRepo.searchChannels(query)
                        _state.update { it.copy(searchResults = results) }
                        if (_state.value.hasGuideData()) {
                            buildGuideChannels()
                        }
                    } catch (_: Exception) { }
                }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList()) }
        searchQueryFlow.value = ""
        if (_state.value.hasGuideData()) {
            buildGuideChannels()
        }
    }

    private fun observeAccountSession() {
        val coordinator = playlistBackup ?: return
        scope.launch {
            coordinator.state
                .map { it.isSigningOut }
                .distinctUntilChanged()
                .collect { isSigningOut ->
                    if (isSigningOut) {
                        clearInMemoryCatalogForAccountTransition()
                    }
                }
        }
    }

    private fun observeAccountCatalogClearEvents() {
        val notifier = settingsRefreshNotifier ?: return
        scope.launch {
            notifier.events.collect {
                val hasPlaylists = withContext(backgroundDispatcher) {
                    runCatching { channelRepo.getPlaylists().isNotEmpty() }.getOrDefault(false)
                }
                if (!hasPlaylists && _state.value.hasAccountScopedCatalog()) {
                    clearInMemoryCatalogForAccountTransition()
                }
            }
        }
    }

    private fun ChannelsUiState.hasAccountScopedCatalog(): Boolean {
        return playlists.isNotEmpty() ||
            selectedPlaylistId != null ||
            channels.isNotEmpty() ||
            groupedChannels.isNotEmpty() ||
            categoryChannels.isNotEmpty() ||
            categories.isNotEmpty() ||
            allCategories.isNotEmpty() ||
            guideChannels.isNotEmpty() ||
            guideProgrammes.isNotEmpty() ||
            programmes.isNotEmpty() ||
            selectedChannel != null
    }

    private fun clearInMemoryCatalogForAccountTransition() {
        guideJob?.cancel()
        epgRefreshJob?.cancel()
        catalogLoadJob?.cancel()
        startupPhase = "signed_out"
        searchQueryFlow.value = ""
        val current = _state.value
        _state.value = ChannelsUiState(
            audioPassthroughEnabled = current.audioPassthroughEnabled,
            preferSurroundCodecs = current.preferSurroundCodecs,
            liveAudioOutputMode = current.liveAudioOutputMode,
        )
    }

    // --- Channel detail / EPG ---

    fun selectChannel(channel: Channel) {
        _state.update { it.copy(selectedChannel = channel) }
        val selectedPlaylistId = _state.value.selectedPlaylistId ?: channel.playlistId
        scope.launch { persistSelectedChannel(selectedPlaylistId, channel) }
        scope.launch {
            try {
                val programmes = withContext(backgroundDispatcher) {
                    LiveTvEpgResolver.lookupKeys(selectedPlaylistId, channel)
                        .firstNotNullOfOrNull { key ->
                            channelRepo.getProgrammes(key).takeIf { it.isNotEmpty() }
                        }
                        .orEmpty()
                }
                _state.update { it.copy(programmes = programmes) }
            } catch (_: Exception) { }
        }
    }

    fun selectAdjacentChannel(direction: Int): Channel? {
        if (direction == 0) return null
        val channels = currentChannelSelection()
        if (channels.isEmpty()) return null

        val currentIndex = _state.value.selectedChannel
            ?.let { selected ->
                channels.indexOfFirst { candidate ->
                    channelMatchesIdentity(candidate, stableChannelId(selected))
                }
            }
            ?.takeIf { it >= 0 }

        val nextIndex = when {
            currentIndex == null -> if (direction > 0) 0 else channels.lastIndex
            channels.size == 1 -> currentIndex
            else -> {
                val rawIndex = currentIndex + direction
                ((rawIndex % channels.size) + channels.size) % channels.size
            }
        }
        if (currentIndex == nextIndex) return null

        val channel = channels[nextIndex]
        selectChannel(channel)
        return channel
    }

    fun clearSelectedChannel() {
        _state.update { it.copy(selectedChannel = null, programmes = emptyList()) }
    }

    // --- Catchup / Timeshift ---

    fun canCatchup(channel: Channel): Boolean {
        return catchupResolver.canCatchup(channel)
    }

    fun resolveCatchupUrl(channel: Channel, programme: EpgProgramme): String? {
        return catchupResolver.resolve(channel, programme)
    }

    private fun hydrateInitialCategorySelection(
        playlistId: String,
        previousPlaylistId: String?,
        categories: List<ChannelCategory>,
        restoreSavedState: Boolean,
    ) {
        if (categories.isEmpty()) return
        scope.launch {
            val categoryNames = categories.map { it.name }.toSet()
            val categoryName = withContext(backgroundDispatcher) {
                resolveRestoredGroup(
                    playlistId = playlistId,
                    previousPlaylistId = previousPlaylistId,
                    availableGroups = categoryNames,
                    restoreSavedState = restoreSavedState,
                ) ?: categories.firstOrNull()?.name
            } ?: return@launch

            val categoryChannels = withContext(backgroundDispatcher) {
                getChannelsForCategoryDirect(playlistId, categoryName)
            }
            val restoredChannel = withContext(backgroundDispatcher) {
                resolveRestoredChannel(
                    playlistId = playlistId,
                    previousPlaylistId = previousPlaylistId,
                    enriched = categoryChannels,
                    restoreSavedState = restoreSavedState,
                )
            } ?: categoryChannels.firstOrNull()?.channel

            _state.update { current ->
                if (current.selectedPlaylistId != playlistId) return@update current

                fun mergeChannels(source: List<ChannelCategory>): List<ChannelCategory> =
                    source.map { category ->
                        if (category.name == categoryName) category.copy(channels = categoryChannels)
                        else category
                    }

                current.copy(
                    selectedGroup = categoryName,
                    categoryChannels = categoryChannels,
                    selectedChannel = restoredChannel,
                    categories = mergeChannels(current.categories),
                    allCategories = mergeChannels(current.allCategories),
                )
            }

            withContext(backgroundDispatcher) {
                persistSelectedGroup(playlistId, categoryName)
                restoredChannel?.let { persistSelectedChannel(playlistId, it) }
            }
            if (_state.value.epgState is EpgState.Loaded) {
                buildGuideChannels()
            }
        }
    }

    private fun loadPlaylistCatalog(
        playlistId: String,
        restoreSavedState: Boolean,
        triggerBackgroundRefresh: Boolean,
        showLoadingUntilRefresh: Boolean,
        recoverEmptyCatalog: Boolean = true,
    ) {
        println("CATALOG_LOAD: playlistId=$playlistId triggerRefresh=$triggerBackgroundRefresh")
        // Cache-first: if the requested playlist is already loaded with categories,
        // do nothing. The user is on the same playlist and we already have data.
        if (_state.value.selectedPlaylistId == playlistId && _state.value.categories.isNotEmpty()) {
            println("CATALOG_LOAD: skipped — playlist=$playlistId already loaded (${_state.value.categories.size} categories)")
            return
        }
        // Otherwise, don't cancel an in-progress first load — let it finish.
        if (catalogLoadJob?.isActive == true && _state.value.categories.isEmpty()) {
            println("CATALOG_LOAD: skipped — first load already in progress")
            return
        }
        catalogLoadJob?.cancel()
        val previousPlaylistId = _state.value.selectedPlaylistId
        val hasCachedCategories = _state.value.categories.isNotEmpty() &&
            _state.value.selectedPlaylistId == playlistId
        _state.update { current ->
            current.copy(
                selectedPlaylistId = playlistId,
                isLoadingChannels = !hasCachedCategories,
                error = null,
            )
        }
        catalogLoadJob = scope.launch {
            try {
                var recoveryRefreshStarted = false
                if (hasCachedCategories) {
                    // Cache is already displayed — remove loading indicator immediately,
                    // then refresh from DB in background to pick up any count changes
                    // (e.g. VOD filter now applied where it wasn't before).
                    println("CATALOG_LOAD: cache hit — ${_state.value.categories.size} categories already shown")
                    _state.update { it.copy(isLoadingChannels = false) }
                    hydrateInitialCategorySelection(
                        playlistId = playlistId,
                        previousPlaylistId = previousPlaylistId,
                        categories = _state.value.categories,
                        restoreSavedState = restoreSavedState,
                    )
                    buildLiveCategories()
                } else {
                    // No cache — query DB for category counts (lightweight GROUP BY) then
                    // strip VOD groups in memory (XTREAM names them "VOD: …").
                    val loadStart = Clock.System.now().toEpochMilliseconds()
                    val categoryCounts = withContext(backgroundDispatcher) {
                        liveCategoryCounts(playlistId)
                    }
                    if (categoryCounts.isNotEmpty()) {
                        val lightweightCategories = withContext(backgroundDispatcher) {
                            val st = _state.value
                            val allCats = CategoryNameCleaner.processCategoryCountsOnly(categoryCounts)
                            val hiddenLower = st.hiddenCategories.map { it.lowercase() }.toSet()
                            allCats.filter { it.name.lowercase() !in hiddenLower }
                        }
                        val selectedPlaylist = _state.value.playlists.firstOrNull { it.id == playlistId }
                        val epgSourceUrl = selectedPlaylist.resolveEpgSourceUrl().orEmpty()
                        _state.update { current ->
                            current.copy(
                                selectedPlaylistId = playlistId,
                                categories = lightweightCategories,
                                allCategories = lightweightCategories,
                                isLoadingChannels = false,
                                channels = emptyList(),
                                groupedChannels = emptyMap(),
                                epgState = when {
                                    epgSourceUrl.isBlank() -> EpgState.NotConfigured
                                    current.epgState is EpgState.Loaded -> current.epgState
                                    else -> EpgState.Loading
                                },
                            )
                        }
                        withContext(backgroundDispatcher) { persistCachedCategoriesNow(lightweightCategories) }
                        hydrateInitialCategorySelection(
                            playlistId = playlistId,
                            previousPlaylistId = previousPlaylistId,
                            categories = lightweightCategories,
                            restoreSavedState = restoreSavedState,
                        )
                        val loadMs = Clock.System.now().toEpochMilliseconds() - loadStart
                        println("CATALOG_LOAD: DB categories loaded in ${loadMs}ms — ${lightweightCategories.size} categories")
                    } else {
                        // Fallback: no category counts — full channel load required.
                        val rowCount = withContext(backgroundDispatcher) {
                            channelRepo.getTotalChannelCount(playlistId)
                        }
                        applyLoadedPlaylist(
                            playlistId = playlistId,
                            previousPlaylistId = previousPlaylistId,
                            enriched = emptyList(),
                            restoreSavedState = restoreSavedState,
                            keepLoading = false,
                            guideErrorOverride = null,
                            epgStateOverride = null,
                        )
                        val loadMs = Clock.System.now().toEpochMilliseconds() - loadStart
                        println("CATALOG_LOAD: no live category counts in ${loadMs}ms playlist=$playlistId totalRows=$rowCount; skipping full channel load")
                        // Empty DB recovery: if we found neither category counts
                        // nor enriched rows, the playlist hasn't been ingested
                        // on this device yet (or the rows were dropped during
                        // a generation bump). Refresh from the source URL so
                        // the user isn't stuck behind the "Syncing IPTV
                        // playlist" overlay forever — refresh either populates
                        // categories/channels (clearing the overlay) or sets
                        // an error (also clearing the overlay).
                        if (rowCount == 0L && recoverEmptyCatalog) {
                            recoveryRefreshStarted = true
                            println("CATALOG_LOAD: empty DB for $playlistId — kicking off source refresh to recover")
                            refreshPlaylistInBackground(
                                playlistId = playlistId,
                                preserveVisibleCatalog = false,
                                restoreSavedState = restoreSavedState,
                            )
                        } else if (rowCount == 0L) {
                            println("CATALOG_LOAD: empty DB for $playlistId - staged import is waiting for background catalog warmup")
                        } else {
                            println("CATALOG_LOAD: playlist=$playlistId has $rowCount rows but no live category counts; keeping DB-only state")
                        }
                    }
                }

                // Background refresh — only if explicitly requested and playlist is stale.
                if (triggerBackgroundRefresh && !recoveryRefreshStarted) {
                    val playlist = _state.value.playlists.firstOrNull { it.id == playlistId }
                    val lastUpdated = playlist?.lastUpdated ?: 0L
                    val nowMs = Clock.System.now().toEpochMilliseconds()
                    val ageMs = nowMs - lastUpdated
                    println("REFRESH_GATE: playlistId=$playlistId ageSec=${ageMs / 1000} thresholdSec=${STALE_THRESHOLD_MS / 1000} decision=${if (ageMs > STALE_THRESHOLD_MS) "REFRESH" else "SKIP"}")
                    if (ageMs > STALE_THRESHOLD_MS) {
                        refreshPlaylistInBackground(
                            playlistId = playlistId,
                            preserveVisibleCatalog = true,
                            restoreSavedState = true,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { current ->
                    current.copy(
                        isLoadingChannels = false,
                        error = if (current.hasUsableCatalogFor(playlistId)) {
                            null
                        } else {
                            com.torve.presentation.error.UserFacingError.CHANNEL_LOAD_FAILED.messageKey
                        },
                    )
                }
            }
        }
    }

    /**
     * Load channels for a specific category on demand.
     * Always queries from DB — never depends on a full in-memory playlist.
     * Hidden channels are excluded at the SQL level (via iptv_hidden_channel table).
     */
    fun loadCategoryChannels(categoryName: String) {
        val playlistId = _state.value.selectedPlaylistId ?: return
        scope.launch {
            val st = _state.value
            val categoryChannels = withContext(backgroundDispatcher) {
                getChannelsForCategoryDirect(playlistId, categoryName)
            }
            _state.update { current ->
                fun mergeChannels(categories: List<ChannelCategory>): List<ChannelCategory> =
                    categories.map { category ->
                        if (category.name == categoryName) category.copy(channels = categoryChannels)
                        else category
                    }

                current.copy(
                    selectedGroup = categoryName,
                    categoryChannels = categoryChannels,
                    categories = mergeChannels(current.categories),
                    allCategories = mergeChannels(current.allCategories),
                )
            }
            if (categoryChannels.all { it.currentProgramme == null && it.nextProgramme == null }) {
                // Only kick off an EPG fetch when the EPG hasn't been
                // loaded yet. If EpgState is already Loaded, the channels
                // having null programmes means their tvg-ids don't match
                // the EPG source — re-loading EPG won't change that.
                //
                // Without this guard, the cycle is:
                //   loadCategoryChannels(X) -> ensureEpgLoaded -> cache
                //   hit -> refreshSelectedCategoryChannels ->
                //   loadCategoryChannels(X) -> here -> ensureEpgLoaded
                //   -> ...
                // Each iteration calls getEpg (the "ChannelsEPG: cache
                // read" log spam visible during the user's reported
                // ~10-second flicker on category click). The cycle also
                // races against the user's click: if a background trigger
                // fires refreshSelectedCategoryChannels while the new
                // click is still suspended on the DB query,
                // refreshSelectedCategoryChannels reads the *old*
                // selectedGroup and queues a parallel load for the old
                // category, which lands its state update LATE and flips
                // the highlight back. Visible as the highlight flickering
                // between the old and the new category for ~10s.
                if (_state.value.epgState !is EpgState.Loaded) {
                }
            }
        }
    }

    /**
     * Direct category channel query for on-demand loading in the TV IPTV screen.
     * CategoryNameCleaner merges multiple raw group_titles into one display name
     * (e.g. "AL| SPORT HD" + "AL| SPORT FHD" → "Albania Sport"). This function
     * resolves the cleaned display name back to raw DB group_titles and queries
     * channels for all matching raw groups.
     * Must be called from a background dispatcher.
     */
    suspend fun getChannelsForCategoryDirect(playlistId: String, cleanedCategoryName: String): List<EnrichedChannel> {
        // Get all raw group_title → count pairs from DB
        val rawCounts = liveCategoryCounts(playlistId)
        // Find which raw group_titles clean to the target category name
        val matchingRawTitles = rawCounts
            .map { it.first }
            .filter { rawTitle ->
                val cleaned = CategoryNameCleaner.clean(rawTitle)
                cleaned.name.equals(cleanedCategoryName, ignoreCase = true)
            }

        if (matchingRawTitles.isEmpty()) {
            // Fallback: try direct match (category name might already be raw)
            val direct = channelRepo.getChannelsForCategory(playlistId, cleanedCategoryName)
                .filter(::isLiveBrowseChannel)
            if (direct.isNotEmpty()) return direct.map { EnrichedChannel(channel = it) }
            return emptyList()
        }

        // Query channels for all matching raw group_titles
        val allChannels = matchingRawTitles.flatMap { rawTitle ->
            channelRepo.getChannelsForCategory(playlistId, rawTitle)
        }.filter(::isLiveBrowseChannel)

        val st = _state.value
        val filtered = if (st.xxxEnabled) allChannels else {
            val adultKeywords = setOf("xxx", "adult", "18+", "porn", "erotic")
            allChannels.filter { ch ->
                val group = ch.groupTitle?.lowercase() ?: ""
                val name = ch.name.lowercase()
                adultKeywords.none { kw -> group.contains(kw) || name.contains(kw) }
            }
        }
        val favoriteIds = runCatching {
            channelRepo.getFavorites()
                .flatMap(::channelIdentityCandidates)
                .toSet()
        }.getOrDefault(emptySet())
        val favoriteAware = if (favoriteIds.isEmpty()) {
            filtered
        } else {
            filtered.map { channel ->
                if (channelIdentityCandidates(channel).any(favoriteIds::contains)) {
                    channel.copy(isFavorite = true)
                } else {
                    channel
                }
            }
        }
        return favoriteAware.map { channel -> EnrichedChannel(channel = channel) }
    }

    /**
     * Load EPG programmes from local DB for a set of channels.
     * Returns a map of epg_channel_key → programme list.
     * Must be called from a background dispatcher.
     */
    suspend fun getProgrammesForChannelsDirect(
        playlistId: String,
        channels: List<EnrichedChannel>,
    ): Map<String, List<EpgProgramme>> {
        val result = mutableMapOf<String, List<EpgProgramme>>()
        for (enriched in channels) {
            val epgKey = canonicalEpgChannelKey(
                playlistId = playlistId,
                channel = enriched.channel,
            ) ?: continue
            try {
                val programmes = LiveTvEpgResolver.lookupKeys(playlistId, enriched.channel)
                    .firstNotNullOfOrNull { key ->
                        channelRepo.getProgrammes(key).takeIf { it.isNotEmpty() }
                    }
                    .orEmpty()
                if (programmes.isNotEmpty()) {
                    result[epgKey] = programmes
                }
            } catch (_: Exception) { }
        }
        return result
    }

    private fun currentChannelSelection(): List<Channel> {
        val state = _state.value
        return when {
            state.searchQuery.length >= 2 -> state.searchResults
            state.selectedGroup != null && state.categoryChannels.isNotEmpty() ->
                state.categoryChannels.map(EnrichedChannel::channel)
            else -> getDisplayChannels().map(EnrichedChannel::channel)
        }
    }

    private fun refreshSelectedCategoryChannels(playlistId: String) {
        val selectedGroup = _state.value.selectedGroup ?: return
        if (_state.value.selectedPlaylistId != playlistId) return
        loadCategoryChannels(selectedGroup)
    }

    /**
     * Explicitly load the full playlist into memory. Only called when a feature
     * that truly needs all channels is activated (search, EPG guide, advanced filters).
     * Not called during normal category browsing.
     *
     * Two guards:
     *  - [ensureFullLoadJob] tracks an in-flight load so concurrent
     *    callers don't spawn parallel coroutines.
     *  - When the DB returns 0 enriched channels we deliberately do NOT
     *    call [applyLoadedPlaylist] — that would re-fire
     *    [buildGuideChannels] which re-calls this function, looping
     *    forever when the playlist has a generation pointer but no
     *    channel rows backing it (stale DB state, partial sync, etc.).
     */
    private var ensureFullLoadJob: kotlinx.coroutines.Job? = null
    fun ensureFullPlaylistLoaded() {
        val playlistId = _state.value.selectedPlaylistId ?: return
        if (_state.value.channels.isNotEmpty()) return // already loaded
        if (ensureFullLoadJob?.isActive == true) return // load in flight
        ensureFullLoadJob = scope.launch {
            try {
                val totalRows = withContext(backgroundDispatcher) {
                    channelRepo.getTotalChannelCount(playlistId)
                }
                if (totalRows > MAX_FULL_PLAYLIST_LOAD_IN_STATE) {
                    println(
                        "ensureFullPlaylistLoaded: skipped playlist=$playlistId totalRows=$totalRows " +
                            "limit=$MAX_FULL_PLAYLIST_LOAD_IN_STATE to avoid Fire TV OOM",
                    )
                    _state.update { it.copy(isLoadingChannels = false, isLoadingGuide = false) }
                    return@launch
                }
                val enriched = withContext(backgroundDispatcher) {
                    channelRepo.getEnrichedChannels(playlistId)
                }
                if (enriched.isEmpty()) {
                    println("ensureFullPlaylistLoaded: DB returned 0 channels for $playlistId — stale generation? Skipping applyLoadedPlaylist to avoid buildGuideChannels recursion loop.")
                    _state.update { it.copy(isLoadingChannels = false) }
                    return@launch
                }
                applyLoadedPlaylist(
                    playlistId = playlistId,
                    previousPlaylistId = playlistId,
                    enriched = enriched,
                    restoreSavedState = true,
                    keepLoading = false,
                    guideErrorOverride = null,
                    epgStateOverride = null,
                )
            } catch (e: Exception) {
                println("ensureFullPlaylistLoaded failed: ${DiagnosticsRedactor.redact(e.message)}")
            }
        }
    }

    fun ensureEpgLoaded(forceRefresh: Boolean = false) {
        val playlistId = _state.value.selectedPlaylistId ?: return
        ensureEpgLoaded(playlistId, forceRefresh)
    }

    /**
     * TV local-first path: hydrate guideProgrammes from the persisted EPG table
     * without starting a network refresh. Background workers own EPG refreshes;
     * foreground TV navigation should only read already-cached data.
     */
    fun hydrateCachedEpgOnly() {
        val playlistId = _state.value.selectedPlaylistId ?: return
        val playlist = _state.value.playlists.firstOrNull { it.id == playlistId }
        val epgSourceUrl = playlist.resolveEpgSourceUrl().orEmpty()
        if (epgSourceUrl.isBlank()) {
            _state.update { it.copy(epgState = EpgState.NotConfigured) }
            println("ChannelsEPG: cached-only skipped playlistId=$playlistId reason=no_epg_source")
        }
        if (epgRefreshJob?.isActive == true) {
            println("ChannelsEPG: cached-only skipped playlistId=$playlistId reason=epg_job_active")
            return
        }

        epgRefreshJob = scope.launch {
            val cachedEpg = withContext(backgroundDispatcher) { channelRepo.getEpg(playlistId) }
            if (cachedEpg.programmesByChannelKey.isEmpty()) {
                println("ChannelsEPG: cached-only miss playlistId=$playlistId")
                return@launch
            }

            println(
                "ChannelsEPG: cached-only hit playlistId=$playlistId " +
                    "channels=${cachedEpg.channels.size} programmes=${cachedEpg.programmes.size} " +
                    "keys=${cachedEpg.programmesByChannelKey.size}",
            )
            _state.update {
                it.copy(
                    epgState = EpgState.Loaded(
                        sourceUrl = epgSourceUrl,
                        sourceChannelCount = cachedEpg.channels.size,
                        sourceProgrammeCount = cachedEpg.programmes.size,
                        matchedChannelCount = (it.epgState as? EpgState.Loaded)?.matchedChannelCount ?: 0,
                        unmatchedChannelCount = (it.epgState as? EpgState.Loaded)?.unmatchedChannelCount ?: 0,
                    ),
                    guideProgrammes = cachedEpg.programmesByChannelKey,
                    guideError = null,
                )
            }
            refreshSelectedCategoryChannels(playlistId)
        }
    }

    private fun ensureEpgLoaded(playlistId: String, forceRefresh: Boolean = false) {
        val playlist = _state.value.playlists.firstOrNull { it.id == playlistId }
        val epgSourceUrl = playlist.resolveEpgSourceUrl().orEmpty()
        if (epgSourceUrl.isBlank()) {
            _state.update { it.copy(epgState = EpgState.NotConfigured) }
            return
        }
        if (epgRefreshJob?.isActive == true && !forceRefresh) {
            return
        }

        epgRefreshJob = scope.launch {
            val cachedEpg = withContext(backgroundDispatcher) { channelRepo.getEpg(playlistId) }
            if (!forceRefresh && cachedEpg.programmesByChannelKey.isNotEmpty()) {
                // Stuff the programme map straight into state.guideProgrammes. Avoid
                // `buildGuideChannels()` here — that cascades into `ensureFullPlaylistLoaded`
                // which materialises all 70k+ channels into memory and OOMs the Fire TV.
                // Per-channel programme lookup happens lazily by canonical EPG key when
                // each channel row renders, so no global guide build is needed.
                _state.update {
                    it.copy(
                        epgState = EpgState.Loaded(
                            sourceUrl = epgSourceUrl,
                            sourceChannelCount = cachedEpg.channels.size,
                            sourceProgrammeCount = cachedEpg.programmes.size,
                            matchedChannelCount = (it.epgState as? EpgState.Loaded)?.matchedChannelCount ?: 0,
                            unmatchedChannelCount = (it.epgState as? EpgState.Loaded)?.unmatchedChannelCount ?: 0,
                        ),
                        guideProgrammes = cachedEpg.programmesByChannelKey,
                        guideError = null,
                    )
                }
                refreshSelectedCategoryChannels(playlistId)
                return@launch
            }

            _state.update { it.copy(epgState = EpgState.Loading, guideError = null) }
            val refreshed = runCatching {
                withContext(backgroundDispatcher) { channelRepo.refreshEpg(playlistId, _state.value.hiddenChannels) }
                withContext(backgroundDispatcher) { channelRepo.getEpg(playlistId) }
            }

            refreshed.onSuccess { epg ->
                if (epg.programmesByChannelKey.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            epgState = EpgState.Loaded(
                                sourceUrl = epgSourceUrl,
                                sourceChannelCount = epg.channels.size,
                                sourceProgrammeCount = epg.programmes.size,
                                matchedChannelCount = (it.epgState as? EpgState.Loaded)?.matchedChannelCount ?: 0,
                                unmatchedChannelCount = (it.epgState as? EpgState.Loaded)?.unmatchedChannelCount ?: 0,
                            ),
                            guideProgrammes = epg.programmesByChannelKey,
                            guideError = null,
                        )
                    }
                    refreshSelectedCategoryChannels(playlistId)
                } else {
                    val message = withContext(backgroundDispatcher) {
                        channelRepo.getEpgLoadError(playlistId)
                    } ?: "EPG did not return any programme data."
                    _state.update {
                        it.copy(
                            epgState = EpgState.Error(message),
                            guideError = message,
                        )
                    }
                }
            }.onFailure { error ->
                val message = error.message
                    ?: withContext(backgroundDispatcher) { channelRepo.getEpgLoadError(playlistId) }
                    ?: "Failed to load EPG"
                _state.update {
                    it.copy(
                        epgState = EpgState.Error(message),
                        guideError = message,
                    )
                }
            }
        }
    }

    private suspend fun applyLoadedPlaylist(
        playlistId: String,
        previousPlaylistId: String?,
        enriched: List<EnrichedChannel>,
        restoreSavedState: Boolean,
        keepLoading: Boolean,
        guideErrorOverride: String?,
        epgStateOverride: EpgState?,
        rebuildGuide: Boolean = true,
    ) {
        // Heavy list operations (groupBy, mapNotNull, flatMap, distinct, sort)
        // over 76K+ items — run on IO to avoid main-thread starvation.
        data class CatalogPrep(
            val grouped: Map<String, List<EnrichedChannel>>,
            val countries: List<String>,
            val restoredGroup: String?,
            val restoredChannel: Channel?,
            val favoritesBound: Int,
        )

        val prepStartMs = Clock.System.now().toEpochMilliseconds()
        val liveEnriched = withContext(backgroundDispatcher) {
            enriched.filter { isLiveBrowseChannel(it.channel) }
        }
        val prep = withContext(backgroundDispatcher) {
            val grouped = liveEnriched.groupBy { it.channel.groupTitle ?: "Ungrouped" }
            val countries = liveEnriched.mapNotNull { it.channel.tvgCountry }
                .flatMap { it.split(",", ";").map(String::trim) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            val restoredGroup = resolveRestoredGroup(playlistId, previousPlaylistId, grouped.keys, restoreSavedState)
            val restoredChannel = resolveRestoredChannel(playlistId, previousPlaylistId, liveEnriched, restoreSavedState)
            val favoritesBound = liveEnriched.count { it.channel.isFavorite }
            CatalogPrep(grouped, countries, restoredGroup, restoredChannel, favoritesBound)
        }
        val prepMs = Clock.System.now().toEpochMilliseconds() - prepStartMs
        println("STARTUP_METRIC: catalogPrep=${prepMs}ms groups=${prep.grouped.size} countries=${prep.countries.size}")

        println(
            "StartupRecovery: first render playlistId=$playlistId liveChannels=${liveEnriched.size}/${enriched.size} groups=${prep.grouped.size} " +
                "favoritesRebound=${prep.favoritesBound} restoredGroup=${prep.restoredGroup ?: "none"} " +
                "restoredChannel=${prep.restoredChannel?.name ?: "none"} keepLoading=$keepLoading",
        )

        _state.update { current ->
            // Belt-and-braces: a successful applyLoadedPlaylist with a
            // non-empty `enriched` list proves the playlist parsed and
            // populated. Clear any stale `error` flag set by an
            // earlier transient failure so other UI surfaces (Settings
            // → Sources, provider-health rows, banners) don't keep
            // reporting "load failed" against a working catalogue.
            val clearedError = if (liveEnriched.isNotEmpty()) null else current.error
            // On a same-playlist refresh (background reload, EPG
            // catch-up), preserve the user's CURRENT selection instead
            // of restoring from prefs. Without this, a click on a new
            // category gets clobbered ~300ms later by the next refresh
            // cycle, producing a visible flicker between the user's
            // pick and the saved value (caught live in user recording
            // 2026-05-02 181744.mp4: HULU <-> DISNEY+ ping-pong every
            // 0.3s after clicking DISNEY+).
            val sameLoad = previousPlaylistId == playlistId
            val keptGroup = if (sameLoad && current.selectedGroup != null) {
                current.selectedGroup
            } else {
                prep.restoredGroup
            }
            val keptChannel = if (sameLoad && current.selectedChannel != null) {
                current.selectedChannel
            } else {
                prep.restoredChannel
            }
            current.copy(
                selectedPlaylistId = playlistId,
                channels = liveEnriched,
                groupedChannels = prep.grouped,
                isLoadingChannels = keepLoading,
                selectedGroup = keptGroup,
                selectedChannel = keptChannel,
                availableCountries = prep.countries,
                guideError = guideErrorOverride ?: current.guideError,
                epgState = epgStateOverride ?: current.epgState,
                error = clearedError,
            )
        }

        // Persist the actually-applied selection (which on a same-load
        // refresh is the user's current pick, not prep.restoredGroup) —
        // otherwise the pref would drift back to the stale restored
        // value and the next cold start would land on the wrong group.
        val appliedGroup = _state.value.selectedGroup
        val appliedChannel = _state.value.selectedChannel
        withContext(backgroundDispatcher) {
            persistSelectedGroup(playlistId, appliedGroup)
            appliedChannel?.let { persistSelectedChannel(playlistId, it) }
        }

        buildLiveCategories()
        if (rebuildGuide) {
            buildGuideChannels()
        }
    }

    private fun refreshPlaylistInBackground(
        playlistId: String,
        preserveVisibleCatalog: Boolean,
        restoreSavedState: Boolean,
        includeEpg: Boolean = false,
    ) {
        scope.launch {
            try {
                println(
                    "StartupRecovery: background refresh start playlistId=$playlistId " +
                        "preserveVisibleCatalog=$preserveVisibleCatalog includeEpg=$includeEpg",
                )
                withContext(backgroundDispatcher) {
                    if (includeEpg) {
                        channelRepo.refreshPlaylist(playlistId)
                    } else {
                        channelRepo.refreshPlaylistCatalog(playlistId)
                    }
                }
                if (_state.value.selectedPlaylistId != playlistId) return@launch
                val refreshedLiveCounts = withContext(backgroundDispatcher) { liveCategoryCounts(playlistId) }
                if (refreshedLiveCounts.isNotEmpty()) {
                    val allCats = withContext(backgroundDispatcher) {
                        CategoryNameCleaner.processCategoryCountsOnly(refreshedLiveCounts)
                    }
                    val hiddenLower = _state.value.hiddenCategories.map { it.lowercase() }.toSet()
                    val visibleCats = allCats.filter { it.name.lowercase() !in hiddenLower }
                    _state.update { current ->
                        current.copy(
                            categories = visibleCats,
                            allCategories = allCats,
                            channels = emptyList(),
                            groupedChannels = emptyMap(),
                            isLoadingChannels = false,
                            error = null,
                        )
                    }
                    withContext(backgroundDispatcher) { persistCachedCategoriesNow(visibleCats) }
                    hydrateInitialCategorySelection(
                        playlistId = playlistId,
                        previousPlaylistId = playlistId,
                        categories = visibleCats,
                        restoreSavedState = restoreSavedState,
                    )
                    refreshSelectedCategoryChannels(playlistId)
                    println(
                        "StartupRecovery: background refresh complete playlistId=$playlistId liveCategories=${visibleCats.size}",
                    )
                    return@launch
                }
                val refreshed = withContext(backgroundDispatcher) { channelRepo.getEnrichedChannels(playlistId) }
                // Update channels/categories but skip guide rebuild — the initial
                // buildGuideChannels() already rendered EPG and kicked off a
                // background refresh. Rebuilding here would cause a redundant EPG
                // network fetch and heavy recomposition with the large guideProgrammes map.
                applyLoadedPlaylist(
                    playlistId = playlistId,
                    previousPlaylistId = playlistId,
                    enriched = refreshed,
                    restoreSavedState = restoreSavedState,
                    keepLoading = false,
                    guideErrorOverride = null,
                    epgStateOverride = null,
                    rebuildGuide = false,
                )
                println(
                    "StartupRecovery: background refresh complete playlistId=$playlistId refreshedChannels=${refreshed.size}",
                )
            } catch (e: Exception) {
                println(
                    "StartupRecovery: background refresh failed playlistId=$playlistId error=${e.message.orEmpty()}",
                )
                if (!preserveVisibleCatalog) {
                    _state.update { current ->
                        current.copy(
                            isLoadingChannels = false,
                            error = com.torve.presentation.error.UserFacingError.CHANNEL_LOAD_FAILED.messageKey,
                        )
                    }
                }
            }
        }
    }

    private suspend fun resolvePreferredPlaylistId(playlists: List<ChannelPlaylist>): String? {
        if (playlists.isEmpty()) return null
        val currentSelected = _state.value.selectedPlaylistId
        if (currentSelected != null && playlists.any { it.id == currentSelected }) {
            return currentSelected
        }
        val saved = prefsRepo.getString(KEY_CHANNELS_SELECTED_PLAYLIST)
            ?: currentBootstrapUserId()?.let { userId ->
                localSettingsRepo?.getString(channelsBootstrapSelectedPlaylistKey(userId))
            }
        if (saved != null && playlists.any { it.id == saved }) {
            return saved
        }
        return playlists.first().id
    }

    private suspend fun resolveRestoredGroup(
        playlistId: String,
        previousPlaylistId: String?,
        availableGroups: Set<String>,
        restoreSavedState: Boolean,
    ): String? {
        val currentGroup = _state.value.selectedGroup
        if (previousPlaylistId == playlistId && currentGroup in availableGroups) {
            return currentGroup
        }
        if (!restoreSavedState) return null
        val savedGroup = prefsRepo.getString(selectedGroupKey(playlistId))
        return savedGroup?.takeIf { it in availableGroups }
    }

    private suspend fun resolveRestoredChannel(
        playlistId: String,
        previousPlaylistId: String?,
        enriched: List<EnrichedChannel>,
        restoreSavedState: Boolean,
    ): Channel? {
        val currentChannel = _state.value.selectedChannel
        if (currentChannel != null &&
            previousPlaylistId == playlistId &&
            enriched.any { candidate -> channelMatchesIdentity(candidate.channel, stableChannelId(currentChannel)) }
        ) {
            return enriched.first { candidate -> channelMatchesIdentity(candidate.channel, stableChannelId(currentChannel)) }.channel
        }
        if (!restoreSavedState) return null

        val savedChannelId = prefsRepo.getString(selectedChannelKey(playlistId))
            ?: prefsRepo.getString(lastWatchedChannelKey(playlistId))
            ?: return null
        val restored = enriched.firstOrNull { channelMatchesIdentity(it.channel, savedChannelId) }?.channel
        println(
            "StartupRecovery: restored channel lookup playlistId=$playlistId savedId=$savedChannelId success=${restored != null}",
        )
        return restored
    }

    private suspend fun persistSelectedGroup(
        playlistId: String,
        group: String?,
    ) {
        if (group.isNullOrBlank()) {
            prefsRepo.remove(selectedGroupKey(playlistId))
        } else {
            prefsRepo.setString(selectedGroupKey(playlistId), group)
        }
    }

    private suspend fun persistSelectedChannel(
        playlistId: String,
        channel: Channel,
    ) {
        prefsRepo.setString(selectedChannelKey(playlistId), stableChannelId(channel))
    }

    private suspend fun persistLastWatchedChannel(channel: Channel) {
        prefsRepo.setString(lastWatchedChannelKey(channel.playlistId), stableChannelId(channel))
    }

    private fun selectedGroupKey(playlistId: String): String = "$KEY_CHANNELS_SELECTED_GROUP_PREFIX$playlistId"

    private fun selectedChannelKey(playlistId: String): String = "$KEY_CHANNELS_SELECTED_CHANNEL_PREFIX$playlistId"

    private fun lastWatchedChannelKey(playlistId: String): String = "$KEY_CHANNELS_LAST_WATCHED_CHANNEL_PREFIX$playlistId"

    private fun debugLog(message: String) {
        if (EPG_DEBUG_LOG_ENABLED) {
            println(message)
        }
    }

    private fun prioritizeGuideChannels(
        channels: List<EnrichedChannel>,
        selectedGroup: String?,
        groupedChannels: Map<String, List<EnrichedChannel>>,
    ): List<EnrichedChannel> {
        if (channels.size <= MAX_GUIDE_CHANNELS_IN_STATE) return channels
        val prioritized = LinkedHashMap<String, EnrichedChannel>(MAX_GUIDE_CHANNELS_IN_STATE)
        selectedGroup
            ?.let(groupedChannels::get)
            .orEmpty()
            .forEach { enriched ->
                prioritized[stableChannelId(enriched.channel)] = enriched
            }
        channels.forEach { enriched ->
            val stableId = stableChannelId(enriched.channel)
            if (stableId !in prioritized) {
                prioritized[stableId] = enriched
            }
            if (prioritized.size >= MAX_GUIDE_CHANNELS_IN_STATE) {
                return prioritized.values.toList()
            }
        }
        return prioritized.values.toList()
    }

    private fun ChannelPlaylist?.resolveEpgSourceUrl(): String? {
        val playlist = this ?: return null
        val explicit = playlist.epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        if (playlist.type != PlaylistType.XTREAM) return null
        val server = playlist.server?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val username = playlist.username?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val password = playlist.password?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "$server/xmltv.php?username=$username&password=$password"
    }
}
