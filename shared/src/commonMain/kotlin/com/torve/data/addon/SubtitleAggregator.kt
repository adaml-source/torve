package com.torve.data.addon

import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Aggregates subtitles from all installed subtitle addons in parallel.
 */
class SubtitleAggregator(
    private val addonClient: StremioAddonClient,
) {
    /**
     * Fetch subtitles from all enabled addons that declare "subtitles" resource.
     * Returns a merged, deduplicated list sorted by language.
     */
    suspend fun fetchSubtitles(
        addons: List<InstalledAddon>,
        type: MediaType,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        addonTimeoutMs: Long = 10_000,
    ): List<StremioSubtitle> = coroutineScope {
        val stremioType = when (type) {
            MediaType.MOVIE -> "movie"
            MediaType.SERIES -> "series"
        }
        val stremioId = if (type == MediaType.SERIES && season != null && episode != null) {
            "$imdbId:$season:$episode"
        } else {
            imdbId
        }

        val subtitleAddons = addons.filter { addon ->
            addon.isEnabled && addon.manifest.resources.any { it == "subtitles" }
        }

        if (subtitleAddons.isEmpty()) return@coroutineScope emptyList()

        subtitleAddons.map { addon ->
            async {
                try {
                    val baseUrl = addon.manifestUrl
                        .removeSuffix("/manifest.json")
                        .removeSuffix("/")
                    withTimeout(addonTimeoutMs) {
                        addonClient.fetchSubtitles(baseUrl, stremioType, stremioId)
                            .subtitles
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
            .distinctBy { it.url }
            .sortedBy { it.lang }
    }
}
