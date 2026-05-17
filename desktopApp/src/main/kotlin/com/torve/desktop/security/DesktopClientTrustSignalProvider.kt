package com.torve.desktop.security

import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.security.ClientTrustSignal
import com.torve.domain.security.ClientTrustSignalProvider

class DesktopClientTrustSignalProvider(
    private val deviceIdProvider: DeviceIdProvider,
) : ClientTrustSignalProvider {
    override suspend fun currentSignal(includeIntegrityToken: Boolean): ClientTrustSignal {
        val platform = when (deviceIdProvider.getPlatform()) {
            "windows" -> "desktop_windows"
            "macos" -> "desktop_macos"
            "linux" -> "desktop_linux"
            else -> "desktop"
        }
        return ClientTrustSignal(
            platform = platform,
            appVersion = System.getProperty("torve.desktop.version")
                ?: deviceIdProvider.getAppVersion(),
            buildNumber = System.getProperty("torve.desktop.build"),
            flavor = "desktop",
            distributionChannel = System.getProperty("torve.desktop.channel")
                ?: "desktop",
            packageName = "com.torve.desktop",
            installerPackage = null,
            signingCertificateSha256 = null,
            isDebuggable = System.getProperty("torve.desktop.debug")?.toBooleanStrictOrNull(),
            isEmulator = false,
            hasKnownHookingIndicators = false,
            hasKnownRootIndicators = false,
            integrityProvider = "desktop_none",
        )
    }
}
