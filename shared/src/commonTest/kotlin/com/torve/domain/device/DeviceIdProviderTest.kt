package com.torve.domain.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceIdProviderTest {
    @Test
    fun getDeviceRegistration_preservesDesktopDeviceType() {
        val provider = object : DeviceIdProvider {
            override fun getDeviceId(): String = "desktop-install-1"

            override fun getDeviceName(): String = "Windows Desktop"

            override fun getDeviceType(): String = DeviceType.DESKTOP.wireValue

            override fun getPlatform(): String = "windows"

            override fun getAppVersion(): String = "desktop-preview"
        }

        val registration = provider.getDeviceRegistration()

        assertEquals("desktop-install-1", registration.installation_id)
        assertEquals("Windows Desktop", registration.device_name)
        assertEquals(DeviceType.DESKTOP.wireValue, registration.device_type)
        assertEquals("windows", registration.platform)
        assertEquals("desktop-preview", registration.app_version)
    }

    @Test
    fun getDeviceRegistration_includesStableDeviceId() {
        val provider = object : DeviceIdProvider {
            override fun getDeviceId(): String = "install-uuid-123"
            override fun getStableDeviceId(): String = "a1b2c3d4e5f6"
        }

        val registration = provider.getDeviceRegistration()

        assertEquals("install-uuid-123", registration.installation_id)
        assertEquals("a1b2c3d4e5f6", registration.stable_device_id)
    }

    @Test
    fun getDeviceRegistration_stableDeviceIdNullByDefault() {
        val provider = object : DeviceIdProvider {
            override fun getDeviceId(): String = "install-uuid-456"
        }

        val registration = provider.getDeviceRegistration()

        assertEquals("install-uuid-456", registration.installation_id)
        assertNull(registration.stable_device_id)
    }

    @Test
    fun stableDeviceId_independentFromInstallationId() {
        val provider = object : DeviceIdProvider {
            override fun getDeviceId(): String = "install-abc123def"
            override fun getStableDeviceId(): String = "abc123def"
        }

        val registration = provider.getDeviceRegistration()

        assertEquals("install-abc123def", registration.installation_id)
        assertEquals("abc123def", registration.stable_device_id)
    }
}
