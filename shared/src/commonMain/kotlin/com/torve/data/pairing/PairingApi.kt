package com.torve.data.pairing

import com.torve.data.auth.DeviceRegistrationDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class PairingApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun listPairings(accessToken: String): PairingListDto {
        val response = httpClient.get("${baseUrl()}/me/pairings") {
            bearerAuth(accessToken)
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(parseErrorDetail(raw) ?: "Failed to load pairings (${response.status.value})")
        }
        return try {
            parsePairingListPayload(raw)
        } catch (e: Exception) {
            throw IllegalStateException("Pairings parse error: ${e.message} | raw=${raw.take(500)}", e)
        }
    }

    suspend fun createPairingCode(
        accessToken: String?,
        device: DeviceRegistrationDto,
    ): PairingCodeDto {
        val url = "${baseUrl()}/pairing/code"
        val response = httpClient.post(url) {
            accessToken?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
            setBody(device)
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = parseErrorDetail(raw)
            // Only treat as "server lacks code pairing" if the route itself is missing,
            // not if the device lookup failed (e.g. "Device not found or not active").
            if (response.status.value == 404 && detail == null) {
                throw PairingUnsupportedException("Code-based pairing is not available on this server.")
            }
            throw IllegalStateException(detail ?: "Pairing code failed (${response.status.value}): $raw")
        }
        return json.decodeFromString(PairingCodeDto.serializer(), raw)
    }

    suspend fun claimPairingCode(accessToken: String, code: String, device: DeviceRegistrationDto? = null): PairingStatusDto {
        val response = httpClient.post("${baseUrl()}/pairing/claim") {
            bearerAuth(accessToken)
            setBody(PairingClaimDto(
                code = code,
                device_id = device?.device_id,
                installation_id = device?.installation_id,
                device_name = device?.device_name,
                device_type = device?.device_type,
                platform = device?.platform,
            ))
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = parseErrorDetail(raw)
            if (response.status.value == 404 && detail == null) {
                throw PairingUnsupportedException("Code-based pairing is not available on this server.")
            }
            throw IllegalStateException(detail ?: "Pairing claim failed (${response.status.value}): $raw")
        }
        return json.decodeFromString(PairingStatusDto.serializer(), raw)
    }

    suspend fun revokePairing(accessToken: String, pairingId: String): PairingStatusDto {
        val response = httpClient.post("${baseUrl()}/me/pairings/$pairingId/revoke") {
            bearerAuth(accessToken)
        }
        val raw = response.bodyAsText()
        if (response.status.value == 404) {
            throw PairingUnsupportedException("Pairing management is not available on this server.")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(parseErrorDetail(raw) ?: "Failed to revoke pairing (${response.status.value})")
        }
        return json.decodeFromString(PairingStatusDto.serializer(), raw)
    }

    suspend fun probeCodePairingSupport(accessToken: String? = null): Boolean {
        val codeRouteExists = probePath("${baseUrl()}/pairing/code", accessToken)
        val claimRouteExists = probePath("${baseUrl()}/pairing/claim", accessToken)
        return codeRouteExists && claimRouteExists
    }

    private suspend fun probePath(url: String, accessToken: String?): Boolean {
        val response = httpClient.request(url) {
            method = HttpMethod.Options
            accessToken?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
        }
        return response.status.value != 404
    }

    private fun parseErrorDetail(raw: String): String? = runCatching {
        json.decodeFromString(PairingErrorDto.serializer(), raw).detail
    }.getOrNull()
}

internal fun parsePairingListPayload(raw: String): PairingListDto {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
    return when (val element = json.parseToJsonElement(raw.ifBlank { "[]" })) {
        is JsonArray -> PairingListDto(
            pairings = json.decodeFromJsonElement(ListSerializer(PairedDeviceDto.serializer()), element),
        )
        is JsonObject -> json.decodeFromJsonElement(PairingListDto.serializer(), element)
        else -> error("Unexpected pairing payload")
    }
}

@Serializable
data class PairingListDto(
    val pairings: List<PairedDeviceDto> = emptyList(),
)

@Serializable
data class PairingCodeDto(
    val code: String,
    @SerialName("expires_at")
    val expiresAt: String,
)

@Serializable
data class PairedDeviceDto(
    // Legacy format (local server) uses these field names
    @SerialName("pairing_id")
    val pairingId: String = "",
    @SerialName("device_id")
    val deviceId: String = "",
    @SerialName("installation_id")
    val installationId: String = "",
    @SerialName("device_name")
    val deviceName: String = "",
    @SerialName("device_type")
    val deviceType: String = "",
    val platform: String = "",
    @SerialName("last_seen_at")
    val lastSeenAt: String = "",
    @SerialName("pairing_state")
    val pairingState: String = "paired",
    @SerialName("revoked_at")
    val revokedAt: String? = null,
    // Production format uses these field names
    val id: String = "",
    @SerialName("controller_device_id")
    val controllerDeviceId: String = "",
    @SerialName("target_device_id")
    val targetDeviceId: String = "",
    val status: String = "",
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("target_device_name")
    val targetDeviceName: String = "",
    @SerialName("target_device_type")
    val targetDeviceType: String = "",
    @SerialName("target_platform")
    val targetPlatform: String = "",
) {
    /** Resolved pairing ID — prefer production format, fall back to legacy. */
    fun resolvedPairingId(): String = id.ifBlank { pairingId }
    /** Resolved peer device ID — use target from production, device_id from legacy. */
    fun resolvedDeviceId(): String = targetDeviceId.ifBlank { deviceId }
    /** Resolved installation ID. */
    fun resolvedInstallationId(): String = installationId.ifBlank { targetDeviceId }
    /** Resolved device name. */
    fun resolvedDeviceName(): String = targetDeviceName.ifBlank { deviceName.ifBlank { "Paired Device" } }
    /** Resolved device type. */
    fun resolvedDeviceType(): String = targetDeviceType.ifBlank { deviceType.ifBlank { "unknown" } }
    /** Resolved platform. */
    fun resolvedPlatform(): String = targetPlatform.ifBlank { platform.ifBlank { "unknown" } }
    /** Resolved pairing state. */
    fun resolvedPairingState(): String = if (status.isNotBlank()) (if (status == "active") "paired" else status) else pairingState
    /** Resolved last seen. */
    fun resolvedLastSeenAt(): String = lastSeenAt.ifBlank { createdAt }
}

@Serializable
data class PairingStatusDto(
    val status: String,
)

@Serializable
private data class PairingClaimDto(
    val code: String,
    val device_id: String? = null,
    val installation_id: String? = null,
    val device_name: String? = null,
    val device_type: String? = null,
    val platform: String? = null,
)

@Serializable
private data class PairingErrorDto(
    val detail: String? = null,
)

class PairingUnsupportedException(message: String) : IllegalStateException(message)
