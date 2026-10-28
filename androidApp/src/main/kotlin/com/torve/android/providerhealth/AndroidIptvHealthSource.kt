package com.torve.android.providerhealth

import com.torve.domain.model.ChannelPlaylist
import com.torve.presentation.channels.ChannelsStateRelay
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.EpgState
import com.torve.presentation.providerhealth.*

/** Checking saved playlist health must not construct ChannelsViewModel and
 * start catalogue recovery, EPG loading or last-channel restoration. */
class AndroidIptvHealthSource(
    private val relay: ChannelsStateRelay,
    private val savedPlaylists: suspend () -> List<ChannelPlaylist>,
) {
    suspend fun snapshot(): ChannelsUiState {
        val live = relay.snapshot()
        if (live.playlists.isNotEmpty() || live.selectedPlaylistId != null || live.isLoading) return live
        val saved = savedPlaylists()
        return live.copy(playlists = saved, selectedPlaylistId = saved.firstOrNull()?.id,
            epgState = if (saved.any { !it.epgUrl.isNullOrBlank() }) EpgState.Loading else EpgState.NotConfigured)
    }
    fun checker(epg: Boolean): ProviderHealthChecker = object : ProviderHealthChecker {
        override val providerKey = if (epg) "iptv:epg" else "iptv:active"
        override suspend fun check(): com.torve.domain.providerhealth.ProviderHealthEntry {
            val state = snapshot()
            return if (epg) IptvEpgProviderHealthChecker { state }.check()
                else IptvProviderHealthChecker { state }.check()
        }
    }
}
