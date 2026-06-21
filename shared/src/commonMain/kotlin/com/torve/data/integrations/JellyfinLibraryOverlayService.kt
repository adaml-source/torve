package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
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
                parameter("Limit", 1)
            }.body()
            response.items.isNotEmpty()
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
                parameter("IncludeItemTypes", "Movie,Series,Video,Episode,MusicVideo")
                parameter("SortBy", "SortName")
                parameter("SortOrder", "Ascending")
                parameter("Fields", "Overview,PrimaryImageTag,ProductionYear,Path")
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

    suspend fun buildStreamUrl(itemId: String): String? {
        val (server, apiKey) = serverAndKey() ?: return null
        // Direct stream — static=true bypasses transcoding entirely
        return "$server/Videos/$itemId/stream?static=true&api_key=$apiKey"
    }

    companion object {
        private const val KEY_SERVER_URL = "jellyfin_server_url"
        private const val KEY_SELECTED_USER_ID = "jellyfin_selected_user_id"
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
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

// ── Private DTOs ──

@Serializable
private data class JellyfinPublicInfo(
    @SerialName("ServerName") val serverName: String = "",
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
private data class JellyfinProviderIds(
    @SerialName("Tmdb") val tmdb: String? = null,
)

@Serializable
private data class JellyfinUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
)
