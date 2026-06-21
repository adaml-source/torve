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
            ?: defaultDesktopName()
    }

    override fun getDeviceType(): String = "desktop"

    override fun getPlatform(): String = desktopPlatform()

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
    return when (desktopPlatform()) {
        "windows" -> {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            if (localAppData != null) File(localAppData, "Torve")
            else File(System.getProperty("user.home"), ".torve")
        }
        "linux" -> {
            val xdgDataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            if (xdgDataHome != null) File(xdgDataHome, "Torve")
            else File(File(System.getProperty("user.home"), ".local/share"), "Torve")
        }
        else -> File(System.getProperty("user.home"), ".torve")
    }
}

internal fun desktopPlatform(): String {
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        "win" in os -> "windows"
        "mac" in os || "darwin" in os -> "macos"
        "linux" in os || "nux" in os || "nix" in os -> "linux"
        else -> "desktop"
    }
}

private fun defaultDesktopName(): String =
    when (desktopPlatform()) {
        "windows" -> "Windows Desktop"
        "macos" -> "macOS Desktop"
        "linux" -> "Linux Desktop"
        else -> "Desktop"
    }
