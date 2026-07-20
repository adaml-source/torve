package com.torve.data.device

import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.beta.BetaAccessStateDto
import com.torve.data.error.deviceLimitReachedMessage
import com.torve.data.error.parseBackendError
import com.torve.domain.device.DeviceType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.security.ClientTrustHeaders
import com.torve.platform.torveVerboseLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * Backend API client for device governance.
 * Manages device activation, removal, and access-state queries.
 */
class DeviceApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val currentInstallationIdProvider: () -> String? = { null },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun getAccessState(
        accessToken: String,
        installationId: String? = currentInstallationIdProvider(),
    ): AccessStateDto? {
        val effectiveInstallationId = installationId?.takeIf { it.isNotBlank() }
        torveVerboseLog {
            "ACCESS_STATE fetch_start installationIdPresent=${effectiveInstallationId != null}"
        }
        return try {
            val trustHeaders = ClientTrustHeaders.capture()
            val response = httpClient.get("${baseUrl()}/me/access-state") {
                bearerAuth(accessToken)
                effectiveInstallationId?.let { parameter("installation_id", it) }
                appendInstallationHeader(effectiveInstallationId)
                trustHeaders?.appendTo(this)
            }
            if (!response.status.isSuccess()) {
                torveVerboseLog {
                    "ACCESS_STATE fetch_non_success status=${response.status.value} installationIdPresent=${effectiveInstallationId != null}"
                }
                return null
            }
            val raw = response.bodyAsText()
            torveVerboseLog {
                "ACCESS_STATE response status=${response.status.value} bytes=${raw.length}"
            }
            json.decodeFromString(AccessStateDto.serializer(), raw).also { decoded ->
                torveVerboseLog {
                    "ACCESS_STATE fetch_success hasEntitlement=${decoded.resolvedHasPremiumEntitlement()} deviceActivated=${decoded.resolvedIsDeviceActivated()} deviceLimit=${decoded.resolvedDeviceLimit()} deviceCapOverride=${decoded.device_cap_override} activeDeviceCount=${decoded.resolvedActiveDeviceCount()} needsVerification=${decoded.needs_verification} blockReason=${decoded.resolvedDeviceBlockReason()}"
                }
            }
        } catch (e: Exception) {
            torveVerboseLog {
                "ACCESS_STATE fetch_failure ${e::class.simpleName}: ${e.message}"
            }
            // Endpoint may not exist on all backend versions — fail gracefully.
            null
        }
    }

    suspend fun getDevices(accessToken: String): DeviceListDto {
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.get("${baseUrl()}/me/devices") {
            bearerAuth(accessToken)
            appendInstallationHeader()
            trustHeaders?.appendTo(this)
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val parsed = parseBackendError(raw)
            throw IllegalStateException(parsed.message ?: "Failed to fetch devices (${response.status.value})")
        }

        return parseDeviceListPayload(raw, currentInstallationIdProvider())
    }

    suspend fun registerDevice(accessToken: String, device: DeviceRegistrationDto): ManagedDeviceDto? {
        val registrationRoutes = deviceRegistrationRoutes(baseUrl())
        var sawNotFound = false

        for (route in registrationRoutes) {
            val trustHeaders = ClientTrustHeaders.capture()
            val response = httpClient.post(route) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendInstallationHeader(device.installation_id)
                trustHeaders?.appendTo(this)
                setBody(device)
            }
            val raw = response.bodyAsText()
            when {
                response.status.value == 404 -> {
                    sawNotFound = true
                }
                response.status.isSuccess() -> {
                    return parseManagedDevicePayload(raw, currentInstallationIdProvider())
                }
                else -> {
                    val parsed = parseBackendError(raw)
                    throw IllegalStateException(
                        parsed.deviceLimitReachedMessage()
                            ?: parsed.message
                            ?: "Failed to register device (${response.status.value})",
                    )
                }
            }
        }

        if (sawNotFound) return null
        return null
    }

    suspend fun activateCurrent(accessToken: String): DeviceActivateDto {
        val trustHeaders = ClientTrustHeaders.capture()
        return httpClient.post("${baseUrl()}/me/devices/activate-current") {
            bearerAuth(accessToken)
            appendInstallationHeader()
            trustHeaders?.appendTo(this)
        }.body()
    }

    suspend fun removeDevice(accessToken: String, deviceId: String): DeviceRemoveDto {
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.post("${baseUrl()}/me/devices/$deviceId/remove") {
            bearerAuth(accessToken)
            appendInstallationHeader()
            trustHeaders?.appendTo(this)
        }
        // Backend may return the old DeviceRemoveDto or a plain DeviceOut on success.
        // Treat any 2xx as a successful removal.
        return if (response.status.isSuccess()) {
            runCatching { response.body<DeviceRemoveDto>() }.getOrElse {
                DeviceRemoveDto(removed = true, reason = "ok", swaps_remaining = -1)
            }
        } else {
            val detail = runCatching { response.bodyAsText() }.getOrElse { "" }
            DeviceRemoveDto(removed = false, reason = detail.take(200).ifBlank { "removal_failed" }, swaps_remaining = -1)
        }
    }

    suspend fun renameDevice(accessToken: String, deviceId: String, newName: String): ManagedDeviceDto {
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.patch("${baseUrl()}/me/devices/$deviceId") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            appendInstallationHeader()
            trustHeaders?.appendTo(this)
            setBody(DeviceRenameDto(newName))
        }
        return parseManagedDevicePayload(response.bodyAsText(), currentInstallationIdProvider())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendInstallationHeader(
        installationId: String? = currentInstallationIdProvider(),
    ) {
        installationId?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }
}

