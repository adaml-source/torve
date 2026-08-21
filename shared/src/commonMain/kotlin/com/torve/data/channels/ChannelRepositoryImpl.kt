package com.torve.data.channels

import com.torve.data.auth.UserIdProvider
import com.torve.db.Iptv_channel
import com.torve.db.Iptv_playlist
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
import com.torve.domain.model.epgChannelLookupKeys
import com.torve.domain.model.stableChannelId
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.ChannelPlaylistSummary
import com.torve.domain.repository.PlaylistAddProgress
import com.torve.domain.repository.VodCategoryTypeCount
import com.torve.data.network.HttpClientFactory
import com.torve.platform.torveVerboseLog
import com.torve.util.ioDispatcher
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.datetime.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

class ChannelRepositoryImpl(
    private val database: TorveDatabase,
    private val httpClient: HttpClient,
    private val m3uParser: M3uParser,
    private val epgParser: EpgParser,
    private val xtreamClient: XtreamClient,
    private val secureStorage: com.torve.domain.security.SecureStorage,
    private val userIdProvider: UserIdProvider,
    private val sqlDriver: SqlDriver,
) : ChannelRepository {

    private fun uid(): String = userIdProvider.currentUserId()

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
        private const val PREF_CHANNEL_SNAPSHOT_SIGNATURE_PREFIX = "channel_snapshot_signature_"
        private const val DEFAULT_EPG_WINDOW_HOURS_AHEAD = 6
        private const val DEFAULT_EPG_WINDOW_HOURS_BEHIND = 1
        private const val MAX_EPG_WINDOW_HOURS = 18
        private const val EPG_STATE_IDLE = "IDLE"
        private const val EPG_STATE_LOADING = "LOADING"
        private const val EPG_STATE_READY = "READY"
        private const val EPG_STATE_ERROR = "ERROR"
        private const val EPG_DEBUG_LOG_ENABLED = false
        private const val CHANNEL_DEBUG_LOG_ENABLED = false
        private const val PLAYLIST_CACHE_MAX_CHANNELS = 10_000
        private const val CHANNEL_INSERT_BIND_COUNT = 24
        private const val CHANNEL_INSERT_BATCH_SIZE = 40
        private const val BULK_CHANNEL_INSERT_QUERY_ID = 1_947_204_611
        private const val M3U_MAX_BODY_BYTES = 32 * 1024 * 1024
        private const val M3U_INITIAL_BODY_BUFFER_BYTES = 256 * 1024
        private const val M3U_READ_CHUNK_BYTES = 64 * 1024
    }

    // ── Secure Xtream password storage ────────────────────────
    // Passwords are stored in encrypted SecureStorage keyed by playlist ID.
    // SQLite password column is kept for backwards compat but always written as null.

    private fun xtreamPasswordKey(playlistId: String) = "xtream_pwd_$playlistId"

    private suspend fun saveXtreamPassword(playlistId: String, password: String) {
        secureStorage.putString(xtreamPasswordKey(playlistId), password)
        torveVerboseLog { "[XtreamCred] Password saved securely for playlist $playlistId" }
    }

    private suspend fun loadXtreamPassword(playlistId: String): String? {
        val pw = secureStorage.getString(xtreamPasswordKey(playlistId))
        if (pw == null) torveVerboseLog { "[XtreamCred] No secure password found for playlist $playlistId" }
        return pw
    }

    private suspend fun removeXtreamPassword(playlistId: String) {
        secureStorage.remove(xtreamPasswordKey(playlistId))
        torveVerboseLog { "[XtreamCred] Password removed for playlist $playlistId" }
    }

    /** Migrate any plaintext passwords from SQLite to secure storage. Idempotent. */
    private suspend fun migrateXtreamPasswords() {
        val playlists = database.torveQueries.getAllPlaylists(userId = uid()).executeAsList()
        for (row in playlists) {
            if (row.type == "xtream" && !row.password.isNullOrBlank()) {
                val existing = secureStorage.getString(xtreamPasswordKey(row.id))
                if (existing == null) {
                    secureStorage.putString(xtreamPasswordKey(row.id), row.password!!)
                    torveVerboseLog { "[XtreamCred] Migrated plaintext password for playlist ${row.id}" }
                }
                // Null out plaintext password in SQLite
                database.torveQueries.insertPlaylist(
                    user_id = uid(),
                    id = row.id,
                    name = row.name,
                    url = row.url,
                    epg_url = row.epg_url,
                    channel_count = row.channel_count,
                    last_updated = row.last_updated,
                    type = row.type,
                    server = row.server,
                    username = row.username,
                    password = null,
                )
            }
        }
    }

    // In-memory cache of parsed playlists and EPG data
    private val playlistCache = mutableMapOf<String, List<Channel>>()
    private val epgCache = mutableMapOf<String, EpgData>()
    private val epgErrorCache = mutableMapOf<String, String?>()
    private val epgProgressCache = mutableMapOf<String, EpgBatchProgress>()

    private suspend fun repairDuplicatePlaylistsForCurrentUser(): DuplicatePlaylistRepairResult {
        val result = repairDuplicatePlaylistsForUser(
            database = database,
            userId = uid(),
            loadXtreamPassword = ::loadXtreamPassword,
            saveXtreamPassword = ::saveXtreamPassword,
            removeXtreamPassword = ::removeXtreamPassword,
        )
        (result.removedPlaylistIds + result.touchedPlaylistIds).forEach { playlistId ->
            playlistCache.remove(playlistId)
            epgCache.remove(playlistId)
            epgErrorCache.remove(playlistId)
            epgProgressCache.remove(playlistId)
        }
        return result
    }

    private fun findExistingM3uPlaylist(
        url: String,
        excludedId: String? = null,
    ): Iptv_playlist? {
        val identity = m3uPlaylistIdentity(url) ?: return null
        return database.torveQueries.getAllPlaylists(userId = uid())
            .executeAsList()
            .firstOrNull { row ->
                row.id != excludedId &&
                    playlistIdentityFor(
                        type = row.type,
                        url = row.url,
                        server = row.server,
                        username = row.username,
                    ) == identity
            }
    }

    private fun findExistingXtreamPlaylist(
        server: String,
        username: String,
        excludedId: String? = null,
    ): Iptv_playlist? {
        val identity = xtreamPlaylistIdentity(server, username) ?: return null
        return database.torveQueries.getAllPlaylists(userId = uid())
            .executeAsList()
            .firstOrNull { row ->
                row.id != excludedId &&
                    playlistIdentityFor(
                        type = row.type,
                        url = row.url,
                        server = row.server,
                        username = row.username,
                    ) == identity
            }
    }

    private fun mergeDuplicateM3uMetadataIfUseful(
        existing: Iptv_playlist,
        epgUrl: String?,
    ): Iptv_playlist {
        val normalizedEpg = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (existing.epg_url.isNullOrBlank() && normalizedEpg != null) {
            database.torveQueries.insertPlaylist(
                user_id = uid(),
                id = existing.id,
                name = existing.name,
                url = existing.url,
                epg_url = normalizedEpg,
                channel_count = existing.channel_count,
                last_updated = Clock.System.now().toEpochMilliseconds(),
                type = existing.type,
                server = existing.server,
                username = existing.username,
                password = null,
            )
            return database.torveQueries.getPlaylist(userId = uid(), playlistId = existing.id)
                .executeAsOneOrNull()
                ?: existing
        }
        return existing
    }

    private suspend fun mergeDuplicateXtreamMetadataIfUseful(
        existing: Iptv_playlist,
        password: String,
        epgUrl: String?,
    ): Iptv_playlist {
        saveXtreamPassword(existing.id, password)
        val normalizedEpg = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (existing.epg_url.isNullOrBlank() && normalizedEpg != null) {
            database.torveQueries.insertPlaylist(
                user_id = uid(),
                id = existing.id,
                name = existing.name,
                url = existing.url,
                epg_url = normalizedEpg,
                channel_count = existing.channel_count,
                last_updated = Clock.System.now().toEpochMilliseconds(),
                type = existing.type,
                server = existing.server,
                username = existing.username,
                password = null,
            )
            return database.torveQueries.getPlaylist(userId = uid(), playlistId = existing.id)
                .executeAsOneOrNull()
                ?: existing
        }
        return existing
    }

    private suspend fun Iptv_playlist.toDomainPlaylist(): ChannelPlaylist {
        return ChannelPlaylist(
            id = id,
            name = name,
            url = url,
            epgUrl = epg_url,
            channelCount = channel_count.toInt(),
            lastUpdated = last_updated,
            type = PlaylistType.fromString(type),
            server = server,
            username = username,
            password = if (type == "xtream") loadXtreamPassword(id) else null,
        )
    }

    /**
     * Per-playlist mutex guarding [refreshEpg]. Five+ call sites in
     * ChannelsViewModel can each invoke `refreshEpg` for the same
     * playlistId within a few hundred milliseconds (init recovery,
     * loadPlaylists callback, selectPlaylist, selectCategory,
     * background refresh). Without this, the inner Ktor `prepareGet`
     * fires once per call and the device pulls a 40MB+ XMLTV file
     * **N times in parallel** — surfaced live as the user's mobile
     * emulator hanging on "Importing playlist" with 6+ concurrent
     * downloads triggering 60-72MB GC pauses.
     *
     * The first caller wins; subsequent callers suspend on the lock
     * and, when they wake, see the populated [epgCache] so the
     * existing `programmesByChannelKey.isNotEmpty()` early-return at
     * the ViewModel layer keeps them from re-triggering work. Holding
     * the lock for the duration of the fetch is intentional — a
     * cancel-and-replace would tear down a download that's about to
     * finish in favour of one that's about to repeat the same work.
     */
    private val refreshEpgMutexes = mutableMapOf<String, Mutex>()
    private fun refreshEpgMutexFor(playlistId: String): Mutex =
        refreshEpgMutexes.getOrPut(playlistId) { Mutex() }
    private val refreshPlaylistMutexes = mutableMapOf<String, Mutex>()
    private fun refreshPlaylistMutexFor(playlistId: String): Mutex =
        refreshPlaylistMutexes.getOrPut(playlistId) { Mutex() }
    private val catalogRefreshCoalescer = InFlightRefreshCoalescer()
    private val epgHttpClient: HttpClient by lazy {
        HttpClientFactory.createEpgStreamingClient(
            forceIdentityEncoding = EPG_FORCE_IDENTITY_ACCEPT_ENCODING,
        )
    }
    private val playlistHttpClient: HttpClient by lazy {
        HttpClientFactory.createEpgStreamingClient(
            forceIdentityEncoding = false,
        )
    }

    private data class EpgChannelMapping(
        val byXmltvId: Map<String, String>,
        val byNormalizedName: Map<String, String>,
        val allowedCanonicalKeys: Set<String>,
    )

    override suspend fun addPlaylist(
        name: String,
        url: String,
        epgUrl: String?,
        id: String?,
        onProgress: ((PlaylistAddProgress) -> Unit)?,
    ): ChannelPlaylist {
        findExistingM3uPlaylist(url = url, excludedId = id)?.let { existing ->
            val merged = mergeDuplicateM3uMetadataIfUseful(existing, epgUrl)
            return merged.toDomainPlaylist()
        }
        val id = id ?: "ch_${Clock.System.now().toEpochMilliseconds()}"
        val now = Clock.System.now().toEpochMilliseconds()

        // Stream the M3U body so the UI can show real byte-level progress even
        // on large playlists. Falls back to bodyAsText for trivially small
        // responses where streaming overhead isn't worth it.
        val m3uContent = playlistHttpClient.prepareGet(url).execute { response ->
            readM3uResponseAsText(response, onProgress)
        }

        onProgress?.invoke(PlaylistAddProgress(m3uContent.length.toLong(), m3uContent.length.toLong(), PlaylistAddProgress.Phase.PARSING))
        val parsed = m3uParser.parse(m3uContent, id)
        val resolvedEpgUrl = epgUrl ?: parsed.epgUrl

        onProgress?.invoke(PlaylistAddProgress(m3uContent.length.toLong(), m3uContent.length.toLong(), PlaylistAddProgress.Phase.SAVING))
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

    private suspend fun readM3uResponseAsText(
        response: HttpResponse,
        onProgress: ((PlaylistAddProgress) -> Unit)? = null,
    ): String {
        if (!response.status.isSuccess()) {
            throw IllegalStateException("M3U playlist request failed with HTTP ${response.status.value}")
        }
        val declaredLength = response.contentLength()
        if (declaredLength != null && declaredLength > M3U_MAX_BODY_BYTES) {
            throw IllegalStateException(
                "M3U playlist is too large ($declaredLength bytes, limit $M3U_MAX_BODY_BYTES bytes).",
            )
        }

        val channel = response.bodyAsChannel()
        val chunk = ByteArray(M3U_READ_CHUNK_BYTES)
        var bytes = ByteArray(minOf(M3U_INITIAL_BODY_BUFFER_BYTES, M3U_MAX_BODY_BYTES))
        var total = 0
        onProgress?.invoke(PlaylistAddProgress(0L, declaredLength, PlaylistAddProgress.Phase.DOWNLOADING))
        while (true) {
            val remainingBeforeLimit = M3U_MAX_BODY_BYTES + 1 - total
            if (remainingBeforeLimit <= 0) {
                throw IllegalStateException("M3U playlist exceeded $M3U_MAX_BODY_BYTES bytes.")
            }
            val read = channel.readAvailable(chunk, 0, minOf(chunk.size, remainingBeforeLimit))
            if (read <= 0) break
            val newTotal = total + read
            if (newTotal > M3U_MAX_BODY_BYTES) {
                throw IllegalStateException("M3U playlist exceeded $M3U_MAX_BODY_BYTES bytes.")
            }
            if (newTotal > bytes.size) {
                var newSize = bytes.size
                while (newSize < newTotal && newSize < M3U_MAX_BODY_BYTES) {
                    newSize = minOf(M3U_MAX_BODY_BYTES, newSize * 2)
                }
                if (newSize < newTotal) {
                    throw IllegalStateException("M3U playlist exceeded $M3U_MAX_BODY_BYTES bytes.")
                }
                bytes = bytes.copyOf(newSize)
            }
            chunk.copyInto(bytes, destinationOffset = total, startIndex = 0, endIndex = read)
            total = newTotal
            onProgress?.invoke(PlaylistAddProgress(total.toLong(), declaredLength, PlaylistAddProgress.Phase.DOWNLOADING))
        }
        return bytes.decodeToString(0, total)
    }

    override suspend fun saveM3uPlaylistConfig(
        name: String,
        url: String,
        epgUrl: String?,
        id: String?,
    ): ChannelPlaylist = withContext(ioDispatcher) {
        findExistingM3uPlaylist(url = url, excludedId = id)?.let { existing ->
            return@withContext mergeDuplicateM3uMetadataIfUseful(existing, epgUrl).toDomainPlaylist()
        }
        val playlistId = id ?: "ch_${Clock.System.now().toEpochMilliseconds()}"
        val now = Clock.System.now().toEpochMilliseconds()
        val normalizedEpgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        persistPlaylistConfigOnly(
            playlistId = playlistId,
            playlistName = name,
            playlistUrl = url,
            epgUrl = normalizedEpgUrl,
            playlistType = "m3u",
            server = null,
            username = null,
            password = null,
            updatedAt = now,
        )
        ChannelPlaylist(
            id = playlistId,
            name = name,
            url = url,
            epgUrl = normalizedEpgUrl,
            channelCount = 0,
            lastUpdated = now,
            type = PlaylistType.M3U,
        )
    }

    override suspend fun addXtreamPlaylist(
        name: String,
        server: String,
        username: String,
        password: String,
        id: String?,
        epgUrl: String?,
    ): ChannelPlaylist {
        val normalizedServer = server.trimEnd('/')
        val xtreamEpgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: buildXtreamEpgUrl(normalizedServer, username, password)
        findExistingXtreamPlaylist(
            server = normalizedServer,
            username = username,
            excludedId = id,
        )?.let { existing ->
            val merged = mergeDuplicateXtreamMetadataIfUseful(existing, password, xtreamEpgUrl)
            return merged.toDomainPlaylist()
        }

        val id = id ?: "xtream_${Clock.System.now().toEpochMilliseconds()}"
        val now = Clock.System.now().toEpochMilliseconds()

        // Authenticate first
        xtreamClient.authenticate(normalizedServer, username, password)

        val liveCategories = xtreamClient.getLiveCategories(normalizedServer, username, password)
        val vodCategories = try {
            xtreamClient.getVodCategories(normalizedServer, username, password)
        } catch (t: Exception) {
            channelDebugLog("Xtream VOD category fetch failed for playlist $id: ${t.message}")
            emptyList()
        }
        val seriesCategories = try {
            xtreamClient.getSeriesCategories(normalizedServer, username, password)
        } catch (t: Exception) {
            channelDebugLog("Xtream series category fetch failed for playlist $id: ${t.message}")
            emptyList()
        }

        // Store password in secure storage, never in SQLite
        saveXtreamPassword(id, password)
        // Fetch and persist each type sequentially so live/VOD objects are released before
        // the next group is fetched, keeping peak heap to max(live, VOD, series) not their sum.
        val totalCount = persistXtreamChannelsStreaming(
            playlistId = id,
            playlistName = name,
            playlistUrl = "$normalizedServer/player_api.php",
            epgUrl = xtreamEpgUrl,
            server = normalizedServer,
            username = username,
            password = password,
            liveCategories = liveCategories,
            vodCategories = vodCategories,
            seriesCategories = seriesCategories,
            updatedAt = now,
        )
        refreshEpgForPlaylist(id, xtreamEpgUrl)

        return ChannelPlaylist(
            id = id,
            name = name,
            url = "$normalizedServer/player_api.php",
            epgUrl = xtreamEpgUrl,
            channelCount = totalCount,
            lastUpdated = now,
            type = PlaylistType.XTREAM,
            server = normalizedServer,
            username = username,
            password = password, // in-memory only for immediate use
        )
    }

    override suspend fun saveXtreamPlaylistConfig(
        name: String,
        server: String,
        username: String,
        password: String,
        id: String?,
        epgUrl: String?,
    ): ChannelPlaylist = withContext(ioDispatcher) {
        val normalizedServer = server.trimEnd('/')
        val normalizedEpgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: buildXtreamEpgUrl(normalizedServer, username, password)
        findExistingXtreamPlaylist(
            server = normalizedServer,
            username = username,
            excludedId = id,
        )?.let { existing ->
            return@withContext mergeDuplicateXtreamMetadataIfUseful(existing, password, normalizedEpgUrl).toDomainPlaylist()
        }

        val playlistId = id ?: "xtream_${Clock.System.now().toEpochMilliseconds()}"
        val now = Clock.System.now().toEpochMilliseconds()
        saveXtreamPassword(playlistId, password)
        persistPlaylistConfigOnly(
            playlistId = playlistId,
            playlistName = name,
            playlistUrl = "$normalizedServer/player_api.php",
            epgUrl = normalizedEpgUrl,
            playlistType = "xtream",
            server = normalizedServer,
            username = username,
            password = null,
            updatedAt = now,
        )
        ChannelPlaylist(
            id = playlistId,
            name = name,
            url = "$normalizedServer/player_api.php",
            epgUrl = normalizedEpgUrl,
            channelCount = 0,
            lastUpdated = now,
            type = PlaylistType.XTREAM,
            server = normalizedServer,
            username = username,
            password = password,
        )
    }

    private suspend fun fetchXtreamLiveChannels(
        server: String,
        username: String,
        password: String,
        playlistId: String,
        liveCategories: List<XtreamCategory>,
    ): List<Channel> {
        val categoryMap = liveCategories.associateBy { it.categoryId }
        val channels = mutableListOf<Channel>()
        val seenIds = HashSet<String>(8192)
        try {
            xtreamClient.streamLiveStreams(server, username, password) { stream ->
                val key = stream.streamId.ifBlank { "${stream.name.orEmpty()}:${stream.categoryId.orEmpty()}" }
                if (seenIds.add(key)) {
                    channels += xtreamClient.mapLiveStreamToChannel(stream, categoryMap, server, username, password, playlistId)
                }
            }
        } catch (t: XtreamResponseTooLargeException) {
            println(
                "XtreamLiveCatalog: playlistId=$playlistId mode=all-too-large " +
                    "limitBytes=${t.limitBytes} contentLength=${t.contentLength ?: -1}; keeping existing cache if present",
            )
        } catch (t: Exception) {
            channelDebugLog("Xtream live fetch failed for playlist $playlistId: ${t.message}")
        }
        println("XtreamLiveCatalog: playlistId=$playlistId mode=all rows=${channels.size} categories=${liveCategories.size}")
        return channels
    }

    private suspend fun fetchXtreamVodChannels(
        server: String,
        username: String,
        password: String,
        playlistId: String,
        vodCategories: List<XtreamCategory>,
    ): List<Channel> {
        val categoryMap = vodCategories.associateBy { it.categoryId }
        val channels = mutableListOf<Channel>()
        val seenIds = HashSet<String>(4096)
        val fetchedAll = try {
            xtreamClient.streamVodStreams(server, username, password) { stream ->
                val key = stream.streamId.ifBlank { "${stream.name.orEmpty()}:${stream.categoryId.orEmpty()}" }
                if (seenIds.add(key)) {
                    channels += xtreamClient.mapVodStreamToChannel(stream, categoryMap, server, username, password, playlistId)
                }
            }
            println("XtreamVodCatalog: playlistId=$playlistId mode=all rows=${channels.size} categories=${vodCategories.size}")
            true
        } catch (t: XtreamResponseTooLargeException) {
            println(
                "XtreamVodCatalog: playlistId=$playlistId mode=all-too-large " +
                    "limitBytes=${t.limitBytes} contentLength=${t.contentLength ?: -1}; falling back to category fetch",
            )
            channels.clear(); seenIds.clear()
            false
        } catch (t: Exception) {
            channelDebugLog("Xtream VOD movie fetch failed for playlist $playlistId: ${t.message}; falling back to category fetch")
            channels.clear(); seenIds.clear()
            false
        }
        if (fetchedAll) return channels

        return fetchXtreamVodChannelsByCategory(server, username, password, playlistId, vodCategories, categoryMap)
    }

    private suspend fun fetchXtreamVodChannelsByCategory(
        server: String,
        username: String,
        password: String,
        playlistId: String,
        vodCategories: List<XtreamCategory>,
        categoryMap: Map<String, XtreamCategory>,
    ): List<Channel> {
        if (vodCategories.isEmpty()) return emptyList()
        val channels = mutableListOf<Channel>()
        val seenIds = HashSet<String>(4096)
        var failedCategories = 0
        for (category in vodCategories) {
            val categoryId = category.categoryId.takeIf { it.isNotBlank() } ?: continue
            try {
                xtreamClient.streamVodStreams(server, username, password, categoryId = categoryId) { stream ->
                    val key = stream.streamId.ifBlank { "${stream.name.orEmpty()}:${stream.categoryId.orEmpty()}" }
                    if (seenIds.add(key)) {
                        channels += xtreamClient.mapVodStreamToChannel(stream, categoryMap, server, username, password, playlistId)
                    }
                }
            } catch (t: XtreamResponseTooLargeException) {
                failedCategories++
                channelDebugLog(
                    "Xtream VOD category fetch too large playlist=$playlistId " +
                        "categoryId=$categoryId limitBytes=${t.limitBytes} contentLength=${t.contentLength ?: -1}",
                )
            } catch (t: Exception) {
                failedCategories++
                channelDebugLog("Xtream VOD category fetch failed playlist=$playlistId categoryId=$categoryId: ${t.message}")
            }
        }
        println(
            "XtreamVodCatalog: playlistId=$playlistId mode=category rows=${channels.size} " +
                "categories=${vodCategories.size} failedCategories=$failedCategories",
        )
        return channels
    }

    private suspend fun fetchXtreamSeriesChannels(
        server: String,
        username: String,
        password: String,
        playlistId: String,
        seriesCategories: List<XtreamCategory>,
    ): List<Channel> {
        val categoryMap = seriesCategories.associateBy { it.categoryId }
        // Two-pass dedup: first by seriesId, then by normalized title — choosing the richer entry each time.
        val byProviderId = linkedMapOf<String, Channel>()
        try {
            xtreamClient.streamSeries(server, username, password) { series ->
                val providerKey = series.seriesId.trim()
                    .takeIf { it.isNotBlank() && it != "0" }?.let { "series:$it" }
                    ?: "title:${series.name.normalizedSeriesKey()}:cover:${series.cover.orEmpty()}"
                val incoming = xtreamClient.mapSeriesStreamToChannel(series, categoryMap, server, username, password, playlistId)
                val existing = byProviderId[providerKey]
                byProviderId[providerKey] = if (existing == null || incoming.vodDisplayScore() > existing.vodDisplayScore()) incoming else existing
            }
        } catch (t: XtreamResponseTooLargeException) {
            println(
                "XtreamSeriesCatalog: playlistId=$playlistId mode=all-too-large " +
                    "limitBytes=${t.limitBytes} contentLength=${t.contentLength ?: -1}; keeping existing cache if present",
            )
        } catch (t: Exception) {
            channelDebugLog("Xtream series fetch failed for playlist $playlistId: ${t.message}")
        }
        val byTitle = linkedMapOf<String, Channel>()
        byProviderId.values.forEach { channel ->
            val titleKey = channel.name.normalizedSeriesKey().takeIf { it.isNotBlank() }
                ?: channel.kodiProps["vod_series_id"]?.trim()?.takeIf { it.isNotBlank() }
                ?: channel.tvgLogo.orEmpty()
            val existing = byTitle[titleKey]
            byTitle[titleKey] = if (existing == null || channel.vodDisplayScore() > existing.vodDisplayScore()) channel else existing
        }
        val channels = byTitle.values.toList()
        println("XtreamSeriesCatalog: playlistId=$playlistId mode=all rows=${channels.size} categories=${seriesCategories.size}")
        return channels
    }

    private fun Channel.vodDisplayScore(): Int = listOfNotNull(
        tvgLogo.takeIf { !it.isNullOrBlank() },
        kodiProps["vod_plot"].takeIf { !it.isNullOrBlank() },
        kodiProps["vod_rating"].takeIf { !it.isNullOrBlank() },
        kodiProps["vod_genre"].takeIf { !it.isNullOrBlank() },
        kodiProps["vod_backdrop"].takeIf { !it.isNullOrBlank() },
    ).size

    private inline fun <T> List<T>.dedupeByProviderId(keyOf: (T) -> String): List<T> {
        val rows = linkedMapOf<String, T>()
        forEach { row ->
            val key = keyOf(row).ifBlank { row.hashCode().toString() }
            rows[key] = row
        }
        return rows.values.toList()
    }

    private fun List<XtreamSeries>.dedupeXtreamSeries(): List<XtreamSeries> {
        val byProviderIdentity = linkedMapOf<String, XtreamSeries>()
        forEach { show ->
            val providerKey = show.seriesId
                .trim()
                .takeIf { it.isNotBlank() && it != "0" }
                ?.let { "series:$it" }
                ?: "title:${show.name.normalizedSeriesKey()}:cover:${show.cover.orEmpty()}"
            val existing = byProviderIdentity[providerKey]
            byProviderIdentity[providerKey] = chooseBetterXtreamSeries(existing, show)
        }

        val byTitle = linkedMapOf<String, XtreamSeries>()
        byProviderIdentity.values.forEach { show ->
            val titleKey = show.name.normalizedSeriesKey().takeIf { it.isNotBlank() }
                ?: show.seriesId.trim().takeIf { it.isNotBlank() }
                ?: show.cover.orEmpty()
            val existing = byTitle[titleKey]
            byTitle[titleKey] = chooseBetterXtreamSeries(existing, show)
        }
        return byTitle.values.toList()
    }

    private fun chooseBetterXtreamSeries(
        existing: XtreamSeries?,
        candidate: XtreamSeries,
    ): XtreamSeries {
        if (existing == null) return candidate
        return if (candidate.seriesDisplayScore() > existing.seriesDisplayScore()) candidate else existing
    }

    private fun XtreamSeries.seriesDisplayScore(): Int {
        return listOfNotNull(
            cover.takeIf { !it.isNullOrBlank() },
            plot.takeIf { !it.isNullOrBlank() },
            rating.takeIf { !it.isNullOrBlank() },
            genre.takeIf { !it.isNullOrBlank() },
            backdropPath.firstOrNull(),
        ).size
    }

    private fun String?.normalizedSeriesKey(): String {
        return orEmpty()
            .lowercase()
            .replace(Regex("""\(\d{4}\)|\[\d{4}]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    override suspend fun removePlaylist(id: String) {
        removeXtreamPassword(id) // clear from secure storage
        database.torveQueries.deletePlaylist(userId = uid(), playlistId = id)
        database.torveQueries.deleteChannelsForPlaylist(userId = uid(), playlistId = id)
        playlistCache.remove(id)
        epgCache.remove(id)
        epgErrorCache.remove(id)
        epgProgressCache.remove(id)
        database.torveQueries.deletePreference(userId = uid(), key = epgLoadStatePrefKey(id))
        database.torveQueries.deletePreference(userId = uid(), key = epgActiveGenerationPrefKey(id))
        database.torveQueries.deletePreference(userId = uid(), key = channelActiveGenerationPrefKey(id))
        database.torveQueries.deletePreference(userId = uid(), key = channelLastSyncPrefKey(id))
        database.torveQueries.deletePreference(userId = uid(), key = channelStagedGenerationPrefKey(id))
    }

    override suspend fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String?) {
        val playlist = database.torveQueries.getPlaylist(userId = uid(), playlistId = playlistId).executeAsOneOrNull()
            ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        val normalizedEpg = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        database.torveQueries.updatePlaylistEpgUrl(
            epg_url = normalizedEpg,
            last_updated = now,
            userId = uid(),
            playlistId = playlistId,
        )
        epgCache.remove(playlistId)
        val fallbackXtreamEpg = if (
            normalizedEpg == null &&
            playlist.type == "xtream" &&
            playlist.server != null &&
            playlist.username != null
        ) {
            loadXtreamPassword(playlistId)?.let { password ->
                buildXtreamEpgUrl(
                    server = playlist.server,
                    username = playlist.username,
                    password = password,
                )
            }
        } else {
            null
        }
        refreshEpgForPlaylist(playlistId, normalizedEpg ?: fallbackXtreamEpg)

        database.torveQueries.insertPlaylist(
            user_id = uid(),
            id = playlist.id,
            name = playlist.name,
            url = playlist.url,
            epg_url = normalizedEpg,
            channel_count = playlist.channel_count,
            last_updated = now,
            type = playlist.type,
            server = playlist.server,
            username = playlist.username,
            password = null, // never persist plaintext in SQLite
        )
    }

    private var xtreamPasswordsMigrated = false

    override suspend fun getPlaylists(): List<ChannelPlaylist> {
        // One-time migration: move plaintext passwords from SQLite → secure storage
        if (!xtreamPasswordsMigrated) {
            xtreamPasswordsMigrated = true
            runCatching { migrateXtreamPasswords() }
        }
        repairDuplicatePlaylistsForCurrentUser()
        return database.torveQueries.getAllPlaylists(userId = uid()).executeAsList().map { row ->
            row.toDomainPlaylist()
        }
    }

    override suspend fun getPlaylistSummaries(): List<ChannelPlaylistSummary> {
        repairDuplicatePlaylistsForCurrentUser()
        return database.torveQueries.getPlaylistSummaries(userId = uid()).executeAsList().map { row ->
            ChannelPlaylistSummary(
                id = row.id,
                name = row.name,
                channelCount = row.channel_count.toInt(),
                type = row.type,
            )
        }
    }

    override suspend fun refreshPlaylist(playlistId: String) {
        refreshPlaylistMutexFor(playlistId).withLock {
            refreshPlaylistInternal(playlistId, includeEpg = true)
        }
    }

    override suspend fun refreshPlaylistCatalog(playlistId: String) {
        catalogRefreshCoalescer.run(playlistId) {
            refreshPlaylistMutexFor(playlistId).withLock {
                refreshPlaylistInternal(playlistId, includeEpg = false)
            }
        }
    }

    private suspend fun refreshPlaylistInternal(playlistId: String, includeEpg: Boolean) {
        val refreshStartedAt = TimeSource.Monotonic.markNow()
        repairChannelCatalogIfNeeded(playlistId)
        val playlist = database.torveQueries.getPlaylist(userId = uid(), playlistId = playlistId).executeAsOneOrNull()
            ?: return

        val now = Clock.System.now().toEpochMilliseconds()

        // Load Xtream password from secure storage, not SQLite
        val xtreamPassword = if (playlist.type == "xtream") loadXtreamPassword(playlistId) else null
        if (playlist.type == "xtream" && playlist.server != null && playlist.username != null && xtreamPassword != null) {
            // Xtream playlist refresh — categories fetched first (fast), then channels are
            // fetched and persisted sequentially inside persistXtreamChannelsStreaming so
            // each type's Channel objects are released before the next type is fetched.
            val liveCategoryStartedAt = TimeSource.Monotonic.markNow()
            val categories = xtreamClient.getLiveCategories(playlist.server, playlist.username, xtreamPassword)
            val liveCategoryMs = liveCategoryStartedAt.elapsedNow().inWholeMilliseconds

            val vodCategoryStartedAt = TimeSource.Monotonic.markNow()
            val vodCategories = try {
                xtreamClient.getVodCategories(playlist.server, playlist.username, xtreamPassword)
            } catch (t: Exception) {
                channelDebugLog("Xtream VOD category refresh failed for playlist $playlistId: ${t.message}")
                emptyList()
            }
            val vodCategoryMs = vodCategoryStartedAt.elapsedNow().inWholeMilliseconds

            val seriesCategoryStartedAt = TimeSource.Monotonic.markNow()
            val seriesCategories = try {
                xtreamClient.getSeriesCategories(playlist.server, playlist.username, xtreamPassword)
            } catch (t: Exception) {
                channelDebugLog("Xtream series category refresh failed for playlist $playlistId: ${t.message}")
                emptyList()
            }
            val seriesCategoryMs = seriesCategoryStartedAt.elapsedNow().inWholeMilliseconds

            val xtreamEpgUrl = playlist.epg_url ?: buildXtreamEpgUrl(
                server = playlist.server,
                username = playlist.username,
                password = xtreamPassword,
            )

            val persistenceStartedAt = TimeSource.Monotonic.markNow()
            val totalCount = persistXtreamChannelsStreaming(
                playlistId = playlist.id,
                playlistName = playlist.name,
                playlistUrl = playlist.url,
                epgUrl = xtreamEpgUrl,
                server = playlist.server,
                username = playlist.username,
                password = xtreamPassword,
                liveCategories = categories,
                vodCategories = vodCategories,
                seriesCategories = seriesCategories,
                updatedAt = now,
            )
            val persistenceMs = persistenceStartedAt.elapsedNow().inWholeMilliseconds
            println(
                "TorvePerf: iptv stages playlistId=$playlistId type=xtream " +
                    "liveCategoryMs=$liveCategoryMs vodCategoryMs=$vodCategoryMs seriesCategoryMs=$seriesCategoryMs " +
                    "streamingPersistenceMs=$persistenceMs itemCount=$totalCount " +
                    "catalogCompleteMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}",
            )
            if (includeEpg) {
                refreshEpgForPlaylist(playlistId, xtreamEpgUrl)
            }
        } else {
            // M3U playlist refresh
            val requestStartedAt = TimeSource.Monotonic.markNow()
            val m3uContent = playlistHttpClient.prepareGet(playlist.url).execute { response ->
                readM3uResponseAsText(response)
            }
            val requestMs = requestStartedAt.elapsedNow().inWholeMilliseconds
            val parseStartedAt = TimeSource.Monotonic.markNow()
            val parsed = m3uParser.parse(m3uContent, playlistId)
            val parseMs = parseStartedAt.elapsedNow().inWholeMilliseconds
            val resolvedEpgUrl = playlist.epg_url ?: parsed.epgUrl

            val persistenceStartedAt = TimeSource.Monotonic.markNow()
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
            val persistenceMs = persistenceStartedAt.elapsedNow().inWholeMilliseconds
            println(
                "TorvePerf: iptv stages playlistId=$playlistId type=m3u requestMs=$requestMs parseMs=$parseMs " +
                    "persistenceMs=$persistenceMs itemCount=${parsed.channels.size} " +
                    "catalogCompleteMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}",
            )

            // Refresh EPG
            if (includeEpg) {
                refreshEpgForPlaylist(playlistId, resolvedEpgUrl)
            }
        }
    }

    override suspend fun refreshEpg(playlistId: String, hiddenChannelIds: Set<String>) {
        refreshEpgMutexFor(playlistId).withLock {
            // This method is used by the explicit "Refresh EPG" action and by
            // the scheduled worker. Do not skip just because a small/stale cache
            // exists; that made manual refresh a no-op when only a handful of
            // programmes had been ingested.
            val playlist = database.torveQueries.getPlaylist(userId = uid(), playlistId = playlistId).executeAsOneOrNull()
                ?: return
            val epgXtreamPw = if (playlist.type == "xtream") loadXtreamPassword(playlistId) else null
            val sourceUrl = playlist.epg_url?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (
                    playlist.type == "xtream" &&
                    playlist.server != null &&
                    playlist.username != null &&
                    epgXtreamPw != null
                ) {
                    buildXtreamEpgUrl(
                        server = playlist.server,
                        username = playlist.username,
                        password = epgXtreamPw,
                    )
                } else {
                    null
                }
            refreshEpgForPlaylist(playlistId, sourceUrl, hiddenChannelIds)
        }
    }

    override suspend fun getChannels(playlistId: String): List<Channel> {
        repairChannelCatalogIfNeeded(playlistId)
        return playlistCache[playlistId] ?: loadChannelsFromDatabase(playlistId).also { persisted ->
            if (persisted.isNotEmpty() && persisted.size <= PLAYLIST_CACHE_MAX_CHANNELS) {
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
        val favoriteIds = database.torveQueries.getAllFavorites(userId = uid()).executeAsList()
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

            val epgKeys = epgChannelLookupKeys(
                playlistId = playlistId,
                channel = markedCh,
            )
            val current = epgKeys.firstNotNullOfOrNull(currentProgrammeByChannelId::get)
            val next = epgKeys.firstNotNullOfOrNull(nextProgrammeByChannelId::get)
            EnrichedChannel(markedCh, current, next)
        }
    }

    override suspend fun getLiveCategoryCounts(playlistId: String): List<Pair<String, Long>> {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        return database.torveQueries
            .getLiveCategoryCountsForPlaylist(userId = uid(), playlistId = playlistId, generationId = generationId)
            .executeAsList()
            .map { row -> (row.group_title ?: "Ungrouped") to row.channel_count }
    }

    override suspend fun getVodCategoryCounts(playlistId: String): List<Pair<String, Long>> {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        return database.torveQueries
            .getVodCategoryCountsForPlaylist(userId = uid(), playlistId = playlistId, generationId = generationId)
            .executeAsList()
            .map { row -> (row.group_title ?: "VOD") to row.channel_count }
    }

    override suspend fun getVodCategoryTypeCounts(playlistId: String): List<VodCategoryTypeCount> {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        return database.torveQueries
            .getVodCategoryTypeCountsForPlaylist(userId = uid(), playlistId = playlistId, generationId = generationId)
            .executeAsList()
            .map { row ->
                VodCategoryTypeCount(
                    groupTitle = row.group_title ?: "VOD",
                    contentType = parseContentType(row.content_type),
                    count = row.channel_count,
                )
            }
    }

    /**
     * Lightweight category listing — returns category names with channel counts
     * without loading any channel objects into memory.
     */
    override suspend fun getCategoryCounts(playlistId: String): List<Pair<String, Long>> {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        val results = database.torveQueries
            .getCategoryCountsForPlaylist(userId = uid(), playlistId = playlistId, generationId = generationId)
            .executeAsList()
        if (results.isNotEmpty()) {
            return results.map { row -> (row.group_title ?: "Ungrouped") to row.channel_count }
        }
        // Active generation has no data — find any generation that does.
        val rowCount = database.torveQueries
            .getTotalChannelCountForPlaylist(userId = uid(), playlistId = playlistId, generationId = generationId)
            .executeAsOne()
        channelDebugLog(
            "ChannelCatalog: category count empty playlistId=$playlistId generation=$generationId rowCount=$rowCount",
        )
        return emptyList()
    }

    /**
     * Load channels for a single category only — avoids materializing the full playlist.
     */
    override suspend fun getChannelsForCategory(playlistId: String, categoryName: String): List<Channel> {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        val rows = if (categoryName == "Ungrouped") {
            database.torveQueries
                .getChannelsForPlaylistCategoryNull(userId = uid(), playlistId = playlistId, generationId = generationId)
                .executeAsList()
        } else {
            database.torveQueries
                .getChannelsForPlaylistCategory(userId = uid(), playlistId = playlistId, generationId = generationId, groupTitle = categoryName)
                .executeAsList()
        }
        return rows.map { row ->
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

    override suspend fun getChannelsForContentType(
        playlistId: String,
        type: ChannelContentType,
        limit: Int,
    ): List<Channel> {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        return database.torveQueries
            .getChannelsForPlaylistContentTypeLimited(
                userId = uid(),
                playlistId = playlistId,
                generationId = generationId,
                contentType = type.name,
                rowLimit = limit.toLong(),
            )
            .executeAsList()
            .map { row -> row.toDomainChannel() }
    }

    override suspend fun getChannelsForCategoryContentType(
        playlistId: String,
        categoryName: String,
        type: ChannelContentType,
        limit: Int,
    ): List<Channel> {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        val rows = if (categoryName == "Ungrouped") {
            database.torveQueries.getChannelsForPlaylistCategoryNullContentType(
                userId = uid(),
                playlistId = playlistId,
                generationId = generationId,
                contentType = type.name,
                rowLimit = limit.toLong(),
            )
        } else {
            database.torveQueries.getChannelsForPlaylistCategoryContentType(
                userId = uid(),
                playlistId = playlistId,
                generationId = generationId,
                groupTitle = categoryName,
                contentType = type.name,
                rowLimit = limit.toLong(),
            )
        }
        return rows.executeAsList().map { row -> row.toDomainChannel() }
    }

    /**
     * Get total channel count without loading any channel objects.
     */
    override suspend fun getTotalChannelCount(playlistId: String): Long {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return 0L
        return database.torveQueries
            .getTotalChannelCountForPlaylist(userId = uid(), playlistId = playlistId, generationId = generationId)
            .executeAsOne()
    }

    override suspend fun searchChannels(query: String): List<Channel> {
        // SQL-level search with LIKE — avoids loading all channels into memory.
        val pattern = "%${query}%"
        val playlists = getPlaylists()
        return playlists.flatMap { playlist ->
            val generationId = getActiveChannelGeneration(playlist.id) ?: return@flatMap emptyList()
            database.torveQueries
                .searchChannelsForPlaylist(
                    userId = uid(),
                    playlistId = playlist.id,
                    generationId = generationId,
                    nameLike = pattern,
                    tvgNameLike = pattern,
                    groupLike = pattern,
                )
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
                        playlistId = playlist.id,
                        contentType = parseContentType(row.content_type),
                    )
                }
        }
    }

    /**
     * Sync the hidden channel set to the iptv_hidden_channel table so browse queries
     * can exclude hidden channels at the SQL level via NOT EXISTS.
     * Migrates legacy hidden IDs to stable_id format where possible.
     */
    override fun getHiddenChannelIds(): Set<String> {
        return database.torveQueries.getAllHiddenChannelIds().executeAsList().toSet()
    }

    override fun syncHiddenChannelsToDb(hiddenIds: Set<String>) {
        val migrated = migrateLegacyHiddenIds(hiddenIds)
        database.transaction {
            database.torveQueries.clearHiddenChannels()
            for (id in migrated) {
                database.torveQueries.insertHiddenChannel(id)
            }
        }
    }

    /**
     * Migrate legacy hidden channel IDs (pre-stable_id format) to the current stable_id
     * format so they match the iptv_channel.stable_id column in SQL NOT EXISTS queries.
     *
     * Legacy format: "tvgId" or "playlistId_channelName" (no "::" separator)
     * Stable format: "playlistId::tvgId" or "playlistId::normalizedUrl" or "playlistId::normalizedName"
     *
     * Returns the migrated set. Any legacy ID that cannot be mapped is kept as-is
     * (won't match any stable_id but won't be lost either).
     */
    private fun migrateLegacyHiddenIds(hiddenIds: Set<String>): Set<String> {
        if (hiddenIds.isEmpty()) return hiddenIds
        val result = mutableSetOf<String>()
        var migrated = 0
        var unmapped = 0
        for (id in hiddenIds) {
            if (id.contains("::")) {
                // Already in stable format
                result.add(id)
                continue
            }
            // Legacy format — try to resolve to stable_id via DB lookup.
            // Case 1: bare tvg_id (e.g. "channel.epg.id")
            val byTvgId = runCatching {
                database.torveQueries.getChannelByTvgId(userId = uid(), tvgId = id).executeAsOneOrNull()
            }.getOrNull()
            if (byTvgId != null) {
                result.add(byTvgId)
                migrated++
                continue
            }
            // Case 2: "playlistId_channelName" format
            val underscoreIdx = id.indexOf('_')
            if (underscoreIdx > 0 && underscoreIdx < id.length - 1) {
                val playlistId = id.substring(0, underscoreIdx)
                val channelName = id.substring(underscoreIdx + 1)
                val byName = runCatching {
                    database.torveQueries.getChannelByPlaylistAndName(
                        userId = uid(),
                        playlistId = playlistId,
                        name = channelName,
                    )
                        .executeAsOneOrNull()
                }.getOrNull()
                if (byName != null) {
                    result.add(byName)
                    migrated++
                    continue
                }
            }
            // Cannot map — keep the legacy ID (safe but won't match stable_id in SQL).
            result.add(id)
            unmapped++
        }
        if (migrated > 0 || unmapped > 0) {
            println("HiddenChannelMigration: total=${hiddenIds.size} migrated=$migrated unmapped=$unmapped alreadyStable=${hiddenIds.size - migrated - unmapped}")
        }
        return result
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
                userId = uid(),
                playlistId = playlistId,
                generationId = generationId,
                epgChannelKey = channelId,
                startTime = windowStart,
                endTime = windowEnd,
                rowLimit = EPG_MAX_PROGRAMMES_PER_CHANNEL_IN_MEMORY.toLong(),
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
            user_id = uid(),
            channel_id = channelId,
            playlist_id = channel.playlistId,
            name = channel.name,
            logo_url = channel.tvgLogo,
            group_title = channel.groupTitle,
            added_at = now,
        )
    }

    override suspend fun removeFavorite(channelId: String) {
        database.torveQueries.deleteFavorite(userId = uid(), channelId = channelId)
    }

    override suspend fun getFavorites(): List<Channel> {
        return database.torveQueries.getAllFavorites(userId = uid()).executeAsList().map { row ->
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
        return database.torveQueries.isFavorite(userId = uid(), channelId = channelId).executeAsOne() > 0
    }

    override suspend fun recordChannelViewed(channel: Channel) {
        val now = Clock.System.now().toEpochMilliseconds()
        val channelId = stableChannelId(channel)
        database.torveQueries.insertRecentChannel(
            user_id = uid(),
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
        return database.torveQueries.getRecentChannels(userId = uid(), limit = limit).executeAsList().map { row ->
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
        database.torveQueries.clearRecentChannels(userId = uid())
    }

    override suspend fun clearAll() {
        // Clear Xtream passwords from secure storage before deleting playlists
        val playlists = database.torveQueries.getAllPlaylists(userId = uid()).executeAsList()
        for (p in playlists) {
            if (p.type == "xtream") removeXtreamPassword(p.id)
        }
        torveVerboseLog { "[XtreamCred] Cleared ${playlists.count { it.type == "xtream" }} secure passwords on sign-out" }
        database.torveQueries.deleteAllChannels(userId = uid())
        database.torveQueries.deleteAllFavorites(userId = uid())
        database.torveQueries.clearRecentChannels(userId = uid())
        database.torveQueries.deleteAllPlaylists(userId = uid())
        database.torveQueries.deleteAllDebridAccounts(userId = uid())
        // Clear in-memory caches
        playlistCache.clear()
        epgCache.clear()
        epgErrorCache.clear()
        epgProgressCache.clear()
    }

    override suspend fun getChannelsByContentType(
        playlistId: String,
        type: ChannelContentType,
    ): List<EnrichedChannel> {
        repairChannelCatalogIfNeeded(playlistId)
        val generationId = getActiveChannelGeneration(playlistId) ?: return emptyList()
        val favoriteIds = database.torveQueries.getAllFavorites(userId = uid()).executeAsList()
            .map { it.channel_id }
            .toSet()
        return database.torveQueries
            .getChannelsForPlaylistContentType(
                userId = uid(),
                playlistId = playlistId,
                generationId = generationId,
                contentType = type.name,
            )
            .executeAsList()
            .map { row ->
                val channel = Channel(
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
                val markedChannel = if (channelIdentityCandidates(channel).any(favoriteIds::contains)) {
                    channel.copy(isFavorite = true)
                } else {
                    channel
                }
                EnrichedChannel(markedChannel)
            }
    }

    override suspend fun getVodSeriesEpisodes(channel: Channel): List<Channel> {
        if (channel.contentType != ChannelContentType.VOD_SERIES) return channel
            .takeIf { it.url.isNotBlank() }
            ?.let(::listOf)
            .orEmpty()
        val seriesId = channel.kodiProps["vod_series_id"]?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val playlist = database.torveQueries
            .getPlaylist(userId = uid(), playlistId = channel.playlistId)
            .executeAsOneOrNull()
            ?: return emptyList()
        if (playlist.type != "xtream" || playlist.server == null || playlist.username == null) return emptyList()
        val password = loadXtreamPassword(channel.playlistId) ?: return emptyList()
        val seriesInfo = runCatching {
            xtreamClient.getSeriesInfo(
                server = playlist.server,
                username = playlist.username,
                password = password,
                seriesId = seriesId,
            )
        }.getOrElse { error ->
            println("XtreamSeriesPlayback: failed seriesId=$seriesId playlistId=${channel.playlistId}: ${DiagnosticsRedactor.redact(error.message)}")
            return emptyList()
        }
        val episodes = seriesInfo.episodes
            .filter { it.directSource?.isNotBlank() == true || it.id.isNotBlank() }
            .sortedWith(
                compareBy<XtreamSeriesEpisode>(
                    { it.season?.takeIf { season -> season > 0 } ?: Int.MAX_VALUE },
                    { it.episodeNum?.takeIf { episodeNum -> episodeNum > 0 } ?: Int.MAX_VALUE },
                    { it.id },
                ),
            )

        return episodes.map { episode ->
            val episodeUrl = episode.directSource
                ?.takeIf { it.isNotBlank() && it.startsWith("http", ignoreCase = true) }
                ?: run {
                    val ext = episode.containerExtension
                        ?.trim()
                        ?.trimStart('.')
                        ?.takeIf { it.isNotBlank() }
                        ?: "mp4"
                    "${playlist.server.trimEnd('/')}/series/${playlist.username}/$password/${episode.id}.$ext"
                }
            val seasonNumber = episode.season?.takeIf { it > 0 }
            val episodeNumber = episode.episodeNum?.takeIf { it > 0 }
            val episodeLabel = when {
                seasonNumber != null && episodeNumber != null -> {
                    val title = episode.title?.takeIf { it.isNotBlank() }
                    "S${seasonNumber}E${episodeNumber}" + title?.let { " - $it" }.orEmpty()
                }
                !episode.title.isNullOrBlank() -> episode.title
                else -> channel.name
            }
            channel.copy(
                name = "${channel.name} - $episodeLabel",
                url = episodeUrl,
                tvgName = episodeLabel,
                tvgLogo = episode.movieImage ?: channel.tvgLogo,
                duration = episode.durationSecs ?: channel.duration,
                kodiProps = channel.kodiProps + buildMap {
                    put("vod_episode_id", episode.id)
                    episode.containerExtension?.takeIf { it.isNotBlank() }?.let { put("vod_episode_container_extension", it) }
                    seasonNumber?.let { put("vod_season_number", it.toString()) }
                    episodeNumber?.let { put("vod_episode_number", it.toString()) }
                    episode.rating?.takeIf { it > 0.0 }?.let { put("vod_episode_rating", it.toString()) }
                    episode.plot?.takeIf { it.isNotBlank() }?.let { put("vod_episode_plot", it) }
                },
                contentType = ChannelContentType.VOD_SERIES,
            )
        }
    }

    override suspend fun resolveFirstVodSeriesEpisode(channel: Channel): Channel? {
        return getVodSeriesEpisodes(channel).firstOrNull()?.also { episode ->
            println(
                "XtreamSeriesPlayback: resolved seriesId=${channel.kodiProps["vod_series_id"].orEmpty()} " +
                    "episodeId=${episode.kodiProps["vod_episode_id"].orEmpty()} " +
                    "season=${episode.kodiProps["vod_season_number"] ?: "-1"} " +
                    "episode=${episode.kodiProps["vod_episode_number"] ?: "-1"}",
            )
        }
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
        val existingChannelCount = if (existingGeneration != null) {
            database.torveQueries
                .getTotalChannelCountForPlaylist(
                    userId = uid(),
                    playlistId = playlistId,
                    generationId = existingGeneration,
                )
                .executeAsOne()
        } else {
            0L
        }
        val incomingSignature = channelSnapshotSignature(channels)
        val persistedSignature = database.torveQueries
            .getPreference(userId = uid(), key = channelSnapshotSignaturePrefKey(playlistId))
            .executeAsOneOrNull()
        if (existingGeneration != null && persistedSignature == incomingSignature) {
            database.transaction {
                database.torveQueries.insertPlaylist(
                    user_id = uid(),
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
                database.torveQueries.setPreference(
                    user_id = uid(),
                    key = channelLastSyncPrefKey(playlistId),
                    value_ = updatedAt.toString(),
                )
            }
            println(
                "TorvePerf: iptv persistence playlistId=$playlistId itemCount=${channels.size} " +
                    "snapshotUnchanged=true snapshotWriteMs=0 pruneMs=0",
            )
            return
        }
        if (!shouldAcceptIncomingChannelSnapshot(existingChannelCount.toInt(), channels.size)) {
            channelDebugLog(
                "ChannelCatalog: rejected empty replacement playlistId=$playlistId existing=$existingChannelCount incoming=${channels.size}",
            )
            return
        }

        val reusingActiveGeneration = existingGeneration != null
        val nextGeneration = existingGeneration ?: nextChannelSnapshotGeneration(updatedAt, null)
        if (!reusingActiveGeneration) {
            setStagedChannelGeneration(playlistId, nextGeneration)
        }
        val snapshotWriteStartedAt = TimeSource.Monotonic.markNow()
        database.transaction {
            insertChannelSnapshotBatched(
                playlistId = playlistId,
                generationId = nextGeneration,
                channels = channels,
                updatedAt = updatedAt,
            )
            if (reusingActiveGeneration) {
                database.torveQueries.deleteStaleChannelsForPlaylistGeneration(
                    userId = uid(),
                    playlistId = playlistId,
                    generationId = nextGeneration,
                    updatedAt = updatedAt,
                )
            }
            database.torveQueries.insertPlaylist(
                user_id = uid(),
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
            database.torveQueries.setPreference(user_id = uid(), key = channelLastSyncPrefKey(playlistId), value_ = updatedAt.toString())
            database.torveQueries.setPreference(
                user_id = uid(),
                key = channelSnapshotSignaturePrefKey(playlistId),
                value_ = incomingSignature,
            )
        }
        val snapshotWriteMs = snapshotWriteStartedAt.elapsedNow().inWholeMilliseconds
        if (!reusingActiveGeneration) {
            clearStagedChannelGeneration(playlistId)
        }
        val pruneStartedAt = TimeSource.Monotonic.markNow()
        if (!reusingActiveGeneration) {
            runCatching {
                pruneOlderChannelGenerations(playlistId, nextGeneration)
            }.onFailure { error ->
                channelDebugLog(
                    "ChannelCatalog: stale generation cleanup failed playlistId=$playlistId generation=$nextGeneration error=${error.message}",
                )
            }
        }
        println(
            "TorvePerf: iptv persistence playlistId=$playlistId itemCount=${channels.size} " +
                "snapshotWriteMs=$snapshotWriteMs pruneMs=${pruneStartedAt.elapsedNow().inWholeMilliseconds}",
        )

        if (channels.size <= PLAYLIST_CACHE_MAX_CHANNELS) {
            playlistCache[playlistId] = channels
        } else {
            playlistCache.remove(playlistId)
        }
        channelDebugLog(
            "ChannelCatalog: committed playlistId=$playlistId generation=$nextGeneration channels=${channels.size}",
        )
    }

    private fun pruneOlderChannelGenerations(playlistId: String, activeGeneration: Long) {
        database.torveQueries.deleteChannelsOlderGenerations(
            userId = uid(),
            playlistId = playlistId,
            generationId = activeGeneration,
        )
    }

    // Inserts channels using only raw SQL (no table-change notification). sortBase offsets
    // sort_index so multiple groups (live, VOD, series) can be written with correct ordering.
    private fun insertChannelGroupAllRaw(
        ownerId: String,
        playlistId: String,
        generationId: Long,
        channels: List<Channel>,
        sortBase: Int,
        updatedAt: Long,
    ) {
        if (channels.isEmpty()) return
        channels.withIndex().chunked(CHANNEL_INSERT_BATCH_SIZE).forEach { batch ->
            val sql = buildChannelBatchInsertSql(batch.size)
            val identifier = BULK_CHANNEL_INSERT_QUERY_ID.takeIf { batch.size == CHANNEL_INSERT_BATCH_SIZE }
            sqlDriver.execute(identifier, sql, batch.size * CHANNEL_INSERT_BIND_COUNT) {
                batch.forEachIndexed { batchIdx, (localIdx, channel) ->
                    val offset = batchIdx * CHANNEL_INSERT_BIND_COUNT
                    val sortIndex = sortBase + localIdx
                    bindString(offset, ownerId)
                    bindString(offset + 1, playlistId)
                    bindLong(offset + 2, generationId)
                    bindString(offset + 3, stableChannelId(playlistId, channel))
                    bindLong(offset + 4, sortIndex.toLong())
                    bindString(offset + 5, channel.name)
                    bindString(offset + 6, channel.url)
                    bindString(offset + 7, channel.tvgId)
                    bindString(offset + 8, channel.tvgName)
                    bindString(offset + 9, channel.tvgLogo)
                    bindString(offset + 10, channel.groupTitle)
                    bindString(offset + 11, channel.tvgLanguage)
                    bindString(offset + 12, channel.tvgCountry)
                    bindLong(offset + 13, channel.tvgShift?.toLong())
                    bindLong(offset + 14, channel.channelNumber?.toLong())
                    bindLong(offset + 15, channel.duration.toLong())
                    bindString(offset + 16, channel.catchupType)
                    bindLong(offset + 17, channel.catchupDays?.toLong())
                    bindString(offset + 18, channel.catchupSource)
                    bindString(offset + 19, channel.userAgent)
                    bindString(offset + 20, channel.vlcOptions.joinToString("\n"))
                    bindString(offset + 21, encodeKodiProps(channel.kodiProps))
                    bindString(offset + 22, channel.contentType.name)
                    bindLong(offset + 23, updatedAt)
                }
            }
        }
    }

    // Fetches and persists Xtream channels type-by-type so each group's Channel objects
    // can be released before the next group is fetched. Peak heap consumption drops from
    // sum(live + VOD + series) to max(live, VOD, series).
    private suspend fun persistXtreamChannelsStreaming(
        playlistId: String,
        playlistName: String,
        playlistUrl: String,
        epgUrl: String?,
        server: String,
        username: String,
        password: String,
        liveCategories: List<XtreamCategory>,
        vodCategories: List<XtreamCategory>,
        seriesCategories: List<XtreamCategory>,
        updatedAt: Long,
    ): Int {
        val ownerId = uid()
        val existingGeneration = getActiveChannelGeneration(playlistId)
        val existingChannelCount = if (existingGeneration != null) {
            database.torveQueries
                .getTotalChannelCountForPlaylist(ownerId, playlistId, existingGeneration)
                .executeAsOne().toInt()
        } else 0
        val newGeneration = nextChannelSnapshotGeneration(updatedAt, existingGeneration)
        setStagedChannelGeneration(playlistId, newGeneration)

        // lastChannelForNotify / lastSortIndexForNotify track which channel to use for the
        // iptv_channel table-change notification when series is empty.
        var lastChannelForNotify: Channel? = null
        var lastSortIndexForNotify = -1

        // --- Live: fetch, insert in one transaction, then release ---
        var liveChannels = fetchXtreamLiveChannels(server, username, password, playlistId, liveCategories)
        val liveCount = liveChannels.size
        if (liveCount > 0) {
            database.transaction { insertChannelGroupAllRaw(ownerId, playlistId, newGeneration, liveChannels, 0, updatedAt) }
            lastChannelForNotify = liveChannels.last()
            lastSortIndexForNotify = liveCount - 1
        }
        liveChannels = emptyList() // allow GC before VOD fetch

        // --- VOD: fetch, insert, release ---
        var vodChannels = fetchXtreamVodChannels(server, username, password, playlistId, vodCategories)
        val vodCount = vodChannels.size
        if (vodCount > 0) {
            database.transaction { insertChannelGroupAllRaw(ownerId, playlistId, newGeneration, vodChannels, liveCount, updatedAt) }
            lastChannelForNotify = vodChannels.last()
            lastSortIndexForNotify = liveCount + vodCount - 1
        }
        vodChannels = emptyList() // allow GC before series fetch

        // --- Series: still needs full in-memory dedup, but live+VOD are already GC-eligible ---
        var seriesChannels = fetchXtreamSeriesChannels(server, username, password, playlistId, seriesCategories)
        val seriesCount = seriesChannels.size
        val totalCount = liveCount + vodCount + seriesCount

        // Empty-replacement guard: don't wipe an existing playlist if the server returned nothing.
        if (!shouldAcceptIncomingChannelSnapshot(existingChannelCount, totalCount)) {
            channelDebugLog(
                "ChannelCatalog: streaming rejected empty replacement playlistId=$playlistId " +
                    "existing=$existingChannelCount incoming=$totalCount",
            )
            clearStagedChannelGeneration(playlistId)
            // Prune the just-staged generation (has no channel rows, but clean up preferences).
            runCatching { pruneOlderChannelGenerations(playlistId, existingGeneration ?: newGeneration) }
            return existingChannelCount
        }

        // Finalize: insert series + activate generation in one transaction.
        val seriesBase = liveCount + vodCount
        database.transaction {
            // When series is empty but live/VOD channels were inserted via raw SQL only,
            // emit iptv_channel notification via a re-insert of the last committed row.
            if (seriesCount == 0 && lastChannelForNotify != null) {
                val lc = lastChannelForNotify!!
                database.torveQueries.insertChannel(
                    user_id = ownerId,
                    playlist_id = playlistId,
                    generation_id = newGeneration,
                    stable_id = stableChannelId(playlistId, lc),
                    sort_index = lastSortIndexForNotify.toLong(),
                    name = lc.name,
                    stream_url = lc.url,
                    tvg_id = lc.tvgId,
                    tvg_name = lc.tvgName,
                    logo_url = lc.tvgLogo,
                    group_title = lc.groupTitle,
                    tvg_language = lc.tvgLanguage,
                    tvg_country = lc.tvgCountry,
                    tvg_shift = lc.tvgShift?.toLong(),
                    channel_number = lc.channelNumber?.toLong(),
                    duration = lc.duration.toLong(),
                    catchup_type = lc.catchupType,
                    catchup_days = lc.catchupDays?.toLong(),
                    catchup_source = lc.catchupSource,
                    user_agent = lc.userAgent,
                    vlc_options = lc.vlcOptions.joinToString("\n"),
                    kodi_props = encodeKodiProps(lc.kodiProps),
                    content_type = lc.contentType.name,
                    updated_at = updatedAt,
                )
            }
            if (seriesCount > 0) {
                // All rows except last via raw SQL; last via generated query for iptv_channel notification.
                val allButLast = seriesChannels.dropLast(1).withIndex().chunked(CHANNEL_INSERT_BATCH_SIZE)
                allButLast.forEach { batch ->
                    val sql = buildChannelBatchInsertSql(batch.size)
                    val identifier = BULK_CHANNEL_INSERT_QUERY_ID.takeIf { batch.size == CHANNEL_INSERT_BATCH_SIZE }
                    sqlDriver.execute(identifier, sql, batch.size * CHANNEL_INSERT_BIND_COUNT) {
                        batch.forEachIndexed { batchIdx, (localIdx, channel) ->
                            val offset = batchIdx * CHANNEL_INSERT_BIND_COUNT
                            bindString(offset, ownerId)
                            bindString(offset + 1, playlistId)
                            bindLong(offset + 2, newGeneration)
                            bindString(offset + 3, stableChannelId(playlistId, channel))
                            bindLong(offset + 4, (seriesBase + localIdx).toLong())
                            bindString(offset + 5, channel.name)
                            bindString(offset + 6, channel.url)
                            bindString(offset + 7, channel.tvgId)
                            bindString(offset + 8, channel.tvgName)
                            bindString(offset + 9, channel.tvgLogo)
                            bindString(offset + 10, channel.groupTitle)
                            bindString(offset + 11, channel.tvgLanguage)
                            bindString(offset + 12, channel.tvgCountry)
                            bindLong(offset + 13, channel.tvgShift?.toLong())
                            bindLong(offset + 14, channel.channelNumber?.toLong())
                            bindLong(offset + 15, channel.duration.toLong())
                            bindString(offset + 16, channel.catchupType)
                            bindLong(offset + 17, channel.catchupDays?.toLong())
                            bindString(offset + 18, channel.catchupSource)
                            bindString(offset + 19, channel.userAgent)
                            bindString(offset + 20, channel.vlcOptions.joinToString("\n"))
                            bindString(offset + 21, encodeKodiProps(channel.kodiProps))
                            bindString(offset + 22, channel.contentType.name)
                            bindLong(offset + 23, updatedAt)
                        }
                    }
                }
                val last = seriesChannels.last()
                database.torveQueries.insertChannel(
                    user_id = ownerId,
                    playlist_id = playlistId,
                    generation_id = newGeneration,
                    stable_id = stableChannelId(playlistId, last),
                    sort_index = (totalCount - 1).toLong(),
                    name = last.name,
                    stream_url = last.url,
                    tvg_id = last.tvgId,
                    tvg_name = last.tvgName,
                    logo_url = last.tvgLogo,
                    group_title = last.groupTitle,
                    tvg_language = last.tvgLanguage,
                    tvg_country = last.tvgCountry,
                    tvg_shift = last.tvgShift?.toLong(),
                    channel_number = last.channelNumber?.toLong(),
                    duration = last.duration.toLong(),
                    catchup_type = last.catchupType,
                    catchup_days = last.catchupDays?.toLong(),
                    catchup_source = last.catchupSource,
                    user_agent = last.userAgent,
                    vlc_options = last.vlcOptions.joinToString("\n"),
                    kodi_props = encodeKodiProps(last.kodiProps),
                    content_type = last.contentType.name,
                    updated_at = updatedAt,
                )
            }
            database.torveQueries.insertPlaylist(
                user_id = ownerId,
                id = playlistId,
                name = playlistName,
                url = playlistUrl,
                epg_url = epgUrl,
                channel_count = totalCount.toLong(),
                last_updated = updatedAt,
                type = "xtream",
                server = server,
                username = username,
                password = null,
            )
            setActiveChannelGeneration(playlistId, newGeneration)
            database.torveQueries.setPreference(user_id = ownerId, key = channelLastSyncPrefKey(playlistId), value_ = updatedAt.toString())
        }
        seriesChannels = emptyList()

        clearStagedChannelGeneration(playlistId)
        runCatching { pruneOlderChannelGenerations(playlistId, newGeneration) }

        if (totalCount <= PLAYLIST_CACHE_MAX_CHANNELS) {
            // Too expensive to reload here; let the next read repopulate the cache.
            playlistCache.remove(playlistId)
        } else {
            playlistCache.remove(playlistId)
        }
        println(
            "TorvePerf: iptv streaming persistence playlistId=$playlistId " +
                "live=$liveCount vod=$vodCount series=$seriesCount total=$totalCount",
        )
        return totalCount
    }

    private fun insertChannelSnapshotBatched(
        playlistId: String,
        generationId: Long,
        channels: List<Channel>,
        updatedAt: Long,
    ) {
        if (channels.isEmpty()) return
        val ownerId = uid()
        val directRows = channels.dropLast(1).withIndex().toList()
        directRows.chunked(CHANNEL_INSERT_BATCH_SIZE).forEach { batch ->
            val sql = buildChannelBatchInsertSql(batch.size)
            val identifier = BULK_CHANNEL_INSERT_QUERY_ID.takeIf { batch.size == CHANNEL_INSERT_BATCH_SIZE }
            sqlDriver.execute(
                identifier = identifier,
                sql = sql,
                parameters = batch.size * CHANNEL_INSERT_BIND_COUNT,
            ) {
                batch.forEachIndexed { batchIndex, indexedChannel ->
                    val channel = indexedChannel.value
                    val offset = batchIndex * CHANNEL_INSERT_BIND_COUNT
                    bindString(offset, ownerId)
                    bindString(offset + 1, playlistId)
                    bindLong(offset + 2, generationId)
                    bindString(offset + 3, stableChannelId(playlistId, channel))
                    bindLong(offset + 4, indexedChannel.index.toLong())
                    bindString(offset + 5, channel.name)
                    bindString(offset + 6, channel.url)
                    bindString(offset + 7, channel.tvgId)
                    bindString(offset + 8, channel.tvgName)
                    bindString(offset + 9, channel.tvgLogo)
                    bindString(offset + 10, channel.groupTitle)
                    bindString(offset + 11, channel.tvgLanguage)
                    bindString(offset + 12, channel.tvgCountry)
                    bindLong(offset + 13, channel.tvgShift?.toLong())
                    bindLong(offset + 14, channel.channelNumber?.toLong())
                    bindLong(offset + 15, channel.duration.toLong())
                    bindString(offset + 16, channel.catchupType)
                    bindLong(offset + 17, channel.catchupDays?.toLong())
                    bindString(offset + 18, channel.catchupSource)
                    bindString(offset + 19, channel.userAgent)
                    bindString(offset + 20, channel.vlcOptions.joinToString("\n"))
                    bindString(offset + 21, encodeKodiProps(channel.kodiProps))
                    bindString(offset + 22, channel.contentType.name)
                    bindLong(offset + 23, updatedAt)
                }
            }
        }

        // Use the generated query for the final row so SQLDelight emits one
        // table notification after the direct batched inserts.
        val lastIndex = channels.lastIndex
        val last = channels[lastIndex]
        database.torveQueries.insertChannel(
            user_id = ownerId,
            playlist_id = playlistId,
            generation_id = generationId,
            stable_id = stableChannelId(playlistId, last),
            sort_index = lastIndex.toLong(),
            name = last.name,
            stream_url = last.url,
            tvg_id = last.tvgId,
            tvg_name = last.tvgName,
            logo_url = last.tvgLogo,
            group_title = last.groupTitle,
            tvg_language = last.tvgLanguage,
            tvg_country = last.tvgCountry,
            tvg_shift = last.tvgShift?.toLong(),
            channel_number = last.channelNumber?.toLong(),
            duration = last.duration.toLong(),
            catchup_type = last.catchupType,
            catchup_days = last.catchupDays?.toLong(),
            catchup_source = last.catchupSource,
            user_agent = last.userAgent,
            vlc_options = last.vlcOptions.joinToString("\n"),
            kodi_props = encodeKodiProps(last.kodiProps),
            content_type = last.contentType.name,
            updated_at = updatedAt,
        )
    }

    private fun buildChannelBatchInsertSql(rowCount: Int): String {
        val placeholders = List(rowCount) {
            List(CHANNEL_INSERT_BIND_COUNT) { "?" }.joinToString(prefix = "(", postfix = ")")
        }.joinToString()
        return """
            INSERT OR REPLACE INTO iptv_channel (
                user_id, playlist_id, generation_id, stable_id, sort_index, name, stream_url,
                tvg_id, tvg_name, logo_url, group_title, tvg_language, tvg_country, tvg_shift,
                channel_number, duration, catchup_type, catchup_days, catchup_source, user_agent,
                vlc_options, kodi_props, content_type, updated_at
            ) VALUES $placeholders
        """.trimIndent()
    }

    private fun persistPlaylistConfigOnly(
        playlistId: String,
        playlistName: String,
        playlistUrl: String,
        epgUrl: String?,
        playlistType: String,
        server: String?,
        username: String?,
        password: String?,
        updatedAt: Long,
    ) {
        val existing = database.torveQueries
            .getPlaylist(userId = uid(), playlistId = playlistId)
            .executeAsOneOrNull()
        database.torveQueries.insertPlaylist(
            user_id = uid(),
            id = playlistId,
            name = playlistName,
            url = playlistUrl,
            epg_url = epgUrl,
            channel_count = existing?.channel_count ?: 0L,
            last_updated = updatedAt,
            type = playlistType,
            server = server,
            username = username,
            password = password,
        )
        channelDebugLog(
            "ChannelCatalog: saved config-only playlistId=$playlistId type=$playlistType existingChannels=${existing?.channel_count ?: 0L}",
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
            .getChannelsForPlaylistGeneration(userId = uid(), playlistId = playlistId, generationId = generationId)
            .executeAsList()
            .map { row -> row.toDomainChannel() }
    }

    private fun Iptv_channel.toDomainChannel(): Channel {
        return Channel(
            name = name,
            url = stream_url,
            tvgId = tvg_id,
            tvgName = tvg_name,
            tvgLogo = logo_url,
            groupTitle = group_title,
            tvgLanguage = tvg_language,
            tvgCountry = tvg_country,
            tvgShift = tvg_shift?.toInt(),
            channelNumber = channel_number?.toInt(),
            duration = duration.toInt(),
            catchupType = catchup_type,
            catchupDays = catchup_days?.toInt(),
            catchupSource = catchup_source,
            userAgent = user_agent,
            vlcOptions = decodeVlcOptions(vlc_options),
            kodiProps = decodeKodiProps(kodi_props),
            playlistId = playlist_id,
            contentType = parseContentType(content_type),
        )
    }

    private fun resolveChannelByStoredId(
        playlistId: String,
        storedId: String,
    ): Channel? {
        val cached = playlistCache[playlistId]
        if (cached != null) {
            cached.firstOrNull { channelMatchesIdentity(it, storedId) }?.let { return it }
        }
        val generationId = getActiveChannelGeneration(playlistId) ?: return null
        return database.torveQueries
            .getChannelForPlaylistGeneration(
                userId = uid(),
                playlistId = playlistId,
                generationId = generationId,
                stableId = storedId,
            )
            .executeAsOneOrNull()
            ?.toDomainChannel()
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
                "ChannelsEPG: parse success playlistId=$playlistId sourceConfigured=true generation=${parsedEpg.generationId ?: -1} channels=${parsedEpg.channels.size} programmes=${parsedEpg.programmes.size} groupedKeys=${parsedEpg.programmesByChannelKey.size}",
            )
        } else {
            setEpgLoadState(playlistId, EPG_STATE_ERROR)
        }
    }

    private suspend fun fetchAndParseEpg(playlistId: String, sourceUrl: String, hiddenChannelIds: Set<String> = emptySet()): EpgData? = withContext(ioDispatcher) {
        val refreshStartedAt = TimeSource.Monotonic.markNow()
        var lastError: String? = null
        var inFlightGeneration: Long? = null

        for (attempt in 1..EPG_MAX_FETCH_ATTEMPTS) {
            try {
                println(
                    "ChannelsEPG: fetch start playlistId=$playlistId sourceConfigured=true attempt=$attempt/$EPG_MAX_FETCH_ATTEMPTS requestTimeoutMs=$EPG_REQUEST_TIMEOUT_MS",
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
                            "ChannelsEPG: content length guard playlistId=$playlistId sourceConfigured=true contentLength=$contentLength",
                        )
                        return@execute null
                    }

                    val (windowStart, windowEnd) = resolveEpgWindowBounds()
                    val nextGeneration = Clock.System.now().toEpochMilliseconds()
                    val channelMappingStartedAt = TimeSource.Monotonic.markNow()
                    val channelMapping = buildEpgChannelMapping(playlistId, hiddenChannelIds)
                    val channelMappingMs = channelMappingStartedAt.elapsedNow().inWholeMilliseconds
                    inFlightGeneration = nextGeneration
                    val ingestStartedAt = TimeSource.Monotonic.markNow()
                    var tempFilePath: String? = null

                    println(
                        "ChannelsEPG: fetch response playlistId=$playlistId sourceConfigured=true attempt=$attempt status=$statusCode contentType=$contentType contentEncoding=$contentEncoding contentLength=${contentLength ?: -1}",
                    )
                    try {
                        val downloadStartedAt = TimeSource.Monotonic.markNow()
                        val downloadResult = GzipSupport.downloadToTempFile(
                            response = response,
                            maxCompressedBytes = EPG_MAX_DOWNLOAD_BYTES,
                        )
                        if (downloadResult == null) {
                            clearEpgGenerationRows(playlistId, nextGeneration)
                            epgErrorCache[playlistId] = "EPG download failed."
                            return@execute null
                        }
                        val downloadMs = downloadStartedAt.elapsedNow().inWholeMilliseconds
                        tempFilePath = downloadResult.tempFilePath

                        val parseStartedAt = TimeSource.Monotonic.markNow()
                        val ingestResult = GzipSupport.parseXmlTvAutoFromFileToDbOrNull(
                            tempFilePath = downloadResult.tempFilePath,
                            parser = epgParser,
                            db = database,
                            userId = uid(),
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
                        batchSize = 200,
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
                        val parseAndPersistenceMs = parseStartedAt.elapsedNow().inWholeMilliseconds
                        val stats = ingestResult.stats
                        val ingestDurationMs = ingestStartedAt.elapsedNow().inWholeMilliseconds
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
                        database.torveQueries.deleteEpgProgrammesOlderGenerations(userId = uid(), playlistId = playlistId, generationId = nextGeneration)
                        database.torveQueries.deleteEpgChannelsOlderGenerations(userId = uid(), playlistId = playlistId, generationId = nextGeneration)
                        inFlightGeneration = null
                        val databaseLoadStartedAt = TimeSource.Monotonic.markNow()
                        val loaded = loadEpgFromDatabase(playlistId, nextGeneration, windowStart, windowEnd)
                        println(
                            "TorvePerf: epg stages playlistId=$playlistId channelMappingMs=$channelMappingMs " +
                                "downloadMs=$downloadMs parseAndPersistenceMs=$parseAndPersistenceMs " +
                                "persistenceMs=${stats.persistenceDurationMs} " +
                                "databaseLoadMs=${databaseLoadStartedAt.elapsedNow().inWholeMilliseconds} " +
                                "bytesDownloaded=${downloadResult.bytesDownloaded} bytesParsed=${ingestResult.bytesParsed} " +
                                "programmeCount=${stats.programmesKept} completeMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}",
                        )
                        loaded
                    } finally {
                        tempFilePath?.let { GzipSupport.deleteTempFile(it) }
                    }
                }

                if (failedStatusCode != null) {
                    val statusCode = failedStatusCode ?: 0
                    if (attempt < EPG_MAX_FETCH_ATTEMPTS && shouldRetryEpgStatus(statusCode)) {
                        val retryDelayMs = epgRetryDelayMs(attempt)
                        println(
                            "ChannelsEPG: retry scheduled playlistId=$playlistId sourceConfigured=true attempt=$attempt waitMs=$retryDelayMs",
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
            } catch (e: Exception) {
                inFlightGeneration?.let { generation ->
                    clearEpgGenerationRows(playlistId, generation)
                }
                inFlightGeneration = null
                lastError = e.message ?: e::class.simpleName ?: "Failed to fetch EPG"
                val isTimeout = lastError.contains("timeout", ignoreCase = true)
                println(
                    "ChannelsEPG: fetch failed playlistId=$playlistId sourceConfigured=true attempt=$attempt error=${DiagnosticsRedactor.redact(lastError)}",
                )
                if (attempt < EPG_MAX_FETCH_ATTEMPTS && isTimeout) {
                    val retryDelayMs = epgRetryDelayMs(attempt)
                    println(
                        "ChannelsEPG: timeout retry scheduled playlistId=$playlistId sourceConfigured=true attempt=$attempt waitMs=$retryDelayMs",
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
            } catch (throwable: Throwable) {
                if (throwable.isOutOfMemory()) {
                    val message = "EPG too large. Reduce EPG days or guide window."
                    epgErrorCache[playlistId] = message
                    println(
                        "ChannelsEPG: oom playlistId=$playlistId sourceConfigured=true attempt=$attempt error=${DiagnosticsRedactor.redact(throwable.message)}",
                    )
                    inFlightGeneration?.let { generation ->
                        clearEpgGenerationRows(playlistId, generation)
                    }
                    return@withContext null
                }
                throw throwable
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

    private fun Throwable.isOutOfMemory(): Boolean =
        toString().contains("OutOfMemory", ignoreCase = true)

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
        val raw = database.torveQueries.getPreference(userId = uid(), key = key).executeAsOneOrNull()
        return raw?.toIntOrNull()?.coerceIn(min, max) ?: default
    }

    private fun epgLoadStatePrefKey(playlistId: String): String = "$PREF_EPG_LOAD_STATE_PREFIX$playlistId"

    private fun epgActiveGenerationPrefKey(playlistId: String): String = "$PREF_EPG_ACTIVE_GENERATION_PREFIX$playlistId"

    private fun channelActiveGenerationPrefKey(playlistId: String): String = "$PREF_CHANNEL_ACTIVE_GENERATION_PREFIX$playlistId"

    private fun channelLastSyncPrefKey(playlistId: String): String = "$PREF_CHANNEL_LAST_SYNC_PREFIX$playlistId"

    private fun channelStagedGenerationPrefKey(playlistId: String): String = "$PREF_CHANNEL_STAGED_GENERATION_PREFIX$playlistId"

    private fun channelSnapshotSignaturePrefKey(playlistId: String): String = "$PREF_CHANNEL_SNAPSHOT_SIGNATURE_PREFIX$playlistId"

    private fun setEpgLoadState(playlistId: String, state: String) {
        database.torveQueries.setPreference(user_id = uid(), key = epgLoadStatePrefKey(playlistId), value_ = state)
    }

    private fun getEpgLoadState(playlistId: String): String {
        return database.torveQueries.getPreference(userId = uid(), key = epgLoadStatePrefKey(playlistId))
            .executeAsOneOrNull()
            ?: EPG_STATE_IDLE
    }

    private fun setActiveEpgGeneration(playlistId: String, generationId: Long) {
        database.torveQueries.setPreference(user_id = uid(), key = epgActiveGenerationPrefKey(playlistId), value_ = generationId.toString())
    }

    private fun getActiveEpgGeneration(playlistId: String): Long? {
        return database.torveQueries.getPreference(userId = uid(), key = epgActiveGenerationPrefKey(playlistId))
            .executeAsOneOrNull()
            ?.toLongOrNull()
    }

    private fun setActiveChannelGeneration(playlistId: String, generationId: Long) {
        database.torveQueries.setPreference(user_id = uid(), key = channelActiveGenerationPrefKey(playlistId), value_ = generationId.toString())
    }

    private fun getActiveChannelGeneration(playlistId: String): Long? {
        return database.torveQueries.getPreference(userId = uid(), key = channelActiveGenerationPrefKey(playlistId))
            .executeAsOneOrNull()
            ?.toLongOrNull()
    }

    private fun setStagedChannelGeneration(playlistId: String, generationId: Long) {
        database.torveQueries.setPreference(user_id = uid(), key = channelStagedGenerationPrefKey(playlistId), value_ = generationId.toString())
    }

    private fun getStagedChannelGeneration(playlistId: String): Long? {
        return database.torveQueries.getPreference(userId = uid(), key = channelStagedGenerationPrefKey(playlistId))
            .executeAsOneOrNull()
            ?.toLongOrNull()
    }

    private fun clearStagedChannelGeneration(playlistId: String) {
        database.torveQueries.deletePreference(userId = uid(), key = channelStagedGenerationPrefKey(playlistId))
    }

    private fun repairChannelCatalogIfNeeded(playlistId: String) {
        val recovery = planChannelCatalogRecovery(
            activeGeneration = getActiveChannelGeneration(playlistId),
            stagedGeneration = getStagedChannelGeneration(playlistId),
        )
        recovery.staleGenerationToDelete?.let { generationId ->
            database.torveQueries.clearChannelsForPlaylistGeneration(userId = uid(), playlistId = playlistId, generationId = generationId)
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

        val generationId = getActiveChannelGeneration(playlistId) ?: return EpgChannelMapping(
            byXmltvId = emptyMap(),
            byNormalizedName = emptyMap(),
            allowedCanonicalKeys = emptySet(),
        )
        val channels = database.torveQueries
            .getEpgChannelMappingRowsForPlaylist(
                userId = uid(),
                playlistId = playlistId,
                generationId = generationId,
            )
            .executeAsList()
        channels.forEach { channel ->
            // Skip hidden channels — their EPG data won't be ingested.
            if (hiddenChannelIds.isNotEmpty() &&
                hiddenChannelIds.contains(channel.stable_id)
            ) {
                skippedHidden++
                return@forEach
            }

            val canonical = canonicalEpgChannelKey(
                playlistId = playlistId,
                tvgId = channel.tvg_id,
                name = channel.name,
            ) ?: return@forEach
            allowedKeys += canonical

            channel.tvg_id?.trim()?.takeIf { it.isNotEmpty() }?.let { tvgId ->
                if (tvgId !in byXmltvId) byXmltvId[tvgId] = canonical
                val normalizedTvgId = normalizeEpgMatchKey(tvgId)
                if (normalizedTvgId !in byNormalizedName) byNormalizedName[normalizedTvgId] = canonical
            }
            channel.tvg_name?.trim()?.takeIf { it.isNotEmpty() }?.let { tvgName ->
                val normalizedTvgName = normalizeEpgMatchKey(tvgName)
                if (normalizedTvgName !in byNormalizedName) byNormalizedName[normalizedTvgName] = canonical
            }
            channel.name.trim().takeIf { it.isNotEmpty() }?.let { name ->
                val normalizedName = normalizeEpgMatchKey(name)
                if (normalizedName !in byNormalizedName) byNormalizedName[normalizedName] = canonical
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
            .getEpgChannelsForPlaylistGeneration(userId = uid(), playlistId = playlistId, generationId = generationId)
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
                userId = uid(),
                playlistId = playlistId,
                generationId = generationId,
                startTime = windowStartMs,
                endTime = windowEndMs,
                rowLimit = EPG_MAX_PROGRAMMES_TOTAL_IN_MEMORY.toLong(),
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

    private fun canonicalEpgChannelKey(
        playlistId: String,
        tvgId: String?,
        name: String,
    ): String? {
        val normalizedPlaylistId = playlistId.trim().ifEmpty { return null }
        tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { return "$normalizedPlaylistId::$it" }
        val normalizedName = normalizeEpgMatchKey(name)
        if (normalizedName.isEmpty()) return null
        return "$normalizedPlaylistId::$normalizedName"
    }

    private fun clearEpgGenerationRows(playlistId: String, generationId: Long) {
        database.torveQueries.clearEpgProgrammesForPlaylistGeneration(userId = uid(), playlistId = playlistId, generationId = generationId)
        database.torveQueries.clearEpgChannelsForPlaylistGeneration(userId = uid(), playlistId = playlistId, generationId = generationId)
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

internal fun channelSnapshotSignature(channels: List<Channel>): String {
    var hash = -0x340d631b8c467531L
    fun mix(value: String?) {
        value.orEmpty().forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        hash = hash xor 0xffL
        hash *= 0x100000001b3L
    }
    channels.forEachIndexed { index, channel ->
        mix(index.toString())
        mix(channel.name)
        mix(channel.url)
        mix(channel.tvgId)
        mix(channel.tvgName)
        mix(channel.tvgLogo)
        mix(channel.groupTitle)
        mix(channel.tvgLanguage)
        mix(channel.tvgCountry)
        mix(channel.tvgShift?.toString())
        mix(channel.channelNumber?.toString())
        mix(channel.duration.toString())
        mix(channel.catchupType)
        mix(channel.catchupDays?.toString())
        mix(channel.catchupSource)
        mix(channel.userAgent)
        channel.vlcOptions.forEach(::mix)
        channel.kodiProps.entries.sortedBy { it.key }.forEach { (key, value) ->
            mix(key)
            mix(value)
        }
        mix(channel.contentType.name)
    }
    return hash.toULong().toString(16)
}

internal class InFlightRefreshCoalescer {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Result<Unit>>>()

    suspend fun run(key: String, operation: suspend () -> Unit) {
        val (deferred, owner) = mutex.withLock {
            val existing = inFlight[key]
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<Result<Unit>>().also { inFlight[key] = it } to true
            }
        }
        if (!owner) {
            deferred.await().getOrThrow()
            return
        }

        val result = runCatching { operation() }
        deferred.complete(result)
        mutex.withLock {
            if (inFlight[key] === deferred) inFlight.remove(key)
        }
        result.getOrThrow()
    }
}
