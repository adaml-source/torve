package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.integrations.ConnectionProbeResult
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.head
import io.ktor.client.request.parameter
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.torve.presentation.settings.OptionalAppFeature
import com.torve.presentation.settings.isAppFeatureEnabled

class JellyfinLibraryOverlayService(
    private val httpClient: HttpClient,
    private val prefsRepo: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
    private val family: com.torve.data.profile.FamilyControls? = null,
) : LibraryOverlayService {
    private val lookupLock = Mutex()
    private data class LibraryIndex(val connection: Pair<String, String>, val userId: String, val type: String,
        val loadedAt: Long, val items: List<JellyfinBrowseItem>)
    private val libraryIndexes = mutableMapOf<String, LibraryIndex>()

    // Jellyfin ignores Emby's AnyProviderIdEquals parameter. Page its compact
    // identity index and compare IDs locally; never match a remake by title.
    private suspend fun findCatalogItem(tmdbId: Int, mediaType: MediaType, imdbId: String? = null,
        refreshMissing: Boolean = false): JellyfinBrowseItem? {
        val connection = serverAndKey() ?: return null
        val userId = resolveUserId() ?: return null
        val type = if (mediaType == MediaType.MOVIE) "Movie" else "Series"
        fun JellyfinBrowseItem.matches(): Boolean = this.type.equals(type, true) &&
            ((tmdbId > 0 && providerIds?.tmdb?.toIntOrNull() == tmdbId) ||
                ((tmdbId <= 0 || providerIds?.tmdb.isNullOrBlank()) && !imdbId.isNullOrBlank() && providerIds?.imdb == imdbId))
        return lookupLock.withLock {
            val cached = libraryIndexes[type]?.takeIf {
                it.connection == connection && it.userId == userId &&
                    Clock.System.now().toEpochMilliseconds() - it.loadedAt < 120_000L
            }
            // A miss is refreshed: a copy may have been imported since the last lookup.
            if (cached != null) {
                val match = cached.items.firstOrNull { it.matches() }
                if (match != null || !refreshMissing) return@withLock match
            }
            val items = mutableListOf<JellyfinBrowseItem>()
            val seen = mutableSetOf<String>()
            var offset = 0
            while (true) {
                val response = httpClient.get("${connection.first}/Users/$userId/Items") {
                    header("X-Emby-Token", connection.second)
                    parameter("Recursive", "true")
                    parameter("IncludeItemTypes", type)
                    parameter("Fields", "ProviderIds")
                    parameter("EnableImages", "false")
                    parameter("EnableUserData", "false")
                    parameter("IsMissing", "false")
                    parameter("IsPlaceHolder", "false")
                    parameter("SortBy", "SortName")
                    parameter("Limit", 200)
                    parameter("StartIndex", offset)
                }.body<JellyfinBrowseItemsResponse>()
                val added = response.items.filter { seen.add(it.id) }
                items += added
                offset += response.items.size
                if (added.isEmpty() || response.items.size < 200 ||
                    response.totalRecordCount?.let { offset >= it } == true) break
            }
            libraryIndexes[type] = LibraryIndex(connection, userId, type,
                Clock.System.now().toEpochMilliseconds(), items)
            items.firstOrNull { it.matches() }
        }
    }

    private suspend fun resolveUserId(): String? {
        return try {
            resolveUserIdOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun userLookup(server: String, apiKey: String, path: String): HttpResponse = try {
        httpClient.get("$server$path") {
            header("X-Emby-Token", apiKey)
            timeout { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (response: ResponseException) {
        response.response
    } catch (failure: Exception) {
        throw IllegalStateException(
            "Cannot reach Jellyfin. Check that the server is running and reachable from this device. A home-network address needs a connection to that network.",
            failure,
        )
    }

    private fun userLookupFailure(status: HttpStatusCode): Nothing = when (status.value) {
        401, 403 -> error("Jellyfin rejected access to its users. Reconnect in Jellyfin settings with a valid API key or user access token.")
        404 -> error("Jellyfin could not find its users endpoint. Check the server URL in Jellyfin settings.")
        else -> error("Jellyfin is unavailable (HTTP ${status.value}). Try again when the server is ready.")
    }

    private suspend fun resolveUserIdOrThrow(): String {
        val stored = prefsRepo.getString(KEY_SELECTED_USER_ID)
        if (!stored.isNullOrBlank()) return stored
        val (server, apiKey) = serverAndKey() ?: error("Server URL or API key not configured")
        // Try /Users/Me first (works with user access tokens)
        val me = userLookup(server, apiKey, "/Users/Me")
        if (me.status.isSuccess()) {
            val id = try { me.body<JellyfinUser>().id.takeIf(String::isNotBlank) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            return id ?: error("Jellyfin returned an invalid user response. Check the server URL in Jellyfin settings.")
        }
        // A server API key has no current user. Only these responses justify
        // the users-list fallback; an offline server must not be retried three times.
        if (me.status.value !in setOf(400, 401, 403, 404)) userLookupFailure(me.status)
        // Fall back to first user from /Users (works with server API keys)
        val users = userLookup(server, apiKey, "/Users")
        if (!users.status.isSuccess()) userLookupFailure(users.status)
        val firstUser = try { users.body<List<JellyfinUserFull>>().firstOrNull { it.id.isNotBlank() }?.id }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error("Jellyfin returned an invalid users response. Check the server URL in Jellyfin settings.") }
            ?: error("This Jellyfin server has no user profiles. Create a user on the server, then try again.")
        prefsRepo.setString(KEY_SELECTED_USER_ID, firstUser)
        return firstUser
    }

    private suspend fun serverAndKey(): Pair<String, String>? {
        if (!prefsRepo.isAppFeatureEnabled(OptionalAppFeature.JELLYFIN)) return null
        val server = prefsRepo.getString(KEY_SERVER_URL)?.trimEnd('/') ?: return null
        val apiKey = secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY) ?: return null
        if (server.isBlank() || apiKey.isBlank()) return null
        return server to apiKey
    }

    override suspend fun isInLibrary(tmdbId: Int, mediaType: MediaType): Boolean {
        if (family?.state?.value?.isKids == true) return false
        return runCatching { findCatalogItem(tmdbId, mediaType) != null }.getOrDefault(false)
    }

    override suspend fun getContinueWatching(limit: Int): List<WatchProgress> {
        if (family?.state?.value?.isKids == true) return emptyList()
        val (server, apiKey) = serverAndKey() ?: return emptyList()
        val userId = resolveUserId() ?: return emptyList()
        val response = runCatching {
            httpClient.get("$server/Users/$userId/Items/Resume") {
                header("X-Emby-Token", apiKey)
                parameter("Limit", limit)
                parameter("Fields", "ProviderIds")
                parameter("EnableImages", "true")
                parameter("EnableUserData", "true")
            }.body<JellyfinBrowseItemsResponse>()
        }.getOrNull() ?: return emptyList()

        prefsRepo.setString(KEY_LIBRARY_OVERLAY_LAST_SYNC, Clock.System.now().toEpochMilliseconds().toString())

        val seriesIds = response.items.filter { it.isEpisode }.mapNotNull { it.seriesId }.distinct()
        val parents = if (seriesIds.isEmpty()) emptyMap() else runCatching {
            httpClient.get("$server/Users/$userId/Items") {
                header("X-Emby-Token", apiKey)
                parameter("Ids", seriesIds.joinToString(","))
                parameter("Fields", "ProviderIds")
            }.body<JellyfinBrowseItemsResponse>().items.associateBy { it.id }
        }.getOrDefault(emptyMap())
        return response.items.mapNotNull { item ->
            val parent = item.seriesId?.let(parents::get)
            val identity = if (item.isEpisode) parent else item
            val mediaId = identity?.providerIds?.tmdb?.toIntOrNull()?.toString()
                ?: identity?.providerIds?.imdb?.takeIf { it.isNotBlank() }
                ?: "jf_${if (item.isEpisode) item.seriesId ?: item.id else item.id}"
            val totalTicks = item.runTimeTicks ?: return@mapNotNull null
            val positionTicks = item.userData?.playbackPositionTicks ?: 0L
            val art = parent ?: item
            WatchProgress(
                mediaId = mediaId,
                mediaType = if (item.isEpisode || item.type.equals("Series", true)) MediaType.SERIES else MediaType.MOVIE,
                title = item.displayTitle,
                posterUrl = if (art.resolvedPrimaryImageTag != null) buildImageUrl(art.id) else null,
                backdropUrl = if (art.backdropImageTags.isNotEmpty()) buildBackdropImageUrl(art.id) else null,
                positionMs = positionTicks / 10_000L,
                durationMs = totalTicks / 10_000L,
                seasonNumber = item.parentIndexNumber.takeIf { item.isEpisode },
                episodeNumber = item.indexNumber.takeIf { item.isEpisode },
                showTitle = item.seriesName.takeIf { item.isEpisode },
                updatedAt = item.userData?.lastPlayedDate?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L,
            )
        }
    }

    suspend fun getUserProfiles(): List<JellyfinProfile> {
        val (server, apiKey) = serverAndKey() ?: return emptyList()
        return runCatching {
            httpClient.get("$server/Users") {
                header("X-Emby-Token", apiKey)
            }.body<List<JellyfinUserFull>>()
        }.getOrDefault(emptyList()).map { user ->
            JellyfinProfile(
                id = user.id,
                name = user.name,
                isAdmin = user.policy?.isAdministrator == true,
            )
        }
    }

    suspend fun getSelectedUserId(): String? = prefsRepo.getString(KEY_SELECTED_USER_ID)

    suspend fun setSelectedUserId(userId: String?) {
        if (userId.isNullOrBlank()) {
            prefsRepo.remove(KEY_SELECTED_USER_ID)
        } else {
            prefsRepo.setString(KEY_SELECTED_USER_ID, userId)
        }
    }

    override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean =
        probeConnection(serverUrl, apiKey) == ConnectionProbeResult.CONNECTED

    override suspend fun probeConnection(serverUrl: String, apiKey: String): ConnectionProbeResult {
        if (!prefsRepo.isAppFeatureEnabled(OptionalAppFeature.JELLYFIN)) return ConnectionProbeResult.SETUP_INCOMPLETE
        val base = serverUrl.trim().trimEnd('/')
        if (base.isBlank() || apiKey.isBlank()) return ConnectionProbeResult.SETUP_INCOMPLETE
        return authenticatedProbe {
            httpClient.get("$base/System/Info") {
                header("X-Emby-Token", apiKey)
            }.status
        }
    }

    // ── Library browsing ──

    suspend fun isConnected(): Boolean = serverAndKey() != null

    /**
     * Returns library sections, or throws with a diagnostic message on failure.
     */
    suspend fun getLibrarySectionsOrThrow(forFamilySetup: Boolean = false): List<JellyfinLibrarySection> {
        if (forFamilySetup) family?.requireParent()
        val (server, apiKey) = serverAndKey()
            ?: error("Server URL or API key not configured")
        val userId = resolveUserIdOrThrow()
        val response = httpClient.get("$server/Users/$userId/Views") {
            header("X-Emby-Token", apiKey)
        }.body<JellyfinViewsResponse>()
        // Only exclude pure audio/book libraries — show everything else
        val filtered = response.items.filter { section ->
            section.collectionType !in listOf("music", "playlists", "books")
        }
        println("JELLYFIN: getLibrarySections total=${response.items.size} filtered=${filtered.size} sections=${filtered.map { "${it.name}(${it.collectionType})" }}")
        return filtered.filter { forFamilySetup || family?.allowsSource("jellyfin:${it.id}") != false }
    }

    suspend fun getLibraryItems(
        parentId: String,
        startIndex: Int = 0,
        limit: Int = 50,
    ): Pair<List<JellyfinBrowseItem>, Int> {
        if (family?.allowsSource("jellyfin:$parentId") == false) return emptyList<JellyfinBrowseItem>() to 0
        val (server, apiKey) = serverAndKey() ?: return emptyList<JellyfinBrowseItem>() to 0
        val userId = resolveUserId() ?: return emptyList<JellyfinBrowseItem>() to 0
        return runCatching {
            val resp: JellyfinBrowseItemsResponse = httpClient.get("$server/Users/$userId/Items") {
                header("X-Emby-Token", apiKey)
                parameter("ParentId", parentId)
                parameter("Recursive", "true")
                // Library browsing is parent-level. Episodes are discovered from
                // the selected series detail and must not flood the main library.
                parameter("IncludeItemTypes", "Movie,Series,Video,MusicVideo")
                parameter("SortBy", "SortName")
                parameter("SortOrder", "Ascending")
                parameter(
                    "Fields",
                    "Overview,Path,ProviderIds,Genres,Studios,DateCreated,OfficialRating",
                )
                parameter("Limit", limit)
                parameter("StartIndex", startIndex)
            }.body()
            println("JELLYFIN: getLibraryItems parentId=$parentId returned=${resp.items.size} total=${resp.totalRecordCount}")
            val visible = resp.items.filter { item ->
                val country = family?.state?.value?.configuration?.kids?.get(family.state.value.activeProfileId)?.ratingCountry ?: "US"
                val media = com.torve.domain.model.MediaItem(id = "jf_${item.id}", imdbId = item.providerIds?.imdb, tmdbId = item.providerIds?.tmdb?.toIntOrNull(),
                    type = if (item.type.equals("Series", true)) MediaType.SERIES else MediaType.MOVIE, title = item.name,
                    ageCertifications = item.officialRating?.let { listOf(com.torve.domain.model.AgeCertification(country, it)) }.orEmpty(), familySourceKey = "jellyfin:$parentId")
                family?.rememberExternal("jellyfin", item.id, media, "jellyfin:$parentId") != false
            }
            visible to (resp.totalRecordCount ?: resp.items.size)
        }.getOrDefault(emptyList<JellyfinBrowseItem>() to 0)
    }

    suspend fun getSeriesEpisodes(seriesId: String): List<JellyfinBrowseItem> {
        if (family?.externalAllowed("jellyfin", seriesId) == false) return emptyList()
        val (server, apiKey) = serverAndKey() ?: return emptyList()
        val userId = resolveUserId() ?: return emptyList()
        val response: JellyfinBrowseItemsResponse = httpClient.get("$server/Shows/$seriesId/Episodes") {
            header("X-Emby-Token", apiKey)
            parameter("UserId", userId)
            parameter("Fields", "ProviderIds,Overview")
        }.body()
        response.items.forEach { family?.inheritExternal("jellyfin", seriesId, it.id) }
        return response.items.filter(JellyfinBrowseItem::isEpisode)
            .sortedWith(compareBy({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: 0 }))
    }

    suspend fun buildImageUrl(itemId: String, maxHeight: Int = 400): String? {
        val (server, _) = serverAndKey() ?: return null
        return "$server/Items/$itemId/Images/Primary?maxHeight=$maxHeight&quality=90"
    }

    suspend fun buildBackdropImageUrl(itemId: String, maxWidth: Int = 1280): String? {
        val (server, _) = serverAndKey() ?: return null
        return "$server/Items/$itemId/Images/Backdrop/0?maxWidth=$maxWidth&quality=90"
    }

    suspend fun buildStreamUrl(itemId: String): String? {
        if (family?.externalAllowed("jellyfin", itemId) == false) return null
        val (server, apiKey) = serverAndKey() ?: return null
        // Direct stream — static=true bypasses transcoding entirely
        return "$server/Videos/$itemId/stream?static=true&api_key=$apiKey".also { family?.registerExternalPlayback(it, "jellyfin", itemId) }
    }
    /** Header-capable players do not need the account key embedded in their source URL. */
    suspend fun buildAuthenticatedStream(itemId: String): PlaybackRoute.JellyfinStream? {
        if (family?.externalAllowed("jellyfin", itemId) == false) return null
        val (server, apiKey) = serverAndKey() ?: return null
        return PlaybackRoute.JellyfinStream("$server/Videos/$itemId/stream?static=true", mapOf("X-Emby-Token" to apiKey)).also { family?.registerExternalPlayback(it.url, "jellyfin", itemId) }
    }

    suspend fun findAvailableEpisodes(tmdbId: Int): Set<Pair<Int, Int>> {
        if (family?.state?.value?.isKids == true) return emptySet()
        val (server, apiKey) = serverAndKey() ?: return emptySet()
        val userId = resolveUserId() ?: return emptySet()
        val series = runCatching { findCatalogItem(tmdbId, MediaType.SERIES) }.getOrNull() ?: return emptySet()

        return runCatching {
            httpClient.get("$server/Shows/${series.id}/Episodes") {
                header("X-Emby-Token", apiKey)
                parameter("UserId", userId)
                parameter("Fields", "ProviderIds")
            }.body<JellyfinBrowseItemsResponse>()
        }.getOrNull()?.items.orEmpty().mapNotNull { episode ->
            val season = episode.parentIndexNumber ?: return@mapNotNull null
            val number = episode.indexNumber ?: return@mapNotNull null
            season to number
        }.toSet()
    }

    /** Read-only, user-scoped copies. Errors remain unknown instead of becoming absence. */
    suspend fun playableCatalogCopies(tmdbId: Int, mediaType: MediaType, imdbId: String? = null): Set<String>? {
        if (family?.state?.value?.isKids == true) return emptySet()
        val (server, apiKey) = serverAndKey() ?: return null
        val userId = resolveUserId() ?: error("Jellyfin user is unavailable")
        val item = findCatalogItem(tmdbId, mediaType, imdbId, refreshMissing = true) ?: return emptySet()
        if (mediaType == MediaType.MOVIE) {
            return if (confirmPlayableCopy(item.id, server, apiKey)) setOf("movie") else emptySet()
        }
        val response = httpClient.get("$server/Shows/${item.id}/Episodes") {
            header("X-Emby-Token", apiKey)
            parameter("UserId", userId)
            parameter("IsMissing", "false")
            parameter("IsPlaceHolder", "false")
            parameter("Fields", "MediaSources")
        }
        check(response.status.isSuccess()) { "Jellyfin episode lookup failed" }
        return response.body<JellyfinBrowseItemsResponse>().items.filter {
            it.isEpisode && !it.isMissing && !it.isPlaceHolder && it.mediaSources.isNotEmpty()
        }.mapNotNull {
            val season = it.parentIndexNumber ?: return@mapNotNull null
            val episode = it.indexNumber ?: return@mapNotNull null
            "s${season}e$episode"
        }.toSet()
    }

    private suspend fun confirmPlayableCopy(itemId: String, server: String, apiKey: String): Boolean {
        val response = httpClient.head("$server/Videos/$itemId/stream") {
            header("X-Emby-Token", apiKey)
            parameter("static", "true")
        }
        if (response.status == HttpStatusCode.NotFound) return false
        check(response.status.isSuccess()) { "Jellyfin playback availability could not be verified" }
        return true
    }

    suspend fun verifiedCatalogPlaybackRoute(tmdbId: Int, mediaType: MediaType, season: Int?, episode: Int?, imdbId: String? = null): PlaybackRoute.JellyfinStream? {
        if (family?.state?.value?.isKids == true) return null
        val route = findPlaybackRoute(tmdbId, mediaType, season, episode, imdbId) ?: return null
        val (server, apiKey) = serverAndKey() ?: return null
        val itemId = route.url.substringAfter("/Videos/").substringBefore("/stream")
        return route.takeIf { confirmPlayableCopy(itemId, server, apiKey) }
    }

    /**
     * Resolves a catalog title to its permanent Jellyfin copy. Authentication
     * travels as a one-shot player header, never in the navigation URL or logs.
     */
    suspend fun findPlaybackRoute(
        tmdbId: Int,
        mediaType: MediaType,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null,
    ): PlaybackRoute.JellyfinStream? {
        val (server, apiKey) = serverAndKey() ?: return null
        val userId = resolveUserId() ?: return null
        val topLevel = findCatalogItem(tmdbId, mediaType, imdbId, refreshMissing = true) ?: return null

        val playableId = if (mediaType == MediaType.MOVIE) {
            topLevel.id
        } else {
            val season = seasonNumber ?: return null
            val episode = episodeNumber ?: return null
            runCatching {
                httpClient.get("$server/Shows/${topLevel.id}/Episodes") {
                    header("X-Emby-Token", apiKey)
                    parameter("UserId", userId)
                    parameter("Season", season)
                    parameter("Fields", "ProviderIds")
                }.body<JellyfinBrowseItemsResponse>()
            }.getOrNull()?.items?.firstOrNull {
                it.parentIndexNumber == season && it.indexNumber == episode
            }?.id ?: return null
        }

        return PlaybackRoute.JellyfinStream(
            url = "$server/Videos/$playableId/stream?static=true",
            headers = mapOf("X-Emby-Token" to apiKey),
        )
    }

    companion object {
        private const val KEY_SERVER_URL = "jellyfin_server_url"
        internal const val KEY_SELECTED_USER_ID = "jellyfin_selected_user_id"
        private const val KEY_LIBRARY_OVERLAY_LAST_SYNC = "library_overlay_last_sync_time"
    }
}

// ── Public models ──

data class JellyfinProfile(
    val id: String,
    val name: String,
    val isAdmin: Boolean = false,
)

@Serializable
data class JellyfinLibrarySection(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("CollectionType") val collectionType: String? = null,
)

@Serializable
data class JellyfinBrowseItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Type") val type: String = "",
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ProviderIds") val providerIds: JellyfinProviderIds? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("Studios") val studios: List<JellyfinStudio> = emptyList(),
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("UserData") val userData: JellyfinBrowseUserData? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("IsMissing") val isMissing: Boolean = false,
    @SerialName("IsPlaceHolder") val isPlaceHolder: Boolean = false,
    @SerialName("MediaSources") val mediaSources: List<JellyfinAvailableMediaSource> = emptyList(),
    @kotlinx.serialization.Transient val fallbackPosterUrl: String? = null,
    @kotlinx.serialization.Transient val fallbackBackdropUrl: String? = null,
) {
    val resolvedPrimaryImageTag: String?
        get() = imageTags["Primary"] ?: primaryImageTag

    val isEpisode: Boolean
        get() = type.equals("Episode", ignoreCase = true)

    val displayTitle: String
        get() = if (isEpisode) seriesName?.takeIf { it.isNotBlank() } ?: name else name

    val displaySubtitle: String?
        get() = if (isEpisode) {
            val season = parentIndexNumber?.toString()?.padStart(2, '0') ?: "??"
            val episode = indexNumber?.toString()?.padStart(2, '0') ?: "??"
            "S${season}E$episode · $name"
        } else {
            null
        }
}

@Serializable
data class JellyfinAvailableMediaSource(@SerialName("Id") val id: String = "")

@Serializable
data class JellyfinStudio(
    @SerialName("Name") val name: String = "",
)

// ── Private DTOs ──

@Serializable
private data class JellyfinPublicInfo(
    @SerialName("ServerName") val serverName: String = "",
)

@Serializable
data class JellyfinBrowseUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0L,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

@Serializable
private data class JellyfinUser(
    @SerialName("Id") val id: String,
)

@Serializable
private data class JellyfinUserFull(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Policy") val policy: JellyfinUserPolicy? = null,
)

@Serializable
private data class JellyfinUserPolicy(
    @SerialName("IsAdministrator") val isAdministrator: Boolean = false,
)

@Serializable
private data class JellyfinViewsResponse(
    @SerialName("Items") val items: List<JellyfinLibrarySection> = emptyList(),
)

@Serializable
private data class JellyfinBrowseItemsResponse(
    @SerialName("Items") val items: List<JellyfinBrowseItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

@Serializable
private data class JellyfinItemsResponse(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
)

@Serializable
private data class JellyfinItem(
    @SerialName("Name") val name: String,
    @SerialName("Type") val type: String = "",
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProviderIds") val providerIds: JellyfinProviderIds? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
)

@Serializable
data class JellyfinProviderIds(
    @SerialName("Tmdb") val tmdb: String? = null,
    @SerialName("Imdb") val imdb: String? = null,
)

@Serializable
private data class JellyfinUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
)
