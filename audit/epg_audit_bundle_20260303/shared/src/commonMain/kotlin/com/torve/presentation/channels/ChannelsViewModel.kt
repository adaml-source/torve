package com.torve.presentation.channels

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.PlaylistType
import com.torve.domain.model.canonicalEpgChannelKey
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

private const val KEY_CHANNELS_AUDIO_PASSTHROUGH = "channels_audio_passthrough_enabled"
private const val KEY_CHANNELS_PREFER_SURROUND = "channels_prefer_surround_codecs"
private const val EPG_DEBUG_LOG_ENABLED = false

class ChannelsViewModel(
    private val channelRepo: ChannelRepository,
    private val prefsRepo: PreferencesRepository,
    private val catchupResolver: CatchupResolver = CatchupResolver(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ChannelsUiState())
    val state: StateFlow<ChannelsUiState> = _state.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")

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
            val countries = prefsRepo.getString("channels_country_filter")
            val xxx = prefsRepo.getString("channels_xxx_enabled")
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
                val playlists = channelRepo.getPlaylists()
                _state.update { it.copy(playlists = playlists, isLoading = false) }
                // Auto-select first playlist if none selected
                if (_state.value.selectedPlaylistId == null && playlists.isNotEmpty()) {
                    selectPlaylist(playlists.first().id)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectPlaylist(playlistId: String) {
        _state.update { it.copy(selectedPlaylistId = playlistId, isLoadingChannels = true) }
        scope.launch {
            try {
                val enriched = channelRepo.getEnrichedChannels(playlistId)
                val grouped = enriched.groupBy { it.channel.groupTitle ?: "Ungrouped" }
                // Extract available countries
                val countries = enriched.mapNotNull { it.channel.tvgCountry }
                    .flatMap { it.split(",", ";").map { c -> c.trim() } }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                _state.update {
                    it.copy(
                        channels = enriched,
                        groupedChannels = grouped,
                        isLoadingChannels = false,
                        selectedGroup = null,
                        availableCountries = countries,
                    )
                }
                // Build categories and guide data
                buildLiveCategories()
                buildGuideChannels()
            } catch (oom: OutOfMemoryError) {
                val fallbackChannels = runCatching { channelRepo.getChannels(playlistId) }.getOrDefault(emptyList())
                val fallbackEnriched = fallbackChannels.map { EnrichedChannel(channel = it) }
                val fallbackGrouped = fallbackEnriched.groupBy { it.channel.groupTitle ?: "Ungrouped" }
                val countries = fallbackEnriched.mapNotNull { it.channel.tvgCountry }
                    .flatMap { it.split(",", ";").map { c -> c.trim() } }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                _state.update {
                    it.copy(
                        channels = fallbackEnriched,
                        groupedChannels = fallbackGrouped,
                        isLoadingChannels = false,
                        selectedGroup = null,
                        availableCountries = countries,
                        guideError = "EPG is too large for device memory. Reduce provider EPG days and retry.",
                        epgState = EpgState.Error("EPG is too large for device memory. Reduce provider EPG days and retry."),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingChannels = false, error = e.message) }
            }
        }
    }

    fun selectGroup(group: String?) {
        _state.update { it.copy(selectedGroup = group) }
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
                val favIds = st.favorites.map { it.tvgId ?: "${it.playlistId}_${it.name}" }.toSet()
                result = result.filter { enriched ->
                    val chId = enriched.channel.tvgId ?: "${enriched.channel.playlistId}_${enriched.channel.name}"
                    chId in favIds
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
        val filtered = applyFilters(st.channels).filter {
            it.channel.contentType == ChannelContentType.LIVE || it.channel.contentType == ChannelContentType.UNKNOWN
        }.filter {
            // Filter out hidden channels
            val chId = it.channel.tvgId ?: "${it.channel.playlistId}_${it.channel.name}"
            chId !in st.hiddenChannels
        }
        val grouped = filtered.groupBy { it.channel.groupTitle ?: "Ungrouped" }
        val allCategories = CategoryNameCleaner.processCategories(grouped)
        val hiddenLower = st.hiddenCategories.map { it.lowercase() }.toSet()
        val visibleCategories = allCategories.filter { it.name.lowercase() !in hiddenLower }
        _state.update { it.copy(categories = visibleCategories, allCategories = allCategories) }
    }

    private fun buildGuideChannels(forceRefreshEpg: Boolean = false) {
        val st = _state.value
        val playlistId = st.selectedPlaylistId ?: return
        // Invariant: guideProgrammes is keyed ONLY by canonical epg_channel_key.
        // No alias keys, no fuzzy matching, no entry scans.
        // Guide shows channels that have EPG data (current or next programme)
        val withEpg = st.channels.filter { it.currentProgramme != null || it.nextProgramme != null }
        // Fall back to all live channels if no current/next programme is cached.
        val guide = if (withEpg.isNotEmpty()) withEpg else st.channels.filter {
            it.channel.contentType == ChannelContentType.LIVE || it.channel.contentType == ChannelContentType.UNKNOWN
        }

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

        // Load full programme data for guide timeline
        scope.launch {
            _state.update {
                it.copy(
                    guideChannels = guide,
                    isLoadingGuide = true,
                    guideError = null,
                    epgState = EpgState.Loading,
                )
            }
            val buildStartedAt = Clock.System.now().toEpochMilliseconds()
            try {
                println(
                    "ChannelsEPG: load start playlistId=$playlistId source=$epgSourceUrl forceRefresh=$forceRefreshEpg",
                )

                if (forceRefreshEpg) {
                    withContext(Dispatchers.IO) { channelRepo.refreshEpg(playlistId) }
                }

                var epgData = withContext(Dispatchers.IO) { channelRepo.getEpg(playlistId) }
                var epgLoadError = withContext(Dispatchers.IO) { channelRepo.getEpgLoadError(playlistId) }
                if (epgData.programmesByChannelKey.isEmpty() && !forceRefreshEpg) {
                    println("ChannelsEPG: cache empty for playlistId=$playlistId, refreshing epg only")
                    try {
                        withContext(Dispatchers.IO) { channelRepo.refreshEpg(playlistId) }
                    } catch (e: Exception) {
                        println("ChannelsEPG: refresh failed playlistId=$playlistId error=${e.message}")
                    }
                    epgData = withContext(Dispatchers.IO) { channelRepo.getEpg(playlistId) }
                    epgLoadError = withContext(Dispatchers.IO) { channelRepo.getEpgLoadError(playlistId) }
                }

                if (epgData.programmesByChannelKey.isEmpty() && !epgLoadError.isNullOrBlank()) {
                    throw IllegalStateException(epgLoadError)
                }

                println(
                    "ChannelsEPG: source parsed playlistId=$playlistId generation=${epgData.generationId ?: -1} channels=${epgData.channels.size} programmes=${epgData.programmes.size} groupedKeys=${epgData.programmesByChannelKey.size}",
                )

                data class GuideBuildResult(
                    val programmesByKey: Map<String, List<EpgProgramme>>,
                    val matchedChannels: Int,
                    val unmatchedChannels: Int,
                )

                val buildResult = withContext(Dispatchers.Default) {
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

                println(
                    "ChannelsEPG: mapped playlistId=$playlistId generation=${epgData.generationId ?: -1} guideChannels=${guide.size} mappedKeys=${buildResult.programmesByKey.size} matchedChannels=${buildResult.matchedChannels} unmatchedChannels=${buildResult.unmatchedChannels}",
                )
                debugLog(
                    "ChannelsEPG: guide build complete playlistId=$playlistId generation=${epgData.generationId ?: -1} buildMs=${Clock.System.now().toEpochMilliseconds() - buildStartedAt} channels=${guide.size} programmeRows=${epgData.programmes.size} guideMapSize=${buildResult.programmesByKey.size}",
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

    fun retryGuideLoad() {
        buildGuideChannels(forceRefreshEpg = true)
    }

    // --- Hidden categories/channels management ---

    private fun loadHiddenItems() {
        scope.launch {
            val cats = prefsRepo.getString("channels_hidden_categories")
            val chs = prefsRepo.getString("channels_hidden_channels")
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
        _state.update { it.copy(hiddenCategories = updated) }
        scope.launch {
            prefsRepo.setString("channels_hidden_categories", updated.joinToString("|||"))
        }
        buildLiveCategories()
    }

    fun toggleHiddenChannel(channelId: String) {
        val current = _state.value.hiddenChannels
        val updated = if (channelId in current) current - channelId else current + channelId
        _state.update { it.copy(hiddenChannels = updated) }
        scope.launch {
            prefsRepo.setString("channels_hidden_channels", updated.joinToString("|||"))
        }
        buildLiveCategories()
    }

    fun getAllCategoryNames(): List<String> {
        val st = _state.value
        return CategoryNameCleaner.processCategories(
            st.channels.groupBy { it.channel.groupTitle ?: "Ungrouped" },
        ).map { it.name }
    }

    fun hideAllCategories() {
        val allNames = _state.value.allCategories.map { it.name }.toSet()
        _state.update { it.copy(hiddenCategories = allNames) }
        scope.launch {
            prefsRepo.setString("channels_hidden_categories", allNames.joinToString("|||"))
        }
        buildLiveCategories()
    }

    fun showAllCategories() {
        _state.update { it.copy(hiddenCategories = emptySet()) }
        scope.launch {
            prefsRepo.setString("channels_hidden_categories", "")
        }
        buildLiveCategories()
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
                loadRecentlyViewed()
            } catch (_: Exception) { }
        }
    }

    private fun loadRecentlyViewed() {
        scope.launch {
            try {
                val recent = channelRepo.getRecentlyViewedChannels(20)
                _state.update { it.copy(recentlyViewedChannels = recent) }
            } catch (_: Exception) { }
        }
    }

    // --- Filter & sort ---

    fun setFilter(filter: ChannelsFilterType) {
        _state.update { it.copy(activeFilter = filter) }
        buildLiveCategories()
    }

    fun setSort(sort: ChannelsSortType) {
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
            val passthrough = prefsRepo.getString(KEY_CHANNELS_AUDIO_PASSTHROUGH)
                ?.toBooleanStrictOrNull() ?: false
            val preferSurround = prefsRepo.getString(KEY_CHANNELS_PREFER_SURROUND)
                ?.toBooleanStrictOrNull() ?: true
            _state.update {
                it.copy(
                    audioPassthroughEnabled = passthrough,
                    preferSurroundCodecs = preferSurround,
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

    fun removePlaylist(playlistId: String) {
        scope.launch {
            try {
                channelRepo.removePlaylist(playlistId)
                if (_state.value.selectedPlaylistId == playlistId) {
                    _state.update { it.copy(selectedPlaylistId = null, channels = emptyList(), groupedChannels = emptyMap()) }
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
                selectPlaylist(playlistId)
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
                val favs = channelRepo.getFavorites()
                _state.update { it.copy(favorites = favs) }
            } catch (_: Exception) { }
        }
    }

    fun toggleFavorite(channel: Channel) {
        val channelId = channel.tvgId ?: "${channel.playlistId}_${channel.name}"
        scope.launch {
            try {
                if (channelRepo.isFavorite(channelId)) {
                    channelRepo.removeFavorite(channelId)
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

    private fun debugLog(message: String) {
        if (EPG_DEBUG_LOG_ENABLED) {
            println(message)
        }
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
