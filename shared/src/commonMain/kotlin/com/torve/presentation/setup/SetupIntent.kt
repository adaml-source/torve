package com.torve.presentation.setup

import com.torve.domain.providerhealth.ProviderHealthCategory

/**
 * One of the four headline setup intents the user picks from on first
 * launch (and revisits from Settings → Sources later).
 *
 * Each intent maps to one or more [ProviderHealthCategory] rows that the
 * UI inspects to decide green/yellow/red.
 */
enum class SetupIntent(
    val title: String,
    val tagline: String,
    val healthCategories: List<ProviderHealthCategory>,
) {
    DEBRID(
        title = "Real-Debrid / AllDebrid / Premiumize / TorBox",
        tagline = "I have a debrid account.",
        healthCategories = listOf(ProviderHealthCategory.DEBRID),
    ),
    IPTV(
        title = "IPTV",
        tagline = "I have an M3U or Xtream playlist.",
        healthCategories = listOf(ProviderHealthCategory.IPTV, ProviderHealthCategory.EPG),
    ),
    PLEX_JELLYFIN(
        title = "Plex or Jellyfin",
        tagline = "I have a personal media server.",
        healthCategories = listOf(ProviderHealthCategory.PLEX_JELLYFIN),
    ),
    USENET(
        title = "Usenet / Easynews / NZB indexers",
        tagline = "I have Usenet credentials.",
        healthCategories = listOf(
            ProviderHealthCategory.USENET_INDEXER,
            ProviderHealthCategory.USENET_PROVIDER,
            ProviderHealthCategory.DOWNLOAD_CLIENT,
        ),
    ),
}
