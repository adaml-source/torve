package com.streamvault.data.integrations

import com.streamvault.domain.integrations.IntegrationSecretKey
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.domain.integrations.LibraryOverlayService
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.repository.PreferencesRepository
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
        return runCatching {
            httpClient.get("$server/Users/Me") {
                header("X-Emby-Token", apiKey)
            }.body<JellyfinUser>()
        }.getOrNull()?.id
    }

    override suspend fun isInLibrary(tmdbId: Int, mediaType: MediaType): Boolean {
        val server = prefsRepo.getString(KEY_SERVER_URL)?.trimEnd('/') ?: return false
        val apiKey = secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY) ?: return false
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
        val server = prefsRepo.getString(KEY_SERVER_URL)?.trimEnd('/') ?: return emptyList()
        val apiKey = secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY) ?: return emptyList()
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
        val server = prefsRepo.getString(KEY_SERVER_URL)?.trimEnd('/') ?: return emptyList()
        val apiKey = secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY) ?: return emptyList()
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
            httpClient.get("$base/System/Info/Public").body<JellyfinPublicInfo>()
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val KEY_SERVER_URL = "jellyfin_server_url"
        private const val KEY_SELECTED_USER_ID = "jellyfin_selected_user_id"
        private const val KEY_LIBRARY_OVERLAY_LAST_SYNC = "library_overlay_last_sync_time"
    }
}

data class JellyfinProfile(
    val id: String,
    val name: String,
    val isAdmin: Boolean = false,
)

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

