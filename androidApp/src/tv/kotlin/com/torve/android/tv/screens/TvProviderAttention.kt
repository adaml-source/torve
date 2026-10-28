package com.torve.android.tv.screens

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

internal enum class TvProviderAttentionDestination(val category: TvSettingsCategory) {
    STREAMING_SETUP(TvSettingsCategory.ADVANCED),
    SEERR(TvSettingsCategory.CONNECTIONS),
    AUTOMATION(TvSettingsCategory.CONNECTIONS),
    TRAKT(TvSettingsCategory.CONNECTIONS),
    SIMKL(TvSettingsCategory.CONNECTIONS),
    PLEX(TvSettingsCategory.ADVANCED),
    JELLYFIN(TvSettingsCategory.ADVANCED),
    ADDONS(TvSettingsCategory.ADVANCED),
    CHANNELS(TvSettingsCategory.LIBRARY),
    PLAYBACK(TvSettingsCategory.PLAYBACK),
}

internal fun providerAttentionEntries(entries: List<ProviderHealthEntry>): List<ProviderHealthEntry> =
    entries.filter { it.status == ProviderHealthStatus.RED || it.status == ProviderHealthStatus.YELLOW }
        .sortedWith(compareBy<ProviderHealthEntry>({ it.status != ProviderHealthStatus.RED }, { it.label }, { it.providerKey }))

internal fun providerAttentionDestination(entry: ProviderHealthEntry): TvProviderAttentionDestination =
    when (entry.category) {
        ProviderHealthCategory.DEBRID,
        ProviderHealthCategory.USENET_INDEXER,
        ProviderHealthCategory.USENET_PROVIDER,
        ProviderHealthCategory.DOWNLOAD_CLIENT -> TvProviderAttentionDestination.STREAMING_SETUP
        ProviderHealthCategory.REQUEST_MANAGER -> if (entry.providerKey == "seerr:request_manager") {
            TvProviderAttentionDestination.SEERR
        } else {
            TvProviderAttentionDestination.AUTOMATION
        }
        ProviderHealthCategory.TRAKT -> TvProviderAttentionDestination.TRAKT
        ProviderHealthCategory.SIMKL -> TvProviderAttentionDestination.SIMKL
        ProviderHealthCategory.PLEX_JELLYFIN -> if (entry.providerKey.contains("plex", ignoreCase = true)) {
            TvProviderAttentionDestination.PLEX
        } else {
            TvProviderAttentionDestination.JELLYFIN
        }
        ProviderHealthCategory.ADDON -> TvProviderAttentionDestination.ADDONS
        ProviderHealthCategory.IPTV, ProviderHealthCategory.EPG -> TvProviderAttentionDestination.CHANNELS
        ProviderHealthCategory.PLAYBACK -> TvProviderAttentionDestination.PLAYBACK
    }

internal fun providerAttentionItemId(entry: ProviderHealthEntry): String =
    "settings/connections/provider_attention/${entry.category.name}/${entry.providerKey}"

internal fun providerAttentionCategories(entries: List<ProviderHealthEntry>): Set<TvSettingsCategory> =
    if (entries.isEmpty()) emptySet() else entries.mapTo(mutableSetOf(TvSettingsCategory.CONNECTIONS)) {
        providerAttentionDestination(it).category
    }
