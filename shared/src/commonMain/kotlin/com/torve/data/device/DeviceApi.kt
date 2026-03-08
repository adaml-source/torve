package com.torve.data.device

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * Backend API client for device governance.
 * Manages device activation, removal, and access-state queries.
 */
class DeviceApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun getAccessState(accessToken: String): AccessStateDto {
        return httpClient.get("${baseUrl()}/me/access-state") {
            bearerAuth(accessToken)
        }.body()
    }

    suspend fun getDevices(accessToken: String): DeviceListDto {
        return httpClient.get("${baseUrl()}/me/devices") {
            bearerAuth(accessToken)
        }.body()
    }

    suspend fun activateCurrent(accessToken: String): DeviceActivateDto {
        return httpClient.post("${baseUrl()}/me/devices/activate-current") {
            bearerAuth(accessToken)
        }.body()
    }

    suspend fun removeDevice(accessToken: String, deviceId: String): DeviceRemoveDto {
        return httpClient.post("${baseUrl()}/me/devices/$deviceId/remove") {
            bearerAuth(accessToken)
        }.body()
    }

    suspend fun renameDevice(accessToken: String, deviceId: String, newName: String): ManagedDeviceDto {
        return httpClient.patch("${baseUrl()}/me/devices/$deviceId") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(DeviceRenameDto(newName))
        }.body()
    }
}

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
    val active_device_count: Int,
    val max_active_devices: Int,
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
    val user: UserDto,
    val premium: PremiumStateDto,
    val device: DeviceStateDto,
    val device_limit: DeviceLimitDto,
)

@Serializable
data class ManagedDeviceDto(
    val id: String,
    val device_name: String,
    val device_type: String,
    val platform: String,
    val is_current: Boolean = false,
    val is_active: Boolean = false,
    val last_seen_at: String,
    val activated_at: String? = null,
    val removed_at: String? = null,
    val removal_reason: String? = null,
    val first_seen_at: String,
)

@Serializable
data class DeviceListDto(
    val devices: List<ManagedDeviceDto>,
    val active_count: Int,
    val max_active: Int,
    val swaps_remaining: Int,
)

@Serializable
data class DeviceActivateDto(
    val activated: Boolean,
    val reason: String,
    val active_device_count: Int,
    val stale_devices_pruned: Int,
    val swaps_remaining: Int,
)

@Serializable
data class DeviceRemoveDto(
    val removed: Boolean,
    val reason: String,
    val swaps_remaining: Int,
)

@Serializable
private data class DeviceRenameDto(val device_name: String)
