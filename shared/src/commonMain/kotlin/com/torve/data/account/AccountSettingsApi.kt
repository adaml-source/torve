package com.torve.data.account

import com.torve.data.contentpolicy.ContentChannelProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.platform.torveVerboseLog

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

class AccountSettingsApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val channelProvider: ContentChannelProvider? = null,
    private val installationIdProvider: () -> String? = { null },
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun getAccountSettings(accessToken: String): AccountSettingsDto {
        val raw = httpClient.get("${baseUrl()}/me/account-settings") {
            bearerAuth(accessToken)
            appendBackendHeaders()
        }.bodyAsText()
        return parseAccountSettingsResponse(raw)
    }

    /**
     * Fetch the user's saved integrations — metadata only, no secrets.
     * Use [getIntegrationCredentials] to restore individual account-mode secrets.
     */
    suspend fun getIntegrations(accessToken: String): List<IntegrationMetadataDto> {
        return try {
            val response = httpClient.get("${baseUrl()}/me/integrations") {
                bearerAuth(accessToken)
                appendBackendHeaders()
            }
            if (!response.status.isSuccess()) {
                throw AccountApiException(
                    statusCode = response.status.value,
                    endpoint = "GET /me/integrations",
                )
            }
            response.body()
        } catch (e: Exception) {
            torveVerboseLog {
                "[IntegrationAPI] GET integrations FAILED: ${e::class.simpleName} ${DiagnosticsRedactor.redact(e.message)}"
            }
            throw e
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
        // Retry transient failures (HTML error page, 5xx, network blip) with
        // backoff. The backend was observed returning HTML 401 pages right after
        // login while the auth context warmed up — without retries we'd silently
        // drop Trakt/Simkl/OMDB credentials and they wouldn't restore until the
        // next foreground refresh.
        val backoffsMs = longArrayOf(0L, 800L, 2_500L, 6_000L)
        var lastError: Throwable? = null
        for ((attempt, delayMs) in backoffsMs.withIndex()) {
            if (delayMs > 0) delay(delayMs)
            try {
                val response = httpClient.get(
                    "${baseUrl()}/me/integrations/$integrationType/credentials",
                ) {
                    bearerAuth(accessToken)
                    appendBackendHeaders()
                }
                val raw = response.bodyAsText()
                torveVerboseLog {
                    "[IntegrationAPI] GET credentials for $integrationType (attempt ${attempt + 1}): " +
                        "status=${response.status.value}"
                }
                if (!response.status.isSuccess()) {
                    if (response.status.value == 404) return null
                    lastError = RuntimeException("HTTP ${response.status.value}")
                    continue
                }
                val trimmed = raw.trimStart()
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    // Non-JSON body (HTML error page from a proxy, etc.) — treat as transient.
                    lastError = RuntimeException("non-JSON body")
                    continue
                }
                val dto: IntegrationCredentialsDto = lenientJson.decodeFromString(raw)
                return dto.credentials
            } catch (e: Exception) {
                lastError = e
                torveVerboseLog {
                    "[IntegrationAPI] GET credentials FAILED for $integrationType " +
                        "(attempt ${attempt + 1}): ${DiagnosticsRedactor.redact(e.message)}"
                }
            }
        }
        torveVerboseLog {
            "[IntegrationAPI] GET credentials gave up for $integrationType after ${backoffsMs.size} attempts: " +
                DiagnosticsRedactor.redact(lastError?.message)
        }
        return null
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
            torveVerboseLog { "[IntegrationAPI] PUT $url" }
            val resp = httpClient.put(url) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendBackendHeaders()
                setBody(request)
            }
            val ok = resp.status.isSuccess()
            if (!ok) {
                val body = try { resp.bodyAsText() } catch (_: Exception) { "" }
                torveVerboseLog {
                    "[IntegrationAPI] PUT failed: ${resp.status.value} body=${DiagnosticsRedactor.redact(body).take(240)}"
                }
            }
            ok
        } catch (e: Exception) {
            torveVerboseLog { "[IntegrationAPI] PUT exception: ${e::class.simpleName} ${DiagnosticsRedactor.redact(e.message)}" }
            false
        }
    }

    /**
     * Merge new credential keys into an existing integration row server-side.
     * Backfills the Panda `management_token` for users whose row was
     * created before that field was persisted at create time.
     *
     * Returns one of:
     *  - [PatchCredentialsOutcome.Ok] on 200 — backend merged the keys
     *  - [PatchCredentialsOutcome.RowMissing] on 404 — caller should fall back to a full PUT
     *  - [PatchCredentialsOutcome.PremiumRequired] on 403 — legacy compatibility status
     *  - [PatchCredentialsOutcome.Error] for any other failure (network, 4xx/5xx, parse)
     */
    suspend fun patchIntegrationCredentials(
        accessToken: String,
        integrationType: String,
        credentials: Map<String, String>,
    ): PatchCredentialsOutcome {
        return try {
            val url = "${baseUrl()}/me/integrations/$integrationType/credentials"
            torveVerboseLog { "[IntegrationAPI] PATCH $url keys=${credentials.keys}" }
            val resp = httpClient.patch(url) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendBackendHeaders()
                setBody(PatchCredentialsRequest(credentials = credentials))
            }
            when (resp.status.value) {
                in 200..299 -> PatchCredentialsOutcome.Ok
                404 -> PatchCredentialsOutcome.RowMissing
                403 -> PatchCredentialsOutcome.PremiumRequired
                else -> {
                    val body = try { resp.bodyAsText() } catch (_: Exception) { "" }
                    torveVerboseLog {
                        "[IntegrationAPI] PATCH failed: ${resp.status.value} body=${DiagnosticsRedactor.redact(body).take(240)}"
                    }
                    PatchCredentialsOutcome.Error("HTTP ${resp.status.value}")
                }
            }
        } catch (e: Exception) {
            torveVerboseLog { "[IntegrationAPI] PATCH exception: ${e::class.simpleName} ${DiagnosticsRedactor.redact(e.message)}" }
            PatchCredentialsOutcome.Error(e.message ?: "Network failure")
        }
    }

    // ── Playlist backup/restore ────────────────────────────────

    suspend fun getPlaylists(accessToken: String): List<RemotePlaylistDto> {
        return try {
            val response = httpClient.get("${baseUrl()}/me/playlists") {
                bearerAuth(accessToken)
                appendBackendHeaders()
            }
            if (!response.status.isSuccess()) {
                torveVerboseLog { "[PlaylistAPI] GET playlists FAILED: ${response.status.value}" }
                throw AccountApiException(
                    statusCode = response.status.value,
                    endpoint = "GET /me/playlists",
                )
            }
            val raw = response.bodyAsText()
            val playlists = lenientJson.decodeFromString<List<RemotePlaylistDto>>(raw)
            torveVerboseLog {
                "[PlaylistAPI] GET playlists OK count=${playlists.size} xtream_candidates=${playlists.count { it.isXtreamPlaylist() }}"
            }
            playlists
        } catch (e: Exception) {
            torveVerboseLog { "[PlaylistAPI] GET playlists FAILED: ${e::class.simpleName} ${DiagnosticsRedactor.redact(e.message)}" }
            throw e
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
                appendBackendHeaders()
                setBody(request)
            }
            resp.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun validateEpg(
        accessToken: String,
        epgUrl: String,
    ): EpgValidationResponse {
        return try {
            val resp = httpClient.post("${baseUrl()}/me/playlists/validate-epg") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendBackendHeaders()
                setBody(ValidateEpgRequest(epgUrl = epgUrl))
            }
            val raw = resp.bodyAsText()
            if (raw.isBlank()) {
                EpgValidationResponse(
                    success = false,
                    status = "error",
                    message = "EPG check failed: HTTP ${resp.status.value}",
                    httpStatus = resp.status.value,
                )
            } else {
                val parsed = lenientJson.decodeFromString<EpgValidationResponse>(raw)
                if (parsed.httpStatus == null) parsed.copy(httpStatus = resp.status.value) else parsed
            }
        } catch (e: Exception) {
            EpgValidationResponse(
                success = false,
                status = "error",
                message = e.message ?: "EPG check failed.",
            )
        }
    }

    suspend fun deletePlaylist(accessToken: String, playlistId: String): Boolean {
        return try {
            httpClient.delete("${baseUrl()}/me/playlists/$playlistId") {
                bearerAuth(accessToken)
                appendBackendHeaders()
            }.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getPlaylistCredentials(
        accessToken: String,
        playlistId: String,
    ): PlaylistCredentialsDto? {
        repeat(3) { attempt ->
            try {
                val response = httpClient.get("${baseUrl()}/me/playlists/$playlistId/credentials") {
                    bearerAuth(accessToken)
                    appendBackendHeaders()
                }
                if (response.status.isSuccess()) {
                    return response.body()
                }
                if (response.status.value == 429 && attempt < 2) {
                    val delayMs = playlistCredentialRetryDelayMs(
                        retryAfterHeader = response.headers["Retry-After"],
                        attempt = attempt,
                    )
                    torveVerboseLog {
                        "[PlaylistAPI] GET credentials RATE LIMITED for $playlistId: retrying_in_ms=$delayMs attempt=${attempt + 1}"
                    }
                    delay(delayMs)
                    return@repeat
                }
                torveVerboseLog { "[PlaylistAPI] GET credentials FAILED for $playlistId: ${response.status.value}" }
                return null
            } catch (e: Exception) {
                if (attempt < 2) {
                    val delayMs = playlistCredentialRetryDelayMs(retryAfterHeader = null, attempt = attempt)
                    torveVerboseLog {
                        "[PlaylistAPI] GET credentials RETRY for $playlistId: ${e::class.simpleName} ${DiagnosticsRedactor.redact(e.message)} delay_ms=$delayMs attempt=${attempt + 1}"
                    }
                    delay(delayMs)
                    return@repeat
                }
                torveVerboseLog { "[PlaylistAPI] GET credentials FAILED for $playlistId: ${e::class.simpleName} ${DiagnosticsRedactor.redact(e.message)}" }
                return null
            }
        }
        return null
    }

    suspend fun patchAccountSettings(
        accessToken: String,
        settings: Map<String, String?>,
    ): AccountSettingsDto {
        val raw = httpClient.patch("${baseUrl()}/me/account-settings") {
            bearerAuth(accessToken)
            appendBackendHeaders()
            setBody(AccountSettingsPatchRequest(settings))
        }.bodyAsText()
        return parseAccountSettingsResponse(raw)
    }

    suspend fun getAddons(accessToken: String): List<AddonDto> {
        val response = httpClient.get("${baseUrl()}/me/addons") {
            bearerAuth(accessToken)
            appendBackendHeaders()
        }
        if (!response.status.isSuccess()) {
            error("GET /me/addons failed (${response.status.value})")
        }
        return response.body()
    }

    suspend fun installAddon(
        accessToken: String,
        request: AddonInstallRequest,
    ): AddonDto {
        val response = httpClient.post("${baseUrl()}/me/addons") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            appendBackendHeaders()
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            error("POST /me/addons failed (${response.status.value})")
        }
        return response.body()
    }

    suspend fun removeAddon(accessToken: String, serverId: String) {
        val response = httpClient.delete("${baseUrl()}/me/addons/$serverId") {
            bearerAuth(accessToken)
            appendBackendHeaders()
        }
        if (!response.status.isSuccess()) {
            error("DELETE /me/addons/$serverId failed (${response.status.value})")
        }
    }

    suspend fun toggleAddon(accessToken: String, serverId: String): AddonDto {
        val response = httpClient.post("${baseUrl()}/me/addons/$serverId/toggle") {
            bearerAuth(accessToken)
            appendBackendHeaders()
        }
        if (!response.status.isSuccess()) {
            error("POST /me/addons/$serverId/toggle failed (${response.status.value})")
        }
        return response.body()
    }

    suspend fun updateAddon(
        accessToken: String,
        serverId: String,
        request: AddonUpdateRequest,
    ): AddonDto {
        val response = httpClient.patch("${baseUrl()}/me/addons/$serverId") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            appendBackendHeaders()
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            error("PATCH /me/addons/$serverId failed (${response.status.value})")
        }
        return response.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendBackendHeaders() {
        channelProvider?.channel?.let { header("X-Torve-Channel", it) }
        installationIdProvider()?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }
}

