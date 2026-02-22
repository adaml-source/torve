package com.streamvault.data.simkl

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
    }

    var clientId: String = ""
        private set

    fun setClientId(id: String) {
        this.clientId = id
    }

    // -------------------------------------------------------------------------
    // Device Code OAuth
    // -------------------------------------------------------------------------

    suspend fun getDeviceCode(): SimklDeviceCode {
        val resp: SimklDeviceCodeResponse = httpClient.get("$BASE_URL/oauth/pin?client_id=$clientId").body()
        return SimklDeviceCode(
            userCode = resp.userCode,
            verificationUrl = resp.verificationUrl,
            expiresIn = resp.expiresIn,
            interval = resp.interval,
        )
    }

    suspend fun pollDeviceToken(userCode: String): SimklTokens? {
        return try {
            val resp: SimklPinStatusResponse = httpClient.get(
                "$BASE_URL/oauth/pin/$userCode?client_id=$clientId",
            ).body()
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
