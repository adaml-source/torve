package com.torve.data.addon

import com.torve.data.acceleration.AccelerationApi
import com.torve.data.acceleration.AccelerationOutcomeDto
import com.torve.data.debrid.DebridClient
import com.torve.db.Stream_resolve_memory
import com.torve.domain.model.ContentWarmupResult
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.db.TorveDatabase
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.HashAvailabilityState
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.InventoryMatchesSnapshot
import com.torve.domain.model.KnownHashAvailabilitySnapshot
import com.torve.domain.model.MediaType
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.RecentSuccessfulSourcesSnapshot
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StartupCandidatesSnapshot
import com.torve.domain.model.StartupConfidenceReasonCode
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.apiValue
import com.torve.domain.repository.StreamRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class StreamRepositoryImpl(
    private val debridClient: DebridClient,
    private val streamAggregator: StreamAggregator,
    private val database: TorveDatabase,
    private val accelerationApi: AccelerationApi,
) : StreamRepository {

    private data class StreamRequestKey(
        val type: MediaType,
        val imdbId: String,
        val contentId: String?,
        val title: String?,
        val season: Int?,
        val episode: Int?,
    ) {
        val contentKey: String = buildString {
            append(type.name)
            append(':')
            append(imdbId)
            append(':')
            append(season ?: -1)
            append(':')
            append(episode ?: -1)
        }
    }

    private data class PrefetchedStreamBatch(
        val streams: List<ParsedStream>,
        val fetchedAt: Long,
        val policy: StreamFetchPolicy,
    ) {
        fun isUsableFor(requestedPolicy: StreamFetchPolicy, now: Long): Boolean {
            if (now - fetchedAt > STARTUP_CACHE_TTL_MS) return false
            return when (requestedPolicy) {
                StreamFetchPolicy.FULL -> policy == StreamFetchPolicy.FULL
                StreamFetchPolicy.PLAYBACK_STARTUP -> true
            }
        }
    }

    private val memoryMutex = Mutex()
    private val startupCache = mutableMapOf<String, PrefetchedStreamBatch>()
    private val lastRequestByStreamKey = linkedMapOf<String, StreamRequestKey>()
    private val warmupRegistry = ContentWarmupRegistry()

    override suspend fun fetchStreams(
        type: MediaType,
        imdbId: String,
        contentId: String?,
        title: String?,
        season: Int?,
        episode: Int?,
        addons: List<InstalledAddon>,
        debridAccounts: Map<DebridServiceType, String>,
        preferences: StreamPreferences,
        fetchPolicy: StreamFetchPolicy,
    ): List<ParsedStream> {
        val request = StreamRequestKey(
            type = type,
            imdbId = imdbId,
            contentId = contentId,
            title = title,
            season = season,
            episode = episode,
        )
        val now = Clock.System.now().toEpochMilliseconds()
        val cached = memoryMutex.withLock {
            startupCache[request.contentKey]
                ?.takeIf { it.isUsableFor(fetchPolicy, now) }
                ?.streams
        }

        val baseStreams = cached ?: run {
            val localStreams = streamAggregator.resolveStreams(
                addons = addons,
                type = type,
                imdbId = imdbId,
                season = season,
                episode = episode,
                debridAccounts = debridAccounts,
                preferences = preferences,
                fetchPolicy = fetchPolicy,
            )
            val mergedStreams = if (fetchPolicy == StreamFetchPolicy.PLAYBACK_STARTUP) {
                val startupRequest = SourceAccelerationRequest(
                    mediaType = type,
                    imdbId = imdbId,
                    contentId = contentId,
                    title = title,
                    seasonNumber = season,
                    episodeNumber = episode,
                    context = com.torve.domain.model.SourceAccelerationContext(
                        addons = addons,
                        debridAccounts = debridAccounts,
                        preferences = preferences,
                        startupFetchPolicy = fetchPolicy,
                    ),
                )
                val accelerated = accelerationApi
                    .getStartupCandidates(startupRequest)
                    .mapNotNull(SourceAccelerationMapper::backendCandidateToParsedStream)
                    .sortedByDescending { it.accelerationScore ?: it.score.toDouble() }
                mergeStartupStreams(accelerated, localStreams)
            } else {
                localStreams
            }
            memoryMutex.withLock {
                startupCache[request.contentKey] = PrefetchedStreamBatch(
                    streams = mergedStreams,
                    fetchedAt = now,
                    policy = fetchPolicy,
                )
            }
            mergedStreams
        }

        rememberStreamRequest(request, baseStreams)
        return applyResolveMemory(request, baseStreams)
    }

    private fun isDirectPlayableUrl(url: String): Boolean {
        // Known direct HTTP streaming sources that bypass debrid resolution
        return url.contains("members.easynews.com") ||
            url.contains("/easynews/") // Panda Easynews proxy URLs
    }

    private fun isNzbDownloadLink(url: String): Boolean {
        return url.contains("/getnzb/") || url.endsWith(".nzb") || url.contains("nzbindex") || url.contains("nzbgeek") || url.contains("scenenzbs")
    }

    override suspend fun resolveStream(
        stream: ParsedStream,
        provider: DebridServiceType,
        apiKey: String,
    ): ResolvedStream {
        return try {
            val resolved = if (stream.directUrl != null && stream.infoHash == null && isNzbDownloadLink(stream.directUrl)) {
                // NZB download link — not directly playable without a download client
                throw Exception("NZB files require a download client (NZBget/SABnzbd). Configure one in Panda settings.")
            } else if (stream.directUrl != null && stream.infoHash == null && isDirectPlayableUrl(stream.directUrl)) {
                // Direct HTTP stream (e.g., Easynews) — play without debrid
                ResolvedStream(url = stream.directUrl, service = null)
            } else if (stream.directUrl != null && stream.infoHash == null) {
                debridClient.unrestrictUrl(provider, apiKey, stream.directUrl)
            } else {
                val infoHash = stream.infoHash
                    ?: throw Exception("Stream has no infoHash or direct URL")
                debridClient.resolveStream(
                    provider = provider,
                    apiKey = apiKey,
                    infoHash = infoHash,
                    fileIdx = stream.fileIdx,
                )
            }.requirePlayableUrl()

            recordResolveSuccess(stream, provider)
            reportPlaybackOutcome(stream, provider, success = true)
            resolved
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            reportPlaybackOutcome(stream, provider, success = false)
            throw e
        }
    }

    override suspend fun reportPlaybackOutcome(
        stream: ParsedStream,
        provider: DebridServiceType,
        success: Boolean,
    ) {
        val request = memoryMutex.withLock { lastRequestByStreamKey[streamMemoryKey(stream)] }
        val contentId = request?.contentId
            ?: request?.imdbId?.takeIf { it.isNotBlank() }?.let { "imdb:$it" }
            ?: return
        val sourceKey = stream.accelerationSourceKey
            ?: stream.infoHash
            ?: stream.directUrl
            ?: return
        accelerationApi.reportOutcome(
            AccelerationOutcomeDto(
                contentId = contentId,
                providerType = stream.accelerationProviderType ?: provider.apiValue,
                sourceKey = sourceKey,
                success = success,
                infohash = stream.infoHash,
                quality = stream.quality,
            ),
        )
    }

    override suspend fun getStartupCandidates(request: SourceAccelerationRequest): StartupCandidatesSnapshot {
        val streams = fetchStreams(
            type = request.mediaType,
            imdbId = request.imdbId,
            contentId = request.resolvedContentId,
            title = request.title,
            season = request.seasonNumber,
            episode = request.episodeNumber,
            addons = request.context.addons,
            debridAccounts = request.context.debridAccounts,
            preferences = request.context.preferences,
            fetchPolicy = request.context.startupFetchPolicy,
        )
        val candidates = streams.map { stream ->
            SourceAccelerationMapper.toStartupCandidate(stream.toStartupBackendModel(request))
        }
        return StartupCandidatesSnapshot(
            request = request,
            readinessState = summarizeReadiness(candidates.map { it.readinessState }),
            candidates = candidates,
        )
    }

    override suspend fun getWarmStartupCandidates(
        request: SourceAccelerationRequest,
        maxAgeMs: Long,
    ): StartupCandidatesSnapshot? {
        return warmupRegistry.getFreshSnapshot(
            request = request,
            maxAgeMs = maxAgeMs,
        )
    }

    override suspend fun warmupStartupCandidates(
        request: SourceAccelerationRequest,
        trigger: ContentWarmupTrigger,
    ): ContentWarmupResult {
        return warmupRegistry.warmup(
            request = request,
            trigger = trigger,
            hasWarmupContext = request.context.addons.isNotEmpty(),
        ) {
            getStartupCandidates(request)
        }
    }

    override suspend fun getRecentSuccessfulSources(
        request: SourceAccelerationRequest,
    ): RecentSuccessfulSourcesSnapshot {
        val candidates = database.torveQueries
            .getResolveMemoryForContent(request.contentKey)
            .executeAsList()
            .map { row ->
                SourceAccelerationMapper.toRecentSuccessCandidate(
                    row.toRecentSuccessBackendModel(request),
                )
            }
        return RecentSuccessfulSourcesSnapshot(
            request = request,
            readinessState = summarizeReadiness(candidates.map { it.readinessState }),
            candidates = candidates,
        )
    }

    override suspend fun getInventoryMatches(request: SourceAccelerationRequest): InventoryMatchesSnapshot {
        return InventoryMatchesSnapshot(
            request = request,
            readinessState = ReadinessState.UNAVAILABLE,
            matches = emptyList(),
        )
    }

    override suspend fun getKnownHashAvailability(
        request: SourceAccelerationRequest,
    ): KnownHashAvailabilitySnapshot {
        if (request.context.debridAccounts.isEmpty()) {
            return KnownHashAvailabilitySnapshot(
                request = request,
                readinessState = ReadinessState.UNAVAILABLE,
                observations = emptyList(),
            )
        }

        val streams = fetchStreams(
            type = request.mediaType,
            imdbId = request.imdbId,
            contentId = request.resolvedContentId,
            title = request.title,
            season = request.seasonNumber,
            episode = request.episodeNumber,
            addons = request.context.addons,
            debridAccounts = request.context.debridAccounts,
            preferences = request.context.preferences,
            fetchPolicy = request.context.startupFetchPolicy,
        )

        val providerLabel = when (request.context.debridAccounts.size) {
            0 -> "Connected service"
            1 -> request.context.debridAccounts.keys.first().label
            else -> "Connected services"
        }

        val observations = streams
            .filter { !it.infoHash.isNullOrBlank() }
            .distinctBy { it.infoHash }
            .mapNotNull { stream ->
                val infoHash = stream.infoHash ?: return@mapNotNull null
                SourceAccelerationMapper.toKnownHashAvailabilityObservation(
                    KnownHashAvailabilityObservationBackendModel(
                        infoHash = infoHash,
                        providerLabel = providerLabel,
                        availabilityState = if (stream.isCached) {
                            HashAvailabilityState.AVAILABLE
                        } else {
                            HashAvailabilityState.UNKNOWN
                        },
                        readinessState = if (stream.isCached) {
                            ReadinessState.READY_WITH_RESOLVE
                        } else {
                            ReadinessState.LOOKUP_ONLY
                        },
                        confidenceReasons = buildList {
                            if (stream.isCached) add(StartupConfidenceReasonCode.HASH_CACHED)
                            add(StartupConfidenceReasonCode.KNOWN_INFO_HASH)
                            if (stream.source != null || stream.addonName.isNotBlank()) {
                                add(StartupConfidenceReasonCode.PROVIDER_SIGNAL)
                            }
                        },
                    ),
                )
            }

        return KnownHashAvailabilitySnapshot(
            request = request,
            readinessState = summarizeReadiness(observations.map { it.readinessState }),
            observations = observations,
        )
    }

    private suspend fun rememberStreamRequest(
        request: StreamRequestKey,
        streams: List<ParsedStream>,
    ) {
        memoryMutex.withLock {
            streams.forEach { stream ->
                lastRequestByStreamKey[streamMemoryKey(stream)] = request
            }
            while (lastRequestByStreamKey.size > MAX_TRACKED_STREAM_KEYS) {
                val oldestKey = lastRequestByStreamKey.entries.firstOrNull()?.key ?: break
                lastRequestByStreamKey.remove(oldestKey)
            }
        }
    }

    private fun applyResolveMemory(
        request: StreamRequestKey,
        streams: List<ParsedStream>,
    ): List<ParsedStream> {
        if (streams.isEmpty()) return emptyList()
        val historyByKey = database.torveQueries
            .getResolveMemoryForContent(request.contentKey)
            .executeAsList()
            .associateBy { it.stream_key }

        return streams
            .map { stream ->
                val memory = historyByKey[streamMemoryKey(stream)] ?: return@map stream
                stream.copy(
                    score = (stream.score + resolveMemoryBonus(memory)).coerceIn(0, 100),
                    recentSuccessCount = memory.success_count.toInt(),
                    lastSuccessfulResolveAt = memory.last_success_at,
                )
            }
            .sortedWith(
                compareByDescending<ParsedStream> { it.recentSuccessCount > 0 }
                    .thenByDescending { it.lastSuccessfulResolveAt ?: 0L }
                    .thenByDescending { it.recentSuccessCount }
                    .thenByDescending { it.score },
            )
    }

    private suspend fun recordResolveSuccess(
        stream: ParsedStream,
        provider: DebridServiceType,
    ) {
        val streamKey = streamMemoryKey(stream)
        val request = memoryMutex.withLock { lastRequestByStreamKey[streamKey] } ?: return
        val existing = database.torveQueries
            .getResolveMemoryEntry(request.contentKey, streamKey)
            .executeAsOneOrNull()
        val successCount = (existing?.success_count ?: 0L) + 1L
        val timestamp = Clock.System.now().toEpochMilliseconds()

        database.torveQueries.upsertResolveMemory(
            content_key = request.contentKey,
            stream_key = streamKey,
            media_type = request.type.databaseValue,
            imdb_id = request.imdbId,
            season_number = request.season?.toLong(),
            episode_number = request.episode?.toLong(),
            addon_name = stream.addonName,
            stream_title = stream.title,
            info_hash = stream.infoHash,
            direct_url = stream.directUrl,
            quality = stream.quality,
            source_name = stream.source,
            is_cached = if (stream.isCached) 1L else 0L,
            resolved_provider = provider.name,
            success_count = successCount,
            last_success_at = timestamp,
        )

        memoryMutex.withLock {
            startupCache.remove(request.contentKey)
        }
    }

    private fun resolveMemoryBonus(memory: Stream_resolve_memory): Int {
        val ageMs = Clock.System.now().toEpochMilliseconds() - memory.last_success_at
        val recencyBonus = when {
            ageMs <= 6 * 60 * 60 * 1000L -> 10
            ageMs <= 24 * 60 * 60 * 1000L -> 8
            ageMs <= 3 * 24 * 60 * 60 * 1000L -> 6
            ageMs <= 7 * 24 * 60 * 60 * 1000L -> 4
            else -> 2
        }
        val repeatBonus = minOf(8, memory.success_count.toInt() * 2)
        return recencyBonus + repeatBonus
    }

    private fun ParsedStream.toStartupBackendModel(
        request: SourceAccelerationRequest,
    ): StartupCandidateBackendModel {
        val isDirect = directUrl != null && infoHash == null
        val confidenceReasons = accelerationConfidenceReasons.ifEmpty {
            buildList {
                add(
                    if (request.isEpisodeRequest) {
                        StartupConfidenceReasonCode.EXACT_EPISODE_MATCH
                    } else {
                        StartupConfidenceReasonCode.EXACT_CONTENT_MATCH
                    },
                )
                if (recentSuccessCount > 0) add(StartupConfidenceReasonCode.RECENT_SUCCESS)
                if (isDirect) add(StartupConfidenceReasonCode.DIRECT_PLAYABLE_URL)
                if (isCached) add(StartupConfidenceReasonCode.HASH_CACHED)
                if (infoHash != null) add(StartupConfidenceReasonCode.KNOWN_INFO_HASH)
                if (source != null || addonName.isNotBlank()) add(StartupConfidenceReasonCode.PROVIDER_SIGNAL)
            }
        }
        val readinessState = when {
            isDirect || isCached -> ReadinessState.READY_NOW
            infoHash != null || recentSuccessCount > 0 -> ReadinessState.READY_WITH_RESOLVE
            else -> ReadinessState.LOOKUP_ONLY
        }
        return StartupCandidateBackendModel(
            streamKey = accelerationSourceKey ?: streamMemoryKey(this),
            title = title,
            qualityLabel = quality,
            addonName = addonName,
            sourceLabel = source,
            readinessState = readinessState,
            provenanceKind = accelerationProvenanceKind ?: if (recentSuccessCount > 0) {
                CandidateProvenanceKind.LOCAL_MEMORY
            } else {
                CandidateProvenanceKind.STARTUP_FETCH
            },
            provenanceProviderLabel = accelerationProviderType ?: addonName,
            confidenceReasons = confidenceReasons,
            isDirectPlayback = isDirect,
            isKnownCached = isCached,
            sizeBytes = size?.let { StreamAggregator.parseSizeToBytes(it) },
            seeds = seeds,
            score = accelerationScore,
            scoreBreakdown = accelerationScoreBreakdown,
        )
    }

    private fun Stream_resolve_memory.toRecentSuccessBackendModel(
        request: SourceAccelerationRequest,
    ): RecentSuccessCandidateBackendModel {
        return RecentSuccessCandidateBackendModel(
            streamKey = stream_key,
            title = stream_title,
            providerLabel = resolved_provider,
            addonName = addon_name,
            qualityLabel = quality,
            sourceLabel = source_name,
            readinessState = if (direct_url != null && info_hash == null) {
                ReadinessState.READY_NOW
            } else {
                ReadinessState.READY_WITH_RESOLVE
            },
            confidenceReasons = buildList {
                add(StartupConfidenceReasonCode.RECENT_SUCCESS)
                add(
                    if (request.isEpisodeRequest) {
                        StartupConfidenceReasonCode.EXACT_EPISODE_MATCH
                    } else {
                        StartupConfidenceReasonCode.EXACT_CONTENT_MATCH
                    },
                )
                if (info_hash != null) add(StartupConfidenceReasonCode.KNOWN_INFO_HASH)
                if (is_cached != 0L) add(StartupConfidenceReasonCode.HASH_CACHED)
            },
            lastSuccessfulAt = last_success_at,
            successCount = success_count.toInt(),
        )
    }

    private fun summarizeReadiness(states: List<ReadinessState>): ReadinessState {
        if (states.isEmpty()) return ReadinessState.EMPTY
        return when {
            states.any { it == ReadinessState.READY_NOW } -> ReadinessState.READY_NOW
            states.any { it == ReadinessState.READY_WITH_RESOLVE } -> ReadinessState.READY_WITH_RESOLVE
            states.any { it == ReadinessState.LOOKUP_ONLY } -> ReadinessState.LOOKUP_ONLY
            states.any { it == ReadinessState.UNAVAILABLE } -> ReadinessState.UNAVAILABLE
            else -> ReadinessState.EMPTY
        }
    }

    private val MediaType.databaseValue: String
        get() = when (this) {
            MediaType.MOVIE -> "movie"
            MediaType.SERIES -> "series"
        }

    private fun mergeStartupStreams(
        acceleratedStreams: List<ParsedStream>,
        localStreams: List<ParsedStream>,
    ): List<ParsedStream> {
        if (acceleratedStreams.isEmpty()) return localStreams
        if (localStreams.isEmpty()) return acceleratedStreams

        val localByKey = localStreams.associateBy(::startupIdentityKey)
        val merged = mutableListOf<ParsedStream>()
        val seen = linkedSetOf<String>()

        acceleratedStreams.forEach { stream ->
            val key = startupIdentityKey(stream)
            if (seen.add(key)) {
                merged += localByKey[key] ?: stream
            }
        }
        localStreams.forEach { stream ->
            val key = startupIdentityKey(stream)
            if (seen.add(key)) {
                merged += stream
            }
        }
        return merged
    }

    private companion object {
        const val STARTUP_CACHE_TTL_MS = 2 * 60 * 1000L
        const val MAX_TRACKED_STREAM_KEYS = 512
    }
}

private fun startupIdentityKey(stream: ParsedStream): String {
    return stream.accelerationSourceKey
        ?: stream.infoHash
        ?: stream.directUrl
        ?: "${stream.addonName}|${stream.title}|${stream.quality}|${stream.source.orEmpty()}"
}

private fun streamMemoryKey(stream: ParsedStream): String {
    return stream.infoHash
        ?: stream.directUrl
        ?: "${stream.addonName}|${stream.title}|${stream.quality}|${stream.source.orEmpty()}"
}

private fun ResolvedStream.requirePlayableUrl(): ResolvedStream {
    if (url.isBlank()) {
        throw IllegalStateException("Resolved stream returned no playable URL")
    }
    return this
}
