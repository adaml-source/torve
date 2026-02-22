package com.streamvault.presentation.iptv

import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.IptvCategory
import com.streamvault.domain.model.IptvChannel
import com.streamvault.domain.model.IptvPlaylist

enum class IptvSubTab { HOME, LIVE, GUIDE }

enum class IptvViewMode { LIST, GRID }

enum class IptvSortType { DEFAULT, NAME_AZ, NAME_ZA, RECENTLY_ADDED }

enum class IptvFilterType { ALL, HD, FHD, UHD, FAVORITES }

data class IptvUiState(
    val playlists: List<IptvPlaylist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val channels: List<EnrichedChannel> = emptyList(),
    val groupedChannels: Map<String, List<EnrichedChannel>> = emptyMap(),
    val favorites: List<IptvChannel> = emptyList(),
    val selectedGroup: String? = null,
    val searchQuery: String = "",
    val searchResults: List<IptvChannel> = emptyList(),
    val selectedChannel: IptvChannel? = null,
    val programmes: List<EpgProgramme> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingChannels: Boolean = false,
    val isAddingPlaylist: Boolean = false,
    val error: String? = null,
    // Add playlist dialog
    val showAddPlaylist: Boolean = false,
    val newPlaylistName: String = "",
    val newPlaylistUrl: String = "",
    val newPlaylistEpgUrl: String = "",
    // Xtream Codes fields
    val newPlaylistType: String = "m3u",
    val newXtreamServer: String = "",
    val newXtreamUsername: String = "",
    val newXtreamPassword: String = "",
    // Country filter + XXX toggle
    val availableCountries: List<String> = emptyList(),
    val selectedCountries: Set<String> = emptySet(),
    val xxxEnabled: Boolean = false,
    val showCountryFilter: Boolean = false,
    // Sub-tabs & view mode
    val selectedSubTab: IptvSubTab = IptvSubTab.LIVE,
    val viewMode: IptvViewMode = IptvViewMode.LIST,
    // Collapsible categories
    val categories: List<IptvCategory> = emptyList(),
    val expandedCategories: Set<String> = emptySet(),
    // Recently viewed
    val recentlyViewedChannels: List<IptvChannel> = emptyList(),
    // Filter & sort
    val activeFilter: IptvFilterType = IptvFilterType.ALL,
    val activeSort: IptvSortType = IptvSortType.DEFAULT,
    val showFilterSheet: Boolean = false,
    // Category/channel management
    val showCategoryManager: Boolean = false,
    val hiddenCategories: Set<String> = emptySet(),
    val hiddenChannels: Set<String> = emptySet(),
    val allCategories: List<IptvCategory> = emptyList(),
    // EPG Guide
    val guideChannels: List<EnrichedChannel> = emptyList(),
    val guideProgrammes: Map<String, List<EpgProgramme>> = emptyMap(),
)