internal fun deviceRegistrationRoutes(baseUrl: String): List<String> = listOf(
    "$baseUrl/me/devices/register",
    "$baseUrl/devices/register",
)

internal fun parseDeviceListPayload(raw: String, currentInstallationId: String? = null): DeviceListDto {
    val json = deviceJson()
    return when (val element = json.parseToJsonElement(raw.ifBlank { "[]" })) {
            is JsonArray -> {
                val devices = json.decodeFromJsonElement(ListSerializer(ManagedDeviceDto.serializer()), element)
                    .map { normalizeManagedDevice(it, currentInstallationId) }
                DeviceListDto(
                    devices = devices,
                    active_count = devices.count { it.is_active },
                    max_active = 0,
                    swaps_remaining = 0,
                )
            }
            is JsonObject -> {
                if ("devices" in element || "active_count" in element || "max_active" in element) {
                    val decoded = json.decodeFromJsonElement(DeviceListDto.serializer(), element)
                    val normalizedDevices = decoded.devices.map { normalizeManagedDevice(it, currentInstallationId) }
                    decoded.copy(
                        devices = normalizedDevices,
                        active_count = decoded.active_count.takeIf { it > 0 || normalizedDevices.none { device -> device.is_active } }
                            ?: normalizedDevices.count { it.is_active },
                        max_active = decoded.max_active.coerceAtLeast(0),
                    )
                } else {
                    val device = normalizeManagedDevice(
                        json.decodeFromJsonElement(ManagedDeviceDto.serializer(), element),
                        currentInstallationId,
                    )
                    DeviceListDto(
                        devices = listOf(device),
                        active_count = if (device.is_active) 1 else 0,
                        max_active = 0,
                        swaps_remaining = 0,
                    )
                }
            }
            else -> error("Unexpected device list payload")
        }
}

internal fun parseManagedDevicePayload(raw: String, currentInstallationId: String? = null): ManagedDeviceDto {
    val json = deviceJson()
    val decoded = json.decodeFromString(ManagedDeviceDto.serializer(), raw)
    return normalizeManagedDevice(decoded, currentInstallationId)
}

private fun deviceJson() = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

internal fun normalizeManagedDevice(
    device: ManagedDeviceDto,
    currentInstallationId: String? = null,
): ManagedDeviceDto {
    val fallbackName = buildString {
        append(
            when (DeviceType.fromWireValue(device.device_type)) {
                DeviceType.TV -> "TV"
                DeviceType.TABLET -> "Tablet"
                DeviceType.DESKTOP -> "Desktop"
                else -> "Phone"
            },
        )
        val suffix = device.installation_id?.takeLast(4)
        if (!suffix.isNullOrBlank()) {
            append(" • ")
            append(suffix)
        }
    }

    return device.copy(
        device_name = device.device_name.ifBlank {
            device.display_name?.takeIf { it.isNotBlank() } ?: fallbackName
        },
        is_current = device.is_current || (
            !currentInstallationId.isNullOrBlank() &&
                device.installation_id == currentInstallationId
            ),
        last_seen_at = device.last_seen_at.ifBlank {
            device.updated_at ?: device.created_at ?: ""
        },
        removed_at = device.removed_at ?: device.revoked_at,
        first_seen_at = device.first_seen_at.ifBlank {
            device.created_at ?: device.updated_at ?: device.last_seen_at
        },
    )
}

