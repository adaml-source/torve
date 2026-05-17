package com.torve.domain.repository

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist

/**
 * Download-phase progress report for M3U playlist fetching.
 *
 * - [bytesRead] monotonically grows during download.
 * - [totalBytes] is null when the server doesn't send Content-Length.
 * - [phase] transitions Downloading → Parsing → Saving on success.
 */
data class PlaylistAddProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
    val phase: Phase = Phase.DOWNLOADING,
) {
    enum class Phase { DOWNLOADING, PARSING, SAVING }
}

data class VodCategoryTypeCount(
    val groupTitle: String,
    val contentType: ChannelContentType,
    val count: Long,
)

data class ChannelPlaylistSummary(
    val id: String,
    val name: String,
    val channelCount: Int,
    val type: String,
)

interface ChannelRepository {
    suspend fun addPlaylist(
        name: String,
        url: String,
        epgUrl: String? = null,
        id: String? = null,
        onProgress: ((PlaylistAddProgress) -> Unit)? = null,
    ): ChannelPlaylist
    suspend fun addXtreamPlaylist(
        name: String,
        server: String,
        username: String,
        password: String,
        id: String? = null,
        epgUrl: String? = null,
    ): ChannelPlaylist
    suspend fun saveM3uPlaylistConfig(
        name: String,
        url: String,
        epgUrl: String? = null,
        id: String? = null,
    ): ChannelPlaylist = addPlaylist(
        name = name,
        url = url,
        epgUrl = epgUrl,
        id = id,
    )
    suspend fun saveXtreamPlaylistConfig(
        name: String,
        server: String,
        username: String,
        password: String,
        id: String? = null,
        epgUrl: String? = null,
    ): ChannelPlaylist = addXtreamPlaylist(
        name = name,
        server = server,
        username = username,
        password = password,
        id = id,
        epgUrl = epgUrl,
    )
    suspend fun removePlaylist(id: String)
    suspend fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String?)
    suspend fun getPlaylists(): List<ChannelPlaylist>
    suspend fun getPlaylistSummaries(): List<ChannelPlaylistSummary> = getPlaylists().map {
        ChannelPlaylistSummary(
            id = it.id,
            name = it.name,
            channelCount = it.channelCount,
            type = it.type.name.lowercase(),
        )
    }
    suspend fun refreshPlaylist(playlistId: String)
    suspend fun refreshPlaylistCatalog(playlistId: String) {
        refreshPlaylist(playlistId)
    }
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
    suspend fun getLiveCategoryCounts(playlistId: String): List<Pair<String, Long>>
    suspend fun getVodCategoryCounts(playlistId: String): List<Pair<String, Long>>
    suspend fun getVodCategoryTypeCounts(playlistId: String): List<VodCategoryTypeCount> = emptyList()
    suspend fun getChannelsForCategory(playlistId: String, categoryName: String): List<Channel>
    suspend fun getChannelsForContentType(
        playlistId: String,
        type: ChannelContentType,
        limit: Int = 160,
    ): List<Channel> = getChannelsByContentType(playlistId, type).take(limit).map { it.channel }

    suspend fun getChannelsForCategoryContentType(
        playlistId: String,
        categoryName: String,
        type: ChannelContentType,
        limit: Int = 160,
    ): List<Channel> = getChannelsForCategory(playlistId, categoryName)
        .asSequence()
        .filter { it.contentType == type }
        .take(limit)
        .toList()

    suspend fun getTotalChannelCount(playlistId: String): Long
    suspend fun getVodSeriesEpisodes(channel: Channel): List<Channel> = emptyList()
    suspend fun resolveFirstVodSeriesEpisode(channel: Channel): Channel? = null
    fun syncHiddenChannelsToDb(hiddenIds: Set<String>)
    fun getHiddenChannelIds(): Set<String>
}
