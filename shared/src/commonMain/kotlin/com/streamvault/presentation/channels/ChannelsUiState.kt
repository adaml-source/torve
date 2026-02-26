package com.streamvault.presentation.channels

import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.ChannelCategory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelPlaylist

enum class ChannelsSubTab { LIVE, FAVOURITES }

enum class ChannelsViewMode { LIST, GRID }

enum class ChannelsSortType { DEFAULT, NAME_AZ, NAME_ZA, RECENTLY_ADDED }

enum class ChannelsFilterType { ALL, HD, FHD, UHD, FAVORITES }

data class ChannelsUiState(
    val playlists: List<ChannelPlaylist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val channels: List<EnrichedChannel> = emptyList(),
    val groupedChannels: Map<String, List<EnrichedChannel>> = emptyMap(),
    val favorites: List<Channel> = emptyList(),
    val selectedGroup: String? = null,
    val searchQuery: String = "",
    val searchResults: List<Channel> = emptyList(),
    val selectedChannel: Channel? = null,
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
    val selectedSubTab: ChannelsSubTab = ChannelsSubTab.LIVE,
    val viewMode: ChannelsViewMode = ChannelsViewMode.LIST,
    // Collapsible categories
    val categories: List<ChannelCategory> = emptyList(),
    val expandedCategories: Set<String> = emptySet(),
    // Recently viewed
    val recentlyViewedChannels: List<Channel> = emptyList(),
    // Filter & sort
    val activeFilter: ChannelsFilterType = ChannelsFilterType.ALL,
    val activeSort: ChannelsSortType = ChannelsSortType.DEFAULT,
    val showFilterSheet: Boolean = false,
    // Category/channel management
    val showCategoryManager: Boolean = false,
    val hiddenCategories: Set<String> = emptySet(),
    val hiddenChannels: Set<String> = emptySet(),
    val allCategories: List<ChannelCategory> = emptyList(),
    // EPG Guide
    val guideChannels: List<EnrichedChannel> = emptyList(),
    val guideProgrammes: Map<String, List<EpgProgramme>> = emptyMap(),
)
