package com.torve.desktop

data class DesktopReleaseInfo(
    val appName: String,
    val version: String,
    val vendor: String,
    val description: String,
    val channel: String,
) {
    val versionLabel: String
        get() = "Version $version"

    val aboutLines: List<String>
        get() = listOf(
            appName,
            versionLabel,
            "Vendor: $vendor",
            "Channel: $channel",
            description,
        )

    companion object {
        fun current(): DesktopReleaseInfo {
            return DesktopReleaseInfo(
                appName = System.getProperty("torve.desktop.appName") ?: "Torve",
                version = System.getProperty("torve.desktop.version") ?: "dev",
                vendor = System.getProperty("torve.desktop.vendor") ?: "Torve",
                description = System.getProperty("torve.desktop.description")
                    ?: "Torve desktop for Windows with embedded VLC playback.",
                channel = System.getProperty("torve.desktop.channel") ?: "internal-preview",
            )
        }
    }
}
