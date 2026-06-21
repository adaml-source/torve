package com.torve.data.lanlibrary

import com.torve.domain.lanlibrary.LanLibraryManifest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Authenticated HTTP client for talking to a desktop hub's
 * `/local/manifest`, `/local/stream-token/{id}`, and
 * `/local/stream/{id}?token=...` endpoints.
 *
 * Caller supplies `host`, `port`, and the `X-Torve-Lan-Auth` secret
 * (fetched separately via the registry). Every method returns null on
 * non-2xx so the consumer can treat unreachable hubs as silent.
 */
class LanLibraryHttpClient(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    /**
     * Pull the manifest from [host]:[port]. Returns null on any non-2xx
     * or transport failure — the consumer treats this as "this hub is
     * unreachable right now" and moves on.
     */
    suspend fun fetchManifest(host: String, port: Int, authSecret: String): LanLibraryManifest? {
        return runCatching {
            val response = httpClient.get(buildUrl(host, port, "/local/manifest")) {
                header("X-Torve-Lan-Auth", authSecret)
            }
            if (!response.status.isSuccess()) return@runCatching null
            json.decodeFromString(LanLibraryManifest.serializer(), response.bodyAsText())
        }.getOrNull()
    }

    /**
     * Ask the publisher to issue a per-item streaming token for
     * [entryId]. The publisher's `LanMediaTokenTable` mints a fresh
     * opaque token bound to the registered manifest id; the client
     * never mints, never reuses the hub auth secret.
     *
     * Returns null when the hub rejects the request (404 unknown id,
     * 401 auth header), so the route chooser can fall back to provider
     * stream / re-download cleanly.
     */
    suspend fun requestStreamToken(
        host: String,
        port: Int,
        authSecret: String,
        entryId: String,
    ): LanStreamToken? {
        return runCatching {
            val response = httpClient.post(buildUrl(host, port, "/local/stream-token/$entryId")) {
                header("X-Torve-Lan-Auth", authSecret)
            }
            if (!response.status.isSuccess()) return@runCatching null
            json.decodeFromString(LanStreamToken.serializer(), response.bodyAsText())
        }.getOrNull()
    }

    /**
     * Build the player-facing streaming URL by concatenating the
     * publisher-issued [token] path. Headers (`X-Torve-Lan-Auth`) must
     * still be attached at request time — see
     * [com.torve.presentation.lanlibrary.LanLibraryConsumer.findLanRoute].
     */
    fun streamUrl(host: String, port: Int, entryId: String, perEntryToken: String): String =
        buildUrl(host, port, "/local/stream/$entryId?token=$perEntryToken")

    private fun buildUrl(host: String, port: Int, path: String): String =
        "http://$host:$port$path"
}

/**
 * Response from `POST /local/stream-token/{id}`. Carries the relative
 * path the consumer should append to `host:port` and the token TTL so
 * the consumer can re-issue close to expiry instead of mid-playback.
 */
@Serializable
data class LanStreamToken(
    @SerialName("path") val path: String,
    @SerialName("token") val token: String,
    @SerialName("expires_at_epoch_ms") val expiresAtEpochMs: Long,
)
