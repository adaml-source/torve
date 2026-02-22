package com.streamvault.presentation.iptv

import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.IptvChannel
import com.streamvault.domain.model.IptvPlaylist

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
)
