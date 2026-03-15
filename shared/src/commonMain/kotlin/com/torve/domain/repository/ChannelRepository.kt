package com.torve.domain.repository

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist

interface ChannelRepository {
    suspend fun addPlaylist(name: String, url: String, epgUrl: String? = null): ChannelPlaylist
    suspend fun addXtreamPlaylist(name: String, server: String, username: String, password: String): ChannelPlaylist
    suspend fun removePlaylist(id: String)
    suspend fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String?)
    suspend fun getPlaylists(): List<ChannelPlaylist>
    suspend fun refreshPlaylist(playlistId: String)
    suspend fun refreshEpg(playlistId: String, hiddenChannelIds: Set<String> = emptySet())
    suspend fun getChannels(playlistId: String): List<Channel>
    suspend fun getChannelsByGroup(playlistId: String): Map<String, List<Channel>>
    suspend fun getEnrichedChannels(playlistId: String): List<EnrichedChannel>
    suspend fun searchChannels(query: String): List<Channel>
    suspend fun getEpg(playlistId: String): EpgData
    suspend fun getEpgLoadError(playlistId: String): String?
    suspend fun getProgrammes(channelId: String): List<EpgProgramme>
    suspend fun addFavorite(channel: Channel)
    suspend fun removeFavorite(channelId: String)
    suspend fun getFavorites(): List<Channel>
    suspend fun isFavorite(channelId: String): Boolean
    suspend fun recordChannelViewed(channel: Channel)
    suspend fun getRecentlyViewedChannels(limit: Long = 20): List<Channel>
    suspend fun clearRecentlyViewedChannels()
    suspend fun getChannelsByContentType(playlistId: String, type: ChannelContentType): List<EnrichedChannel>
}
