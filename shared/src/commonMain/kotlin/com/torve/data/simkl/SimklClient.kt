package com.torve.data.simkl

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SIMKL API client.
 * Supports device-code OAuth and basic sync operations.
 */
class SimklClient(
    private val httpClient: HttpClient,
) {
    companion object {
        const val BASE_URL = "https://api.simkl.com"
        // Public OAuth client identifier for SIMKL device-code auth. It is
        // not a Torve secret and cannot grant premium or resolver access.
        const val DEFAULT_PUBLIC_CLIENT_ID = "a1ed8e154e3f526ed28d3e5edcfde96f6bbe168f830faa5412563b996e5330aa"
    }

    var clientId: String = DEFAULT_PUBLIC_CLIENT_ID
        private set

    fun setClientId(id: String) {
        this.clientId = id.ifBlank { DEFAULT_PUBLIC_CLIENT_ID }
    }

    // -------------------------------------------------------------------------
    // Device Code OAuth
    // -------------------------------------------------------------------------

    suspend fun getDeviceCode(): SimklDeviceCode {
        val response: HttpResponse = httpClient.get("$BASE_URL/oauth/pin?client_id=$clientId")
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("SIMKL API error ${response.status.value}: $body")
        }
        val resp: SimklDeviceCodeResponse = response.body()
        if (resp.userCode.isBlank() || resp.verificationUrl.isBlank()) {
            throw Exception("SIMKL returned empty device code. Check Client ID.")
        }
        return SimklDeviceCode(
            userCode = resp.userCode,
            verificationUrl = resp.verificationUrl,
            expiresIn = resp.expiresIn,
            interval = resp.interval,
        )
    }

    suspend fun pollDeviceToken(userCode: String): SimklTokens? {
        return try {
            val response: HttpResponse = httpClient.get(
                "$BASE_URL/oauth/pin/$userCode?client_id=$clientId",
            )
            if (!response.status.isSuccess()) return null
            val resp: SimklPinStatusResponse = response.body()
            if (resp.result == "OK" && resp.accessToken != null) {
                SimklTokens(accessToken = resp.accessToken)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // User
    // -------------------------------------------------------------------------

    suspend fun getUser(accessToken: String): SimklUser {
        val resp: SimklUserResponse = httpClient.get("$BASE_URL/users/settings") {
            header("Authorization", "Bearer $accessToken")
            header("simkl-api-key", clientId)
        }.body()
        return SimklUser(
            username = resp.user?.name ?: "Unknown",
            avatar = resp.user?.avatar,
        )
    }

    // -------------------------------------------------------------------------
    // Sync
    // -------------------------------------------------------------------------

    suspend fun addToHistory(accessToken: String, body: SimklSyncBody) {
        httpClient.post("$BASE_URL/sync/history") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $accessToken")
            header("simkl-api-key", clientId)
            setBody(body)
        }
    }

    suspend fun addToWatchlist(accessToken: String, body: SimklSyncBody) {
        httpClient.post("$BASE_URL/sync/add-to-list") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $accessToken")
            header("simkl-api-key", clientId)
            setBody(body)
        }
    }

    suspend fun removeFromWatchlist(accessToken: String, body: SimklSyncBody) {
        httpClient.post("$BASE_URL/sync/remove-from-list") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $accessToken")
            header("simkl-api-key", clientId)
            setBody(body)
        }
    }
}

// --- Data Classes ---

data class SimklDeviceCode(
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int,
)

data class SimklTokens(
    val accessToken: String,
)

data class SimklUser(
    val username: String,
    val avatar: String? = null,
)

// --- API Response Models ---

@Serializable
data class SimklDeviceCodeResponse(
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
)

@Serializable
data class SimklPinStatusResponse(
    val result: String = "",
    @SerialName("access_token") val accessToken: String? = null,
)

@Serializable
data class SimklUserResponse(
    val user: SimklUserInfo? = null,
)

@Serializable
data class SimklUserInfo(
    val name: String? = null,
    val avatar: String? = null,
)

@Serializable
data class SimklSyncBody(
    val movies: List<SimklSyncItem>? = null,
    val shows: List<SimklSyncItem>? = null,
)

@Serializable
data class SimklSyncItem(
    val ids: SimklIds,
)

@Serializable
data class SimklIds(
    val simkl: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
)
