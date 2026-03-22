package com.torve.data.account

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

class AccountSettingsApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun getAccountSettings(accessToken: String): AccountSettingsDto {
        val raw = httpClient.get("${baseUrl()}/me/account-settings") {
            bearerAuth(accessToken)
        }.bodyAsText()
        return parseAccountSettingsResponse(raw)
    }

    /**
     * Fetch the user's saved integrations — metadata only, no secrets.
     * Use [getIntegrationCredentials] to restore individual account-mode secrets.
     */
    suspend fun getIntegrations(accessToken: String): List<IntegrationMetadataDto> {
        return try {
            httpClient.get("${baseUrl()}/me/integrations") {
                bearerAuth(accessToken)
            }.body()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch the credential for a single account-mode integration.
     * Returns null if the integration is device-only, not found, or the call fails.
     * Never call this for device-only integrations — the backend will return empty.
     */
    /**
     * Fetch credentials dict for a single integration.
     * Returns the full map so multi-value credentials (e.g. Trakt access+refresh tokens)
     * can be restored correctly.
     */
    suspend fun getIntegrationCredentials(
        accessToken: String,
        integrationType: String,
    ): Map<String, String>? {
        return try {
            val raw = httpClient.get(
                "${baseUrl()}/me/integrations/$integrationType/credentials",
            ) {
                bearerAuth(accessToken)
            }.bodyAsText()
            println("[IntegrationAPI] GET credentials for $integrationType: ${raw.take(100)}")
            val dto: IntegrationCredentialsDto = lenientJson.decodeFromString(raw)
            dto.credentials
        } catch (e: Exception) {
            println("[IntegrationAPI] GET credentials FAILED for $integrationType: ${e.message}")
            null
        }
    }

    /**
     * Save an integration to the user's account.
     * Call this when the user selects ACCOUNT storage mode and clicks Save/Connect.
     * Returns true on success.
     */
    suspend fun saveIntegration(
        accessToken: String,
        integrationType: String,
        request: SaveIntegrationRequest,
    ): Boolean {
        return try {
            val url = "${baseUrl()}/me/integrations/$integrationType"
            println("[IntegrationAPI] PUT $url")
            val resp = httpClient.put(url) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val ok = resp.status.isSuccess()
            if (!ok) {
                val body = try { resp.bodyAsText() } catch (_: Exception) { "" }
                println("[IntegrationAPI] PUT failed: ${resp.status.value} body=$body")
            }
            ok
        } catch (e: Exception) {
            println("[IntegrationAPI] PUT exception: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    // ── Playlist backup/restore ────────────────────────────────

    suspend fun getPlaylists(accessToken: String): List<RemotePlaylistDto> {
        return try {
            httpClient.get("${baseUrl()}/me/playlists") {
                bearerAuth(accessToken)
            }.body()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun savePlaylist(
        accessToken: String,
        playlistId: String,
        request: SavePlaylistRequest,
    ): Boolean {
        return try {
            val resp = httpClient.put("${baseUrl()}/me/playlists/$playlistId") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            resp.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deletePlaylist(accessToken: String, playlistId: String): Boolean {
        return try {
            httpClient.delete("${baseUrl()}/me/playlists/$playlistId") {
                bearerAuth(accessToken)
            }.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getPlaylistCredentials(
        accessToken: String,
        playlistId: String,
    ): PlaylistCredentialsDto? {
        return try {
            httpClient.get("${baseUrl()}/me/playlists/$playlistId/credentials") {
                bearerAuth(accessToken)
            }.body()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun patchAccountSettings(
        accessToken: String,
        settings: Map<String, String?>,
    ): AccountSettingsDto {
        val raw = httpClient.patch("${baseUrl()}/me/account-settings") {
            bearerAuth(accessToken)
            setBody(AccountSettingsPatchRequest(settings))
        }.bodyAsText()
        return parseAccountSettingsResponse(raw)
    }
}

/**
 * Parse the account-settings response manually to handle null values
 * in the settings map that the backend may return. Nulls are filtered
 * out — they represent deleted keys.
 */
private fun parseAccountSettingsResponse(raw: String): AccountSettingsDto {
    val root = lenientJson.parseToJsonElement(raw).jsonObject
    val settingsObj = root["settings"]?.jsonObject ?: JsonObject(emptyMap())
    val filtered = mutableMapOf<String, String>()
    for ((key, value) in settingsObj) {
        if (value is JsonNull) continue
        if (value is JsonPrimitive && value.isString) {
            filtered[key] = value.content
        } else if (value is JsonPrimitive) {
            filtered[key] = value.content
        }
    }
    val updatedAt = root["updated_at"]?.jsonPrimitive?.content
    val updatedByDeviceId = root["updated_by_device_id"]?.jsonPrimitive?.content
    return AccountSettingsDto(
        settings = filtered,
        updatedAt = updatedAt,
        updatedByDeviceId = updatedByDeviceId,
    )
}

@Serializable
data class AccountSettingsDto(
    val settings: Map<String, String> = emptyMap(),
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("updated_by_device_id")
    val updatedByDeviceId: String? = null,
)

@Serializable
data class AccountSettingsPatchRequest(
    val settings: Map<String, String?>,
)

// ── Integration restore API ─────────────────────────────────
// GET /me/integrations returns metadata only — never secrets.
// Secrets are fetched individually via GET /me/integrations/{type}/credentials.

@Serializable
data class IntegrationMetadataDto(
    val id: String = "",
    @SerialName("integration_type")
    val integrationType: String,
    @SerialName("storage_mode")
    val storageMode: String, // "account" or "device_only"
    @SerialName("display_identifier")
    val displayIdentifier: String? = null,
    val config: Map<String, String> = emptyMap(),
    @SerialName("is_connected")
    val isConnected: Boolean = false,
    @SerialName("has_credentials")
    val hasCredentials: Boolean = false,
    @SerialName("last_verified_at")
    val lastVerifiedAt: String? = null,
)

@Serializable
data class IntegrationCredentialsDto(
    val credentials: Map<String, String>? = null,
) {
    /** Extract the first credential value from the dict. */
    val firstValue: String?
        get() = credentials?.values?.firstOrNull()
}

// ── Playlist backup/restore API ──────────────────────────────

@Serializable
data class RemotePlaylistDto(
    val id: String = "",
    @SerialName("playlist_id")
    val playlistId: String = "",
    val name: String,
    val url: String? = null,
    @SerialName("epg_url")
    val epgUrl: String? = null,
    @SerialName("playlist_type")
    val playlistType: String = "m3u",
    val server: String? = null,
    val username: String? = null,
    @SerialName("has_password")
    val hasPassword: Boolean = false,
)

@Serializable
data class PlaylistCredentialsDto(
    @SerialName("playlist_id")
    val playlistId: String = "",
    val password: String? = null,
)

@Serializable
data class SavePlaylistRequest(
    @SerialName("playlist_id")
    val playlistId: String,
    val name: String,
    val url: String? = null,
    @SerialName("epg_url")
    val epgUrl: String? = null,
    @SerialName("playlist_type")
    val playlistType: String = "m3u",
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class SaveIntegrationRequest(
    @SerialName("integration_type")
    val integrationType: String,
    @SerialName("storage_mode")
    val storageMode: String, // "account" or "device_only"
    val credentials: Map<String, String>? = null, // e.g. {"api_key": "xxx"}
    @SerialName("display_identifier")
    val displayIdentifier: String? = null,
    val config: Map<String, String> = emptyMap(),
)
