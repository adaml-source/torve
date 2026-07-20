package com.torve.presentation.channels

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.repository.PlaylistAddProgress

enum class ChannelsSubTab { LIVE, FAVOURITES, GUIDE, MOVIES }

enum class ChannelsViewMode { LIST, GRID }

enum class ChannelsSortType { DEFAULT, NAME_AZ, NAME_ZA, RECENTLY_ADDED }

enum class ChannelsFilterType { ALL, HD, FHD, UHD, FAVORITES }

sealed interface EpgState {
    data object NotConfigured : EpgState
    data object Loading : EpgState
    data class Loaded(
        val sourceUrl: String,
        val sourceChannelCount: Int,
        val sourceProgrammeCount: Int,
        val matchedChannelCount: Int,
        val unmatchedChannelCount: Int,
    ) : EpgState
    data class Error(
        val message: String,
    ) : EpgState
}

data class ChannelsUiState(
    val playlists: List<ChannelPlaylist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val channels: List<EnrichedChannel> = emptyList(),
    val groupedChannels: Map<String, List<EnrichedChannel>> = emptyMap(),
    /** Channels for the currently selected category — loaded on demand, not from full playlist. */
    val categoryChannels: List<EnrichedChannel> = emptyList(),
    val favorites: List<Channel> = emptyList(),
    val selectedGroup: String? = null,
    val searchQuery: String = "",
    val searchResults: List<Channel> = emptyList(),
    val selectedChannel: Channel? = null,
    val programmes: List<EpgProgramme> = emptyList(),
    val vodCategories: List<ChannelCategory> = emptyList(),
    val expandedVodCategories: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isLoadingChannels: Boolean = false,
    val isAddingPlaylist: Boolean = false,
    val addPlaylistProgress: PlaylistAddProgress? = null,
    val isCheckingEpg: Boolean = false,
    val epgCheckMessage: String? = null,
    val epgCheckSuccess: Boolean? = null,
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
    val isLoadingGuide: Boolean = false,
    val guideError: String? = null,
    val epgState: EpgState = EpgState.NotConfigured,
    // Guide filter + sort controls (desktop EPG)
    val guideSearchQuery: String = "",
    val guideSortMode: GuideSortMode = GuideSortMode.NUMBER,
    // Audio output (TV live player)
    val audioPassthroughEnabled: Boolean = false,
    val preferSurroundCodecs: Boolean = true,
    val liveAudioOutputMode: LiveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE,
)

/**
 * Sort mode for the EPG guide channel list (desktop). NUMBER preserves
 * the playlist's channel-number ordering (Xtream catalogue order or m3u
 * file order); NAME sorts alphabetically; EPG_FIRST puts channels with
 * EPG-matched programmes ahead of empty rows.
 */
enum class GuideSortMode { NUMBER, NAME, EPG_FIRST }
