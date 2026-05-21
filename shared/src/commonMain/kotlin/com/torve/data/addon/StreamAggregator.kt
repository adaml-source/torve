package com.torve.data.addon

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.platform.TorveRuntimeDebug
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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

    private inline fun streamDebugLog(message: () -> String) {
        if (TorveRuntimeDebug.verboseLoggingEnabled) {
            println(DiagnosticsRedactor.redact(message()))
        }
    }

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
        val addonUrls = resolveStreamAddonBaseUrls(addons, debridAccounts)

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
        val unique = rawStreams.distinctBy {
            it.directUrl ?: it.magnetUrl ?: it.infoHash ?: it.title
        }

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
                    streamDebugLog {
                        "StreamAggregator: debrid cache check failed provider=$provider error=${e::class.simpleName} ${DiagnosticsRedactor.redact(e.message)}"
                    }
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
        val staticFiltered = enriched.filter { stream ->
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

        val verified = preflightDebridCandidates(
            streams = staticFiltered,
            debridAccounts = debridAccounts,
            season = season,
            episode = episode,
            fetchPolicy = fetchPolicy,
        )

        val filtered = verified.filter { stream ->
            // Cached-only filter
            if (preferences.cachedOnly && stream.requiresDebridVerification() && !stream.isCached) {
                return@filter false
            }
            true
        }

        // 6. Score and sort
        val scored = scorer.scoreAll(filtered, preferences)
        streamDebugLog {
            "TORVE_STREAMS: pipeline raw=${rawStreams.size} unique=${unique.size} " +
                "filtered=${filtered.size} scored=${scored.size} addons=${scored.addonCounts()}"
        }
        scored
    }

    private suspend fun fetchFromAddon(
        url: String,
        type: MediaType,
        imdbId: String,
        season: Int?,
        episode: Int?,
        fetchPolicy: StreamFetchPolicy,
    ): List<ParsedStream> {
        val primary = fetchFromAddonUrl(
            url = url,
            type = type,
            imdbId = imdbId,
            season = season,
            episode = episode,
            fetchPolicy = fetchPolicy,
        )
        val fallbackUrl = url.torrentioFullDebridFallbackUrl() ?: return primary
        val fallback = fetchFromAddonUrl(
            url = fallbackUrl,
            type = type,
            imdbId = imdbId,
            season = season,
            episode = episode,
            fetchPolicy = fetchPolicy,
        )
        if (fallback.isNotEmpty()) {
            streamDebugLog { "TORVE_STREAMS: addon=torrentio.strem.fun(debrid-fallback) streams=${fallback.size}" }
        }
        return (primary + fallback).distinctBy {
            it.directUrl ?: it.magnetUrl ?: it.infoHash ?: it.title
        }
    }

    private suspend fun preflightDebridCandidates(
        streams: List<ParsedStream>,
        debridAccounts: Map<DebridServiceType, String>,
        season: Int?,
        episode: Int?,
        fetchPolicy: StreamFetchPolicy,
    ): List<ParsedStream> = coroutineScope {
        if (debridAccounts.isEmpty()) return@coroutineScope streams

        val passthrough = streams.filterNot { it.requiresDebridVerification() }
        val allCandidates = streams
            .filter { it.requiresDebridVerification() }
            .distinctBy { it.magnetUrl ?: it.infoHash ?: it.title }
        val candidates = streams
            .filter { it.requiresDebridVerification() }
            .distinctBy { it.magnetUrl ?: it.infoHash ?: it.title }
            .take(
                when (fetchPolicy) {
                    StreamFetchPolicy.PLAYBACK_STARTUP -> TORRENT_PREFLIGHT_STARTUP_LIMIT
                    StreamFetchPolicy.FULL -> TORRENT_PREFLIGHT_FULL_LIMIT
                },
            )

        streamDebugLog {
            "TORVE_STREAMS: torrentPreflight start raw=${streams.size} passthrough=${passthrough.size} " +
                "candidates=${allCandidates.size} checking=${candidates.size} policy=${fetchPolicy.label}"
        }
        if (candidates.isEmpty()) return@coroutineScope passthrough

        val semaphore = Semaphore(TORRENT_PREFLIGHT_CONCURRENCY)
        val resolved = candidates.map { stream ->
            async {
                semaphore.withPermit {
                    preflightDebridCandidate(stream, debridAccounts, season, episode)
                }
            }
        }.awaitAll().filterNotNull()

        streamDebugLog {
            "TORVE_STREAMS: torrentPreflight candidates=${candidates.size} verified=${resolved.size} " +
                "passthrough=${passthrough.size}"
        }
        passthrough + resolved
    }

    private suspend fun preflightDebridCandidate(
        stream: ParsedStream,
        debridAccounts: Map<DebridServiceType, String>,
        season: Int?,
        episode: Int?,
    ): ParsedStream? {
        val infoHash = stream.infoHash
            ?: stream.magnetUrl?.extractBtihInfoHash()
            ?: return null
        debridAccounts.forEach { (provider, apiKey) ->
            val key = apiKey.trim()
            if (key.isBlank()) return@forEach
            val resolved = runCatching {
                withTimeoutOrNull(TORRENT_PREFLIGHT_TIMEOUT_MS) {
                    debridClient.resolveStream(
                        provider = provider,
                        apiKey = key,
                        infoHash = infoHash,
                        fileIdx = stream.fileIdx,
                        magnetUrl = stream.magnetUrl,
                        displayName = stream.title,
                        season = season,
                        episode = episode,
                    )
                }
            }.getOrNull()
            if (resolved?.url?.isNotBlank() == true) {
                return stream.copy(isCached = true)
            }
        }
        return null
    }

    private suspend fun fetchFromAddonUrl(
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
                val streams = withTimeout(fetchPolicy.addonTimeoutMs) {
                    addonClient.getStreams(url, type, imdbId, season, episode)
                }
                streamDebugLog { "TORVE_STREAMS: addon=${safeAddonHealthKey(url)} streams=${streams.size}" }
                return streams
            } catch (e: Exception) {
                lastError = e
                if (attempt < fetchPolicy.retryCount && fetchPolicy.retryDelayMs > 0) {
                    delay(fetchPolicy.retryDelayMs)
                }
            }
        }

        addonFailures[safeAddonHealthKey(url)] = DiagnosticsRedactor.redact(lastError?.message ?: "Unknown error")
        return emptyList()
    }

    companion object {
        private const val TORRENT_PREFLIGHT_CONCURRENCY = 8
        private const val TORRENT_PREFLIGHT_TIMEOUT_MS = 8_000L
        private const val TORRENT_PREFLIGHT_STARTUP_LIMIT = 24
        private const val TORRENT_PREFLIGHT_FULL_LIMIT = 160

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
    debridAccounts: Map<DebridServiceType, String> = emptyMap(),
): List<String> {
    return addons
        .filter { addon ->
            addon.isEnabled &&
                addon.supportsStreamResolution()
        }
        .flatMap { addon ->
            val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json").removeSuffix("/")
            if (addon.isPlainTorrentio()) {
                val debridUrls = torrentioDebridBaseUrls(debridAccounts)
                debridUrls.ifEmpty { listOf(baseUrl) }
            } else {
                listOf(baseUrl)
            }
        }
        .distinct()
}

