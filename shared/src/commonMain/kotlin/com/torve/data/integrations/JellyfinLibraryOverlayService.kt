package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class JellyfinLibraryOverlayService(
    private val httpClient: HttpClient,
    private val prefsRepo: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) : LibraryOverlayService {

    private suspend fun resolveUserId(): String? {
        val stored = prefsRepo.getString(KEY_SELECTED_USER_ID)
        if (!stored.isNullOrBlank()) return stored
        val server = prefsRepo.getString(KEY_SERVER_URL)?.trimEnd('/') ?: return null
        val apiKey = secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY) ?: return null
        // Try /Users/Me first (works with user access tokens)
        val meId = runCatching {
            httpClient.get("$server/Users/Me") {
                header("X-Emby-Token", apiKey)
            }.body<JellyfinUser>()
        }.getOrNull()?.id
        if (meId != null) return meId
        // Fall back to first user from /Users (works with server API keys)
        val firstUser = runCatching {
            httpClient.get("$server/Users") {
                header("X-Emby-Token", apiKey)
            }.body<List<JellyfinUserFull>>()
        }.getOrNull()?.firstOrNull()?.id
        if (firstUser != null) {
            prefsRepo.setString(KEY_SELECTED_USER_ID, firstUser)
        }
        return firstUser
    }

    private suspend fun serverAndKey(): Pair<String, String>? {
        val server = prefsRepo.getString(KEY_SERVER_URL)?.trimEnd('/') ?: return null
        val apiKey = secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY) ?: return null
        if (server.isBlank() || apiKey.isBlank()) return null
        return server to apiKey
    }

    override suspend fun isInLibrary(tmdbId: Int, mediaType: MediaType): Boolean {
        val (server, apiKey) = serverAndKey() ?: return false
        val userId = resolveUserId() ?: return false
        val includeType = if (mediaType == MediaType.MOVIE) "Movie" else "Series"
        return runCatching {
            val response: JellyfinItemsResponse = httpClient.get("$server/Users/$userId/Items") {
                header("X-Emby-Token", apiKey)
                parameter("Recursive", "true")
                parameter("AnyProviderIdEquals", "Tmdb.$tmdbId")
                parameter("IncludeItemTypes", includeType)
                parameter("Fields", "ProviderIds")
                parameter("Limit", 1)
            }.body()
            // Do not trust a merely non-empty response. Older or proxied
            // Jellyfin servers can ignore an unsupported query parameter and
            // return the first library item, which would mark every title as
            // present. Verify both identity and type from the returned item.
            response.items.any { item ->
                item.type.equals(includeType, ignoreCase = true) &&
                    item.providerIds?.tmdb?.toIntOrNull() == tmdbId
            }
        }.getOrDefault(false)
    }

    override suspend fun getContinueWatching(limit: Int): List<WatchProgress> {
        val (server, apiKey) = serverAndKey() ?: return emptyList()
        val userId = resolveUserId() ?: return emptyList()
        val response = runCatching {
            httpClient.get("$server/Users/$userId/Items/Resume") {
                header("X-Emby-Token", apiKey)
                parameter("Limit", limit)
                parameter("Fields", "ProviderIds,UserData")
            }.body<JellyfinItemsResponse>()
        }.getOrNull() ?: return emptyList()

        prefsRepo.setString(KEY_LIBRARY_OVERLAY_LAST_SYNC, Clock.System.now().toEpochMilliseconds().toString())

        return response.items.mapNotNull { item ->
            val tmdbId = item.providerIds?.tmdb?.toIntOrNull() ?: return@mapNotNull null
            val totalTicks = item.runTimeTicks ?: return@mapNotNull null
            val positionTicks = item.userData?.playbackPositionTicks ?: 0L
            WatchProgress(
                mediaId = tmdbId.toString(),
                mediaType = if (item.type.equals("Movie", ignoreCase = true)) MediaType.MOVIE else MediaType.SERIES,
                title = item.name,
                posterUrl = null,
                backdropUrl = null,
                positionMs = positionTicks / 10_000L,
                durationMs = totalTicks / 10_000L,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
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

    override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean {
        val base = serverUrl.trim().trimEnd('/')
        if (base.isBlank() || apiKey.isBlank()) return false
        return runCatching {
            httpClient.get("$base/System/Info") {
                header("X-Emby-Token", apiKey)
            }.body<JellyfinPublicInfo>()
            true
        }.getOrDefault(false)
    }

    // ── Library browsing ──

    suspend fun isConnected(): Boolean = serverAndKey() != null

    /**
     * Returns library sections, or throws with a diagnostic message on failure.
     */
    suspend fun getLibrarySectionsOrThrow(): List<JellyfinLibrarySection> {
        val (server, apiKey) = serverAndKey()
            ?: error("Server URL or API key not configured")
        var userId = resolveUserId()
        if (userId == null) {
            // resolveUserId failed — try getUserProfiles as last resort
            val profiles = getUserProfiles()
            if (profiles.isEmpty()) {
                error("Could not resolve Jellyfin user. /Users/Me and /Users both failed. Check your API key permissions.")
            }
            userId = profiles.first().id
            prefsRepo.setString(KEY_SELECTED_USER_ID, userId)
        }
        val response = httpClient.get("$server/Users/$userId/Views") {
            header("X-Emby-Token", apiKey)
        }.body<JellyfinViewsResponse>()
        // Only exclude pure audio/book libraries — show everything else
        val filtered = response.items.filter { section ->
            section.collectionType !in listOf("music", "playlists", "books")
        }
        println("JELLYFIN: getLibrarySections total=${response.items.size} filtered=${filtered.size} sections=${filtered.map { "${it.name}(${it.collectionType})" }}")
        return filtered
    }

    suspend fun getLibraryItems(
        parentId: String,
        startIndex: Int = 0,
        limit: Int = 50,
    ): Pair<List<JellyfinBrowseItem>, Int> {
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
                    "Overview,ImageTags,PrimaryImageTag,BackdropImageTags,ProductionYear,Path,ProviderIds,CommunityRating,OfficialRating,Genres,Studios,SeriesName,SeriesId,DateCreated,RunTimeTicks,UserData",
                )
                parameter("Limit", limit)
                parameter("StartIndex", startIndex)
            }.body()
            println("JELLYFIN: getLibraryItems parentId=$parentId returned=${resp.items.size} total=${resp.totalRecordCount}")
            resp.items to resp.totalRecordCount
        }.getOrDefault(emptyList<JellyfinBrowseItem>() to 0)
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
        val (server, apiKey) = serverAndKey() ?: return null
        // Direct stream — static=true bypasses transcoding entirely
        return "$server/Videos/$itemId/stream?static=true&api_key=$apiKey"
    }

    suspend fun findAvailableEpisodes(tmdbId: Int): Set<Pair<Int, Int>> {
        val (server, apiKey) = serverAndKey() ?: return emptySet()
        val userId = resolveUserId() ?: return emptySet()
        val series = runCatching {
            httpClient.get("$server/Users/$userId/Items") {
                header("X-Emby-Token", apiKey)
                parameter("Recursive", "true")
                parameter("AnyProviderIdEquals", "Tmdb.$tmdbId")
                parameter("IncludeItemTypes", "Series")
                parameter("Fields", "ProviderIds")
                parameter("Limit", 10)
            }.body<JellyfinBrowseItemsResponse>()
        }.getOrNull()?.items?.firstOrNull { item ->
            item.type.equals("Series", ignoreCase = true) &&
                item.providerIds?.tmdb?.toIntOrNull() == tmdbId
        } ?: return emptySet()

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

    /**
     * Resolves a catalog title to its permanent Jellyfin copy. Authentication
     * travels as a one-shot player header, never in the navigation URL or logs.
     */
    suspend fun findPlaybackRoute(
        tmdbId: Int,
        mediaType: MediaType,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): PlaybackRoute.JellyfinStream? {
        val (server, apiKey) = serverAndKey() ?: return null
        val userId = resolveUserId() ?: return null
        val topLevelType = if (mediaType == MediaType.MOVIE) "Movie" else "Series"
        val topLevel = runCatching {
            httpClient.get("$server/Users/$userId/Items") {
                header("X-Emby-Token", apiKey)
                parameter("Recursive", "true")
                parameter("AnyProviderIdEquals", "Tmdb.$tmdbId")
                parameter("IncludeItemTypes", topLevelType)
                parameter("Fields", "ProviderIds")
                parameter("Limit", 10)
            }.body<JellyfinBrowseItemsResponse>()
        }.getOrNull()?.items?.firstOrNull { item ->
            item.type.equals(topLevelType, ignoreCase = true) &&
                item.providerIds?.tmdb?.toIntOrNull() == tmdbId
        } ?: return null

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
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
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
