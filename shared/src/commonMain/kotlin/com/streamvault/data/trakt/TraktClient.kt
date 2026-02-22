package com.streamvault.data.trakt

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

class TraktClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        const val TRAKT_BASE = "https://api.trakt.tv"
    }

    var clientId: String = ""
        private set
    var clientSecret: String = ""
        private set

    fun setCredentials(clientId: String, clientSecret: String) {
        this.clientId = clientId
        this.clientSecret = clientSecret
    }

    private fun traktHeaders(accessToken: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to clientId,
        )
        if (accessToken != null) {
            headers["Authorization"] = "Bearer $accessToken"
        }
        return headers
    }

    // -------------------------------------------------------------------------
    // Device Code Flow
    // -------------------------------------------------------------------------

    suspend fun getDeviceCode(): TraktDeviceCode {
        if (clientId.isBlank()) {
            throw Exception("Trakt Client ID not configured. Set it in Settings.")
        }
        val resp: TraktDeviceCodeResponse = httpClient.post("$TRAKT_BASE/oauth/device/code") {
            contentType(ContentType.Application.Json)
            traktHeaders().forEach { (k, v) -> header(k, v) }
            setBody(mapOf("client_id" to clientId))
        }.body()
        return TraktDeviceCode(
            deviceCode = resp.deviceCode,
            userCode = resp.userCode,
            verificationUrl = resp.verificationUrl,
            interval = resp.interval,
            expiresIn = resp.expiresIn,
        )
    }

    suspend fun pollDeviceToken(deviceCode: String): TraktTokens? {
        return try {
            val resp: TraktTokenResponse = httpClient.post("$TRAKT_BASE/oauth/device/token") {
                contentType(ContentType.Application.Json)
                header("Content-Type", "application/json")
                setBody(
                    json.encodeToString(
                        kotlinx.serialization.serializer<Map<String, String>>(),
                        mapOf(
                            "code" to deviceCode,
                            "client_id" to clientId,
                            "client_secret" to clientSecret,
                        ),
                    ),
                )
            }.body()
            TraktTokens(
                accessToken = resp.accessToken,
                refreshToken = resp.refreshToken,
                expiresIn = resp.expiresIn,
                createdAt = resp.createdAt,
            )
        } catch (_: Exception) {
            null // Still pending or error
        }
    }

    suspend fun refreshToken(refreshToken: String): TraktTokens {
        val resp: TraktTokenResponse = httpClient.post("$TRAKT_BASE/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    mapOf(
                        "refresh_token" to refreshToken,
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob",
                        "grant_type" to "refresh_token",
                    ),
                ),
            )
        }.body()
        return TraktTokens(
            accessToken = resp.accessToken,
            refreshToken = resp.refreshToken,
            expiresIn = resp.expiresIn,
            createdAt = resp.createdAt,
        )
    }

    // -------------------------------------------------------------------------
    // User Info
    // -------------------------------------------------------------------------

    suspend fun getUser(accessToken: String): TraktUser {
        val resp: TraktUserResponse = httpClient.get("$TRAKT_BASE/users/me") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            parameter("extended", "full")
        }.body()
        return TraktUser(
            username = resp.username,
            name = resp.name,
            vip = resp.vip,
            joined = resp.joinedAt,
            avatar = resp.images?.avatar?.full,
        )
    }

    suspend fun revokeToken(accessToken: String) {
        try {
            httpClient.post("$TRAKT_BASE/oauth/revoke") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        kotlinx.serialization.serializer<Map<String, String>>(),
                        mapOf(
                            "token" to accessToken,
                            "client_id" to clientId,
                            "client_secret" to clientSecret,
                        ),
                    ),
                )
            }
        } catch (_: Exception) {
            // Best-effort revocation
        }
    }

    // -------------------------------------------------------------------------
    // Sync
    // -------------------------------------------------------------------------

    suspend fun syncWatched(accessToken: String, mediaType: String): String {
        return httpClient.get("$TRAKT_BASE/sync/watched/$mediaType") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }.bodyAsText()
    }

    suspend fun addToHistory(accessToken: String, body: TraktHistoryBody) {
        httpClient.post("$TRAKT_BASE/sync/history") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    // -------------------------------------------------------------------------
    // Scrobble
    // -------------------------------------------------------------------------

    suspend fun scrobbleStart(accessToken: String, body: TraktScrobbleBody) {
        httpClient.post("$TRAKT_BASE/scrobble/start") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    suspend fun scrobblePause(accessToken: String, body: TraktScrobbleBody) {
        httpClient.post("$TRAKT_BASE/scrobble/pause") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    suspend fun scrobbleStop(accessToken: String, body: TraktScrobbleBody) {
        httpClient.post("$TRAKT_BASE/scrobble/stop") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }
}
