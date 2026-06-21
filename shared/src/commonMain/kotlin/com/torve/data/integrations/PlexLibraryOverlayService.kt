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

class PlexLibraryOverlayService(
    private val httpClient: HttpClient,
    private val prefsRepo: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) : LibraryOverlayService {

    override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean {
        val base = serverUrl.trim().trimEnd('/')
        if (base.isBlank() || apiKey.isBlank()) return false
        return runCatching {
            val resp: PlexMediaContainer<PlexIdentity> = httpClient.get("$base/identity") {
                header("X-Plex-Token", apiKey)
                header("Accept", "application/json")
            }.body()
            resp.mediaContainer.machineIdentifier.isNotBlank()
        }.getOrDefault(false)
    }

    override suspend fun isInLibrary(tmdbId: Int, mediaType: MediaType): Boolean {
        val server = prefsRepo.getString(KEY_SERVER_URL)?.trimEnd('/') ?: return false
        val token = secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN) ?: return false
        return runCatching {
            val resp: PlexMediaContainer<PlexSearchContainer> =
                httpClient.get("$server/hubs/search") {
                    header("X-Plex-Token", token)
                    header("Accept", "application/json")
                    parameter("query", tmdbId.toString())
                    parameter("includeGuids", "1")
                }.body()

            val targetType = if (mediaType == MediaType.MOVIE) "movie" else "show"
            resp.mediaContainer.hubs.flatMap { it.metadata }.any { item ->
                item.type == targetType && extractTmdbId(item.guids) == tmdbId
            }
        }.getOrDefault(false)
    }

    override suspend fun getContinueWatching(limit: Int): List<WatchProgress> {
        val server = prefsRepo.getString(KEY_SERVER_URL)?.trimEnd('/') ?: return emptyList()
        val token = secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN) ?: return emptyList()

        val items = runCatching {
            val resp: PlexMediaContainer<PlexOnDeck> =
                httpClient.get("$server/library/onDeck") {
                    header("X-Plex-Token", token)
                    header("Accept", "application/json")
                    parameter("includeGuids", "1")
                    parameter("X-Plex-Container-Start", "0")
                    parameter("X-Plex-Container-Size", limit.toString())
                }.body()
            resp.mediaContainer.metadata
        }.getOrDefault(emptyList())

        return items.mapNotNull { item ->
            val tmdbId = extractTmdbId(item.guids) ?: return@mapNotNull null
            val duration = item.duration ?: return@mapNotNull null
            val viewOffset = item.viewOffset ?: 0L
            WatchProgress(
                mediaId = tmdbId.toString(),
                mediaType = if (item.type == "movie") MediaType.MOVIE else MediaType.SERIES,
                title = item.title,
                posterUrl = null,
                backdropUrl = null,
                positionMs = viewOffset,
                durationMs = duration,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    private fun extractTmdbId(guids: List<PlexGuid>): Int? {
        return guids.firstOrNull { it.id.startsWith("tmdb://") }
            ?.id?.removePrefix("tmdb://")?.toIntOrNull()
    }

    companion object {
        private const val KEY_SERVER_URL = "plex_server_url"
    }
}

// ── Plex API response models ──

@Serializable
private data class PlexMediaContainer<T>(
    @SerialName("MediaContainer") val mediaContainer: T,
)

@Serializable
private data class PlexIdentity(
    val machineIdentifier: String = "",
)

@Serializable
private data class PlexOnDeck(
    @SerialName("Metadata") val metadata: List<PlexMetadataItem> = emptyList(),
)

@Serializable
private data class PlexSearchContainer(
    @SerialName("Hub") val hubs: List<PlexHub> = emptyList(),
)

@Serializable
private data class PlexHub(
    val hubIdentifier: String = "",
    @SerialName("Metadata") val metadata: List<PlexMetadataItem> = emptyList(),
)

@Serializable
private data class PlexMetadataItem(
    val title: String = "",
    val type: String = "",
    val duration: Long? = null,
    val viewOffset: Long? = null,
    @SerialName("Guid") val guids: List<PlexGuid> = emptyList(),
)

@Serializable
private data class PlexGuid(
    val id: String = "",
)
