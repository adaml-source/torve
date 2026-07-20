package com.torve.data.addon

import com.torve.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json

private const val ADDON_MANIFEST_MAX_BYTES = 256 * 1024
private const val ADDON_STREAM_MAX_BYTES = 2 * 1024 * 1024
private const val ADDON_CATALOG_MAX_BYTES = 2 * 1024 * 1024
private const val ADDON_META_MAX_BYTES = 512 * 1024
private const val ADDON_SUBTITLE_MAX_BYTES = 512 * 1024

/**
 * Generic Stremio addon client that supports any addon conforming
 * to the Stremio protocol (not just Torrentio).
 */
class StremioAddonClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    /**
     * Fetch the addon manifest from a base URL.
     */
    suspend fun getManifest(baseUrl: String): StremioManifest {
        val url = baseUrl.trimEnd('/') + "/manifest.json"
        val response = httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            header("Accept", "application/json,text/plain;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
        }
        val body = response.safeAddonBodyAsText(maxBytes = ADDON_MANIFEST_MAX_BYTES)
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) {
            throw IllegalStateException(
                "Addon manifest did not return JSON. " +
                    "Got HTML or non-JSON response. Check the addon URL or try again later."
            )
        }
        return json.decodeFromString(body)
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
            // CRITICAL: send the same browser-shaped headers we use for
            // /manifest.json. Some addon backends (Panda observed
            // 2026-05-04) silently return `streams: []` for /stream/
            // requests that arrive with Ktor's default Java-engine
            // User-Agent. The manifest fetch worked, the stream fetch
            // didn't, and the desktop got zero results while the
            // Android build (different Ktor engine -> different default
            // UA) worked with the same Panda config.
            val response = httpClient.get(url) {
                header("User-Agent", "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                header("Accept", "application/json,text/plain;q=0.9,*/*;q=0.8")
                header("Accept-Language", "en-US,en;q=0.9")
            }
            val streamResponse = json.decodeFromString<StremioStreamResponse>(
                response.safeAddonBodyAsText(maxBytes = ADDON_STREAM_MAX_BYTES),
            )
            val addonName = try {
                getManifest(baseUrl).name
            } catch (_: Exception) {
                baseUrl.substringAfter("://").substringBefore("/")
            }
            val addonOrigin = addonOriginForStream(baseUrl)
            streamResponse.streams.map { StreamParser.parse(it, addonName, addonBaseUrl = addonOrigin) }
        } catch (e: Exception) {
            emptyList()
        }
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

    /**
     * Fetch catalog items from a Stremio addon.
     * URL pattern: /catalog/{type}/{id}.json or /catalog/{type}/{id}/{extras}.json
     */
    suspend fun fetchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int? = null,
        genre: String? = null,
        search: String? = null,
    ): StremioCatalogResponse {
        val base = baseUrl.trimEnd('/')
        val extras = buildList {
            genre?.let { add("genre=${it.encodeURLPathPart()}") }
            search?.let { add("search=${it.encodeURLPathPart()}") }
            skip?.let { add("skip=$it") }
        }
        val url = if (extras.isNotEmpty()) {
            "$base/catalog/$type/$catalogId/${extras.joinToString("&")}.json"
        } else {
            "$base/catalog/$type/$catalogId.json"
        }
        return try {
            val response = httpClient.get(url)
            json.decodeFromString(response.safeAddonBodyAsText(maxBytes = ADDON_CATALOG_MAX_BYTES))
        } catch (_: Exception) {
            StremioCatalogResponse()
        }
    }

    /**
     * Fetch detailed metadata for a single item.
     * URL pattern: /meta/{type}/{id}.json
     */
    suspend fun fetchMeta(
        baseUrl: String,
        type: String,
        id: String,
    ): StremioMetaResponse {
        val url = "${baseUrl.trimEnd('/')}/meta/$type/$id.json"
        return try {
            val response = httpClient.get(url)
            json.decodeFromString(response.safeAddonBodyAsText(maxBytes = ADDON_META_MAX_BYTES))
        } catch (_: Exception) {
            StremioMetaResponse()
        }
    }

    /**
     * Fetch subtitles for a media item from a subtitle addon.
     * URL pattern: /subtitles/{type}/{id}.json
     */
    suspend fun fetchSubtitles(
        baseUrl: String,
        type: String,
        id: String,
    ): StremioSubtitleResponse {
        val url = "${baseUrl.trimEnd('/')}/subtitles/$type/$id.json"
        return try {
            val response = httpClient.get(url)
            json.decodeFromString(response.safeAddonBodyAsText(maxBytes = ADDON_SUBTITLE_MAX_BYTES))
        } catch (_: Exception) {
            StremioSubtitleResponse()
        }
    }
}

private fun addonOriginForStream(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
    val host = trimmed
        .substringAfter("://", missingDelimiterValue = trimmed)
        .substringBefore("/")
        .substringBefore("?")
        .substringBefore("#")
    return if (scheme.isNotBlank() && host.isNotBlank()) {
        "$scheme://$host"
    } else {
        trimmed
    }
}