@Serializable
private data class ErrorDetailDto(
    val detail: String? = null,
)

// ── DTOs ──

@Serializable
data class UserDto(val id: String, val email: String)

@Serializable
data class EntitlementDto(
    val key: String,
    val status: String,
    val source_store: String,
    val starts_at: String,
    val ends_at: String? = null,
)

@Serializable
data class PremiumStateDto(
    val has_entitlement: Boolean,
    val premium_access: Boolean,
    val reason: String,
    val entitlements: List<EntitlementDto>,
)

@Serializable
data class DeviceStateDto(
    val id: String,
    val name: String,
    val is_active: Boolean,
    val active_device_count: Int = 0,
    val max_active_devices: Int = 0,
    val platform: String,
    val device_type: String,
)

@Serializable
data class DeviceLimitDto(
    val cap_reached: Boolean,
    val swaps_remaining: Int,
    val stale_devices_pruned: Int,
    val active_devices: List<ManagedDeviceDto> = emptyList(),
)

@Serializable
data class AccessStateDto(
    // Legacy nested objects — nullable for new flat response format
    val user: UserDto? = null,
    val premium: PremiumStateDto? = null,
    val device: DeviceStateDto? = null,
    val device_limit: JsonElement? = null,
    // Canonical flat fields (new backend format)
    @SerialName("has_premium_access")
    val has_premium_access: Boolean? = null,
    @SerialName("access_tier")
    val access_tier: String? = null,
    @SerialName("is_device_activated")
    val is_device_activated: Boolean? = null,
    @SerialName("device_block_reason")
    val device_block_reason: String? = null,
    @SerialName("device_cap_override")
    val device_cap_override: Int? = null,
    @SerialName("active_device_count")
    val active_device_count: Int? = null,
    @SerialName("needs_verification")
    val needs_verification: Boolean = false,
    @SerialName("entitlement_type")
    val entitlement_type: String? = null,
    @SerialName("source")
    val source: String? = null,
    @SerialName("auto_renew")
    val auto_renew: Boolean? = null,
    // Backend returns the subscription expiry as a flat ISO-8601 string
    // alongside `access_tier`. Without this binding the date never
    // reaches the UI even though it's right there in the response.
    @SerialName("expires_at")
    val expires_at: String? = null,
    @SerialName("granted_at")
    val granted_at: String? = null,
    @SerialName("beta_access")
    val beta_access: BetaAccessStateDto? = null,
)

fun AccessStateDto.resolvedHasPremiumEntitlement(): Boolean {
    // Prefer canonical flat field
    resolvedAccessTier()?.let { tier ->
        return tier != SubscriptionTier.FREE
    }
    // Fall back to flat boolean or legacy nested field
    return has_premium_access ?: premium?.has_entitlement ?: false
}

fun AccessStateDto.resolvedAccessTier(): SubscriptionTier? {
    return parseAccessTier(access_tier)
        ?: parseAccessTier(entitlement_type)
        ?: parseAccessTier(source)
}

/**
 * Parse the flat top-level `expires_at` ISO-8601 string into epoch ms.
 * The backend uses this for admin-granted entitlements where no
 * per-purchase entitlement record exists, so it's the only path the
 * subscription expiry can arrive through for those users.
 */
