package com.streamvault.presentation.iptv

import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.IptvCategory
import com.streamvault.domain.model.IptvChannel
import com.streamvault.domain.model.IptvContentType
import com.streamvault.domain.repository.IptvRepository
import com.streamvault.domain.repository.PreferencesRepository
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

class IptvViewModel(
    private val iptvRepo: IptvRepository,
    private val prefsRepo: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(IptvUiState())
    val state: StateFlow<IptvUiState> = _state.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")

    init {
        loadSavedFilters()
        loadHiddenItems()
        loadPlaylists()
        loadFavorites()
        loadRecentlyViewed()
        observeSearch()
    }

    private fun loadSavedFilters() {
        scope.launch {
            val countries = prefsRepo.getString("iptv_country_filter")
            val xxx = prefsRepo.getString("iptv_xxx_enabled")
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
                val playlists = iptvRepo.getPlaylists()
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
                val enriched = iptvRepo.getEnrichedChannels(playlistId)
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
            result = result.filter { enriched ->
                val country = enriched.channel.tvgCountry ?: return@filter true
                val channelCountries = country.split(",", ";").map { it.trim().lowercase() }
                st.selectedCountries.any { it.lowercase() in channelCountries }
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
            IptvFilterType.HD -> result = result.filter { matchesQuality(it.channel.name, "hd") }
            IptvFilterType.FHD -> result = result.filter { matchesQuality(it.channel.name, "fhd") }
            IptvFilterType.UHD -> result = result.filter { matchesQuality(it.channel.name, "4k", "uhd") }
            IptvFilterType.FAVORITES -> {
                val favIds = st.favorites.map { it.tvgId ?: "${it.playlistId}_${it.name}" }.toSet()
                result = result.filter { enriched ->
                    val chId = enriched.channel.tvgId ?: "${enriched.channel.playlistId}_${enriched.channel.name}"
                    chId in favIds
                }
            }
            IptvFilterType.ALL -> { /* no additional filter */ }
        }

        // Sort
        result = when (st.activeSort) {
            IptvSortType.NAME_AZ -> result.sortedBy { it.channel.name.lowercase() }
            IptvSortType.NAME_ZA -> result.sortedByDescending { it.channel.name.lowercase() }
            IptvSortType.RECENTLY_ADDED -> result.reversed()
            IptvSortType.DEFAULT -> result
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

    fun selectSubTab(tab: IptvSubTab) {
        _state.update { it.copy(selectedSubTab = tab) }
    }

    fun toggleViewMode() {
        _state.update {
            it.copy(viewMode = if (it.viewMode == IptvViewMode.LIST) IptvViewMode.GRID else IptvViewMode.LIST)
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
            it.channel.contentType == IptvContentType.LIVE || it.channel.contentType == IptvContentType.UNKNOWN
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

    private fun buildGuideChannels() {
        val st = _state.value
        val playlistId = st.selectedPlaylistId ?: return
        // Guide shows channels that have EPG data (current or next programme)
        val withEpg = st.channels.filter { it.currentProgramme != null || it.nextProgramme != null }
        // Fall back to all live channels if no EPG data
        val guide = if (withEpg.isNotEmpty()) withEpg else st.channels.filter {
            it.channel.contentType == IptvContentType.LIVE || it.channel.contentType == IptvContentType.UNKNOWN
        }.take(100)

        // Load full programme data for guide timeline
        scope.launch {
            try {
                val epgData = iptvRepo.getEpg(playlistId)
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                val windowEnd = now + 6 * 3600 * 1000L // 6 hours ahead

                val progMap = mutableMapOf<String, List<EpgProgramme>>()
                guide.forEach { enriched ->
                    val epgId = enriched.channel.tvgId
                        ?: enriched.channel.tvgName
                        ?: enriched.channel.name
                    // Find matching EPG channel id
                    val matchId = epgData.channels.keys.firstOrNull { key ->
                        key.equals(enriched.channel.tvgId, ignoreCase = true)
                    } ?: epgData.channels.entries.firstOrNull { (_, epgCh) ->
                        epgCh.displayName.equals(enriched.channel.tvgName ?: enriched.channel.name, ignoreCase = true)
                    }?.key

                    if (matchId != null) {
                        val progs = epgData.programmes
                            .filter { it.channelId == matchId && it.endTime > now && it.startTime < windowEnd }
                            .sortedBy { it.startTime }
                        val chKey = enriched.channel.tvgId ?: "${enriched.channel.playlistId}_${enriched.channel.name}"
                        progMap[chKey] = progs
                    }
                }
                _state.update { it.copy(guideChannels = guide, guideProgrammes = progMap) }
            } catch (_: Exception) {
                _state.update { it.copy(guideChannels = guide) }
            }
        }
    }

    // --- Hidden categories/channels management ---

    private fun loadHiddenItems() {
        scope.launch {
            val cats = prefsRepo.getString("iptv_hidden_categories")
            val chs = prefsRepo.getString("iptv_hidden_channels")
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
            prefsRepo.setString("iptv_hidden_categories", updated.joinToString("|||"))
        }
        buildLiveCategories()
    }

    fun toggleHiddenChannel(channelId: String) {
        val current = _state.value.hiddenChannels
        val updated = if (channelId in current) current - channelId else current + channelId
        _state.update { it.copy(hiddenChannels = updated) }
        scope.launch {
            prefsRepo.setString("iptv_hidden_channels", updated.joinToString("|||"))
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
            prefsRepo.setString("iptv_hidden_categories", allNames.joinToString("|||"))
        }
        buildLiveCategories()
    }

    fun showAllCategories() {
        _state.update { it.copy(hiddenCategories = emptySet()) }
        scope.launch {
            prefsRepo.setString("iptv_hidden_categories", "")
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
            prefsRepo.setString("iptv_hidden_categories", updated.joinToString("|||"))
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
            prefsRepo.setString("iptv_hidden_categories", updated.joinToString("|||"))
        }
        buildLiveCategories()
    }

    // --- Recently viewed ---

    fun recordChannelViewed(channel: IptvChannel) {
        scope.launch {
            try {
                iptvRepo.recordChannelViewed(channel)
                loadRecentlyViewed()
            } catch (_: Exception) { }
        }
    }

    private fun loadRecentlyViewed() {
        scope.launch {
            try {
                val recent = iptvRepo.getRecentlyViewedChannels(20)
                _state.update { it.copy(recentlyViewedChannels = recent) }
            } catch (_: Exception) { }
        }
    }

    // --- Filter & sort ---

    fun setFilter(filter: IptvFilterType) {
        _state.update { it.copy(activeFilter = filter) }
        buildLiveCategories()
    }

    fun setSort(sort: IptvSortType) {
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
                iptvRepo.addPlaylist(st.newPlaylistName, st.newPlaylistUrl, epg)
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
                iptvRepo.addXtreamPlaylist(
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

    fun toggleCountry(country: String) {
        val current = _state.value.selectedCountries
        val updated = if (country in current) current - country else current + country
        _state.update { it.copy(selectedCountries = updated) }
        scope.launch {
            prefsRepo.setString("iptv_country_filter", updated.joinToString(","))
        }
    }

    fun clearCountryFilter() {
        _state.update { it.copy(selectedCountries = emptySet()) }
        scope.launch { prefsRepo.remove("iptv_country_filter") }
    }

    fun setXxxEnabled(enabled: Boolean) {
        _state.update { it.copy(xxxEnabled = enabled) }
        scope.launch { prefsRepo.setString("iptv_xxx_enabled", enabled.toString()) }
    }

    fun removePlaylist(playlistId: String) {
        scope.launch {
            try {
                iptvRepo.removePlaylist(playlistId)
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
                iptvRepo.refreshPlaylist(playlistId)
                selectPlaylist(playlistId)
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingChannels = false, error = e.message) }
            }
        }
    }

    // --- Favorites ---

    fun loadFavorites() {
        scope.launch {
            try {
                val favs = iptvRepo.getFavorites()
                _state.update { it.copy(favorites = favs) }
            } catch (_: Exception) { }
        }
    }

    fun toggleFavorite(channel: IptvChannel) {
        val channelId = channel.tvgId ?: "${channel.playlistId}_${channel.name}"
        scope.launch {
            try {
                if (iptvRepo.isFavorite(channelId)) {
                    iptvRepo.removeFavorite(channelId)
                } else {
                    iptvRepo.addFavorite(channel)
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
                        val results = iptvRepo.searchChannels(query)
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

    fun selectChannel(channel: IptvChannel) {
        _state.update { it.copy(selectedChannel = channel) }
        val epgId = channel.tvgId ?: return
        scope.launch {
            try {
                val programmes = iptvRepo.getProgrammes(epgId)
                _state.update { it.copy(programmes = programmes) }
            } catch (_: Exception) { }
        }
    }

    fun clearSelectedChannel() {
        _state.update { it.copy(selectedChannel = null, programmes = emptyList()) }
    }
}
