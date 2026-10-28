package com.torve.data.profile

import com.torve.domain.model.*
import com.torve.domain.repository.*

/** All channel browsing, favorites and direct stream grants use the same approved-group boundary. */
class FamilyChannelRepository(private val raw: ChannelRepository, private val family: FamilyControls) : ChannelRepository by raw {
    private val epgPermissions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    private fun source(channel: Channel) = "channels:${channel.playlistId}:${channel.groupTitle ?: "Ungrouped"}"
    private fun visible(channel: Channel): Boolean {
        if (!family.allowsSource(source(channel))) return false
        if (!family.state.value.isKids) return true
        if (channel.contentType != ChannelContentType.LIVE) return false
        listOfNotNull(channel.tvgId, channel.tvgName, channel.name).forEach { epgPermissions.value = epgPermissions.value + (it to family.sessionKey()) }
        family.registerPlayback(channel.url, source(channel))
        return true
    }
    suspend fun setupChoices(): List<Triple<String, String, String>> {
        family.requireParent()
        return raw.getPlaylists().flatMap { playlist -> raw.getLiveCategoryCounts(playlist.id).map { (name, _) -> Triple("channels:${playlist.id}:$name", name, playlist.name) } }
    }
    override suspend fun getPlaylists(): List<ChannelPlaylist> {
        family.initialize()
        val all = raw.getPlaylists()
        if (!family.state.value.configuration.enabled || !family.state.value.isKids) return if (family.allowsSource("channels")) all else emptyList()
        val sources = family.state.value.configuration.kids[family.state.value.activeProfileId]?.allowedSources.orEmpty()
        return all.filter { playlist -> sources.any { it.startsWith("channels:${playlist.id}:") } }
    }
    override suspend fun getPlaylistSummaries() = getPlaylists().map { ChannelPlaylistSummary(it.id, it.name, it.channelCount, it.type.name.lowercase()) }
    override suspend fun getChannels(playlistId: String) = raw.getChannels(playlistId).filter(::visible)
    override suspend fun getChannelsByGroup(playlistId: String) = getChannels(playlistId).groupBy { it.groupTitle ?: "Ungrouped" }
    override suspend fun getEnrichedChannels(playlistId: String) = raw.getEnrichedChannels(playlistId).filter { visible(it.channel) }
    override suspend fun getChannelsForCategory(playlistId: String, categoryName: String) = raw.getChannelsForCategory(playlistId, categoryName).filter(::visible)
    override suspend fun getChannelsForContentType(playlistId: String, type: ChannelContentType, limit: Int) = raw.getChannelsForContentType(playlistId, type, limit).filter(::visible)
    override suspend fun getChannelsForCategoryContentType(playlistId: String, categoryName: String, type: ChannelContentType, limit: Int) = raw.getChannelsForCategoryContentType(playlistId, categoryName, type, limit).filter(::visible)
    override suspend fun getChannelsByContentType(playlistId: String, type: ChannelContentType) = raw.getChannelsByContentType(playlistId, type).filter { visible(it.channel) }
    override suspend fun getEpg(playlistId: String): EpgData {
        val data = raw.getEpg(playlistId)
        if (!family.state.value.isKids) return if (family.allowsSource("channels")) data else EpgData(emptyMap(), emptyList())
        getChannels(playlistId)
        val keys = epgPermissions.value.filterValues { it == family.sessionKey() }.keys
        return data.copy(channels = data.channels.filterKeys { it in keys }, programmes = data.programmes.filter { it.channelId in keys }, programmesByChannelKey = data.programmesByChannelKey.filterKeys { it in keys })
    }
    override suspend fun getProgrammes(channelId: String): List<EpgProgramme> {
        if (family.state.value.isKids && epgPermissions.value[channelId] != family.sessionKey()) return emptyList()
        if (!family.state.value.isKids && !family.allowsSource("channels")) return emptyList()
        return raw.getProgrammes(channelId)
    }
    override suspend fun searchChannels(query: String) = raw.searchChannels(query).filter(::visible)
    override suspend fun getFavorites() = raw.getFavorites().filter(::visible)
    override suspend fun getRecentlyViewedChannels(limit: Long) = raw.getRecentlyViewedChannels(limit).filter(::visible)
    override suspend fun getCategoryCounts(playlistId: String) = raw.getCategoryCounts(playlistId).filter { family.allowsSource("channels:$playlistId:${it.first}") }
    override suspend fun getLiveCategoryCounts(playlistId: String) = raw.getLiveCategoryCounts(playlistId).filter { family.allowsSource("channels:$playlistId:${it.first}") }
    override suspend fun getVodCategoryCounts(playlistId: String) = if (family.state.value.isKids) emptyList() else raw.getVodCategoryCounts(playlistId).filter { family.allowsSource("channels:$playlistId:${it.first}") }
    override suspend fun getVodCategoryTypeCounts(playlistId: String) = if (family.state.value.isKids) emptyList() else raw.getVodCategoryTypeCounts(playlistId)
    override suspend fun getTotalChannelCount(playlistId: String) = if (family.state.value.isKids) getChannels(playlistId).size.toLong() else raw.getTotalChannelCount(playlistId)
    override suspend fun getVodSeriesEpisodes(channel: Channel) = if (visible(channel)) raw.getVodSeriesEpisodes(channel).filter(::visible) else emptyList()
    override suspend fun resolveFirstVodSeriesEpisode(channel: Channel) = if (family.state.value.isKids) null else raw.resolveFirstVodSeriesEpisode(channel)
    override suspend fun addFavorite(channel: Channel) { check(visible(channel)); raw.addFavorite(channel) }
    override suspend fun recordChannelViewed(channel: Channel) { check(visible(channel)); raw.recordChannelViewed(channel) }
    override suspend fun addPlaylist(name: String, url: String, epgUrl: String?, id: String?, onProgress: ((PlaylistAddProgress) -> Unit)?): ChannelPlaylist { family.requireParent(); return raw.addPlaylist(name, url, epgUrl, id, onProgress) }
    override suspend fun addXtreamPlaylist(name: String, server: String, username: String, password: String, id: String?, epgUrl: String?): ChannelPlaylist { family.requireParent(); return raw.addXtreamPlaylist(name, server, username, password, id, epgUrl) }
    override suspend fun saveM3uPlaylistConfig(name: String, url: String, epgUrl: String?, id: String?): ChannelPlaylist { family.requireParent(); return raw.saveM3uPlaylistConfig(name, url, epgUrl, id) }
    override suspend fun saveXtreamPlaylistConfig(name: String, server: String, username: String, password: String, id: String?, epgUrl: String?): ChannelPlaylist { family.requireParent(); return raw.saveXtreamPlaylistConfig(name, server, username, password, id, epgUrl) }
    override suspend fun removePlaylist(id: String) { family.requireParent(); raw.removePlaylist(id) }
    override suspend fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String?) { family.requireParent(); raw.updatePlaylistEpgUrl(playlistId, epgUrl) }
}
