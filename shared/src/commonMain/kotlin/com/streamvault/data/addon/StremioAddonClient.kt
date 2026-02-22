package com.streamvault.data.addon

import com.streamvault.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

/**
 * Generic Stremio addon client that supports any addon conforming
 * to the Stremio protocol (not just Torrentio).
 */
class StremioAddonClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        const val TORRENTIO_BASE = "https://torrentio.strem.fun"
    }

    /**
     * Fetch the addon manifest from a base URL.
     */
    suspend fun getManifest(baseUrl: String): StremioManifest {
        val url = baseUrl.trimEnd('/') + "/manifest.json"
        val response = httpClient.get(url)
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Fetch streams from any Stremio-compatible addon.
     */
    suspend fun getStreams(
        baseUrl: String,
        type: MediaType,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<ParsedStream> {
        val stremioType = when (type) {
            MediaType.MOVIE -> "movie"
            MediaType.SERIES -> "series"
        }
        val stremioId = if (type == MediaType.SERIES && season != null && episode != null) {
            "$imdbId:$season:$episode"
        } else {
            imdbId
        }

        return try {
            val url = "${baseUrl.trimEnd('/')}/stream/$stremioType/$stremioId.json"
            val response: StremioStreamResponse = httpClient.get(url).body()
            val addonName = try {
                getManifest(baseUrl).name
            } catch (_: Exception) {
                baseUrl.substringAfter("://").substringBefore("/")
            }
            response.streams.map { StreamParser.parse(it, addonName) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Convenience: fetch streams from Torrentio specifically.
     */
    suspend fun fetchTorrentioStreams(
        type: MediaType,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<ParsedStream> {
        return getStreams(TORRENTIO_BASE, type, imdbId, season, episode)
    }

    /**
     * Fetch streams from multiple addons in parallel-style (sequentially for simplicity).
     */
    suspend fun fetchAllStreams(
        addonUrls: List<String>,
        type: MediaType,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<ParsedStream> {
        return addonUrls.flatMap { url ->
            try {
                getStreams(url, type, imdbId, season, episode)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