class AccountApiException(
    val statusCode: Int,
    val endpoint: String,
) : IllegalStateException("$endpoint failed (HTTP $statusCode)")

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
    val playlistType: String,
    val server: String? = null,
    val username: String? = null,
    @SerialName("has_password")
    val hasPassword: Boolean = false,
)

@Serializable
data class PlaylistCredentialsDto(
    @SerialName("playlist_id")
    val playlistId: String = "",
    val username: String? = null,
    val password: String? = null,
)

internal fun RemotePlaylistDto.isXtreamPlaylist(
    resolvedPlaylistId: String = playlistId.ifBlank { id },
): Boolean {
    val normalizedType = playlistType.trim().lowercase()
    return normalizedType == "xtream" || resolvedPlaylistId.startsWith("xtream_", ignoreCase = true)
}

internal fun playlistCredentialRetryDelayMs(
    retryAfterHeader: String?,
    attempt: Int,
): Long {
    val retryAfterMs = retryAfterHeader?.trim()?.toLongOrNull()?.times(1000)
    return retryAfterMs?.coerceIn(500L, 5_000L) ?: ((attempt + 1) * 750L)
}

@Serializable
data class SavePlaylistRequest(
    @SerialName("playlist_id")
    val playlistId: String,
    val name: String,
    val url: String? = null,
    @SerialName("epg_url")
    val epgUrl: String? = null,
    @SerialName("playlist_type")
    val playlistType: String,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class ValidateEpgRequest(
    @SerialName("epg_url")
    val epgUrl: String,
)

@Serializable
data class EpgValidationResponse(
    val success: Boolean = false,
    val status: String? = null,
    val message: String? = null,
    @SerialName("http_status")
    val httpStatus: Int? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    @SerialName("channel_count")
    val channelCount: Int? = null,
    @SerialName("programme_count")
    val programmeCount: Int? = null,
    @SerialName("bytes_checked")
    val bytesChecked: Long? = null,
) {
    fun displayMessage(): String {
        return message?.takeIf { it.isNotBlank() }
            ?: if (success) {
                "EPG data found."
            } else {
                "EPG check failed."
            }
    }
}

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

@Serializable
data class PatchCredentialsRequest(
    val credentials: Map<String, String>,
)

/** Result of [AccountSettingsApi.patchIntegrationCredentials]. */
sealed class PatchCredentialsOutcome {
    data object Ok : PatchCredentialsOutcome()
    /** No row exists yet for this integration_type — caller should PUT. */
    data object RowMissing : PatchCredentialsOutcome()
    /** Legacy compatibility response for integration writes; must not gate free access. */
    data object PremiumRequired : PatchCredentialsOutcome()
    data class Error(val message: String) : PatchCredentialsOutcome()
}

@Serializable
data class AddonDto(
    val id: String,
    @SerialName("manifest_url")
    val manifestUrl: String,
    @SerialName("addon_id")
    val addonId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    @SerialName("has_catalog")
    val hasCatalog: Boolean = false,
    @SerialName("has_streams")
    val hasStreams: Boolean = false,
    @SerialName("is_enabled")
    val isEnabled: Boolean = true,
    @SerialName("sort_order")
    val sortOrder: Int = 0,
    @SerialName("installed_from")
    val installedFrom: String = "app",
    val installable: Boolean? = null,
    @SerialName("shelf_eligible")
    val shelfEligible: Boolean? = null,
    @SerialName("catalog_queryable")
    val catalogQueryable: Boolean? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class AddonInstallRequest(
    @SerialName("manifest_url")
    val manifestUrl: String,
    @SerialName("addon_id")
    val addonId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    @SerialName("has_catalog")
    val hasCatalog: Boolean = false,
    @SerialName("has_streams")
    val hasStreams: Boolean = false,
    @SerialName("installed_from")
    val installedFrom: String = "app",
)

@Serializable
data class AddonUpdateRequest(
    @SerialName("addon_id")
    val addonId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    @SerialName("has_catalog")
    val hasCatalog: Boolean? = null,
    @SerialName("has_streams")
    val hasStreams: Boolean? = null,
    @SerialName("is_enabled")
    val isEnabled: Boolean? = null,
    @SerialName("sort_order")
    val sortOrder: Int? = null,
    @SerialName("installed_from")
    val installedFrom: String? = null,
)