fun AccessStateDto.resolvedExpiresAtEpochMs(): Long? {
    val raw = expires_at?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { kotlinx.datetime.Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
}

fun AccessStateDto.resolvedIsDeviceActivated(): Boolean {
    return is_device_activated ?: device?.is_active ?: false
}

fun AccessStateDto.resolvedDeviceBlockReason(): String? {
    return device_block_reason
        ?.takeIf { it.isNotBlank() }
        ?: premium?.reason?.takeIf {
            it.isNotBlank() && resolvedHasPremiumEntitlement() && !resolvedIsDeviceActivated()
        }
}

fun AccessStateDto.resolvedUsablePremiumAccess(): Boolean {
    // Compatibility name from the former paid-access model. Shared clients
    // must treat free/default access as usable access even when entitlement
    // fields are empty or explicitly "free".
    return true
}

fun AccessStateDto.resolvedDeviceLimit(): Int? {
    val flatLimit = (device_limit as? JsonPrimitive)?.intOrNull
    return flatLimit?.takeIf { it > 0 }
        ?: device?.max_active_devices?.takeIf { it > 0 }
}

fun AccessStateDto.resolvedDeviceCapOverride(): Int? {
    return device_cap_override?.takeIf { it > 0 }
}

fun AccessStateDto.resolvedActiveDeviceCount(): Int? {
    return active_device_count
        ?: device?.active_device_count?.takeIf { it >= 0 }
}

fun AccessStateDto.resolvedDeviceLimitCapReached(): Boolean? {
    val count = resolvedActiveDeviceCount()
    val limit = resolvedDeviceLimit()
    if (count != null && limit != null) return count >= limit
    return legacyDeviceLimit()?.cap_reached
}

fun AccessStateDto.resolvedSwapsRemaining(): Int? {
    return legacyDeviceLimit()?.swaps_remaining
}

fun AccessStateDto.resolvedStaleDevicesPruned(): Int? {
    return legacyDeviceLimit()?.stale_devices_pruned
}

fun AccessStateDto.resolvedDeviceLimitActiveDevices(): List<ManagedDeviceDto> {
    return legacyDeviceLimit()?.active_devices.orEmpty()
}

private fun AccessStateDto.legacyDeviceLimit(): DeviceLimitDto? {
    val element = device_limit as? JsonObject ?: return null
    return runCatching {
        deviceJson().decodeFromJsonElement(DeviceLimitDto.serializer(), element)
    }.getOrNull()
}

fun DeviceStateDto.resolvedDeviceType(): DeviceType = DeviceType.fromWireValue(device_type)

fun DeviceStateDto.deviceTypeLabel(): String = resolvedDeviceType().displayLabel

internal fun parseAccessTier(raw: String?): SubscriptionTier? {
    return when (raw?.trim()?.lowercase()) {
        null,
        "",
        -> null
        "free" -> SubscriptionTier.FREE
        "monthly",
        "premium_monthly",
        "premium_subscription",
        "subscription",
        "monthly_access",
        "subscription_access",
        -> SubscriptionTier.MONTHLY
        "lifetime",
        "premium_lifetime",
        "lifetime_access",
        "rebate_code",
        "admin_grant",
        -> SubscriptionTier.LIFETIME
        else -> null
    }
}

@Serializable
data class ManagedDeviceDto(
    val id: String,
    val device_name: String = "",
    val device_type: String,
    val platform: String,
    val is_current: Boolean = false,
    val is_active: Boolean = false,
    val last_seen_at: String = "",
    val activated_at: String? = null,
    val removed_at: String? = null,
    val removal_reason: String? = null,
    val first_seen_at: String = "",
    val display_name: String? = null,
    val installation_id: String? = null,
    val app_version: String? = null,
    val revoked_at: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
)

fun ManagedDeviceDto.resolvedDeviceType(): DeviceType = DeviceType.fromWireValue(device_type)

fun ManagedDeviceDto.deviceTypeLabel(): String = resolvedDeviceType().displayLabel

@Serializable
data class DeviceListDto(
    val devices: List<ManagedDeviceDto>,
    val active_count: Int,
    val max_active: Int = 0,
    val swaps_remaining: Int = 0,
)

@Serializable
data class DeviceActivateDto(
    val activated: Boolean,
    val reason: String,
    val active_device_count: Int,
    val stale_devices_pruned: Int,
    val swaps_remaining: Int,
    val device_limit: Int? = null,
    val max_devices: Int? = null,
    val device_cap_override: Int? = null,
)

@Serializable
data class DeviceRemoveDto(
    val removed: Boolean,
    val reason: String,
    val swaps_remaining: Int,
)

@Serializable
private data class DeviceRenameDto(val device_name: String)
