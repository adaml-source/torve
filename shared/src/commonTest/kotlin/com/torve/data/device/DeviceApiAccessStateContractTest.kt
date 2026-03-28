package com.torve.data.device

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceApiAccessStateContractTest {

    @Test
    fun getAccessStateIncludesInstallationIdQueryParameter() = runTest {
        var capturedInstallationId: String? = null
        val api = DeviceApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    capturedInstallationId = request.url.parameters["installation_id"]
                    respond(
                        content = accessStateResponse(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ),
            baseUrlProvider = { "https://api.torve.app" },
            currentInstallationIdProvider = { "install-123" },
        )

        val result = api.getAccessState(accessToken = "token")

        assertNotNull(result)
        assertEquals("install-123", capturedInstallationId)
    }

    @Test
    fun getAccessStateStillCallsBackendWithoutInstallationId() = runTest {
        var capturedInstallationId: String? = "not-set"
        val api = DeviceApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    capturedInstallationId = request.url.parameters["installation_id"]
                    respond(
                        content = accessStateResponse(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ),
            baseUrlProvider = { "https://api.torve.app" },
            currentInstallationIdProvider = { "" },
        )

        val result = api.getAccessState(accessToken = "token")

        assertNotNull(result)
        assertTrue(capturedInstallationId.isNullOrBlank())
    }

    @Test
    fun finalizedTierKeepsPremiumEntitlementWhenDeviceIsBlocked() {
        val access = AccessStateDto(
            user = UserDto(id = "user-1", email = "user@torve.app"),
            premium = PremiumStateDto(
                has_entitlement = false,
                premium_access = false,
                reason = "",
                entitlements = emptyList(),
            ),
            device = DeviceStateDto(
                id = "device-1",
                name = "Living Room",
                is_active = false,
                active_device_count = 5,
                max_active_devices = 5,
                platform = "android_tv",
                device_type = "tv",
            ),
            device_limit = DeviceLimitDto(
                cap_reached = true,
                swaps_remaining = 0,
                stale_devices_pruned = 0,
            ),
            has_premium_access = false,
            access_tier = "monthly",
            is_device_activated = false,
            device_block_reason = "device_cap_reached",
        )

        assertTrue(access.resolvedHasPremiumEntitlement())
        assertFalse(access.resolvedUsablePremiumAccess())
        assertEquals("device_cap_reached", access.resolvedDeviceBlockReason())
    }

    @Test
    fun freeAccessStateStillResolvesToNoPremium() {
        val access = AccessStateDto(
            user = UserDto(id = "user-1", email = "user@torve.app"),
            premium = PremiumStateDto(
                has_entitlement = false,
                premium_access = false,
                reason = "",
                entitlements = emptyList(),
            ),
            device = DeviceStateDto(
                id = "device-1",
                name = "Phone",
                is_active = false,
                active_device_count = 0,
                max_active_devices = 5,
                platform = "android",
                device_type = "phone",
            ),
            device_limit = DeviceLimitDto(
                cap_reached = false,
                swaps_remaining = 3,
                stale_devices_pruned = 0,
            ),
            has_premium_access = false,
            access_tier = "free",
            is_device_activated = false,
            device_block_reason = null,
        )

        assertFalse(access.resolvedHasPremiumEntitlement())
        assertFalse(access.resolvedUsablePremiumAccess())
    }

    private fun accessStateResponse(): String {
        return """
            {
              "user": { "id": "user-1", "email": "user@torve.app" },
              "premium": {
                "has_entitlement": true,
                "premium_access": true,
                "reason": "",
                "entitlements": []
              },
              "device": {
                "id": "device-1",
                "name": "Phone",
                "is_active": true,
                "active_device_count": 1,
                "max_active_devices": 5,
                "platform": "android",
                "device_type": "phone"
              },
              "device_limit": {
                "cap_reached": false,
                "swaps_remaining": 3,
                "stale_devices_pruned": 0,
                "active_devices": []
              },
              "has_premium_access": true,
              "access_tier": "monthly",
              "is_device_activated": true,
              "device_block_reason": null
            }
        """.trimIndent()
    }
}
