package com.torve.data.addon

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * The core stream resolution engine.
 * Fans out to all addons in parallel, deduplicates, checks debrid cache,
 * filters by preferences, scores, and sorts.
 */
class StreamAggregator(
    private val addonClient: StremioAddonClient,
    private val debridClient: DebridClient,
    private val scorer: StreamScorer,
) {
    /** Track addon failures for diagnostics (addon URL → last error message). */
    private val addonFailures = mutableMapOf<String, String>()

    fun getAddonHealth(): Map<String, String> = addonFailures.toMap()

    /**
     * Full stream resolution pipeline:
     * 1. Fan out to all installed addons in parallel (10s timeout each)
     * 2. Merge and deduplicate by infoHash
     * 3. Batch check debrid cache
     * 4. Enrich with cache status
     * 5. Filter by user preferences
     * 6. Score and sort by weighted preference match
     */
    suspend fun resolveStreams(
        addons: List<InstalledAddon>,
        type: MediaType,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        debridAccounts: Map<DebridServiceType, String> = emptyMap(),
        preferences: StreamPreferences = StreamPreferences(),
        fetchPolicy: StreamFetchPolicy = StreamFetchPolicy.FULL,
    ): List<ParsedStream> = coroutineScope {
        val addonUrls = resolveStreamAddonBaseUrls(addons)

        // 1. Fan out to all addons in parallel using the selected startup policy
        val rawStreams = addonUrls.map { url ->
            async {
                fetchFromAddon(
                    url = url,
                    type = type,
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                    fetchPolicy = fetchPolicy,
                )
            }
        }.awaitAll().flatten()

        // 2. Deduplicate by infoHash (or full title for non-torrent streams)
        val unique = rawStreams.distinctBy { it.infoHash ?: it.directUrl ?: it.title }

        // 3. Batch check debrid cache
        val torrentHashes = unique.mapNotNull { it.infoHash }.distinct()
        val cacheStatus: MutableMap<String, Boolean> = mutableMapOf()
        if (torrentHashes.isNotEmpty()) {
            debridAccounts.forEach { (provider, apiKey) ->
                try {
                    val cached = debridClient.checkCache(provider, apiKey, torrentHashes)
                    cached.forEach { (hash, isCached) ->
                        if (isCached) cacheStatus[hash] = true
                    }
                } catch (e: Exception) {
                    // println("StreamAggregator: debrid cache check failed for $provider: ${e.message}")
                }
            }
        }

        // 4. Enrich with cache status
        val enriched = unique.map { stream ->
            if (stream.infoHash != null && cacheStatus[stream.infoHash] == true) {
                stream.copy(
                    isCached = true,
                    source = "${stream.source ?: ""} ⚡".trim(),
                )
            } else {
                stream
            }
        }

        // 5. Filter by preferences
        val filtered = enriched.filter { stream ->
            // Cached-only filter
            if (preferences.cachedOnly && cacheStatus.isNotEmpty()) {
                if (stream.infoHash != null && !stream.isCached) return@filter false
            }

            // Quality range filter
            val quality = StreamQuality.fromString(stream.quality)
            if (quality != StreamQuality.UNKNOWN) {
                if (quality.rank < preferences.maxQuality.rank) return@filter false
                if (quality.rank > preferences.minQuality.rank) return@filter false
            }

            // Max file size filter
            preferences.maxFileSizeBytes?.let { maxBytes ->
                val sizeBytes = stream.size?.let { parseSizeToBytes(it) }
                if (sizeBytes != null && sizeBytes > maxBytes) return@filter false
            }

            true
        }

        // 6. Score and sort
        scorer.scoreAll(filtered, preferences)
    }

    private suspend fun fetchFromAddon(
        url: String,
        type: MediaType,
        imdbId: String,
        season: Int?,
        episode: Int?,
        fetchPolicy: StreamFetchPolicy,
    ): List<ParsedStream> {
        var lastError: Exception? = null
        repeat(fetchPolicy.retryCount + 1) { attempt ->
            try {
                return withTimeout(fetchPolicy.addonTimeoutMs) {
                    addonClient.getStreams(url, type, imdbId, season, episode)
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < fetchPolicy.retryCount && fetchPolicy.retryDelayMs > 0) {
                    delay(fetchPolicy.retryDelayMs)
                }
            }
        }

        addonFailures[url] = lastError?.message ?: "Unknown error"
        return emptyList()
    }

    companion object {
        fun parseSizeToBytes(sizeStr: String): Long? {
            val text = sizeStr.trim().uppercase()
            val regex = Regex("""([\d.]+)\s*(GB|MB|TB|KB)""")
            val match = regex.find(text) ?: return null
            val value = match.groupValues[1].toDoubleOrNull() ?: return null
            val unit = match.groupValues[2]
            return when (unit) {
                "TB" -> (value * 1024 * 1024 * 1024 * 1024).toLong()
                "GB" -> (value * 1024 * 1024 * 1024).toLong()
                "MB" -> (value * 1024 * 1024).toLong()
                "KB" -> (value * 1024).toLong()
                else -> null
            }
        }
    }
}

internal fun resolveStreamAddonBaseUrls(
    addons: List<InstalledAddon>,
): List<String> {
    return addons
        .filter { addon ->
            addon.isEnabled &&
                addon.supportsStreamResolution()
        }
        .map { addon ->
            addon.manifestUrl.removeSuffix("/manifest.json").removeSuffix("/")
        }
}

private fun InstalledAddon.supportsStreamResolution(): Boolean {
    val resources = manifest.resources
    return resources.isEmpty() || resources.any { resource ->
        resource.equals("stream", ignoreCase = true)
    }
}
