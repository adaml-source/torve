package com.torve.desktop.platform

import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.device.DeviceIdProvider
import java.io.File
import java.util.UUID

class DesktopDeviceIdProvider : DeviceIdProvider {
    private val installationIdFile = File(desktopDataDir(), "installation-id.txt")
    private val stableIdFile = File(desktopDataDir(), "stable-device-id.txt")

    override fun getDeviceId(): String {
        if (installationIdFile.exists()) {
            return installationIdFile.readText().trim().ifBlank { createAndPersistId() }
        }
        return createAndPersistId()
    }

    override fun getDeviceName(): String {
        return System.getenv("COMPUTERNAME")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name")?.takeIf { it.isNotBlank() }
            ?: "Windows Desktop"
    }

    override fun getDeviceType(): String = "desktop"

    override fun getPlatform(): String = "windows"

    override fun getAppVersion(): String = "desktop-preview"

    override fun getStableDeviceId(): String? {
        return try {
            if (stableIdFile.exists()) {
                stableIdFile.readText().trim().takeIf { it.isNotBlank() }
            } else {
                val id = UUID.randomUUID().toString()
                stableIdFile.parentFile?.mkdirs()
                stableIdFile.writeText(id)
                id
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun getDeviceRegistration(): DeviceRegistrationDto = super.getDeviceRegistration()

    private fun createAndPersistId(): String {
        val id = UUID.randomUUID().toString()
        installationIdFile.parentFile?.mkdirs()
        installationIdFile.writeText(id)
        return id
    }
}

internal fun desktopDataDir(): File {
    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
    return if (localAppData != null) {
        File(localAppData, "Torve")
    } else {
        File(System.getProperty("user.home"), ".torve")
    }
}
