package com.torve.data.channels

import com.torve.db.TorveDatabase
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.PlaylistType
import com.torve.domain.model.channelIdentityCandidates
import com.torve.domain.model.channelMatchesIdentity
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.stableChannelId
import com.torve.domain.repository.ChannelRepository
import com.torve.data.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ChannelRepositoryImpl(
    private val database: TorveDatabase,
    private val httpClient: HttpClient,
    private val m3uParser: M3uParser,
    private val epgParser: EpgParser,
    private val xtreamClient: XtreamClient,
) : ChannelRepository {

    companion object {
        private const val EPG_CONNECT_TIMEOUT_MS = 20_000L
        private const val EPG_REQUEST_TIMEOUT_MS = 120_000L
        private const val EPG_SOCKET_TIMEOUT_MS = 120_000L
        private const val EPG_MAX_FETCH_ATTEMPTS = 3
        private const val EPG_MAX_DOWNLOAD_BYTES = 100L * 1024L * 1024L
        private const val EPG_MAX_UNCOMPRESSED_PARSE_BYTES = 100L * 1024L * 1024L
        private const val EPG_FORCE_IDENTITY_ACCEPT_ENCODING = true
        private const val EPG_MAX_PROGRAMMES_PER_CHANNEL_INGEST = 240
        private const val EPG_MAX_PROGRAMMES_TOTAL_INGEST = 150_000
        private const val EPG_MAX_PROGRAMMES_PER_CHANNEL_IN_MEMORY = 240
        private const val EPG_MAX_PROGRAMMES_TOTAL_IN_MEMORY = 4_000
        private const val EPG_MAX_CHANNELS_IN_MEMORY = 160
        private const val PREF_EPG_WINDOW_HOURS_AHEAD = "epg_window_hours_ahead"
        private const val PREF_EPG_WINDOW_HOURS_BEHIND = "epg_window_hours_behind"
        private const val PREF_EPG_LOAD_STATE_PREFIX = "epg_load_state_"
        private const val PREF_EPG_ACTIVE_GENERATION_PREFIX = "epg_active_generation_"
        private const val PREF_CHANNEL_ACTIVE_GENERATION_PREFIX = "channel_active_generation_"
        private const val PREF_CHANNEL_LAST_SYNC_PREFIX = "channel_last_sync_"
        private const val PREF_CHANNEL_STAGED_GENERATION_PREFIX = "channel_staged_generation_"
        private const val DEFAULT_EPG_WINDOW_HOURS_AHEAD = 6
        private const val DEFAULT_EPG_WINDOW_HOURS_BEHIND = 1
        private const val MAX_EPG_WINDOW_HOURS = 18
        private const val EPG_STATE_IDLE = "IDLE"
        private const val EPG_STATE_LOADING = "LOADING"
        private const val EPG_STATE_READY = "READY"
        private const val EPG_STATE_ERROR = "ERROR"
        private const val EPG_DEBUG_LOG_ENABLED = false
        private const val CHANNEL_DEBUG_LOG_ENABLED = false
    }

    // In-memory cache of parsed playlists and EPG data
    private val playlistCache = mutableMapOf<String, List<Channel>>()
    private val epgCache = mutableMapOf<String, EpgData>()
    private val epgErrorCache = mutableMapOf<String, String?>()
    private val epgProgressCache = mutableMapOf<String, EpgBatchProgress>()
    private val epgHttpClient: HttpClient by lazy {
        HttpClientFactory.createEpgStreamingClient(
            forceIdentityEncoding = EPG_FORCE_IDENTITY_ACCEPT_ENCODING,
        )
    }

    private data class EpgChannelMapping(
        val byXmltvId: Map<String, String>,
        val byNormalizedName: Map<String, String>,
        val allowedCanonicalKeys: Set<String>,
    )

    override suspend fun addPlaylist(name: String, url: String, epgUrl: String?): ChannelPlaylist {
        val id = "ch_${Clock.System.now().toEpochMilliseconds()}"
        val now = Clock.System.now().toEpochMilliseconds()

        // Fetch and parse the playlist
        val m3uContent = httpClient.get(url).bodyAsText()
        val parsed = m3uParser.parse(m3uContent, id)

        val resolvedEpgUrl = epgUrl ?: parsed.epgUrl

        persistPlaylistSnapshot(
            playlistId = id,
            playlistName = name,
            playlistUrl = url,
            epgUrl = resolvedEpgUrl,
            playlistType = "m3u",
            server = null,
            username = null,
            password = null,
            channels = parsed.channels,
            updatedAt = now,
        )

        refreshEpgForPlaylist(id, resolvedEpgUrl)

        return ChannelPlaylist(
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
    ): ChannelPlaylist {
        val id = "xtream_${Clock.System.now().toEpochMilliseconds()}"
        val now = Clock.System.now().toEpochMilliseconds()
        val xtreamEpgUrl = buildXtreamEpgUrl(server, username, password)

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
        persistPlaylistSnapshot(
            playlistId = id,
            playlistName = name,
            playlistUrl = "$server/player_api.php",
            epgUrl = xtreamEpgUrl,
            playlistType = "xtream",
            server = server,
            username = username,
            password = password,
            channels = allChannels,
            updatedAt = now,
        )
        refreshEpgForPlaylist(id, xtreamEpgUrl)

        return ChannelPlaylist(
            id = id,
            name = name,
            url = "$server/player_api.php",
            epgUrl = xtreamEpgUrl,
            channelCount = allChannels.size,
            lastUpdated = now,
            type = PlaylistType.XTREAM,
            server = server,
            username = username,
            password = password,
        )
    }

    override suspend fun removePlaylist(id: String) {
        database.torveQueries.deletePlaylist(id)
        database.torveQueries.deleteChannelsForPlaylist(id)
        playlistCache.remove(id)
        epgCache.remove(id)
        epgErrorCache.remove(id)
        epgProgressCache.remove(id)
        database.torveQueries.deletePreference(epgLoadStatePrefKey(id))
        database.torveQueries.deletePreference(epgActiveGenerationPrefKey(id))
        database.torveQueries.deletePreference(channelActiveGenerationPrefKey(id))
        database.torveQueries.deletePreference(channelLastSyncPrefKey(id))
        database.torveQueries.deletePreference(channelStagedGenerationPrefKey(id))
    }

    override suspend fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String?) {
        val playlist = database.torveQueries.getPlaylist(playlistId).executeAsOneOrNull()
            ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        val normalizedEpg = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        database.torveQueries.updatePlaylistEpgUrl(
            epg_url = normalizedEpg,
            last_updated = now,
            id = playlistId,
        )
        epgCache.remove(playlistId)
        refreshEpgForPlaylist(playlistId, normalizedEpg)

        database.torveQueries.insertPlaylist(
            id = playlist.id,
            name = playlist.name,
            url = playlist.url,
            epg_url = normalizedEpg,
            channel_count = playlist.channel_count,
            last_updated = now,
            type = playlist.type,
            server = playlist.server,
            username = playlist.username,
            password = playlist.password,
        )
    }

    override suspend fun getPlaylists(): List<ChannelPlaylist> {
        return database.torveQueries.getAllPlaylists().executeAsList().map { row ->
            ChannelPlaylist(
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
        repairChannelCatalogIfNeeded(playlistId)
        val playlist = database.torveQueries.getPlaylist(playlistId).executeAsOneOrNull()
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
            val xtreamEpgUrl = playlist.epg_url ?: buildXtreamEpgUrl(
                server = playlist.server,
                username = playlist.username,
                password = playlist.password,
            )

            val allChannels = channels + vodChannels
            persistPlaylistSnapshot(
                playlistId = playlist.id,
                playlistName = playlist.name,
                playlistUrl = playlist.url,
                epgUrl = xtreamEpgUrl,
                playlistType = "xtream",
                server = playlist.server,
                username = playlist.username,
                password = playlist.password,
                channels = allChannels,
                updatedAt = now,
            )
            refreshEpgForPlaylist(playlistId, xtreamEpgUrl)
        } else {
            // M3U playlist refresh
            val m3uContent = httpClient.get(playlist.url).bodyAsText()
            val parsed = m3uParser.parse(m3uContent, playlistId)
            val resolvedEpgUrl = playlist.epg_url ?: parsed.epgUrl

            persistPlaylistSnapshot(
                playlistId = playlist.id,
                playlistName = playlist.name,
                playlistUrl = playlist.url,
                epgUrl = resolvedEpgUrl,
                playlistType = "m3u",
                server = null,
                username = null,
                password = null,
                channels = parsed.channels,
                updatedAt = now,
            )

            // Refresh EPG
            refreshEpgForPlaylist(playlistId, resolvedEpgUrl)
        }
    }

    override suspend fun refreshEpg(playlistId: String, hiddenChannelIds: Set<String>) {
        val playlist = database.torveQueries.getPlaylist(playlistId).executeAsOneOrNull()
            ?: return
        val sourceUrl = playlist.epg_url?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (
                playlist.type == "xtream" &&
                playlist.server != null &&
                playlist.username != null &&
                playlist.password != null
            ) {
                buildXtreamEpgUrl(
                    server = playlist.server,
                    username = playlist.username,
                    password = playlist.password,
                )
            } else {
                null
            }
        refreshEpgForPlaylist(playlistId, sourceUrl, hiddenChannelIds)
    }

    override suspend fun getChannels(playlistId: String): List<Channel> {
        repairChannelCatalogIfNeeded(playlistId)
        return playlistCache[playlistId] ?: loadChannelsFromDatabase(playlistId).also { persisted ->
            if (persisted.isNotEmpty()) {
                playlistCache[playlistId] = persisted
            }
        }
    }

    override suspend fun getChannelsByGroup(playlistId: String): Map<String, List<Channel>> {
        return getChannels(playlistId).groupBy { it.groupTitle ?: "Ungrouped" }
    }

    override suspend fun getEnrichedChannels(playlistId: String): List<EnrichedChannel> {
        val channels = getChannels(playlistId)

        // Load favorite IDs to mark channels
        val favoriteIds = database.torveQueries.getAllFavorites().executeAsList()
            .map { it.channel_id }
            .toSet()

        val epg = epgCache[playlistId]
        val now = Clock.System.now().toEpochMilliseconds()
        val currentProgrammeByChannelId = mutableMapOf<String, EpgProgramme>()
        val nextProgrammeByChannelId = mutableMapOf<String, EpgProgramme>()

        epg?.programmesByChannelKey?.forEach { (epgChannelKey, channelProgrammes) ->
            channelProgrammes.forEach { programme ->
                if (programme.startTime <= now && programme.endTime > now) {
                    val existing = currentProgrammeByChannelId[epgChannelKey]
                    if (existing == null || programme.startTime > existing.startTime) {
                        currentProgrammeByChannelId[epgChannelKey] = programme
                    }
                } else if (programme.startTime > now) {
                    val existing = nextProgrammeByChannelId[epgChannelKey]
                    if (existing == null || programme.startTime < existing.startTime) {
                        nextProgrammeByChannelId[epgChannelKey] = programme
                    }
                }
            }
        }

        return channels.map { ch ->
            val markedCh = if (channelIdentityCandidates(ch).any(favoriteIds::contains)) {
                ch.copy(isFavorite = true)
            } else {
                ch
            }

            if (epg == null) return@map EnrichedChannel(markedCh)

            val epgId = canonicalEpgChannelKey(
                playlistId = playlistId,
                channel = markedCh,
            )
            val current = epgId?.let(currentProgrammeByChannelId::get)
            val next = epgId?.let(nextProgrammeByChannelId::get)
            EnrichedChannel(markedCh, current, next)
        }
    }

    override suspend fun searchChannels(query: String): List<Channel> {
        val lowerQuery = query.lowercase()
        return getPlaylists()
            .flatMap { playlist -> getChannels(playlist.id) }
            .filter { ch ->
            ch.name.lowercase().contains(lowerQuery) ||
                ch.tvgName?.lowercase()?.contains(lowerQuery) == true ||
                ch.groupTitle?.lowercase()?.contains(lowerQuery) == true
            }
    }

    override suspend fun getEpg(playlistId: String): EpgData {
        val epg = epgCache[playlistId] ?: run {
            val generationId = getActiveEpgGeneration(playlistId) ?: return@run EpgData()
            val (windowStart, windowEnd) = resolveEpgWindowBounds()
            val dbEpg = loadEpgFromDatabase(playlistId, generationId, windowStart, windowEnd)
            // Cache the DB result so subsequent reads don't re-query and so the
            // ViewModel sees data in the cache on the next call (prevents spurious
            // network refresh when persisted EPG already exists).
            if (dbEpg.programmesByChannelKey.isNotEmpty()) {
                epgCache[playlistId] = dbEpg
            }
            dbEpg
        }
        println(
            "ChannelsEPG: cache read playlistId=$playlistId state=${getEpgLoadState(playlistId)} generation=${epg.generationId ?: -1} channels=${epg.channels.size} programmes=${epg.programmes.size} groupedKeys=${epg.programmesByChannelKey.size} lastError=${epgErrorCache[playlistId]}",
        )
        return epg
    }

    override suspend fun getEpgLoadError(playlistId: String): String? {
        return epgErrorCache[playlistId]
    }

    override suspend fun getProgrammes(channelId: String): List<EpgProgramme> {
        epgCache.values.firstNotNullOfOrNull { epg ->
            epg.programmesByChannelKey[channelId]
        }?.let { return it }

        val separatorIndex = channelId.indexOf("::")
        if (separatorIndex <= 0) return emptyList()
        val playlistId = channelId.substring(0, separatorIndex)
        val generationId = getActiveEpgGeneration(playlistId) ?: return emptyList()
        val (windowStart, windowEnd) = resolveEpgWindowBounds()
        return database.torveQueries
            .getEpgProgrammesForChannelWindowLimited(
                playlistId,
                generationId,
                channelId,
                windowStart,
                windowEnd,
                EPG_MAX_PROGRAMMES_PER_CHANNEL_IN_MEMORY.toLong(),
            )
            .executeAsList()
            .map { row ->
                EpgProgramme(
                    channelId = row.epg_channel_key,
                    startTime = row.start_time,
                    endTime = row.end_time,
                    title = row.title,
                    subTitle = null,
                    description = null,
                    category = null,
                    iconUrl = null,
                )
            }
            .take(EPG_MAX_PROGRAMMES_PER_CHANNEL_IN_MEMORY)
            .toList()
    }

    override suspend fun addFavorite(channel: Channel) {
        val now = Clock.System.now().toEpochMilliseconds()
        val channelId = stableChannelId(channel)
        database.torveQueries.insertFavorite(
            channel_id = channelId,
            playlist_id = channel.playlistId,
            name = channel.name,
            logo_url = channel.tvgLogo,
            group_title = channel.groupTitle,
            added_at = now,
        )
    }

    override suspend fun removeFavorite(channelId: String) {
        database.torveQueries.deleteFavorite(channelId)
    }

    override suspend fun getFavorites(): List<Channel> {
        return database.torveQueries.getAllFavorites().executeAsList().map { row ->
            val fullChannel = resolveChannelByStoredId(row.playlist_id, row.channel_id)
            fullChannel?.copy(isFavorite = true) ?: Channel(
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
        return database.torveQueries.isFavorite(channelId).executeAsOne() > 0
    }

    override suspend fun recordChannelViewed(channel: Channel) {
        val now = Clock.System.now().toEpochMilliseconds()
        val channelId = stableChannelId(channel)
        database.torveQueries.insertRecentChannel(
            channel_id = channelId,
            playlist_id = channel.playlistId,
            name = channel.name,
            logo_url = channel.tvgLogo,
            group_title = channel.groupTitle,
            stream_url = channel.url,
            viewed_at = now,
        )
    }

    override suspend fun getRecentlyViewedChannels(limit: Long): List<Channel> {
        return database.torveQueries.getRecentChannels(limit).executeAsList().map { row ->
            val fullChannel = resolveChannelByStoredId(row.playlist_id, row.channel_id)
            fullChannel ?: Channel(
                name = row.name,
                url = row.stream_url,
                tvgLogo = row.logo_url,
                groupTitle = row.group_title,
                playlistId = row.playlist_id,
            )
        }
    }

    override suspend fun clearRecentlyViewedChannels() {
        database.torveQueries.clearRecentChannels()
    }

    override suspend fun getChannelsByContentType(
        playlistId: String,
        type: ChannelContentType,
    ): List<EnrichedChannel> {
        val enriched = getEnrichedChannels(playlistId)
        return enriched.filter { it.channel.contentType == type }
    }

    private fun persistPlaylistSnapshot(
        playlistId: String,
        playlistName: String,
        playlistUrl: String,
        epgUrl: String?,
        playlistType: String,
        server: String?,
        username: String?,
        password: String?,
        channels: List<Channel>,
        updatedAt: Long,
    ) {
        val existingGeneration = getActiveChannelGeneration(playlistId)
        val existingChannels = if (existingGeneration != null) {
            loadChannelsFromGeneration(playlistId, existingGeneration)
        } else {
            emptyList()
        }
        if (!shouldAcceptIncomingChannelSnapshot(existingChannels.size, channels.size)) {
            channelDebugLog(
                "ChannelCatalog: rejected empty replacement playlistId=$playlistId existing=${existingChannels.size} incoming=${channels.size}",
            )
            return
        }

        val nextGeneration = nextChannelSnapshotGeneration(updatedAt, existingGeneration)
        setStagedChannelGeneration(playlistId, nextGeneration)
        database.transaction {
            channels.forEachIndexed { index, channel ->
                database.torveQueries.insertChannel(
                    playlist_id = playlistId,
                    generation_id = nextGeneration,
                    stable_id = stableChannelId(playlistId, channel),
                    sort_index = index.toLong(),
                    name = channel.name,
                    stream_url = channel.url,
                    tvg_id = channel.tvgId,
                    tvg_name = channel.tvgName,
                    logo_url = channel.tvgLogo,
                    group_title = channel.groupTitle,
                    tvg_language = channel.tvgLanguage,
                    tvg_country = channel.tvgCountry,
                    tvg_shift = channel.tvgShift?.toLong(),
                    channel_number = channel.channelNumber?.toLong(),
                    duration = channel.duration.toLong(),
                    catchup_type = channel.catchupType,
                    catchup_days = channel.catchupDays?.toLong(),
                    catchup_source = channel.catchupSource,
                    user_agent = channel.userAgent,
                    vlc_options = channel.vlcOptions.joinToString("\n"),
                    kodi_props = encodeKodiProps(channel.kodiProps),
                    content_type = channel.contentType.name,
                    updated_at = updatedAt,
                )
            }
            database.torveQueries.insertPlaylist(
                id = playlistId,
                name = playlistName,
                url = playlistUrl,
                epg_url = epgUrl,
                channel_count = channels.size.toLong(),
                last_updated = updatedAt,
                type = playlistType,
                server = server,
                username = username,
                password = password,
            )
            setActiveChannelGeneration(playlistId, nextGeneration)
            database.torveQueries.setPreference(channelLastSyncPrefKey(playlistId), updatedAt.toString())
            database.torveQueries.deleteChannelsOlderGenerations(playlistId, nextGeneration)
        }
        clearStagedChannelGeneration(playlistId)

        playlistCache[playlistId] = channels
        channelDebugLog(
            "ChannelCatalog: committed playlistId=$playlistId generation=$nextGeneration channels=${channels.size}",
        )
    }

    private fun loadChannelsFromDatabase(playlistId: String): List<Channel> {
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        println("ChannelCatalog: startup load playlistId=$playlistId activeGeneration=$generationId")
        return loadChannelsFromGeneration(playlistId, generationId)
    }

    private fun loadChannelsFromGeneration(
        playlistId: String,
        generationId: Long,
    ): List<Channel> {
        return database.torveQueries
            .getChannelsForPlaylistGeneration(playlistId, generationId)
            .executeAsList()
            .map { row ->
                Channel(
                    name = row.name,
                    url = row.stream_url,
                    tvgId = row.tvg_id,
                    tvgName = row.tvg_name,
                    tvgLogo = row.logo_url,
                    groupTitle = row.group_title,
                    tvgLanguage = row.tvg_language,
                    tvgCountry = row.tvg_country,
                    tvgShift = row.tvg_shift?.toInt(),
                    channelNumber = row.channel_number?.toInt(),
                    duration = row.duration.toInt(),
                    catchupType = row.catchup_type,
                    catchupDays = row.catchup_days?.toInt(),
                    catchupSource = row.catchup_source,
                    userAgent = row.user_agent,
                    vlcOptions = decodeVlcOptions(row.vlc_options),
                    kodiProps = decodeKodiProps(row.kodi_props),
                    playlistId = playlistId,
                    contentType = parseContentType(row.content_type),
                )
            }
    }

    private fun resolveChannelByStoredId(
        playlistId: String,
        storedId: String,
    ): Channel? {
        val cached = playlistCache[playlistId]
        if (cached != null) {
            cached.firstOrNull { channelMatchesIdentity(it, storedId) }?.let { return it }
        }
        val localChannels = loadChannelsFromDatabase(playlistId)
        if (localChannels.isNotEmpty()) {
            playlistCache[playlistId] = localChannels
        }
        return localChannels.firstOrNull { channelMatchesIdentity(it, storedId) }
    }

    private fun parseContentType(value: String): ChannelContentType {
        return runCatching { ChannelContentType.valueOf(value.uppercase()) }
            .getOrDefault(ChannelContentType.UNKNOWN)
    }

    private fun decodeVlcOptions(value: String): List<String> {
        return value
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun encodeKodiProps(value: Map<String, String>): String {
        if (value.isEmpty()) return ""
        return value.entries.joinToString("\n") { (key, entryValue) ->
            "${key.replace("=", "\\=")}=${entryValue.replace("\n", " ")}"
        }
    }

    private fun decodeKodiProps(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        return buildMap {
            value.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).replace("\\=", "=")
                    val entryValue = line.substring(idx + 1)
                    put(key, entryValue)
                }
        }
    }

    private suspend fun refreshEpgForPlaylist(playlistId: String, sourceUrl: String?, hiddenChannelIds: Set<String> = emptySet()) {
        val normalizedUrl = sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedUrl == null) {
            epgCache.remove(playlistId)
            epgErrorCache[playlistId] = null
            epgProgressCache.remove(playlistId)
            setEpgLoadState(playlistId, EPG_STATE_IDLE)
            println("ChannelsEPG: config missing playlistId=$playlistId source=none")
            return
        }

        setEpgLoadState(playlistId, EPG_STATE_LOADING)
        val parsedEpg = fetchAndParseEpg(playlistId, normalizedUrl, hiddenChannelIds)
        if (parsedEpg != null) {
            epgCache[playlistId] = parsedEpg
            epgErrorCache[playlistId] = null
            setEpgLoadState(playlistId, EPG_STATE_READY)
            println(
                "ChannelsEPG: parse success playlistId=$playlistId source=$normalizedUrl generation=${parsedEpg.generationId ?: -1} channels=${parsedEpg.channels.size} programmes=${parsedEpg.programmes.size} groupedKeys=${parsedEpg.programmesByChannelKey.size}",
            )
        } else {
            setEpgLoadState(playlistId, EPG_STATE_ERROR)
        }
    }

    private suspend fun fetchAndParseEpg(playlistId: String, sourceUrl: String, hiddenChannelIds: Set<String> = emptySet()): EpgData? = withContext(Dispatchers.IO) {
        var lastError: String? = null
        var inFlightGeneration: Long? = null

        for (attempt in 1..EPG_MAX_FETCH_ATTEMPTS) {
            try {
                println(
                    "ChannelsEPG: fetch start playlistId=$playlistId source=$sourceUrl attempt=$attempt/$EPG_MAX_FETCH_ATTEMPTS requestTimeoutMs=$EPG_REQUEST_TIMEOUT_MS",
                )
                var failedStatusCode: Int? = null
                val result: EpgData? = epgHttpClient.prepareGet(sourceUrl) {
                    timeout {
                        connectTimeoutMillis = EPG_CONNECT_TIMEOUT_MS
                        requestTimeoutMillis = EPG_REQUEST_TIMEOUT_MS
                        socketTimeoutMillis = EPG_SOCKET_TIMEOUT_MS
                    }
                }.execute { response ->
                    println(
                        "ChannelsEPG: got response object playlistId=$playlistId status=${response.status.value}",
                    )
                    val statusCode = response.status.value
                    val contentType = response.headers["Content-Type"].orEmpty()
                    val contentEncoding = response.headers["Content-Encoding"].orEmpty()
                    val contentLength = response.headers["Content-Length"]?.toLongOrNull()

                    if (!response.status.isSuccess()) {
                        failedStatusCode = statusCode
                        lastError = "EPG request failed ($statusCode)"
                        return@execute null
                    }
                    if (contentLength != null && contentLength > EPG_MAX_DOWNLOAD_BYTES) {
                        val mb = contentLength / (1024L * 1024L)
                        epgErrorCache[playlistId] = "EPG too large (${mb}MB). Reduce provider EPG days."
                        println(
                            "ChannelsEPG: content length guard playlistId=$playlistId source=$sourceUrl contentLength=$contentLength",
                        )
                        return@execute null
                    }

                    val (windowStart, windowEnd) = resolveEpgWindowBounds()
                    val nextGeneration = Clock.System.now().toEpochMilliseconds()
                    val channelMapping = buildEpgChannelMapping(playlistId, hiddenChannelIds)
                    inFlightGeneration = nextGeneration
                    val ingestStartedAt = Clock.System.now().toEpochMilliseconds()
                    var tempFilePath: String? = null

                    println(
                        "ChannelsEPG: fetch response playlistId=$playlistId source=$sourceUrl attempt=$attempt status=$statusCode contentType=$contentType contentEncoding=$contentEncoding contentLength=${contentLength ?: -1}",
                    )
                    try {
                        val downloadResult = GzipSupport.downloadToTempFile(
                            response = response,
                            maxCompressedBytes = EPG_MAX_DOWNLOAD_BYTES,
                        )
                        if (downloadResult == null) {
                            clearEpgGenerationRows(playlistId, nextGeneration)
                            epgErrorCache[playlistId] = "EPG download failed."
                            return@execute null
                        }
                        tempFilePath = downloadResult.tempFilePath

                        val ingestResult = GzipSupport.parseXmlTvAutoFromFileToDbOrNull(
                            tempFilePath = downloadResult.tempFilePath,
                            parser = epgParser,
                            db = database,
                            playlistId = playlistId,
                            generationId = nextGeneration,
                            windowStartMs = windowStart,
                            windowEndMs = windowEnd,
                            contentEncoding = downloadResult.contentEncoding.ifBlank { contentEncoding },
                            contentLength = downloadResult.contentLength ?: contentLength,
                        maxUncompressedBytes = EPG_MAX_UNCOMPRESSED_PARSE_BYTES,
                        channelFilter = channelMapping.allowedCanonicalKeys.takeIf { it.isNotEmpty() },
                        resolveEpgChannelKey = { xmltvId, xmltvDisplayName ->
                            channelMapping.byXmltvId[xmltvId.trim()]
                                ?: channelMapping.byNormalizedName[normalizeEpgMatchKey(xmltvId)]
                                ?: channelMapping.byNormalizedName[normalizeEpgMatchKey(xmltvDisplayName)]
                        },
                        batchSize = 75,
                        maxProgrammesPerChannel = EPG_MAX_PROGRAMMES_PER_CHANNEL_INGEST,
                        maxProgrammesTotal = EPG_MAX_PROGRAMMES_TOTAL_INGEST,
                        onProgress = { progress ->
                            epgProgressCache[playlistId] = progress
                            println(
                                "ChannelsEPG: db ingest progress playlistId=$playlistId totalSeen=${progress.totalSeen} kept=${progress.kept} skippedByWindow=${progress.skippedByWindow} skippedByChannelFilter=${progress.skippedByChannelFilter} skippedByInvalidTime=${progress.skippedByInvalidTime} skippedByNoMapping=${progress.skippedByNoMapping} skippedByCap=${progress.skippedByCap} batches=${progress.batchesCommitted} heapUsedMb=${progress.heapUsedMb} heapFreeMb=${progress.heapFreeMb}",
                                )
                            },
                        )

                        if (ingestResult == null) {
                            clearEpgGenerationRows(playlistId, nextGeneration)
                            epgErrorCache[playlistId] = "EPG XML data could not be parsed."
                            return@execute null
                        }
                        val stats = ingestResult.stats
                        val ingestDurationMs = Clock.System.now().toEpochMilliseconds() - ingestStartedAt
                        println(
                            "ChannelsEPG: ingest transport playlistId=$playlistId generation=$nextGeneration contentLength=${downloadResult.contentLength ?: contentLength ?: -1} usedTempFile=true bytesDownloaded=${downloadResult.bytesDownloaded} gzipDetected=${ingestResult.isGzipDetected} bytesParsed=${ingestResult.bytesParsed} durationMs=$ingestDurationMs",
                        )
                        if (stats.abortedByGlobalCap) {
                            clearEpgGenerationRows(playlistId, nextGeneration)
                            epgErrorCache[playlistId] = "EPG too large. Reduce EPG days or guide window."
                            println(
                                "ChannelsEPG: db ingest aborted playlistId=$playlistId generation=$nextGeneration totalSeen=${stats.totalProgrammesSeen} kept=${stats.programmesKept} skippedByCap=${stats.programmesSkippedByCap}",
                            )
                            return@execute null
                        }
                        println(
                            "ChannelsEPG: db ingest complete playlistId=$playlistId generation=$nextGeneration totalSeen=${stats.totalProgrammesSeen} kept=${stats.programmesKept} skippedByWindow=${stats.programmesSkippedByWindow} skippedByChannelFilter=${stats.programmesSkippedByChannelFilter} skippedByInvalidTime=${stats.programmesSkippedByInvalidTime} skippedByNoMapping=${stats.programmesSkippedByNoMapping} skippedByCap=${stats.programmesSkippedByCap} durationMs=${stats.parseDurationMs}",
                        )
                        setActiveEpgGeneration(playlistId, nextGeneration)
                        database.torveQueries.deleteEpgProgrammesOlderGenerations(playlistId, nextGeneration)
                        database.torveQueries.deleteEpgChannelsOlderGenerations(playlistId, nextGeneration)
                        inFlightGeneration = null
                        loadEpgFromDatabase(playlistId, nextGeneration, windowStart, windowEnd)
                    } finally {
                        tempFilePath?.let { GzipSupport.deleteTempFile(it) }
                    }
                }

                if (failedStatusCode != null) {
                    val statusCode = failedStatusCode ?: 0
                    if (attempt < EPG_MAX_FETCH_ATTEMPTS && shouldRetryEpgStatus(statusCode)) {
                        val retryDelayMs = epgRetryDelayMs(attempt)
                        println(
                            "ChannelsEPG: retry scheduled playlistId=$playlistId source=$sourceUrl attempt=$attempt waitMs=$retryDelayMs",
                        )
                        delay(retryDelayMs)
                        continue
                    }
                    epgErrorCache[playlistId] = lastError
                    return@withContext null
                }

                if (result != null) {
                    return@withContext result
                }
                return@withContext null
            } catch (oom: OutOfMemoryError) {
                val message = "EPG too large. Reduce EPG days or guide window."
                epgErrorCache[playlistId] = message
                println(
                    "ChannelsEPG: oom playlistId=$playlistId source=$sourceUrl attempt=$attempt error=${oom.message}",
                )
                inFlightGeneration?.let { generation ->
                    clearEpgGenerationRows(playlistId, generation)
                }
                return@withContext null
            } catch (e: Exception) {
                inFlightGeneration?.let { generation ->
                    clearEpgGenerationRows(playlistId, generation)
                }
                inFlightGeneration = null
                lastError = e.message ?: e::class.simpleName ?: "Failed to fetch EPG"
                val isTimeout = lastError.contains("timeout", ignoreCase = true)
                println(
                    "ChannelsEPG: fetch failed playlistId=$playlistId source=$sourceUrl attempt=$attempt error=$lastError",
                )
                if (attempt < EPG_MAX_FETCH_ATTEMPTS && isTimeout) {
                    val retryDelayMs = epgRetryDelayMs(attempt)
                    println(
                        "ChannelsEPG: timeout retry scheduled playlistId=$playlistId source=$sourceUrl attempt=$attempt waitMs=$retryDelayMs",
                    )
                    delay(retryDelayMs)
                    continue
                }
                epgErrorCache[playlistId] = if (isTimeout) {
                    "EPG request timed out. Check XMLTV URL/provider and retry."
                } else {
                    lastError
                }
                return@withContext null
            }
        }

        epgErrorCache[playlistId] = lastError ?: "Failed to fetch EPG"
        return@withContext null
    }

    private fun buildXtreamEpgUrl(server: String, username: String, password: String): String {
        val base = server.trim().trimEnd('/')
        val encodedUsername = username.encodeURLParameter()
        val encodedPassword = password.encodeURLParameter()
        return "$base/xmltv.php?username=$encodedUsername&password=$encodedPassword"
    }

    private fun shouldRetryEpgStatus(statusCode: Int): Boolean {
        return statusCode == 408 || statusCode == 429 || statusCode in 500..599
    }

    private fun epgRetryDelayMs(attempt: Int): Long {
        return when (attempt) {
            1 -> 1_500L
            2 -> 3_000L
            else -> 4_500L
        }
    }

    private fun resolveEpgWindowBounds(): Pair<Long, Long> {
        val hoursAhead = readWindowHoursPreference(
            key = PREF_EPG_WINDOW_HOURS_AHEAD,
            default = DEFAULT_EPG_WINDOW_HOURS_AHEAD,
            min = 1,
            max = MAX_EPG_WINDOW_HOURS,
        )
        val hoursBehind = readWindowHoursPreference(
            key = PREF_EPG_WINDOW_HOURS_BEHIND,
            default = DEFAULT_EPG_WINDOW_HOURS_BEHIND,
            min = 0,
            max = MAX_EPG_WINDOW_HOURS,
        )
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val start = nowMs - (hoursBehind * 60L * 60L * 1000L)
        val end = nowMs + (hoursAhead * 60L * 60L * 1000L)
        return start to end
    }

    private fun readWindowHoursPreference(
        key: String,
        default: Int,
        min: Int,
        max: Int,
    ): Int {
        val raw = database.torveQueries.getPreference(key).executeAsOneOrNull()
        return raw?.toIntOrNull()?.coerceIn(min, max) ?: default
    }

    private fun epgLoadStatePrefKey(playlistId: String): String = "$PREF_EPG_LOAD_STATE_PREFIX$playlistId"

    private fun epgActiveGenerationPrefKey(playlistId: String): String = "$PREF_EPG_ACTIVE_GENERATION_PREFIX$playlistId"

    private fun channelActiveGenerationPrefKey(playlistId: String): String = "$PREF_CHANNEL_ACTIVE_GENERATION_PREFIX$playlistId"

    private fun channelLastSyncPrefKey(playlistId: String): String = "$PREF_CHANNEL_LAST_SYNC_PREFIX$playlistId"

    private fun channelStagedGenerationPrefKey(playlistId: String): String = "$PREF_CHANNEL_STAGED_GENERATION_PREFIX$playlistId"

    private fun setEpgLoadState(playlistId: String, state: String) {
        database.torveQueries.setPreference(epgLoadStatePrefKey(playlistId), state)
    }

    private fun getEpgLoadState(playlistId: String): String {
        return database.torveQueries.getPreference(epgLoadStatePrefKey(playlistId))
            .executeAsOneOrNull()
            ?: EPG_STATE_IDLE
    }

    private fun setActiveEpgGeneration(playlistId: String, generationId: Long) {
        database.torveQueries.setPreference(epgActiveGenerationPrefKey(playlistId), generationId.toString())
    }

    private fun getActiveEpgGeneration(playlistId: String): Long? {
        return database.torveQueries.getPreference(epgActiveGenerationPrefKey(playlistId))
            .executeAsOneOrNull()
            ?.toLongOrNull()
    }

    private fun setActiveChannelGeneration(playlistId: String, generationId: Long) {
        database.torveQueries.setPreference(channelActiveGenerationPrefKey(playlistId), generationId.toString())
    }

    private fun getActiveChannelGeneration(playlistId: String): Long? {
        return database.torveQueries.getPreference(channelActiveGenerationPrefKey(playlistId))
            .executeAsOneOrNull()
            ?.toLongOrNull()
    }

    private fun setStagedChannelGeneration(playlistId: String, generationId: Long) {
        database.torveQueries.setPreference(channelStagedGenerationPrefKey(playlistId), generationId.toString())
    }

    private fun getStagedChannelGeneration(playlistId: String): Long? {
        return database.torveQueries.getPreference(channelStagedGenerationPrefKey(playlistId))
            .executeAsOneOrNull()
            ?.toLongOrNull()
    }

    private fun clearStagedChannelGeneration(playlistId: String) {
        database.torveQueries.deletePreference(channelStagedGenerationPrefKey(playlistId))
    }

    private fun repairChannelCatalogIfNeeded(playlistId: String) {
        val recovery = planChannelCatalogRecovery(
            activeGeneration = getActiveChannelGeneration(playlistId),
            stagedGeneration = getStagedChannelGeneration(playlistId),
        )
        recovery.staleGenerationToDelete?.let { generationId ->
            database.torveQueries.clearChannelsForPlaylistGeneration(playlistId, generationId)
            playlistCache.remove(playlistId)
            channelDebugLog(
                "ChannelCatalog: discarded interrupted staged generation playlistId=$playlistId generation=$generationId fallback=${recovery.fallbackActiveGeneration}",
            )
            println(
                "ChannelCatalog: repaired staged generation playlistId=$playlistId " +
                    "discardedGeneration=$generationId fallbackActive=${recovery.fallbackActiveGeneration ?: -1}",
            )
        }
        if (recovery.clearStagedGeneration) {
            clearStagedChannelGeneration(playlistId)
        }
    }

    private fun buildEpgChannelMapping(playlistId: String, hiddenChannelIds: Set<String> = emptySet()): EpgChannelMapping {
        val byXmltvId = mutableMapOf<String, String>()
        val byNormalizedName = mutableMapOf<String, String>()
        val allowedKeys = mutableSetOf<String>()
        var skippedHidden = 0

        val channels = playlistCache[playlistId] ?: loadChannelsFromDatabase(playlistId).also { persisted ->
            if (persisted.isNotEmpty()) {
                playlistCache[playlistId] = persisted
            }
        }
        channels.forEach { channel ->
            // Skip hidden channels — their EPG data won't be ingested.
            if (hiddenChannelIds.isNotEmpty() &&
                channelIdentityCandidates(channel).any(hiddenChannelIds::contains)
            ) {
                skippedHidden++
                return@forEach
            }

            val canonical = canonicalEpgChannelKey(
                playlistId = playlistId,
                channel = channel,
            ) ?: return@forEach
            allowedKeys += canonical

            channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { tvgId ->
                byXmltvId.putIfAbsent(tvgId, canonical)
                byNormalizedName.putIfAbsent(normalizeEpgMatchKey(tvgId), canonical)
            }
            channel.tvgName?.trim()?.takeIf { it.isNotEmpty() }?.let { tvgName ->
                byNormalizedName.putIfAbsent(normalizeEpgMatchKey(tvgName), canonical)
            }
            channel.name.trim().takeIf { it.isNotEmpty() }?.let { name ->
                byNormalizedName.putIfAbsent(normalizeEpgMatchKey(name), canonical)
            }
        }

        if (skippedHidden > 0) {
            println("ChannelsEPG: buildEpgChannelMapping skippedHidden=$skippedHidden allowedKeys=${allowedKeys.size}")
        }

        return EpgChannelMapping(
            byXmltvId = byXmltvId,
            byNormalizedName = byNormalizedName,
            allowedCanonicalKeys = allowedKeys,
        )
    }

    private fun loadEpgFromDatabase(
        playlistId: String,
        generationId: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): EpgData {
        val startedAtMs = Clock.System.now().toEpochMilliseconds()
        val channelRows = database.torveQueries
            .getEpgChannelsForPlaylistGeneration(playlistId, generationId)
            .executeAsList()
        val channelsByKey = LinkedHashMap<String, EpgChannel>(channelRows.size)
        channelRows.forEach { row ->
            val channelKey = row.epg_channel_key.trim()
            if (channelKey.isBlank()) return@forEach
            channelsByKey[channelKey] = EpgChannel(
                id = row.xmltv_channel_id?.trim()?.ifEmpty { channelKey } ?: channelKey,
                displayName = row.display_name,
                iconUrl = row.icon_url,
            )
        }

        val programmeRows = database.torveQueries
            .getEpgProgrammesForPlaylistWindowLimited(
                playlistId,
                generationId,
                windowStartMs,
                windowEndMs,
                EPG_MAX_PROGRAMMES_TOTAL_IN_MEMORY.toLong(),
            )
            .executeAsList()
        val programmes = ArrayList<EpgProgramme>(programmeRows.size)
        val groupedMutable = LinkedHashMap<String, MutableList<EpgProgramme>>()
        val includedChannelKeys = LinkedHashSet<String>()

        var currentKey: String? = null
        var currentList: MutableList<EpgProgramme>? = null
        var skippedByPerChannelCap = 0
        var skippedByChannelCap = 0

        programmeRows.forEach { row ->
            val channelKey = row.epg_channel_key.trim()
            if (channelKey.isBlank()) return@forEach

            if (channelKey !in includedChannelKeys && includedChannelKeys.size >= EPG_MAX_CHANNELS_IN_MEMORY) {
                skippedByChannelCap++
                return@forEach
            }

            if (currentKey != channelKey) {
                currentKey = channelKey
                currentList = groupedMutable.getOrPut(channelKey) { mutableListOf() }
                includedChannelKeys += channelKey
            }
            val bucket = currentList ?: return@forEach
            if (bucket.size >= EPG_MAX_PROGRAMMES_PER_CHANNEL_IN_MEMORY) {
                skippedByPerChannelCap++
                return@forEach
            }

            val programme = EpgProgramme(
                channelId = channelKey,
                startTime = row.start_time,
                endTime = row.end_time,
                title = row.title,
                subTitle = null,
                description = null,
                category = null,
                iconUrl = null,
            )
            programmes += programme
            bucket += programme
        }

        val programmesByKey = LinkedHashMap<String, List<EpgProgramme>>(groupedMutable.size)
        groupedMutable.forEach { (key, value) ->
            programmesByKey[key] = value
        }

        val durationMs = Clock.System.now().toEpochMilliseconds() - startedAtMs
        debugLog(
            "ChannelsEPG: db load playlistId=$playlistId generation=$generationId channels=${channelsByKey.size} programmeRows=${programmeRows.size} groupedKeys=${programmesByKey.size} skippedByPerChannelCap=$skippedByPerChannelCap skippedByChannelCap=$skippedByChannelCap durationMs=$durationMs",
        )

        return EpgData(
            channels = channelsByKey,
            programmes = programmes,
            programmesByChannelKey = programmesByKey,
            generationId = generationId,
        )
    }

    private fun normalizeEpgMatchKey(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val out = StringBuilder(trimmed.length)
        trimmed.forEach { ch ->
            val lowered = when {
                ch in 'A'..'Z' -> (ch.code + 32).toChar()
                else -> ch
            }
            if ((lowered in 'a'..'z') || (lowered in '0'..'9')) {
                out.append(lowered)
            }
        }
        return out.toString()
    }

    private fun clearEpgGenerationRows(playlistId: String, generationId: Long) {
        database.torveQueries.clearEpgProgrammesForPlaylistGeneration(playlistId, generationId)
        database.torveQueries.clearEpgChannelsForPlaylistGeneration(playlistId, generationId)
    }

    private fun debugLog(message: String) {
        if (EPG_DEBUG_LOG_ENABLED) {
            println(message)
        }
    }

    private fun channelDebugLog(message: String) {
        if (CHANNEL_DEBUG_LOG_ENABLED) {
            println(message)
        }
    }
}