private fun InstalledAddon.supportsStreamResolution(): Boolean {
    val resources = manifest.resources
    return resources.isEmpty() || resources.any { resource ->
        resource.equals("stream", ignoreCase = true)
    }
}

private fun ParsedStream.requiresDebridVerification(): Boolean =
    directUrl == null && (infoHash != null || magnetUrl != null)

private fun List<ParsedStream>.addonCounts(): String {
    return groupingBy { it.addonName }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .joinToString(",") { "${it.key}:${it.value}" }
}

private fun InstalledAddon.isPlainTorrentio(): Boolean {
    val baseUrl = manifestUrl.removeSuffix("/manifest.json").removeSuffix("/")
    val afterHost = baseUrl.substringAfter("torrentio.strem.fun", "")
    val alreadyConfigured = afterHost.contains("=") ||
        afterHost.contains("%3D", ignoreCase = true)
    return (manifest.id == "com.stremio.torrentio.addon" ||
        baseUrl.contains("torrentio.strem.fun", ignoreCase = true)) &&
        !alreadyConfigured
}

private fun torrentioDebridBaseUrls(
    debridAccounts: Map<DebridServiceType, String>,
): List<String> {
    return debridAccounts.mapNotNull { (provider, apiKey) ->
        val key = apiKey.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val providerKey = when (provider) {
            DebridServiceType.REAL_DEBRID -> "realdebrid"
            DebridServiceType.ALL_DEBRID -> "alldebrid"
            DebridServiceType.PREMIUMIZE -> "premiumize"
            DebridServiceType.TORBOX -> "torbox"
        }
        val config = "debridoptions=nodownloadlinks|$providerKey=$key"
        "https://torrentio.strem.fun/${config.encodeURLPathPart()}"
    }
}

private fun String.torrentioFullDebridFallbackUrl(): String? {
    if (!contains("torrentio.strem.fun", ignoreCase = true)) return null
    val hasCachedOnlyOption = contains("debridoptions%3Dnodownloadlinks", ignoreCase = true) ||
        contains("debridoptions=nodownloadlinks", ignoreCase = true)
    if (!hasCachedOnlyOption) return null
    return replace(
        Regex("debridoptions(?:%3D|=)nodownloadlinks(?:%7C|\\|)", RegexOption.IGNORE_CASE),
        "",
    ).replace(
        Regex("(?:%7C|\\|)debridoptions(?:%3D|=)nodownloadlinks", RegexOption.IGNORE_CASE),
        "",
    )
}

private fun safeAddonHealthKey(url: String): String {
    val host = url.substringAfter("://", url).substringBefore("/")
    return when {
        host.equals("torrentio.strem.fun", ignoreCase = true) -> {
            val configured = url.contains("realdebrid%3D", ignoreCase = true) ||
                url.contains("alldebrid%3D", ignoreCase = true) ||
                url.contains("premiumize%3D", ignoreCase = true) ||
                url.contains("torbox%3D", ignoreCase = true) ||
                url.contains("realdebrid=", ignoreCase = true) ||
                url.contains("alldebrid=", ignoreCase = true) ||
                url.contains("premiumize=", ignoreCase = true) ||
                url.contains("torbox=", ignoreCase = true)
            if (configured) "torrentio.strem.fun(debrid)" else "torrentio.strem.fun(plain)"
        }
        host.isNotBlank() -> host
        else -> "unknown-addon"
    }
}
