package com.torve.android.tv.screens

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

/** Health shortcuts enter an actual settings form and retain their Connection tests origin. */
internal fun tvConnectionSettingsAction(entry: ProviderHealthEntry, jellyfinConnected: Boolean): String? = when {
    entry.providerKey.startsWith("automation:") -> null
    else -> when (entry.category) {
        ProviderHealthCategory.DEBRID -> "Connections"
        ProviderHealthCategory.IPTV, ProviderHealthCategory.EPG -> "Channels"
        ProviderHealthCategory.ADDON -> "extensions"
        ProviderHealthCategory.TRAKT -> "Trakt"
        ProviderHealthCategory.SIMKL -> "SIMKL"
        ProviderHealthCategory.PLEX_JELLYFIN -> if (jellyfinConnected) "Jellyfin" else "Plex"
        ProviderHealthCategory.PLAYBACK -> "Playback"
        ProviderHealthCategory.REQUEST_MANAGER -> "Seerr"
        ProviderHealthCategory.USENET_INDEXER, ProviderHealthCategory.USENET_PROVIDER,
        ProviderHealthCategory.DOWNLOAD_CLIENT -> "extensions"
    }
}

internal fun tvConnectionStatusLabel(status: ProviderHealthStatus): String = when (status) {
    ProviderHealthStatus.GREEN -> "Available"
    ProviderHealthStatus.RED -> "Connection failed"
    ProviderHealthStatus.YELLOW -> "Needs attention"
    ProviderHealthStatus.UNCONFIGURED -> "Not configured"
    ProviderHealthStatus.UNKNOWN -> "Not checked"
}
