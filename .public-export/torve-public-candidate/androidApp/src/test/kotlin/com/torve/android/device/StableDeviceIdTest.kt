package com.torve.android.device

import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.device.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies that stable_device_id is correctly wired into the device
 * registration DTO and sent alongside installation_id.
 */
class StableDeviceIdTest {

    @Test
    fun `registration DTO includes stable_device_id when provider returns it`() {
        val provider = object : DeviceIdProvider {
            override fun getDeviceId(): String = "install-abc123"
            override fun getStableDeviceId(): String = "abc123"
            override fun getDeviceName(): String = "Pixel 9"
            override fun getDeviceType(): String = DeviceType.PHONE.wireValue
            override fun getPlatform(): String = "android"
        }

        val dto = provider.getDeviceRegistration()

        assertEquals("install-abc123", dto.installation_id)
        assertEquals("abc123", dto.stable_device_id)
        assertEquals("Pixel 9", dto.device_name)
    }

    @Test
    fun `registration DTO has null stable_device_id when provider returns null`() {
        val provider = object : DeviceIdProvider {
            override fun getDeviceId(): String = "random-uuid"
            override fun getStableDeviceId(): String? = null
        }

        val dto = provider.getDeviceRegistration()

        assertEquals("random-uuid", dto.installation_id)
        assertNull(dto.stable_device_id)
    }

    @Test
    fun `stable_device_id is raw ANDROID_ID without prefix`() {
        val rawAndroidId = "f8e7d6c5b4a3"
        val provider = object : DeviceIdProvider {
            override fun getDeviceId(): String = "install-f8e7d6c5b4a3"
            override fun getStableDeviceId(): String = rawAndroidId
        }

        val dto = provider.getDeviceRegistration()

        assertEquals("install-f8e7d6c5b4a3", dto.installation_id)
        assertEquals("f8e7d6c5b4a3", dto.stable_device_id)
        assertNotEquals(dto.installation_id, dto.stable_device_id)
    }

    @Test
    fun `TV device includes stable_device_id`() {
        val provider = object : DeviceIdProvider {
            override fun getDeviceId(): String = "install-tv-device-id"
            override fun getStableDeviceId(): String = "tv-device-id"
            override fun getDeviceName(): String = "Fire TV Stick"
            override fun getDeviceType(): String = DeviceType.TV.wireValue
            override fun getPlatform(): String = "android_tv"
        }

        val dto = provider.getDeviceRegistration()

        assertNotNull(dto.stable_device_id)
        assertEquals("tv", dto.device_type)
        assertEquals("android_tv", dto.platform)
        assertEquals("tv-device-id", dto.stable_device_id)
    }

    @Test
    fun `DTO serialization includes stable_device_id field`() {
        val dto = DeviceRegistrationDto(
            installation_id = "install-123",
            device_name = "Test Device",
            device_type = "phone",
            platform = "android",
            stable_device_id = "stable-abc",
        )
        val json = kotlinx.serialization.json.Json.encodeToString(
            DeviceRegistrationDto.serializer(),
            dto,
        )

        assert("\"stable_device_id\":\"stable-abc\"" in json) {
            "JSON should contain stable_device_id field: $json"
        }
        assert("\"installation_id\":\"install-123\"" in json) {
            "JSON should still contain installation_id field: $json"
        }
    }

    @Test
    fun `DTO serialization omits stable_device_id when null`() {
        val dto = DeviceRegistrationDto(
            installation_id = "install-456",
            device_name = "Test Device",
            device_type = "phone",
            platform = "android",
            stable_device_id = null,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(
            DeviceRegistrationDto.serializer(),
            dto,
        )

        // Null optional field should not appear in JSON (kotlinx.serialization default)
        assert("stable_device_id" !in json || "null" in json) {
            "JSON should omit or null stable_device_id when not set: $json"
        }
    }
}
