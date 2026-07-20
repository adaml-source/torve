package com.torve.data.device

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceApiAccessStateContractTest {

    @Test
    fun getAccessStateIncludesInstallationIdQueryParameter() = runTest {
        var capturedInstallationId: String? = null
        var capturedInstallationHeader: String? = null
        val api = DeviceApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    capturedInstallationId = request.url.parameters["installation_id"]
                    capturedInstallationHeader = request.headers["X-Torve-Installation-Id"]
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
        assertEquals("install-123", capturedInstallationHeader)
    }

    @Test
    fun getAccessStateStillCallsBackendWithoutInstallationId() = runTest {
        var capturedInstallationId: String? = "not-set"
        var capturedInstallationHeader: String? = "not-set"
        val api = DeviceApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    capturedInstallationId = request.url.parameters["installation_id"]
                    capturedInstallationHeader = request.headers["X-Torve-Installation-Id"]
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
        assertTrue(capturedInstallationHeader.isNullOrBlank())
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
            device_limit = legacyDeviceLimit(
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
        assertTrue(access.resolvedUsablePremiumAccess())
        assertEquals("device_cap_reached", access.resolvedDeviceBlockReason())
    }

    @Test
    fun freeAccessStateResolvesToUsableAccessWithoutPremiumEntitlement() {
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
            device_limit = legacyDeviceLimit(
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
        assertTrue(access.resolvedUsablePremiumAccess())
    }

    @Test
    fun lifetimeAccessEntitlementTypeResolvesToLifetimeTier() {
        val access = AccessStateDto(
            has_premium_access = true,
            access_tier = null,
            entitlement_type = "lifetime_access",
            source = "admin_grant",
            is_device_activated = true,
        )

        assertTrue(access.resolvedHasPremiumEntitlement())
        assertEquals(com.torve.domain.model.SubscriptionTier.LIFETIME, access.resolvedAccessTier())
        assertTrue(access.resolvedUsablePremiumAccess())
    }

    @Test
    fun adminGrantSourceResolvesToLifetimeTierWhenAccessTierIsMissing() {
        val access = AccessStateDto(
            has_premium_access = true,
            access_tier = null,
            entitlement_type = null,
            source = "admin_grant",
            is_device_activated = true,
        )

        assertTrue(access.resolvedHasPremiumEntitlement())
        assertEquals(com.torve.domain.model.SubscriptionTier.LIFETIME, access.resolvedAccessTier())
        assertTrue(access.resolvedUsablePremiumAccess())
    }

    @Test
    fun flatDeviceLimitResolvesEffectiveLimitAndThreshold() = runTest {
        val api = DeviceApi(
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = """
                            {
                              "user": { "id": "user-20", "email": "user@torve.app" },
                              "has_premium_access": true,
                              "access_tier": "monthly",
                              "device_limit": 20,
                              "device_cap_override": null,
                              "active_device_count": 20,
                              "is_device_activated": false,
                              "device_block_reason": "device_cap_reached"
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ),
            baseUrlProvider = { "https://api.torve.app" },
        )

        val access = api.getAccessState(accessToken = "token")

        assertNotNull(access)
        assertEquals(20, access.resolvedDeviceLimit())
        assertEquals(20, access.resolvedActiveDeviceCount())
        assertEquals(null, access.resolvedDeviceCapOverride())
        assertEquals(true, access.resolvedDeviceLimitCapReached())
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

    private fun legacyDeviceLimit(
        cap_reached: Boolean,
        swaps_remaining: Int,
        stale_devices_pruned: Int,
    ): JsonElement {
        return Json.encodeToJsonElement(
            DeviceLimitDto(
                cap_reached = cap_reached,
                swaps_remaining = swaps_remaining,
                stale_devices_pruned = stale_devices_pruned,
            ),
        )
    }
}
