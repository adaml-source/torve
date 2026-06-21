package com.torve.data.pairing

import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.error.parseBackendError
import com.torve.domain.device.DeviceType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
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
    private val installationIdProvider: () -> String? = { null },
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
            appendInstallationHeader()
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
            appendInstallationHeader(device.installation_id)
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

    /**
     * Anonymous code creation for the TV-sign-in-via-phone flow. Distinct
     * route from [createPairingCode] — `/pairing/signin/code` issues codes
     * from a separate `pairing_signin_codes` table and requires no bearer
     * auth (the TV is signed out by definition). The legacy
     * `/pairing/code` is the premium-gated phone↔TV remote-control flow.
     */
    suspend fun createSigninCode(device: DeviceRegistrationDto): PairingCodeDto {
        val url = "${baseUrl()}/pairing/signin/code"
        val response = httpClient.post(url) {
            appendInstallationHeader(device.installation_id)
            setBody(device)
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = parseErrorDetail(raw)
            if (response.status.value == 404 && detail == null) {
                throw PairingUnsupportedException("TV sign-in is not available on this server.")
            }
            throw IllegalStateException(detail ?: "Pairing signin code failed (${response.status.value}): $raw")
        }
        return json.decodeFromString(PairingCodeDto.serializer(), raw)
    }

    /**
     * Anonymous status poll for the TV-sign-in-via-phone flow. Returns
     * tokens on the first claimed-poll matching the same `installationId`
     * that created the code; subsequent polls return `expired`. A 404
     * means the code never existed, expired, or the installationId
     * doesn't match — all of which the TV should treat as expired so it
     * restarts cleanly.
     */
    suspend fun pollSigninStatus(
        code: String,
        installationId: String,
    ): PairingStatusDto {
        val response = httpClient.post("${baseUrl()}/pairing/signin/status") {
            appendInstallationHeader(installationId)
            setBody(PairingStatusRequestDto(code = code, installationId = installationId))
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = parseErrorDetail(raw)
            if (response.status.value == 404) {
                return PairingStatusDto(status = "expired")
            }
            throw IllegalStateException(detail ?: "Pairing signin status failed (${response.status.value}): $raw")
        }
        return json.decodeFromString(PairingStatusDto.serializer(), raw)
    }

    /**
     * Phone-side claim of a TV-sign-in code. The phone is signed in;
     * backend mints a fresh access+refresh pair for the phone's user,
     * stores them on the row, and the TV's next status poll receives
     * them. May return 409 with the standard DeviceLimitError body when
     * the phone's account is at its device cap — the caller MUST surface
     * this to the user as "you've reached your device limit, free a
     * slot first" so they can resolve it the same way as a regular login
     * that hits the cap.
     */
    suspend fun claimSigninCode(accessToken: String, code: String): PairingStatusDto {
        val response = httpClient.post("${baseUrl()}/pairing/signin/claim") {
            bearerAuth(accessToken)
            appendInstallationHeader()
            setBody(PairingSigninClaimDto(code = code))
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = parseErrorDetail(raw)
            if (response.status.value == 409) {
                throw PairingDeviceLimitException(detail ?: "Device limit reached.")
            }
            if (response.status.value == 404 && detail == null) {
                throw PairingUnsupportedException("TV sign-in is not available on this server.")
            }
            throw IllegalStateException(detail ?: "Pairing signin claim failed (${response.status.value}): $raw")
        }
        return json.decodeFromString(PairingStatusDto.serializer(), raw)
    }

    suspend fun claimPairingCode(accessToken: String, code: String, device: DeviceRegistrationDto? = null): PairingStatusDto {
        val response = httpClient.post("${baseUrl()}/pairing/claim") {
            bearerAuth(accessToken)
            appendInstallationHeader(device?.installation_id)
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
            appendInstallationHeader()
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

    /**
     * Poll the backend for a pairing code's status — used by a
     * signed-OUT device (typically a TV) that just generated a code via
     * [createPairingCode] and is waiting for a signed-IN device (phone)
     * to claim it. Returns:
     *
     *   - status == "pending" — nobody has claimed yet, keep polling.
     *   - status == "claimed" with tokens populated — first-time observer
     *     of a successful claim. The caller MUST persist these tokens
     *     (via [com.torve.data.auth.AuthClient.signInViaPairing]) before
     *     polling again, because the backend stamps `consumed_at` and a
     *     subsequent poll returns claimed-without-tokens.
     *   - status == "claimed" without tokens — already consumed by an
     *     earlier observer; should never normally happen for the same
     *     poller, but if it does the caller has nothing to act on.
     *   - status == "expired" — code TTL ran out, ask user to restart.
     *
     * No bearer auth is sent — pairing/status is the unauthenticated
     * polling endpoint that's the whole point of the QR-sign-in flow
     * (the TV doesn't have a token yet by definition).
     */
    suspend fun pollPairingStatus(
        code: String,
        installationId: String,
    ): PairingStatusDto {
        val response = httpClient.post("${baseUrl()}/pairing/status") {
            appendInstallationHeader(installationId)
            setBody(PairingStatusRequestDto(code = code, installationId = installationId))
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = parseErrorDetail(raw)
            if (response.status.value == 404 && detail == null) {
                throw PairingUnsupportedException("Pairing status polling is not available on this server.")
            }
            // 404 with a "not found" detail is a normal "code expired or
            // never existed" outcome — surface as expired so the caller
            // can restart cleanly.
            if (response.status.value == 404) {
                return PairingStatusDto(status = "expired")
            }
            throw IllegalStateException(detail ?: "Pairing status failed (${response.status.value}): $raw")
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
            appendInstallationHeader()
        }
        return response.status.value != 404
    }

    private fun parseErrorDetail(raw: String): String? =
        parseBackendError(raw).message

    private fun HttpRequestBuilder.appendInstallationHeader(
        installationId: String? = installationIdProvider(),
    ) {
        installationId?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }
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
    /** Resolved device type enum. */
    fun resolvedDeviceTypeEnum(): DeviceType = DeviceType.fromWireValue(resolvedDeviceType())
    /** User-facing device type label. */
    fun resolvedDeviceTypeLabel(): String = resolvedDeviceTypeEnum().displayLabel
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
    /**
     * Auth tokens for the user that claimed this pairing. Populated by
     * the backend when [status] == "claimed" and the TV (which created
     * the code unauthenticated) is polling — this is how a signed-out
     * device gets its first access/refresh tokens via the QR-sign-in
     * flow. Null on every other status (pending, expired) and on
     * server-link replies (the phone-side claim only sees the link
     * confirmation, not tokens for itself — it's already authed).
     */
    val tokens: PairingTokensDto? = null,
    val user: PairingUserDto? = null,
    @SerialName("paired_device")
    val pairedDevice: PairingDeviceDto? = null,
)

@Serializable
data class PairingTokensDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("token_type")
    val tokenType: String = "bearer",
    @SerialName("expires_in")
    val expiresIn: Int = 900,
)

@Serializable
data class PairingUserDto(
    val id: String,
    val email: String,
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("is_verified")
    val isVerified: Boolean = false,
)

@Serializable
data class PairingDeviceDto(
    val id: String = "",
    val name: String? = null,
)

@Serializable
private data class PairingStatusRequestDto(
    val code: String,
    @SerialName("installation_id")
    val installationId: String,
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
private data class PairingSigninClaimDto(
    val code: String,
)

@Serializable
private data class PairingErrorDto(
    val detail: String? = null,
)

class PairingUnsupportedException(message: String) : IllegalStateException(message)

/**
 * Thrown by [PairingApi.claimSigninCode] when the phone's account has
 * already reached its active-device cap. Caller routes the user to the
 * same "manage devices" UX as a regular login that hits the cap.
 */
class PairingDeviceLimitException(message: String) : IllegalStateException(message)
