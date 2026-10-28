package com.torve.data.addon

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.canResolveStreams
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.platform.TorveRuntimeDebug
import io.ktor.http.encodeURLPathPart
import io.ktor.http.Url
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Track addon failures for diagnostics (addon URL Ã¢â€ â€™ last error message). */
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
        onStatistics: (List<com.torve.domain.model.ProviderSourceStat>) -> Unit = {},
        onPartialStreams: (suspend (List<ParsedStream>) -> Unit)? = null,
    ): List<ParsedStream> = coroutineScope {
        val addonUrls = resolveStreamAddonBaseUrls(addons, debridAccounts)
        val verificationCache = mutableMapOf<String, ParsedStream>()
        val partialLock = Mutex()
        val partialByProvider = linkedMapOf<String, List<ParsedStream>>()
        suspend fun publish(url: String, streams: List<ParsedStream>, providerVerification: MutableMap<String, ParsedStream>) {
            if (onPartialStreams == null) return
            // A slow cache service for one provider must not hold the publication
            // lock and hide ready video URLs returned by another provider.
            val prepared = prepareStreams(streams, debridAccounts, preferences, season, episode, fetchPolicy, providerVerification)
            partialLock.withLock {
                verificationCache.putAll(providerVerification)
                partialByProvider[url] = prepared
                onPartialStreams.invoke(scorer.scoreAll(deduplicateStreams(partialByProvider.values.flatten()), preferences))
            }
        }

        // 1. Fan out to all addons in parallel using the selected startup policy
        val batches = addonUrls.map { url ->
            async {
                val started = kotlin.time.TimeSource.Monotonic.markNow()
                var successful = false
                // Provider deadlines cover receiving its response. Filtering,
                // debrid verification and UI publication run separately; they
                // must not make a responsive provider appear to have timed out.
                // Batches are cumulative, so only the newest pending one is needed.
                val updates = Channel<List<ParsedStream>>(Channel.CONFLATED)
                val providerVerification = mutableMapOf<String, ParsedStream>()
                val publisher = if (onPartialStreams != null) launch {
                    for (rows in updates) publish(url, rows, providerVerification)
                } else null
                val streams = try { fetchFromAddon(
                    url = url,
                    type = type,
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                    fetchPolicy = fetchPolicy,
                    onOutcome = { if (it) successful = true },
                    onStreams = { if (publisher != null) updates.trySend(it) },
                ).also {
                    updates.close()
                    publisher?.join()
                } } finally {
                    updates.cancel()
                    publisher?.cancelAndJoin()
                }
                val provider = addons.firstOrNull { url.startsWith(it.manifestUrl.removeSuffix("/manifest.json").trimEnd('/')) }?.manifest?.name
                    ?: runCatching { Url(url).host }.getOrDefault("Provider")
                streams to com.torve.domain.model.ProviderSourceStat(provider, streams.size,
                    streams.count { it.isCached }, started.elapsedNow().inWholeMilliseconds, failed = !successful)
            }
        }.awaitAll()
        onStatistics(batches.map { it.second })
        val rawStreams = batches.flatMap { it.first }

        prepareStreams(rawStreams, debridAccounts, preferences, season, episode, fetchPolicy, verificationCache)
    }

    private suspend fun prepareStreams(rawStreams: List<ParsedStream>,
        debridAccounts: Map<DebridServiceType, String>, preferences: StreamPreferences,
        season: Int?, episode: Int?, fetchPolicy: StreamFetchPolicy, verificationCache: MutableMap<String, ParsedStream>): List<ParsedStream> {
        // 2. Deduplicate by infoHash (or full title for non-torrent streams)
        val unique = deduplicateStreams(rawStreams)

        // 3. Batch check debrid cache
        val torrentHashes = unique.filter { it.requiresDebridVerification() && !it.isCached && it.duplicateKey() !in verificationCache }.mapNotNull { it.infoHash }.distinct()
        val cacheStatus: MutableMap<String, Boolean> = mutableMapOf()
        if (torrentHashes.isNotEmpty()) {
            debridAccounts.forEach { (provider, apiKey) ->
                try {
                    val cached = withTimeoutOrNull(5_000L) {
                        debridClient.checkCache(provider, apiKey, torrentHashes)
                    }.orEmpty()
                    cached.forEach { (hash, isCached) ->
                        if (isCached) cacheStatus[hash] = true
                    }
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
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
                    source = "${stream.source ?: ""} Ã¢Å¡Â¡".trim(),
                )
            } else {
                stream
            }
        }

        // 5. Filter by preferences
        val staticFiltered = enriched.filter { stream ->
            if (preferences.excludeSeasonPacks && stream.isSeasonPack()) return@filter false
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

        // Searching user-installed extensions must never create cloud downloads
        // or resolve every torrent. The read-only batch cache response is enough;
        // resolve only the source the user selects (or the autoplay candidate).
        val freshVerified = if (ExtensionRuntimePolicy.userManagedOnly) {
            staticFiltered.filter { it.duplicateKey() !in verificationCache }
        } else preflightDebridCandidates(
            streams = staticFiltered.filter { it.duplicateKey() !in verificationCache },
            debridAccounts = debridAccounts,
            season = season,
            episode = episode,
            fetchPolicy = fetchPolicy,
        )

        freshVerified.forEach { verificationCache[it.duplicateKey()] = it }
        val verified = staticFiltered.map { stream ->
            stream.copy(isCached = stream.isCached || verificationCache[stream.duplicateKey()]?.isCached == true)
        }
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
        return scored
    }

    private suspend fun fetchFromAddon(
        url: String,
        type: MediaType,
        imdbId: String,
        season: Int?,
        episode: Int?,
        fetchPolicy: StreamFetchPolicy,
        onOutcome: (Boolean) -> Unit,
        onStreams: suspend (List<ParsedStream>) -> Unit = {},
    ): List<ParsedStream> {
        val primary = fetchFromAddonUrl(
            url = url,
            type = type,
            imdbId = imdbId,
            season = season,
            episode = episode,
            fetchPolicy = fetchPolicy, onOutcome = onOutcome, onStreams = onStreams,
        )
        if (ExtensionRuntimePolicy.userManagedOnly) return primary
        val fallbackUrl = url.torrentioFullDebridFallbackUrl() ?: return primary
        val fallback = fetchFromAddonUrl(
            url = fallbackUrl,
            type = type,
            imdbId = imdbId,
            season = season,
            episode = episode,
            fetchPolicy = fetchPolicy,
            onOutcome = onOutcome, onStreams = onStreams,
        )
        if (fallback.isNotEmpty()) {
            streamDebugLog { "TORVE_STREAMS: addon=torrentio.strem.fun(debrid-fallback) streams=${fallback.size}" }
        }
        return deduplicateStreams(primary + fallback)
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
            .distinctBy(ParsedStream::duplicateKey)
        val candidates = streams
            .filter { it.requiresDebridVerification() }
            .distinctBy(ParsedStream::duplicateKey)
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
        if (candidates.isEmpty()) return@coroutineScope passthrough + allCandidates

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
        // Preflight is an availability hint, not an authoritative source
        // filter. A timeout or temporary debrid error must not erase a stream
        // that an addon just returned, and candidates beyond the bounded
        // preflight window must remain selectable as well.
        passthrough + mergeDebridPreflightResults(allCandidates, resolved)
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
        onOutcome: (Boolean) -> Unit,
        onStreams: suspend (List<ParsedStream>) -> Unit = {},
    ): List<ParsedStream> {
        var lastError: Exception? = null
        var partial = emptyList<ParsedStream>()
        val userManaged = ExtensionRuntimePolicy.userManagedOnly
        val attempts = if (userManaged) 1 else fetchPolicy.retryCount + 1
        repeat(attempts) { attempt ->
            try {
                val streams = withTimeout(if (userManaged) 20_000L else addonSearchTimeoutMs(url, fetchPolicy)) {
                    addonClient.getStreams(url, type, imdbId, season, episode) { rows ->
                        partial = rows; onStreams(rows)
                    }
                }
                streamDebugLog { "TORVE_STREAMS: addon=${safeAddonHealthKey(url)} streams=${streams.size}" }
                addonFailures.remove(safeAddonHealthKey(url))
                onOutcome(true)
                return streams
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                lastError = e
                if (attempt + 1 < attempts && fetchPolicy.retryDelayMs > 0) {
                    delay(fetchPolicy.retryDelayMs)
                }
            }
        }

        streamDebugLog { "TORVE_STREAMS: provider_failed=${safeAddonHealthKey(url)} type=${lastError?.let { it::class.simpleName }} reason=${DiagnosticsRedactor.redact(lastError?.message.orEmpty())}" }
        onOutcome(false)
        addonFailures[safeAddonHealthKey(url)] = DiagnosticsRedactor.redact(lastError?.message ?: "Unknown error")
        return partial
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

internal fun addonSearchTimeoutMs(url: String, policy: StreamFetchPolicy): Long {
    // Panda waits up to 14 seconds for its Usenet providers. The full request
    // needs to outlive that budget; keep the short startup pass unchanged.
    val host = runCatching { Url(url).host }.getOrNull()
    return if (policy == StreamFetchPolicy.FULL && host.equals("panda.torve.app", ignoreCase = true)) {
        20_000L
    } else policy.addonTimeoutMs
}

internal fun mergeDebridPreflightResults(
    candidates: List<ParsedStream>,
    verified: List<ParsedStream>,
): List<ParsedStream> {
    val verifiedByKey = verified.associateBy(ParsedStream::debridPreflightKey)
    return candidates.map { candidate ->
        verifiedByKey[candidate.debridPreflightKey()] ?: candidate
    }
}

private fun ParsedStream.debridPreflightKey(): String = duplicateKey()

internal fun resolveStreamAddonBaseUrls(
    addons: List<InstalledAddon>,
    debridAccounts: Map<DebridServiceType, String> = emptyMap(),
    configureProviders: Boolean = !ExtensionRuntimePolicy.userManagedOnly,
): List<String> {
    return addons
        .filter { addon -> addon.canResolveStreams() }
        .flatMap { addon ->
            val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json").removeSuffix("/")
            if (configureProviders && addon.isPlainTorrentio()) {
                val debridUrls = torrentioDebridBaseUrls(debridAccounts)
                debridUrls.ifEmpty { listOf(baseUrl) }
            } else {
                listOf(baseUrl)
            }
        }
        .distinct()
}

internal fun ParsedStream.requiresDebridVerification(): Boolean =
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
