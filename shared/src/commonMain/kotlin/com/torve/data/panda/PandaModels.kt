package com.torve.data.panda

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Domain types ---

data class PandaProvider(
    val id: String,
    val name: String,
    val authMethods: List<String>,
    val logoUrl: String? = null,
    val helpUrl: String? = null,
)

data class PandaSourceProvider(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
)

data class PandaDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int,
)

sealed class PandaAuthPollResult {
    data class Approved(val apiKey: String) : PandaAuthPollResult()
    data object Pending : PandaAuthPollResult()
    data object Expired : PandaAuthPollResult()
    data class Error(val message: String) : PandaAuthPollResult()
}

@Serializable
data class PandaDebridConnection(
    val provider: String = "none",
    val apiKey: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class PandaDebridConnectionSecret(
    val provider: String = "none",
    @SerialName("api_key") val apiKey: String = "",
    val enabled: Boolean = true,
)

class PandaApiException(
    val errorCode: String,
    override val message: String,
    val httpStatus: Int = 0,
) : Exception(message)

data class DownloadClientFieldSpec(
    val fields: List<String>,
    val cloud: Boolean = false,
)

data class PandaSchema(
    val debridServices: List<String>,
    val usenetProviders: List<String>,
    val nzbIndexers: List<String>,
    val downloadClients: List<String>,
    val qualityOptions: List<String>,
    val qualityProfiles: List<String>,
    val releaseLanguages: List<String>,
    val sortOptions: List<String>,
    val resultLimits: List<String>,
    val downloadClientFields: Map<String, DownloadClientFieldSpec>,
)

// --- API response models ---

@Serializable
data class PandaProvidersResponse(
    val providers: List<PandaProviderDto> = emptyList(),
)

@Serializable
data class PandaProviderDto(
    val id: String = "",
    val name: String = "",
    @SerialName("auth_methods") val authMethods: List<String> = emptyList(),
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("help_url") val helpUrl: String? = null,
)

@Serializable
data class PandaDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    @SerialName("expires_in") val expiresIn: Int = 600,
    val interval: Int = 5,
)

@Serializable
data class PandaAuthPollResponse(
    val status: String = "",
    @SerialName("api_key") val apiKey: String? = null,
)

/**
 * A single NZB indexer entry in Panda's multi-indexer list.
 *
 * @param type Newznab flavor id from /api/v1/schema (e.g. "scenenzbs", "nzbgeek",
 *             "custom"). "none" marks an empty placeholder row and is stripped on save.
 * @param url  Only meaningful when [type] is "custom" — points at a user-hosted
 *             Newznab-compatible endpoint. Empty string otherwise.
 * @param apiKey The indexer's API key. Rows without a key are stripped on save.
 */
@Serializable
data class NzbIndexerRow(
    val type: String = "none",
    val url: String = "",
    val apiKey: String = "",
)

/**
 * Owner-only plaintext secret payload returned by `/api/v1/configs/me/secrets`.
 * Panda redacts every secret on the public read endpoints; this one is the
 * single path back to the unredacted values. Field names are snake_case to
 * match the server response shape exactly.
 */
@Serializable
data class PandaConfigSecrets(
    @SerialName("config_id") val configId: String? = null,
    @SerialName("debrid_api_key") val debridApiKey: String = "",
    @SerialName("debrid_connections")
    val debridConnections: List<PandaDebridConnectionSecret> = emptyList(),
    @SerialName("usenet_password") val usenetPassword: String = "",
    @SerialName("download_client_api_key") val downloadClientApiKey: String = "",
    @SerialName("download_client_password") val downloadClientPassword: String = "",
    @SerialName("nzb_indexer_api_key") val nzbIndexerApiKey: String = "",
    @SerialName("nzb_indexers") val nzbIndexers: List<PandaIndexerSecret> = emptyList(),
)

@Serializable
data class PandaIndexerSecret(
    val type: String = "",
    val url: String = "",
    @SerialName("api_key") val apiKey: String = "",
)

@Serializable
data class PandaConfigPayload(
    val version: Int = 2,
    val enabledProviders: List<String> = listOf("yts", "eztv", "1337x", "thepiratebay", "torrentgalaxy", "nyaasi"),
    val qualityProfile: String = "balanced",
    val maxQuality: String = "2160p",
    // Legacy scalar — kept for older Panda deploys. When both are present the array wins.
    val releaseLanguage: String = "any",
    // Preferred multi-select language list. `["any"]` / empty means "no filter".
    val releaseLanguages: List<String> = listOf("any"),
    val debridService: String = "none",
    val debridApiKey: String = "",
    val debridConnections: List<PandaDebridConnection> = emptyList(),
    val putioClientId: String = "",
    val groupByQuality: Boolean = true,
    val sortTorrentsBy: String = "qualitysize",
    val allowUncached: Boolean = false,
    val maxResults: String = "10",
    val hideDownloadLinks: Boolean = true,
    val hideCatalog: Boolean = true,
    // Usenet
    val enableUsenet: Boolean = false,
    val usenetProvider: String = "none",
    val usenetHost: String = "",
    val usenetPort: Int = 563,
    val usenetUsername: String = "",
    val usenetPassword: String = "",
    val usenetSSL: Boolean = true,
    val usenetConnections: Int = 10,
    // Legacy single-indexer fields. Preserved for older Panda deploys that predate
    // the nzbIndexers array. When both are present the server prefers the array
    // and auto-upgrades; we still populate these from nzbIndexers.first().
    val nzbIndexer: String = "none",
    val nzbIndexerUrl: String = "",
    val nzbIndexerApiKey: String = "",
    // Preferred multi-indexer list. Panda searches all entries in parallel.
    val nzbIndexers: List<NzbIndexerRow> = emptyList(),
    val downloadClient: String = "none",
    val downloadClientUrl: String = "",
    val downloadClientUsername: String = "",
    val downloadClientPassword: String = "",
    val downloadClientApiKey: String = "",
    /**
     * When true, Panda routes playback through the cloud NZB download client for
     * titles available in both Easynews and an NZB indexer — preserving the
     * user's Easynews monthly data cap. Server-side ignored unless Usenet is
     * enabled, at least one indexer has a key, and downloadClient is one of
     * premiumize/torbox/alldebrid.
     */
    val easynewsPreferNzb: Boolean = false,
)

