package com.torve.data.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceApiPayloadTest {
    @Test
    fun parses_live_device_list_payload_and_derives_legacy_fields() {
        val payload = """
            [
              {
                "id":"641f7b96-edde-425e-9f37-7132802753d3",
                "device_type":"phone",
                "platform":"android",
                "display_name":null,
                "installation_id":"manual-prod-check-1",
                "app_version":"dev",
                "last_seen_at":"2026-03-17T16:55:59.456420Z",
                "is_active":true,
                "revoked_at":null,
                "created_at":"2026-03-17T16:55:34.186432Z",
                "updated_at":"2026-03-17T16:55:59.457231Z"
              }
            ]
        """.trimIndent()

        val parsed = parseDeviceListPayload(payload, currentInstallationId = "manual-prod-check-1")

        assertEquals(1, parsed.active_count)
        assertEquals(5, parsed.max_active)
        assertEquals(1, parsed.devices.size)

        val device = parsed.devices.single()
        assertTrue(device.is_current)
        assertTrue(device.is_active)
        assertEquals("manual-prod-check-1", device.installation_id)
        assertEquals("2026-03-17T16:55:34.186432Z", device.first_seen_at)
        assertEquals("2026-03-17T16:55:59.456420Z", device.last_seen_at)
        assertTrue(device.device_name.startsWith("Phone"))
        assertNull(device.removed_at)
    }

    @Test
    fun parse_managed_device_payload_prefers_display_name_and_maps_revoked_state() {
        val payload = """
            {
              "id":"device-2",
              "device_type":"tv",
              "platform":"android_tv",
              "display_name":"Living Room TV",
              "installation_id":"install-2",
              "is_active":false,
              "last_seen_at":"2026-03-17T17:00:00Z",
              "revoked_at":"2026-03-17T17:05:00Z",
              "created_at":"2026-03-17T16:00:00Z"
            }
        """.trimIndent()

        val device = parseManagedDevicePayload(payload, currentInstallationId = "other-install")

        assertEquals("Living Room TV", device.device_name)
        assertEquals("2026-03-17T16:00:00Z", device.first_seen_at)
        assertEquals("2026-03-17T17:05:00Z", device.removed_at)
        assertEquals("install-2", device.installation_id)
        assertTrue(!device.is_current)
    }

    @Test
    fun parses_wrapped_device_list_payload() {
        val payload = """
            {
              "devices": [
                {
                  "id":"d1",
                  "device_type":"phone",
                  "platform":"android",
                  "device_name":"Pixel 9",
                  "is_active":true,
                  "last_seen_at":"2026-03-17T18:00:00Z",
                  "first_seen_at":"2026-03-17T16:00:00Z"
                }
              ],
              "active_count": 1,
              "max_active": 5,
              "swaps_remaining": 3
            }
        """.trimIndent()

        val parsed = parseDeviceListPayload(payload)

        assertEquals(1, parsed.active_count)
        assertEquals(5, parsed.max_active)
        assertEquals(3, parsed.swaps_remaining)
        assertEquals(1, parsed.devices.size)
        assertEquals("Pixel 9", parsed.devices[0].device_name)
    }

    @Test
    fun null_display_name_falls_back_to_device_type_label() {
        val payload = """
            {
              "id":"d2",
              "device_type":"tv",
              "platform":"android_tv",
              "display_name":null,
              "installation_id":"tv-001",
              "is_active":true,
              "last_seen_at":"2026-03-17T18:00:00Z",
              "created_at":"2026-03-17T16:00:00Z"
            }
        """.trimIndent()

        val device = parseManagedDevicePayload(payload)

        assertTrue(device.device_name.isNotBlank())
        assertTrue(device.device_name.startsWith("TV"))
    }

    @Test
    fun empty_array_returns_zero_active() {
        val parsed = parseDeviceListPayload("[]")

        assertEquals(0, parsed.active_count)
        assertEquals(5, parsed.max_active)
        assertTrue(parsed.devices.isEmpty())
    }

    @Test
    fun registration_routes_try_me_endpoint_before_compat_endpoint() {
        val routes = deviceRegistrationRoutes("https://api.torve.app")

        assertEquals(
            listOf(
                "https://api.torve.app/me/devices/register",
                "https://api.torve.app/devices/register",
            ),
            routes,
        )
    }
}
