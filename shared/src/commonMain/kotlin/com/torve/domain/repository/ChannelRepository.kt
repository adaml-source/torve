package com.torve.domain.repository

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist

interface ChannelRepository {
    suspend fun addPlaylist(name: String, url: String, epgUrl: String? = null, id: String? = null): ChannelPlaylist
    suspend fun addXtreamPlaylist(name: String, server: String, username: String, password: String, id: String? = null): ChannelPlaylist
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
    /** Remove all playlists, channels, favorites, and credential caches. Called on sign-out. */
    suspend fun clearAll()
    suspend fun getChannelsByContentType(playlistId: String, type: ChannelContentType): List<EnrichedChannel>
    suspend fun getCategoryCounts(playlistId: String): List<Pair<String, Long>>
    suspend fun getChannelsForCategory(playlistId: String, categoryName: String): List<Channel>
    suspend fun getTotalChannelCount(playlistId: String): Long
    fun syncHiddenChannelsToDb(hiddenIds: Set<String>)
    fun getHiddenChannelIds(): Set<String>
}
