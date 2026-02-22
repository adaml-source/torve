package com.streamvault.data.iptv

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgData
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.IptvChannel
import com.streamvault.domain.model.IptvContentType
import com.streamvault.domain.model.IptvPlaylist
import com.streamvault.domain.model.PlaylistType
import com.streamvault.domain.repository.IptvRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.Clock

class IptvRepositoryImpl(
    private val database: StreamVaultDatabase,
    private val httpClient: HttpClient,
    private val m3uParser: M3uParser,
    private val epgParser: EpgParser,
    private val xtreamClient: XtreamClient,
) : IptvRepository {

    // In-memory cache of parsed playlists and EPG data
    private val playlistCache = mutableMapOf<String, List<IptvChannel>>()
    private val epgCache = mutableMapOf<String, EpgData>()

    override suspend fun addPlaylist(name: String, url: String, epgUrl: String?): IptvPlaylist {
        val id = "iptv_${Clock.System.now().toEpochMilliseconds()}"
        val now = Clock.System.now().toEpochMilliseconds()

        // Fetch and parse the playlist
        val m3uContent = httpClient.get(url).bodyAsText()
        val parsed = m3uParser.parse(m3uContent, id)

        val resolvedEpgUrl = epgUrl ?: parsed.epgUrl

        database.streamVaultQueries.insertPlaylist(
            id = id,
            name = name,
            url = url,
            epg_url = resolvedEpgUrl,
            channel_count = parsed.channels.size.toLong(),
            last_updated = now,
            type = "m3u",
            server = null,
            username = null,
            password = null,
        )

        playlistCache[id] = parsed.channels

        // Fetch EPG if available
        if (resolvedEpgUrl != null) {
            try {
                val epgContent = httpClient.get(resolvedEpgUrl).bodyAsText()
                epgCache[id] = epgParser.parse(epgContent)
            } catch (_: Exception) {
                // EPG fetch is optional
            }
        }

        return IptvPlaylist(
            id = id,
            name = name,
            url = url,
            epgUrl = resolvedEpgUrl,
            channelCount = parsed.channels.size,
            lastUpdated = now,
            type = PlaylistType.M3U,
        )
    }

    override suspend fun addXtreamPlaylist(
        name: String,
        server: String,
        username: String,
        password: String,
    ): IptvPlaylist {
        val id = "xtream_${Clock.System.now().toEpochMilliseconds()}"
        val now = Clock.System.now().toEpochMilliseconds()

        // Authenticate first
        xtreamClient.authenticate(server, username, password)

        // Fetch live categories and streams
        val categories = xtreamClient.getLiveCategories(server, username, password)
        val liveStreams = xtreamClient.getLiveStreams(server, username, password)
        val channels = xtreamClient.mapLiveToChannels(
            streams = liveStreams,
            categories = categories,
            server = server,
            username = username,
            password = password,
            playlistId = id,
        )

        // Also fetch VOD
        val vodCategories = try { xtreamClient.getVodCategories(server, username, password) } catch (_: Exception) { emptyList() }
        val vodStreams = try { xtreamClient.getVodStreams(server, username, password) } catch (_: Exception) { emptyList() }
        val vodChannels = xtreamClient.mapVodToChannels(
            streams = vodStreams,
            categories = vodCategories,
            server = server,
            username = username,
            password = password,
            playlistId = id,
        )

        val allChannels = channels + vodChannels
        playlistCache[id] = allChannels

        database.streamVaultQueries.insertPlaylist(
            id = id,
            name = name,
            url = "$server/player_api.php",
            epg_url = null,
            channel_count = allChannels.size.toLong(),
            last_updated = now,
            type = "xtream",
            server = server,
            username = username,
            password = password,
        )

        return IptvPlaylist(
            id = id,
            name = name,
            url = "$server/player_api.php",
            channelCount = allChannels.size,
            lastUpdated = now,
            type = PlaylistType.XTREAM,
            server = server,
            username = username,
            password = password,
        )
    }

    override suspend fun removePlaylist(id: String) {
        database.streamVaultQueries.deletePlaylist(id)
        playlistCache.remove(id)
        epgCache.remove(id)
    }

    override suspend fun getPlaylists(): List<IptvPlaylist> {
        return database.streamVaultQueries.getAllPlaylists().executeAsList().map { row ->
            IptvPlaylist(
                id = row.id,
                name = row.name,
                url = row.url,
                epgUrl = row.epg_url,
                channelCount = row.channel_count.toInt(),
                lastUpdated = row.last_updated,
                type = PlaylistType.fromString(row.type),
                server = row.server,
                username = row.username,
                password = row.password,
            )
        }
    }

    override suspend fun refreshPlaylist(playlistId: String) {
        val playlist = database.streamVaultQueries.getPlaylist(playlistId).executeAsOneOrNull()
            ?: return

        val now = Clock.System.now().toEpochMilliseconds()

        if (playlist.type == "xtream" && playlist.server != null && playlist.username != null && playlist.password != null) {
            // Xtream playlist refresh
            val categories = xtreamClient.getLiveCategories(playlist.server, playlist.username, playlist.password)
            val liveStreams = xtreamClient.getLiveStreams(playlist.server, playlist.username, playlist.password)
            val channels = xtreamClient.mapLiveToChannels(
                streams = liveStreams,
                categories = categories,
                server = playlist.server,
                username = playlist.username,
                password = playlist.password,
                playlistId = playlistId,
            )

            val vodCategories = try { xtreamClient.getVodCategories(playlist.server, playlist.username, playlist.password) } catch (_: Exception) { emptyList() }
            val vodStreams = try { xtreamClient.getVodStreams(playlist.server, playlist.username, playlist.password) } catch (_: Exception) { emptyList() }
            val vodChannels = xtreamClient.mapVodToChannels(
                streams = vodStreams,
                categories = vodCategories,
                server = playlist.server,
                username = playlist.username,
                password = playlist.password,
                playlistId = playlistId,
            )

            val allChannels = channels + vodChannels
            playlistCache[playlistId] = allChannels

            database.streamVaultQueries.insertPlaylist(
                id = playlist.id,
                name = playlist.name,
                url = playlist.url,
                epg_url = playlist.epg_url,
                channel_count = allChannels.size.toLong(),
                last_updated = now,
                type = "xtream",
                server = playlist.server,
                username = playlist.username,
                password = playlist.password,
            )
        } else {
            // M3U playlist refresh
            val m3uContent = httpClient.get(playlist.url).bodyAsText()
            val parsed = m3uParser.parse(m3uContent, playlistId)
            playlistCache[playlistId] = parsed.channels

            database.streamVaultQueries.insertPlaylist(
                id = playlist.id,
                name = playlist.name,
                url = playlist.url,
                epg_url = playlist.epg_url,
                channel_count = parsed.channels.size.toLong(),
                last_updated = now,
                type = "m3u",
                server = null,
                username = null,
                password = null,
            )

            // Refresh EPG
            val epgUrl = playlist.epg_url ?: parsed.epgUrl
            if (epgUrl != null) {
                try {
                    val epgContent = httpClient.get(epgUrl).bodyAsText()
                    epgCache[playlistId] = epgParser.parse(epgContent)
                } catch (_: Exception) { }
            }
        }
    }

    override suspend fun getChannels(playlistId: String): List<IptvChannel> {
        return playlistCache[playlistId] ?: run {
            refreshPlaylist(playlistId)
            playlistCache[playlistId] ?: emptyList()
        }
    }

    override suspend fun getChannelsByGroup(playlistId: String): Map<String, List<IptvChannel>> {
        return getChannels(playlistId).groupBy { it.groupTitle ?: "Ungrouped" }
    }

    override suspend fun getEnrichedChannels(playlistId: String): List<EnrichedChannel> {
        val channels = getChannels(playlistId)
        val epg = epgCache[playlistId] ?: return channels.map { EnrichedChannel(it) }
        val now = Clock.System.now().toEpochMilliseconds()

        return channels.map { ch ->
            val epgId = matchEpgChannel(ch, epg)
            val current = epgId?.let { id ->
                epg.programmes.find { it.channelId == id && it.startTime <= now && it.endTime > now }
            }
            val next = epgId?.let { id ->
                epg.programmes.filter { it.channelId == id && it.startTime > now }
                    .minByOrNull { it.startTime }
            }
            EnrichedChannel(ch, current, next)
        }
    }

    override suspend fun searchChannels(query: String): List<IptvChannel> {
        val lowerQuery = query.lowercase()
        return playlistCache.values.flatten().filter { ch ->
            ch.name.lowercase().contains(lowerQuery) ||
                ch.tvgName?.lowercase()?.contains(lowerQuery) == true ||
                ch.groupTitle?.lowercase()?.contains(lowerQuery) == true
        }
    }

    override suspend fun getEpg(playlistId: String): EpgData {
        return epgCache[playlistId] ?: EpgData()
    }

    override suspend fun getProgrammes(channelId: String): List<EpgProgramme> {
        return epgCache.values.flatMap { epg ->
            epg.programmes.filter { it.channelId == channelId }
        }.sortedBy { it.startTime }
    }

    override suspend fun addFavorite(channel: IptvChannel) {
        val now = Clock.System.now().toEpochMilliseconds()
        val channelId = channel.tvgId ?: "${channel.playlistId}_${channel.name}"
        database.streamVaultQueries.insertFavorite(
            channel_id = channelId,
            playlist_id = channel.playlistId,
            name = channel.name,
            logo_url = channel.tvgLogo,
            group_title = channel.groupTitle,
            added_at = now,
        )
    }

    override suspend fun removeFavorite(channelId: String) {
        database.streamVaultQueries.deleteFavorite(channelId)
    }

    override suspend fun getFavorites(): List<IptvChannel> {
        return database.streamVaultQueries.getAllFavorites().executeAsList().map { row ->
            // Find the full channel from cache
            val fullChannel = playlistCache.values.flatten().find { ch ->
                (ch.tvgId ?: "${ch.playlistId}_${ch.name}") == row.channel_id
            }
            fullChannel?.copy(isFavorite = true) ?: IptvChannel(
                name = row.name,
                url = "",
                tvgLogo = row.logo_url,
                groupTitle = row.group_title,
                isFavorite = true,
                playlistId = row.playlist_id,
            )
        }
    }

    override suspend fun isFavorite(channelId: String): Boolean {
        return database.streamVaultQueries.isFavorite(channelId).executeAsOne() > 0
    }

    override suspend fun recordChannelViewed(channel: IptvChannel) {
        val now = Clock.System.now().toEpochMilliseconds()
        val channelId = channel.tvgId ?: "${channel.playlistId}_${channel.name}"
        database.streamVaultQueries.insertRecentChannel(
            channel_id = channelId,
            playlist_id = channel.playlistId,
            name = channel.name,
            logo_url = channel.tvgLogo,
            group_title = channel.groupTitle,
            stream_url = channel.url,
            viewed_at = now,
        )
    }

    override suspend fun getRecentlyViewedChannels(limit: Long): List<IptvChannel> {
        return database.streamVaultQueries.getRecentChannels(limit).executeAsList().map { row ->
            // Try to find full channel from cache for complete metadata
            val fullChannel = playlistCache.values.flatten().find { ch ->
                (ch.tvgId ?: "${ch.playlistId}_${ch.name}") == row.channel_id
            }
            fullChannel ?: IptvChannel(
                name = row.name,
                url = row.stream_url,
                tvgLogo = row.logo_url,
                groupTitle = row.group_title,
                playlistId = row.playlist_id,
            )
        }
    }

    override suspend fun getChannelsByContentType(
        playlistId: String,
        type: IptvContentType,
    ): List<EnrichedChannel> {
        val enriched = getEnrichedChannels(playlistId)
        return enriched.filter { it.channel.contentType == type }
    }

    private fun matchEpgChannel(ch: IptvChannel, epg: EpgData): String? {
        ch.tvgId?.let { if (it in epg.channels) return it }
        ch.tvgName?.let { name ->
            epg.channels.entries.find { it.value.displayName.equals(name, true) }
                ?.let { return it.key }
        }
        return epg.channels.entries.find {
            it.value.displayName.equals(ch.name, true) ||
                it.value.displayName.contains(ch.name, true)
        }?.key
    }
}
