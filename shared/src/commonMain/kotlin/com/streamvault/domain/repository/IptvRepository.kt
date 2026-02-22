package com.streamvault.domain.repository

import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgData
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.IptvChannel
import com.streamvault.domain.model.IptvContentType
import com.streamvault.domain.model.IptvPlaylist

interface IptvRepository {
    suspend fun addPlaylist(name: String, url: String, epgUrl: String? = null): IptvPlaylist
    suspend fun addXtreamPlaylist(name: String, server: String, username: String, password: String): IptvPlaylist
    suspend fun removePlaylist(id: String)
    suspend fun getPlaylists(): List<IptvPlaylist>
    suspend fun refreshPlaylist(playlistId: String)
    suspend fun getChannels(playlistId: String): List<IptvChannel>
    suspend fun getChannelsByGroup(playlistId: String): Map<String, List<IptvChannel>>
    suspend fun getEnrichedChannels(playlistId: String): List<EnrichedChannel>
    suspend fun searchChannels(query: String): List<IptvChannel>
    suspend fun getEpg(playlistId: String): EpgData
    suspend fun getProgrammes(channelId: String): List<EpgProgramme>
    suspend fun addFavorite(channel: IptvChannel)
    suspend fun removeFavorite(channelId: String)
    suspend fun getFavorites(): List<IptvChannel>
    suspend fun isFavorite(channelId: String): Boolean
    suspend fun recordChannelViewed(channel: IptvChannel)
    suspend fun getRecentlyViewedChannels(limit: Long = 20): List<IptvChannel>
    suspend fun getChannelsByContentType(playlistId: String, type: IptvContentType): List<EnrichedChannel>
}
