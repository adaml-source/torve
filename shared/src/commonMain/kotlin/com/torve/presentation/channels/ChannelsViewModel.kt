package com.torve.presentation.channels

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.PlaylistType
import com.torve.domain.model.channelIdentityCandidates
import com.torve.domain.model.channelMatchesIdentity
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.stableChannelId
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.data.channels.CatchupResolver
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.PreferencesRepository
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
private const val MAX_GUIDE_CHANNELS_IN_STATE = 160
private const val EPG_DEBUG_LOG_ENABLED = false

class ChannelsViewModel(
    private val channelRepo: ChannelRepository,
    private val prefsRepo: PreferencesRepository,
    private val catchupResolver: CatchupResolver = CatchupResolver(),
    private val backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ChannelsUiState())
    val state: StateFlow<ChannelsUiState> = _state.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")
    private var guideJob: Job? = null
    private var epgRefreshJob: Job? = null

    init {
        migrateOldPreferenceKeys()
        loadSavedFilters()
        loadHiddenItems()
        loadPlaylists()
        loadFavorites()
        loadRecentlyViewed()
        loadAudioSettings()
        observeSearch()
    }

    /** Migrate old "iptv_" preference keys to "channels_" (one-time, remove after v2 rollout). */
    private fun migrateOldPreferenceKeys() {
        scope.launch {
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
    }

    private fun loadSavedFilters() {
        scope.launch {
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
    }

    fun loadPlaylists() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val playlists = withContext(backgroundDispatcher) { channelRepo.getPlaylists() }
                val selectedPlaylistId = resolvePreferredPlaylistId(playlists)
                _state.update {
                    it.copy(
                        playlists = playlists,
                        isLoading = false,
                        selectedPlaylistId = selectedPlaylistId,
                    )
                }
                if (selectedPlaylistId != null) {
                    withContext(backgroundDispatcher) {
                        prefsRepo.setString(KEY_CHANNELS_SELECTED_PLAYLIST, selectedPlaylistId)
                    }
                    // Yield to let the UI render the playlist-ready state before
                    // starting the heavy 76K channel catalog load.
                    kotlinx.coroutines.yield()
                    loadPlaylistCatalog(
                        playlistId = selectedPlaylistId,
                        restoreSavedState = true,
                        triggerBackgroundRefresh = true,
                        showLoadingUntilRefresh = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectPlaylist(playlistId: String) {
        scope.launch { prefsRepo.setString(KEY_CHANNELS_SELECTED_PLAYLIST, playlistId) }
        loadPlaylistCatalog(
            playlistId = playlistId,
            restoreSavedState = true,
            triggerBackgroundRefresh = true,
            showLoadingUntilRefresh = false,
        )
    }

    fun selectGroup(group: String?) {
        _state.update { it.copy(selectedGroup = group) }
        val playlistId = _state.value.selectedPlaylistId ?: return
        scope.launch { persistSelectedGroup(playlistId, group) }
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
    }

    fun toggleViewMode() {
        _state.update {
            it.copy(viewMode = if (it.viewMode == ChannelsViewMode.LIST) ChannelsViewMode.GRID else ChannelsViewMode.LIST)
        }
    }

    // --- Category management ---

    fun toggleCategoryExpanded(categoryName: String) {
        _state.update {
            val expanded = it.expandedCategories.toMutableSet()
            if (categoryName in expanded) expanded.remove(categoryName) else expanded.add(categoryName)
            it.copy(expandedCategories = expanded)
        }
    }

    private fun buildLiveCategories() {
        val st = _state.value
        // Run expensive filter/groupBy/sort chains on IO for 76K+ channels.
        scope.launch {
            val catStartMs = Clock.System.now().toEpochMilliseconds()
            val (visibleCategories, allCategories) = withContext(backgroundDispatcher) {
                val filtered = applyFilters(st.channels).filter {
                    it.channel.contentType == ChannelContentType.LIVE || it.channel.contentType == ChannelContentType.UNKNOWN
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
            println("STARTUP_METRIC: buildLiveCategories=${catMs}ms visible=${visibleCategories.size} all=${allCategories.size} channels=${st.channels.size}")
            _state.update { it.copy(categories = visibleCategories, allCategories = allCategories) }
        }
    }

    private fun buildGuideChannels(forceRefreshEpg: Boolean = false) {
        val st = _state.value
        val playlistId = st.selectedPlaylistId ?: return
        // Invariant: guideProgrammes is keyed ONLY by canonical epg_channel_key.
        // No alias keys, no fuzzy matching, no entry scans.
        // Guide shows channels that have EPG data (current or next programme)
        val withEpg = st.channels.filter { it.currentProgramme != null || it.nextProgramme != null }
        // Fall back to all live channels if no current/next programme is cached.
        val guideSource = if (withEpg.isNotEmpty()) withEpg else st.channels.filter {
            it.channel.contentType == ChannelContentType.LIVE || it.channel.contentType == ChannelContentType.UNKNOWN
        }
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
                        println("ChannelsEPG: refresh failed playlistId=$playlistId error=${e.message}")
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
            } catch (oom: OutOfMemoryError) {
                val message = "EPG is too large for device memory. Reduce provider EPG days and retry."
                println("ChannelsEPG: load failed playlistId=$playlistId source=$epgSourceUrl error=$message")
                _state.update {
                    it.copy(
                        guideChannels = guide,
                        guideProgrammes = emptyMap(),
                        isLoadingGuide = false,
                        guideError = message,
                        epgState = EpgState.Error(message),
                    )
                }
            } catch (e: Exception) {
                val message = e.message ?: "Failed to load EPG"
                println("ChannelsEPG: load failed playlistId=$playlistId source=$epgSourceUrl error=$message")
                _state.update {
                    it.copy(
                        guideChannels = guide,
                        guideProgrammes = emptyMap(),
                        isLoadingGuide = false,
                        guideError = message,
                        epgState = EpgState.Error(message),
                    )
                }
            }
        }
    }

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
            val programmes = epgData.programmesByChannelKey[key].orEmpty()
            programmesByKey[key] = programmes
            if (programmes.isEmpty()) {
                unmatchedChannels++
            } else {
                matchedChannels++
            }
        }
        GuideBuildResult(
            programmesByKey = programmesByKey,
            matchedChannels = matchedChannels,
            unmatchedChannels = unmatchedChannels,
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
                println("ChannelsEPG: background refresh failed playlistId=$playlistId error=${e.message}")
                // Don't overwrite visible EPG data — stale data is better than no data.
            }
        }
    }

    fun retryGuideLoad() {
        buildGuideChannels(forceRefreshEpg = true)
    }

    // --- Hidden categories/channels management ---

    private fun loadHiddenItems() {
        scope.launch {
            val (cats, chs) = withContext(backgroundDispatcher) {
                prefsRepo.getString("channels_hidden_categories") to
                    prefsRepo.getString("channels_hidden_channels")
            }
            _state.update {
                it.copy(
                    hiddenCategories = cats?.split("|||")?.filter { c -> c.isNotBlank() }?.toSet() ?: emptySet(),
                    hiddenChannels = chs?.split("|||")?.filter { c -> c.isNotBlank() }?.toSet() ?: emptySet(),
                )
            }
        }
    }

    fun toggleHiddenCategory(categoryName: String) {
        val current = _state.value.hiddenCategories
        val updated = if (categoryName in current) current - categoryName else current + categoryName
        // Single state emission: merge the hidden-set update + category rebuild
        // into one _state.update to avoid double-recomposition.
        val st = _state.value.copy(hiddenCategories = updated)
        val filtered = applyFilters(st.channels).filter {
            it.channel.contentType == ChannelContentType.LIVE || it.channel.contentType == ChannelContentType.UNKNOWN
        }.filter {
            channelIdentityCandidates(it.channel).none(st.hiddenChannels::contains)
        }
        val grouped = filtered.groupBy { it.channel.groupTitle ?: "Ungrouped" }
        val allCategories = CategoryNameCleaner.processCategories(grouped)
        val hiddenLower = st.hiddenCategories.map { it.lowercase() }.toSet()
        val visibleCategories = allCategories.filter { it.name.lowercase() !in hiddenLower }
        _state.update { it.copy(hiddenCategories = updated, categories = visibleCategories, allCategories = allCategories) }
        scope.launch {
            prefsRepo.setString("channels_hidden_categories", updated.joinToString("|||"))
        }
    }

    fun toggleHiddenChannel(channelId: String) {
        val current = _state.value.hiddenChannels
        val updated = if (channelId in current) current - channelId else current + channelId
        // Single state emission: merge the hidden-set update + category rebuild.
        val st = _state.value.copy(hiddenChannels = updated)
        val filtered = applyFilters(st.channels).filter {
            it.channel.contentType == ChannelContentType.LIVE || it.channel.contentType == ChannelContentType.UNKNOWN
        }.filter {
            channelIdentityCandidates(it.channel).none(st.hiddenChannels::contains)
        }
        val grouped = filtered.groupBy { it.channel.groupTitle ?: "Ungrouped" }
        val allCategories = CategoryNameCleaner.processCategories(grouped)
        val hiddenLower = st.hiddenCategories.map { it.lowercase() }.toSet()
        val visibleCategories = allCategories.filter { it.name.lowercase() !in hiddenLower }
        _state.update { it.copy(hiddenChannels = updated, categories = visibleCategories, allCategories = allCategories) }
        scope.launch {
            prefsRepo.setString("channels_hidden_channels", updated.joinToString("|||"))
        }
    }

    fun getAllCategoryNames(): List<String> {
        val st = _state.value
        return CategoryNameCleaner.processCategories(
            st.channels.groupBy { it.channel.groupTitle ?: "Ungrouped" },
        ).map { it.name }
    }

    fun hideAllCategories() {
        val allNames = _state.value.allCategories.map { it.name }.toSet()
        // Single emission — inline buildLiveCategories logic to avoid double-recomposition.
        val st = _state.value.copy(hiddenCategories = allNames)
        val filtered = applyFilters(st.channels).filter {
            it.channel.contentType == ChannelContentType.LIVE || it.channel.contentType == ChannelContentType.UNKNOWN
        }.filter { channelIdentityCandidates(it.channel).none(st.hiddenChannels::contains) }
        val grouped = filtered.groupBy { it.channel.groupTitle ?: "Ungrouped" }
        val allCategories = CategoryNameCleaner.processCategories(grouped)
        val hiddenLower = allNames.map { it.lowercase() }.toSet()
        val visibleCategories = allCategories.filter { it.name.lowercase() !in hiddenLower }
        _state.update { it.copy(hiddenCategories = allNames, categories = visibleCategories, allCategories = allCategories) }
        scope.launch {
            prefsRepo.setString("channels_hidden_categories", allNames.joinToString("|||"))
        }
        buildLiveCategories()
    }

    fun showAllCategories() {
        // Single emission.
        val st = _state.value.copy(hiddenCategories = emptySet())
        val filtered = applyFilters(st.channels).filter {
            it.channel.contentType == ChannelContentType.LIVE || it.channel.contentType == ChannelContentType.UNKNOWN
        }.filter { channelIdentityCandidates(it.channel).none(st.hiddenChannels::contains) }
        val grouped = filtered.groupBy { it.channel.groupTitle ?: "Ungrouped" }
        val allCategories = CategoryNameCleaner.processCategories(grouped)
        val visibleCategories = allCategories // no hidden categories to filter
        _state.update { it.copy(hiddenCategories = emptySet(), categories = visibleCategories, allCategories = allCategories) }
        scope.launch {
            prefsRepo.setString("channels_hidden_categories", "")
        }
    }

    fun hideCountryCategories(countryCode: String) {
        val matching = _state.value.allCategories
            .filter { it.countryCode?.equals(countryCode, ignoreCase = true) == true }
            .map { it.name }
            .toSet()
        val updated = _state.value.hiddenCategories + matching
        _state.update { it.copy(hiddenCategories = updated) }
        scope.launch {
            prefsRepo.setString("channels_hidden_categories", updated.joinToString("|||"))
        }
        buildLiveCategories()
    }

    fun showCountryCategories(countryCode: String) {
        val matching = _state.value.allCategories
            .filter { it.countryCode?.equals(countryCode, ignoreCase = true) == true }
            .map { it.name.lowercase() }
            .toSet()
        val updated = _state.value.hiddenCategories.filter { it.lowercase() !in matching }.toSet()
        _state.update { it.copy(hiddenCategories = updated) }
        scope.launch {
            prefsRepo.setString("channels_hidden_categories", updated.joinToString("|||"))
        }
        buildLiveCategories()
    }

    // --- Recently viewed ---

    fun recordChannelViewed(channel: Channel) {
        scope.launch {
            try {
                channelRepo.recordChannelViewed(channel)
                persistLastWatchedChannel(channel)
                loadRecentlyViewed()
            } catch (_: Exception) { }
        }
    }

    private fun loadRecentlyViewed() {
        scope.launch {
            try {
                val recent = withContext(backgroundDispatcher) { channelRepo.getRecentlyViewedChannels(20) }
                _state.update { it.copy(recentlyViewedChannels = recent) }
            } catch (_: Exception) { }
        }
    }

    // --- Filter & sort ---

    fun setFilter(filter: ChannelsFilterType) {
        // Merged: set filter + rebuild in one emission.
        _state.update { it.copy(activeFilter = filter) }
        buildLiveCategories()
    }

    fun setSort(sort: ChannelsSortType) {
        // Merged: set sort + rebuild in one emission.
        _state.update { it.copy(activeSort = sort) }
        buildLiveCategories()
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
        _state.update { it.copy(newPlaylistEpgUrl = url) }
    }

    fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String) {
        scope.launch {
            try {
                val normalizedUrl = epgUrl.trim().ifBlank { "" }
                channelRepo.updatePlaylistEpgUrl(playlistId, normalizedUrl.ifBlank { null })
                val playlists = channelRepo.getPlaylists()
                _state.update { it.copy(playlists = playlists) }
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
        _state.update { it.copy(newPlaylistType = type) }
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
            _state.update { it.copy(isAddingPlaylist = true, error = null) }
            try {
                val epg = st.newPlaylistEpgUrl.ifBlank { null }
                channelRepo.addPlaylist(st.newPlaylistName, st.newPlaylistUrl, epg)
                dismissAddPlaylistDialog()
                loadPlaylists()
            } catch (e: Exception) {
                _state.update { it.copy(isAddingPlaylist = false, error = e.message) }
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
                channelRepo.addXtreamPlaylist(
                    name = st.newPlaylistName,
                    server = st.newXtreamServer,
                    username = st.newXtreamUsername,
                    password = st.newXtreamPassword,
                )
                dismissAddPlaylistDialog()
                loadPlaylists()
            } catch (e: Exception) {
                _state.update { it.copy(isAddingPlaylist = false, error = e.message) }
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
            if (normalized.isEmpty()) {
                prefsRepo.remove("channels_country_filter")
            } else {
                prefsRepo.setString("channels_country_filter", normalized.joinToString(","))
            }
        }
        buildLiveCategories()
    }

    fun setXxxEnabled(enabled: Boolean) {
        _state.update { it.copy(xxxEnabled = enabled) }
        scope.launch { prefsRepo.setString("channels_xxx_enabled", enabled.toString()) }
        buildLiveCategories()
    }

    private fun loadAudioSettings() {
        scope.launch {
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
    }

    fun clearRecentlyViewed() {
        scope.launch {
            try {
                channelRepo.clearRecentlyViewedChannels()
                _state.update { it.copy(recentlyViewedChannels = emptyList()) }
            } catch (_: Exception) { }
        }
    }

    fun setAudioPassthroughEnabled(enabled: Boolean) {
        _state.update { it.copy(audioPassthroughEnabled = enabled) }
        scope.launch { prefsRepo.setString(KEY_CHANNELS_AUDIO_PASSTHROUGH, enabled.toString()) }
    }

    fun setPreferSurroundCodecs(enabled: Boolean) {
        _state.update { it.copy(preferSurroundCodecs = enabled) }
        scope.launch { prefsRepo.setString(KEY_CHANNELS_PREFER_SURROUND, enabled.toString()) }
    }

    fun setLiveAudioOutputMode(mode: LiveAudioOutputMode) {
        _state.update { it.copy(liveAudioOutputMode = mode) }
        scope.launch { prefsRepo.setString(KEY_CHANNELS_AUDIO_OUTPUT_MODE, mode.storageValue) }
    }

    fun removePlaylist(playlistId: String) {
        scope.launch {
            try {
                channelRepo.removePlaylist(playlistId)
                if (_state.value.selectedPlaylistId == playlistId) {
                    _state.update { it.copy(selectedPlaylistId = null, channels = emptyList(), groupedChannels = emptyMap()) }
                    prefsRepo.remove(KEY_CHANNELS_SELECTED_PLAYLIST)
                }
                loadPlaylists()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun refreshPlaylist() {
        val playlistId = _state.value.selectedPlaylistId ?: return
        scope.launch {
            _state.update { it.copy(isLoadingChannels = true) }
            try {
                channelRepo.refreshPlaylist(playlistId)
                loadPlaylistCatalog(
                    playlistId = playlistId,
                    restoreSavedState = true,
                    triggerBackgroundRefresh = false,
                    showLoadingUntilRefresh = false,
                )
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingChannels = false, error = e.message) }
            }
        }
    }

    fun deletePlaylist(id: String) {
        scope.launch {
            try {
                channelRepo.removePlaylist(id)
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
                val existingId = candidateIds.firstOrNull { channelRepo.isFavorite(it) }
                if (existingId != null) {
                    channelRepo.removeFavorite(existingId)
                } else {
                    channelRepo.addFavorite(channel)
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
                    } catch (_: Exception) { }
                }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList()) }
        searchQueryFlow.value = ""
    }

    // --- Channel detail / EPG ---

    fun selectChannel(channel: Channel) {
        _state.update { it.copy(selectedChannel = channel) }
        val selectedPlaylistId = _state.value.selectedPlaylistId ?: channel.playlistId
        scope.launch { persistSelectedChannel(selectedPlaylistId, channel) }
        val epgId = canonicalEpgChannelKey(
            playlistId = selectedPlaylistId,
            channel = channel,
        ) ?: return
        scope.launch {
            try {
                val programmes = channelRepo.getProgrammes(epgId)
                _state.update { it.copy(programmes = programmes) }
            } catch (_: Exception) { }
        }
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

    private fun loadPlaylistCatalog(
        playlistId: String,
        restoreSavedState: Boolean,
        triggerBackgroundRefresh: Boolean,
        showLoadingUntilRefresh: Boolean,
    ) {
        val previousPlaylistId = _state.value.selectedPlaylistId
        _state.update { current ->
            current.copy(
                selectedPlaylistId = playlistId,
                isLoadingChannels = true,
                error = null,
            )
        }
        scope.launch {
            try {
                println(
                    "StartupRecovery: loading local playlist catalog playlistId=$playlistId " +
                        "restoreSavedState=$restoreSavedState triggerBackgroundRefresh=$triggerBackgroundRefresh",
                )
                // CRITICAL: Load 76K+ channels on IO, NOT on Dispatchers.Main.
                // This was the primary startup ANR cause — executeAsList() for the
                // entire channel catalog plus enrichment ran on the main thread.
                val catalogStartMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                val enriched = withContext(backgroundDispatcher) {
                    channelRepo.getEnrichedChannels(playlistId)
                }
                val catalogMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - catalogStartMs
                println("StartupRecovery: channel DB load completed in ${catalogMs}ms rows=${enriched.size}")
                applyLoadedPlaylist(
                    playlistId = playlistId,
                    previousPlaylistId = previousPlaylistId,
                    enriched = enriched,
                    restoreSavedState = restoreSavedState,
                    keepLoading = showLoadingUntilRefresh && enriched.isEmpty(),
                    guideErrorOverride = null,
                    epgStateOverride = null,
                )
                if (triggerBackgroundRefresh) {
                    refreshPlaylistInBackground(
                        playlistId = playlistId,
                        preserveVisibleCatalog = enriched.isNotEmpty(),
                        restoreSavedState = true,
                    )
                }
            } catch (oom: OutOfMemoryError) {
                val fallbackChannels = withContext(backgroundDispatcher) {
                    runCatching { channelRepo.getChannels(playlistId) }.getOrDefault(emptyList())
                }
                val fallbackEnriched = fallbackChannels.map { EnrichedChannel(channel = it) }
                applyLoadedPlaylist(
                    playlistId = playlistId,
                    previousPlaylistId = previousPlaylistId,
                    enriched = fallbackEnriched,
                    restoreSavedState = restoreSavedState,
                    keepLoading = false,
                    guideErrorOverride = "EPG is too large for device memory. Reduce provider EPG days and retry.",
                    epgStateOverride = EpgState.Error("EPG is too large for device memory. Reduce provider EPG days and retry."),
                )
            } catch (e: Exception) {
                _state.update { current ->
                    current.copy(
                        isLoadingChannels = false,
                        error = e.message,
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
        val prep = withContext(backgroundDispatcher) {
            val grouped = enriched.groupBy { it.channel.groupTitle ?: "Ungrouped" }
            val countries = enriched.mapNotNull { it.channel.tvgCountry }
                .flatMap { it.split(",", ";").map(String::trim) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            val restoredGroup = resolveRestoredGroup(playlistId, previousPlaylistId, grouped.keys, restoreSavedState)
            val restoredChannel = resolveRestoredChannel(playlistId, previousPlaylistId, enriched, restoreSavedState)
            val favoritesBound = enriched.count { it.channel.isFavorite }
            CatalogPrep(grouped, countries, restoredGroup, restoredChannel, favoritesBound)
        }
        val prepMs = Clock.System.now().toEpochMilliseconds() - prepStartMs
        println("STARTUP_METRIC: catalogPrep=${prepMs}ms groups=${prep.grouped.size} countries=${prep.countries.size}")

        println(
            "StartupRecovery: first render playlistId=$playlistId channels=${enriched.size} groups=${prep.grouped.size} " +
                "favoritesRebound=${prep.favoritesBound} restoredGroup=${prep.restoredGroup ?: "none"} " +
                "restoredChannel=${prep.restoredChannel?.name ?: "none"} keepLoading=$keepLoading",
        )

        _state.update { current ->
            current.copy(
                selectedPlaylistId = playlistId,
                channels = enriched,
                groupedChannels = prep.grouped,
                isLoadingChannels = keepLoading,
                selectedGroup = prep.restoredGroup,
                selectedChannel = prep.restoredChannel,
                availableCountries = prep.countries,
                guideError = guideErrorOverride ?: current.guideError,
                epgState = epgStateOverride ?: current.epgState,
            )
        }

        persistSelectedGroup(playlistId, prep.restoredGroup)
        prep.restoredChannel?.let { persistSelectedChannel(playlistId, it) }

        buildLiveCategories()
        if (rebuildGuide) {
            buildGuideChannels()
        }
    }

    private fun refreshPlaylistInBackground(
        playlistId: String,
        preserveVisibleCatalog: Boolean,
        restoreSavedState: Boolean,
    ) {
        scope.launch {
            try {
                println(
                    "StartupRecovery: background refresh start playlistId=$playlistId preserveVisibleCatalog=$preserveVisibleCatalog",
                )
                withContext(backgroundDispatcher) { channelRepo.refreshPlaylist(playlistId) }
                if (_state.value.selectedPlaylistId != playlistId) return@launch
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
                            error = e.message,
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
            prioritized.putIfAbsent(stableChannelId(enriched.channel), enriched)
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
