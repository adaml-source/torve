package com.streamvault.domain.repository

import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgData
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelContentType
import com.streamvault.domain.model.ChannelPlaylist

interface ChannelRepository {
    suspend fun addPlaylist(name: String, url: String, epgUrl: String? = null): ChannelPlaylist
    suspend fun addXtreamPlaylist(name: String, server: String, username: String, password: String): ChannelPlaylist
    suspend fun removePlaylist(id: String)
    suspend fun getPlaylists(): List<ChannelPlaylist>
    suspend fun refreshPlaylist(playlistId: String)
    suspend fun getChannels(playlistId: String): List<Channel>
    suspend fun getChannelsByGroup(playlistId: String): Map<String, List<Channel>>
    suspend fun getEnrichedChannels(playlistId: String): List<EnrichedChannel>
    suspend fun searchChannels(query: String): List<Channel>
    suspend fun getEpg(playlistId: String): EpgData
    suspend fun getProgrammes(channelId: String): List<EpgProgramme>
    suspend fun addFavorite(channel: Channel)
    suspend fun removeFavorite(channelId: String)
    suspend fun getFavorites(): List<Channel>
    suspend fun isFavorite(channelId: String): Boolean
    suspend fun recordChannelViewed(channel: Channel)
    suspend fun getRecentlyViewedChannels(limit: Long = 20): List<Channel>
    suspend fun getChannelsByContentType(playlistId: String, type: ChannelContentType): List<EnrichedChannel>
}