@Serializable
data class PandaConfigPatch(
    val debridService: String? = null,
    val debridApiKey: String? = null,
    val debridConnections: List<PandaDebridConnection>? = null,
    val maxQuality: String? = null,
    val qualityProfile: String? = null,
    val releaseLanguage: String? = null,
    val releaseLanguages: List<String>? = null,
    val enabledProviders: List<String>? = null,
    val sortTorrentsBy: String? = null,
    val allowUncached: Boolean? = null,
    val maxResults: String? = null,
    // Usenet
    val enableUsenet: Boolean? = null,
    val usenetProvider: String? = null,
    val usenetHost: String? = null,
    val usenetPort: Int? = null,
    val usenetUsername: String? = null,
    val usenetPassword: String? = null,
    val usenetSSL: Boolean? = null,
    val usenetConnections: Int? = null,
    val nzbIndexer: String? = null,
    val nzbIndexerUrl: String? = null,
    val nzbIndexerApiKey: String? = null,
    val nzbIndexers: List<NzbIndexerRow>? = null,
    val downloadClient: String? = null,
    val downloadClientUrl: String? = null,
    val downloadClientUsername: String? = null,
    val downloadClientPassword: String? = null,
    val downloadClientApiKey: String? = null,
    val easynewsPreferNzb: Boolean? = null,
)

@Serializable
data class PandaConfigResponse(
    @SerialName("panda_token") val pandaToken: String? = null,
    @SerialName("manifest_url") val manifestUrl: String? = null,
    @SerialName("config_id") val configId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    // Shown exactly once at config creation. The server never returns this on
    // subsequent reads — losing it requires the admin-issued recovery path.
    @SerialName("management_token") val managementToken: String? = null,
    @SerialName("management_token_notice") val managementTokenNotice: String? = null,
) {
    // Defensive redaction: the auto-generated data class toString() would
    // print every field. If this DTO ever reaches a logger by accident (crash
    // report, println, analytics serializer), we must not leak raw secrets.
    override fun toString(): String =
        "PandaConfigResponse(configId=$configId, hasPandaToken=${!pandaToken.isNullOrBlank()}, " +
            "hasManagementToken=${!managementToken.isNullOrBlank()}, expiresAt=$expiresAt)"
}

@Serializable
data class PandaRotateManifestResponse(
    @SerialName("panda_token") val pandaToken: String? = null,
    @SerialName("manifest_url") val manifestUrl: String? = null,
) {
    override fun toString(): String =
        "PandaRotateManifestResponse(hasPandaToken=${!pandaToken.isNullOrBlank()}, hasManifestUrl=${!manifestUrl.isNullOrBlank()})"
}

@Serializable
data class PandaRotateManagementResponse(
    @SerialName("management_token") val managementToken: String = "",
    @SerialName("management_token_notice") val managementTokenNotice: String? = null,
) {
    override fun toString(): String =
        "PandaRotateManagementResponse(hasManagementToken=${managementToken.isNotBlank()})"
}

@Serializable
data class PandaConfigRecord(
    @SerialName("config_id") val configId: String? = null,
    val config: PandaConfigPayload? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class PandaErrorResponse(
    val code: String = "",
    val message: String = "",
)

@Serializable
data class PandaSchemaResponse(
    val debridServices: List<String> = emptyList(),
    val usenetProviders: List<String> = emptyList(),
    val nzbIndexers: List<String> = emptyList(),
    val downloadClients: List<String> = emptyList(),
    val qualityOptions: List<String> = emptyList(),
    val qualityProfiles: List<String> = emptyList(),
    val releaseLanguages: List<String> = emptyList(),
    val sortOptions: List<String> = emptyList(),
    val resultLimits: List<String> = emptyList(),
    val downloadClientFields: Map<String, DownloadClientFieldSpecDto> = emptyMap(),
)

@Serializable
data class DownloadClientFieldSpecDto(
    val fields: List<String> = emptyList(),
    val cloud: Boolean = false,
)

@Serializable
data class PandaApiKeyRequest(
    @SerialName("api_key") val apiKey: String,
)

@Serializable
data class PandaDeviceCodeRequest(
    @SerialName("device_code") val deviceCode: String,
)
