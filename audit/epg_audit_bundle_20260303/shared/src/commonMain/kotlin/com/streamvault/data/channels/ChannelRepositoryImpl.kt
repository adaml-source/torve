package com.streamvault.data.channels

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgData
import com.streamvault.domain.model.EpgChannel
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelContentType
import com.streamvault.domain.model.ChannelPlaylist
import com.streamvault.domain.model.PlaylistType
import com.streamvault.domain.model.canonicalEpgChannelKey
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.data.network.HttpClientFactory
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
    private val database: StreamVaultDatabase,
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
        private const val PREF_EPG_WINDOW_HOURS_AHEAD = "epg_window_hours_ahead"
        private const val PREF_EPG_WINDOW_HOURS_BEHIND = "epg_window_hours_behind"
        private const val PREF_EPG_LOAD_STATE_PREFIX = "epg_load_state_"
        private const val PREF_EPG_ACTIVE_GENERATION_PREFIX = "epg_active_generation_"
        private const val DEFAULT_EPG_WINDOW_HOURS_AHEAD = 6
        private const val DEFAULT_EPG_WINDOW_HOURS_BEHIND = 1
        private const val MAX_EPG_WINDOW_HOURS = 18
        private const val EPG_STATE_IDLE = "IDLE"
        private const val EPG_STATE_LOADING = "LOADING"
        private const val EPG_STATE_READY = "READY"
        private const val EPG_STATE_ERROR = "ERROR"
        private const val EPG_DEBUG_LOG_ENABLED = false
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
        playlistCache[id] = allChannels

        database.streamVaultQueries.insertPlaylist(
            id = id,
            name = name,
            url = "$server/player_api.php",
            epg_url = xtreamEpgUrl,
            channel_count = allChannels.size.toLong(),
            last_updated = now,
            type = "xtream",
            server = server,
            username = username,
            password = password,
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
        database.streamVaultQueries.deletePlaylist(id)
        playlistCache.remove(id)
        epgCache.remove(id)
        epgErrorCache.remove(id)
        epgProgressCache.remove(id)
        database.streamVaultQueries.deletePreference(epgLoadStatePrefKey(id))
        database.streamVaultQueries.deletePreference(epgActiveGenerationPrefKey(id))
    }

    override suspend fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String?) {
        val playlist = database.streamVaultQueries.getPlaylist(playlistId).executeAsOneOrNull()
            ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        val normalizedEpg = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        database.streamVaultQueries.updatePlaylistEpgUrl(
            epg_url = normalizedEpg,
            last_updated = now,
            id = playlistId,
        )
        epgCache.remove(playlistId)
        refreshEpgForPlaylist(playlistId, normalizedEpg)

        database.streamVaultQueries.insertPlaylist(
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
        return database.streamVaultQueries.getAllPlaylists().executeAsList().map { row ->
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
            val xtreamEpgUrl = playlist.epg_url ?: buildXtreamEpgUrl(
                server = playlist.server,
                username = playlist.username,
                password = playlist.password,
            )

            val allChannels = channels + vodChannels
            playlistCache[playlistId] = allChannels

            database.streamVaultQueries.insertPlaylist(
                id = playlist.id,
                name = playlist.name,
                url = playlist.url,
                epg_url = xtreamEpgUrl,
                channel_count = allChannels.size.toLong(),
                last_updated = now,
                type = "xtream",
                server = playlist.server,
                username = playlist.username,
                password = playlist.password,
            )
            refreshEpgForPlaylist(playlistId, xtreamEpgUrl)
        } else {
            // M3U playlist refresh
            val m3uContent = httpClient.get(playlist.url).bodyAsText()
            val parsed = m3uParser.parse(m3uContent, playlistId)
            playlistCache[playlistId] = parsed.channels
            val resolvedEpgUrl = playlist.epg_url ?: parsed.epgUrl

            database.streamVaultQueries.insertPlaylist(
                id = playlist.id,
                name = playlist.name,
                url = playlist.url,
                epg_url = resolvedEpgUrl,
                channel_count = parsed.channels.size.toLong(),
                last_updated = now,
                type = "m3u",
                server = null,
                username = null,
                password = null,
            )

            // Refresh EPG
            refreshEpgForPlaylist(playlistId, resolvedEpgUrl)
        }
    }

    override suspend fun refreshEpg(playlistId: String) {
        val playlist = database.streamVaultQueries.getPlaylist(playlistId).executeAsOneOrNull()
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
        refreshEpgForPlaylist(playlistId, sourceUrl)
    }

    override suspend fun getChannels(playlistId: String): List<Channel> {
        return playlistCache[playlistId] ?: run {
            refreshPlaylist(playlistId)
            playlistCache[playlistId] ?: emptyList()
        }
    }

    override suspend fun getChannelsByGroup(playlistId: String): Map<String, List<Channel>> {
        return getChannels(playlistId).groupBy { it.groupTitle ?: "Ungrouped" }
    }

    override suspend fun getEnrichedChannels(playlistId: String): List<EnrichedChannel> {
        val channels = getChannels(playlistId)

        // Load favorite IDs to mark channels
        val favoriteIds = database.streamVaultQueries.getAllFavorites().executeAsList()
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
            val channelId = ch.tvgId ?: "${ch.playlistId}_${ch.name}"
            val markedCh = if (channelId in favoriteIds) ch.copy(isFavorite = true) else ch

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
        return playlistCache.values.flatten().filter { ch ->
            ch.name.lowercase().contains(lowerQuery) ||
                ch.tvgName?.lowercase()?.contains(lowerQuery) == true ||
                ch.groupTitle?.lowercase()?.contains(lowerQuery) == true
        }
    }

    override suspend fun getEpg(playlistId: String): EpgData {
        val epg = epgCache[playlistId] ?: run {
            val generationId = getActiveEpgGeneration(playlistId) ?: return@run EpgData()
            val (windowStart, windowEnd) = resolveEpgWindowBounds()
            loadEpgFromDatabase(playlistId, generationId, windowStart, windowEnd)
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
        return epgCache.values.firstNotNullOfOrNull { epg ->
            epg.programmesByChannelKey[channelId]
        }.orEmpty()
    }

    override suspend fun addFavorite(channel: Channel) {
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

    override suspend fun getFavorites(): List<Channel> {
        return database.streamVaultQueries.getAllFavorites().executeAsList().map { row ->
            // Find the full channel from cache
            val fullChannel = playlistCache.values.flatten().find { ch ->
                (ch.tvgId ?: "${ch.playlistId}_${ch.name}") == row.channel_id
            }
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
        return database.streamVaultQueries.isFavorite(channelId).executeAsOne() > 0
    }

    override suspend fun recordChannelViewed(channel: Channel) {
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

    override suspend fun getRecentlyViewedChannels(limit: Long): List<Channel> {
        return database.streamVaultQueries.getRecentChannels(limit).executeAsList().map { row ->
            // Try to find full channel from cache for complete metadata
            val fullChannel = playlistCache.values.flatten().find { ch ->
                (ch.tvgId ?: "${ch.playlistId}_${ch.name}") == row.channel_id
            }
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
        database.streamVaultQueries.clearRecentChannels()
    }

    override suspend fun getChannelsByContentType(
        playlistId: String,
        type: ChannelContentType,
    ): List<EnrichedChannel> {
        val enriched = getEnrichedChannels(playlistId)
        return enriched.filter { it.channel.contentType == type }
    }

    private suspend fun refreshEpgForPlaylist(playlistId: String, sourceUrl: String?) {
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
        val parsedEpg = fetchAndParseEpg(playlistId, normalizedUrl)
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

    private suspend fun fetchAndParseEpg(playlistId: String, sourceUrl: String): EpgData? = withContext(Dispatchers.IO) {
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
                    val channelMapping = buildEpgChannelMapping(playlistId)
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
                        database.streamVaultQueries.deleteEpgProgrammesOlderGenerations(playlistId, nextGeneration)
                        database.streamVaultQueries.deleteEpgChannelsOlderGenerations(playlistId, nextGeneration)
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
        val raw = database.streamVaultQueries.getPreference(key).executeAsOneOrNull()
        return raw?.toIntOrNull()?.coerceIn(min, max) ?: default
    }

    private fun epgLoadStatePrefKey(playlistId: String): String = "$PREF_EPG_LOAD_STATE_PREFIX$playlistId"

    private fun epgActiveGenerationPrefKey(playlistId: String): String = "$PREF_EPG_ACTIVE_GENERATION_PREFIX$playlistId"

    private fun setEpgLoadState(playlistId: String, state: String) {
        database.streamVaultQueries.setPreference(epgLoadStatePrefKey(playlistId), state)
    }

    private fun getEpgLoadState(playlistId: String): String {
        return database.streamVaultQueries.getPreference(epgLoadStatePrefKey(playlistId))
            .executeAsOneOrNull()
            ?: EPG_STATE_IDLE
    }

    private fun setActiveEpgGeneration(playlistId: String, generationId: Long) {
        database.streamVaultQueries.setPreference(epgActiveGenerationPrefKey(playlistId), generationId.toString())
    }

    private fun getActiveEpgGeneration(playlistId: String): Long? {
        return database.streamVaultQueries.getPreference(epgActiveGenerationPrefKey(playlistId))
            .executeAsOneOrNull()
            ?.toLongOrNull()
    }

    private fun buildEpgChannelMapping(playlistId: String): EpgChannelMapping {
        val byXmltvId = mutableMapOf<String, String>()
        val byNormalizedName = mutableMapOf<String, String>()
        val allowedKeys = mutableSetOf<String>()

        playlistCache[playlistId].orEmpty().forEach { channel ->
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
        val channelRows = database.streamVaultQueries
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

        val programmeRows = database.streamVaultQueries
            .getEpgProgrammesForPlaylistWindow(
                playlistId,
                generationId,
                windowStartMs,
                windowEndMs,
            )
            .executeAsList()
        val programmes = ArrayList<EpgProgramme>(programmeRows.size)
        val groupedMutable = LinkedHashMap<String, MutableList<EpgProgramme>>()

        var currentKey: String? = null
        var currentList: MutableList<EpgProgramme>? = null
        var skippedByPerChannelCap = 0

        programmeRows.forEach { row ->
            val channelKey = row.epg_channel_key.trim()
            if (channelKey.isBlank()) return@forEach

            if (currentKey != channelKey) {
                currentKey = channelKey
                currentList = groupedMutable.getOrPut(channelKey) { mutableListOf() }
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
            "ChannelsEPG: db load playlistId=$playlistId generation=$generationId channels=${channelsByKey.size} programmeRows=${programmeRows.size} groupedKeys=${programmesByKey.size} skippedByPerChannelCap=$skippedByPerChannelCap durationMs=$durationMs",
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
        database.streamVaultQueries.clearEpgProgrammesForPlaylistGeneration(playlistId, generationId)
        database.streamVaultQueries.clearEpgChannelsForPlaylistGeneration(playlistId, generationId)
    }

    private fun debugLog(message: String) {
        if (EPG_DEBUG_LOG_ENABLED) {
            println(message)
        }
    }
}
