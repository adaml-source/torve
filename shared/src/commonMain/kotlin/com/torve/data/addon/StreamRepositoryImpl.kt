package com.torve.data.addon

import com.torve.data.acceleration.AccelerationApi
import com.torve.data.acceleration.AccelerationOutcomeDto
import com.torve.data.acceleration.StreamHandoffApiException
import com.torve.data.acceleration.StreamHandoffResponseDto
import com.torve.data.debrid.DebridClient
import com.torve.data.debrid.DebridMissingException
import com.torve.data.debrid.DebridNoCachedStreamException
import com.torve.db.Stream_resolve_memory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.domain.repository.StreamFetchResult
import com.torve.domain.repository.StreamReadiness
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.streams.ParsedStreamRuntimeFilter
import com.torve.domain.streams.StreamRuntimeFilterFeedback
import com.torve.domain.telemetry.StreamPathDiagnostics
import com.torve.domain.telemetry.StreamPathTelemetryContext
import com.torve.domain.telemetry.StreamPlaybackPath
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.platform.TorveRuntimeDebug
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

@Serializable
private data class AddonNotReadyErrorBody(
    val error: String = "",
    val message: String? = null,
    @SerialName("retry_after") val retryAfter: Int? = null,
)

internal fun interface StreamAggregationSource {
    suspend fun resolveStreams(
        addons: List<InstalledAddon>,
        type: MediaType,
        imdbId: String,
        season: Int?,
        episode: Int?,
        debridAccounts: Map<DebridServiceType, String>,
        preferences: StreamPreferences,
        fetchPolicy: StreamFetchPolicy,
    ): List<ParsedStream>
}

class StreamRepositoryImpl(
    private val debridClient: DebridClient,
    private val streamAggregator: StreamAggregator,
    private val database: TorveDatabase,
    private val accelerationApi: AccelerationApi,
    private val httpClient: HttpClient,
    private val telemetry: TelemetryEmitter,
    preferencesRepository: PreferencesRepository? = null,
    subscriptionRepository: SubscriptionRepository? = null,
) : StreamRepository {
    private var streamAggregationSourceOverride: StreamAggregationSource? = null

    internal constructor(
        debridClient: DebridClient,
        streamAggregationSource: StreamAggregationSource,
        database: TorveDatabase,
        accelerationApi: AccelerationApi,
        httpClient: HttpClient,
        telemetry: TelemetryEmitter,
        preferencesRepository: PreferencesRepository? = null,
        subscriptionRepository: SubscriptionRepository? = null,
    ) : this(
        debridClient = debridClient,
        streamAggregator = StreamAggregator(
            addonClient = StremioAddonClient(httpClient, Json { ignoreUnknownKeys = true }),
            debridClient = debridClient,
            scorer = StreamScorer(),
        ),
        database = database,
        accelerationApi = accelerationApi,
        httpClient = httpClient,
        telemetry = telemetry,
        preferencesRepository = preferencesRepository,
        subscriptionRepository = subscriptionRepository,
    ) {
        streamAggregationSourceOverride = streamAggregationSource
    }

    // Dedicated parser so we can decode the 504 body without bringing in the
    // shared Json instance (which may be configured for strict mode).
    private val readinessJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private inline fun repositoryDebugLog(message: () -> String) {
        if (TorveRuntimeDebug.verboseLoggingEnabled) {
            println(DiagnosticsRedactor.redact(message()))
        }
    }

    // Probe-only client with redirect-follow disabled. Panda's 302 points at
    // a signed CDN URL — we explicitly don't want to chase that from the
    // probe since (a) the CDN often ignores HEAD or responds slowly, and
    // (b) the 302 is itself proof of readiness. The base client's engine is
    // shared; only the redirect policy differs.
    private val probeClient: HttpClient by lazy {
        httpClient.config {
            followRedirects = false
        }
    }

    private data class StreamRequestKey(
        val type: MediaType,
        val imdbId: String,
        val contentId: String?,
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val debridSignature: String,
    ) {
        val contentKey: String = buildString {
            append(type.name)
            append(':')
            append(imdbId)
            append(':')
            append(season ?: -1)
            append(':')
            append(episode ?: -1)
            append(':')
            append(debridSignature)
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
    private val streamRuntimeFilter = ParsedStreamRuntimeFilter(
        preferencesRepository = preferencesRepository,
        subscriptionRepository = subscriptionRepository,
    )

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
    ): List<ParsedStream> = fetchStreamsWithFeedback(
        type = type,
        imdbId = imdbId,
        contentId = contentId,
        title = title,
        season = season,
        episode = episode,
        addons = addons,
        debridAccounts = debridAccounts,
        preferences = preferences,
        fetchPolicy = fetchPolicy,
    ).streams

    override suspend fun fetchStreamsWithFeedback(
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
    ): StreamFetchResult {
        val request = StreamRequestKey(
            type = type,
            imdbId = imdbId,
            contentId = contentId,
            title = title,
            season = season,
            episode = episode,
            debridSignature = debridSignature(debridAccounts),
        )
        val now = Clock.System.now().toEpochMilliseconds()
        val cached = memoryMutex.withLock {
            startupCache[request.contentKey]
                ?.takeIf { it.isUsableFor(fetchPolicy, now) }
                ?.streams
        }

        val baseStreams = cached ?: run {
            val localStreams = streamAggregationSourceOverride?.resolveStreams(
                addons = addons,
                type = type,
                imdbId = imdbId,
                season = season,
                episode = episode,
                debridAccounts = debridAccounts,
                preferences = preferences,
                fetchPolicy = fetchPolicy,
            ) ?: streamAggregator.resolveStreams(
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
        val memoryAdjustedStreams = applyResolveMemory(request, baseStreams)
        return applyRuntimeStreamFilters(memoryAdjustedStreams)
    }

    private suspend fun applyRuntimeStreamFilters(streams: List<ParsedStream>): StreamFetchResult {
        val result = streamRuntimeFilter.apply(streams)
        val feedback = StreamRuntimeFilterFeedback(
            hiddenCount = result.filterResult.excludedCount,
        )
        if (
            TorveRuntimeDebug.verboseLoggingEnabled &&
            (result.filterResult.excludedCount > 0 || result.filterResult.invalidPatterns.isNotEmpty())
        ) {
            repositoryDebugLog {
                "STREAM_FILTERS applied excluded=${result.filterResult.excludedCount} " +
                    "invalid=${result.filterResult.invalidPatterns.joinToString("|")} " +
                    "groups=${result.filterResult.groupMatches.keys.joinToString("|")}"
            }
        }
        return StreamFetchResult(
            streams = result.streams,
            filterFeedback = feedback,
        )
    }

    /**
     * Ask an addon-hosted URL whether it's actually serving content right
     * now. Single HEAD with redirect-follow disabled so the status code we
     * see is Panda's (not a downstream CDN's 200 after Panda's 302). Panda's
     * contract:
     *  - 2xx → serving directly
     *  - 3xx → redirecting to a ready CDN URL (still Ready — the player
     *    will follow the redirect on its own request)
     *  - 504 + `{"error":"nzb_not_ready", …}` → cloud client is still
     *    downloading. Retry in ~15–30s.
     *  - Anything else → genuine failure; bubble up so the caller can
     *    fall back to another candidate.
     *
     * Network-level failures (DNS, timeout) are [Failed]: we don't know if
     * the server would have said "preparing", and pretending it's preparing
     * would spin the user forever on a dead connection.
     */
    override suspend fun probeStreamReadiness(url: String): StreamReadiness {
        // Probe uses a 1-byte Range GET on the no-redirect-follow probe
        // client. This is the only shape that sees the same status Panda
        // will hand the player:
        //   • HEAD doesn't work — Panda returns 404/405 for HEAD on the
        //     /nzb/ route, which would falsely say Ready while a GET would
        //     return 504 nzb_not_ready.
        //   • Range GET with followRedirects=true would chase Panda's 302
        //     to the CDN, pulling unnecessary bytes and timing out on slow
        //     CDNs.
        //   • Range GET with followRedirects=false lets us see Panda's own
        //     302 (Ready, no CDN fetch) / 504 (Preparing) / 2xx (Ready, at
        //     most 1 byte pulled).
        //
        // The probe still fails open on network-level errors — a spurious
        // timeout here must never block a URL the player could otherwise
        // stream.
        val response: HttpResponse = try {
            probeClient.get(url) {
                header(HttpHeaders.Range, "bytes=0-0")
                timeout {
                    requestTimeoutMillis = PANDA_READINESS_PROBE_TIMEOUT_MS
                    socketTimeoutMillis = PANDA_READINESS_PROBE_TIMEOUT_MS
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Probe timed out / couldn't reach Panda. For addon-hosted URLs
            // this almost always means the upstream is taking its time
            // (large file, cloud client warming up). Treating this as
            // Preparing keeps the overlay on screen — it'll re-probe every
            // 15s, and the player never sees an unfulfillable URL. If the
            // server is genuinely dead, the 5-min budget still terminates
            // with a friendly timeout message.
            repositoryDebugLog { "[StreamProbe] probe error -> Preparing: ${e::class.simpleName}: ${DiagnosticsRedactor.redact(e.message)}" }
            return StreamReadiness.Preparing
        }
        val code = response.status.value
        return when {
            // 2xx = serving directly. 3xx = Panda 302s to a ready CDN URL.
            // ExoPlayer follows redirects itself, so both are playable.
            code in 200..299 || code in 300..399 -> StreamReadiness.Ready(url)
            code == 504 -> {
                val body = runCatching {
                    response.safeAddonBodyAsText(maxBytes = PANDA_READINESS_ERROR_BODY_MAX_BYTES)
                }.getOrNull().orEmpty()
                val parsed = runCatching {
                    readinessJson.decodeFromString(AddonNotReadyErrorBody.serializer(), body)
                }.getOrNull()
                val err = parsed?.error?.takeIf { it.isNotBlank() }
                if (err == null || err.equals("nzb_not_ready", ignoreCase = true)) {
                    StreamReadiness.Preparing
                } else {
                    StreamReadiness.Failed("Server error (504): $err")
                }
            }
            // Any other status (4xx/5xx) is a real server failure. Let the
            // caller fall back to another candidate rather than throwing a
            // "Source error" in the player a few seconds later.
            else -> StreamReadiness.Failed("HTTP $code")
        }
    }

    private fun isNzbDownloadLink(url: String): Boolean {
        return url.contains("/getnzb/") || url.endsWith(".nzb") || url.contains("nzbindex") || url.contains("nzbgeek") || url.contains("scenenzbs")
    }

    override suspend fun resolveStream(
        stream: ParsedStream,
        provider: DebridServiceType?,
        apiKey: String,
    ): ResolvedStream = resolveStreamInternal(stream, provider, apiKey, trackPath = true)

    private suspend fun resolveStreamInternal(
        stream: ParsedStream,
        provider: DebridServiceType?,
        apiKey: String,
        trackPath: Boolean,
    ): ResolvedStream {
        return try {
            if (trackPath) {
                playbackPathForStream(stream)?.let { path ->
                    val request = memoryMutex.withLock { lastRequestByStreamKey[streamMemoryKey(stream)] }
                    StreamPathDiagnostics.record(
                        path = path,
                        telemetry = telemetry,
                        context = StreamPathTelemetryContext(
                            contentType = request?.type?.telemetryContentType() ?: "unknown",
                            providerCategory = providerCategoryForStream(stream),
                        ),
                    )
                }
            }
            resolveGenericStreamHandoffIfAvailable(stream)?.let { resolved ->
                reportPlaybackOutcome(stream, provider, success = true)
                return resolved.requirePlayableUrl()
            }
            if (stream.canUseLegacyDirectFallback() &&
                !LegacyStreamFallbackCompatibility.allowDirectFallbackWithoutMemoryId
            ) {
                throw StreamHandoffApiException(
                    errorCode = "stream_reference_required",
                    message = "This stream needs a backend handoff reference.",
                )
            }
            // Priority: addon-hosted URLs ALWAYS bypass debrid, regardless of
            // whether the stream also carries an infoHash. Panda's Usenet/NZB
            // streams set both fields (the hash identifies the release; the
            // directUrl is the per-user Panda endpoint that serves it). Earlier
            // logic gated this on `infoHash == null`, which forced those
            // streams through Real-Debrid even when no debrid was configured.
            val magnetUrl = stream.magnetUrl
                ?: stream.directUrl?.takeIf { it.isMagnetUri() }
            val resolved = if (stream.directUrl != null && stream.isAddonHostedUrl()) {
                // Don't probe here — the caller (DetailViewModel) does the
                // readiness check via probeStreamReadiness so it can render
                // a preparing UI while we wait.
                ResolvedStream(url = stream.directUrl, service = null)
            } else if (magnetUrl == null && stream.directUrl != null) {
                if (isNzbDownloadLink(stream.directUrl)) {
                    // Raw .nzb link from a non-Panda addon — can't be played
                    // without a download client resolving it first.
                    throw Exception("NZB files require a download client (NZBget/SABnzbd). Configure one in Panda settings.")
                } else if (stream.infoHash == null) {
                    // Hoster URL that needs unrestricting (Real-Debrid, etc.).
                    // Without a provider we can't proceed.
                    if (provider == null || apiKey.isBlank()) {
                        throw DebridMissingException(
                            "Connect Real-Debrid in Panda to use this stream.",
                        )
                    }
                    debridClient.unrestrictUrl(provider, apiKey, stream.directUrl)
                } else {
                    // Configured Stremio addons such as Torrentio+Debrid can
                    // return both an infoHash and a ready debrid URL. The URL
                    // is the playable artifact; do not discard it and fall
                    // back to addMagnet/hash-only resolving.
                    ResolvedStream(url = stream.directUrl, service = provider)
                }
            } else {
                val infoHash = stream.infoHash ?: magnetUrl?.extractBtihInfoHash()
                    ?: throw Exception("Stream has no infoHash, magnet, or direct URL")
                if (provider == null || apiKey.isBlank()) {
                    throw DebridMissingException(
                        "Connect Real-Debrid in Panda to use torrent streams.",
                    )
                }
                debridClient.resolveStream(
                    provider = provider,
                    apiKey = apiKey,
                    infoHash = infoHash,
                    fileIdx = stream.fileIdx,
                    magnetUrl = magnetUrl,
                    displayName = stream.title,
                    season = memoryMutex.withLock { lastRequestByStreamKey[streamMemoryKey(stream)] }?.season,
                    episode = memoryMutex.withLock { lastRequestByStreamKey[streamMemoryKey(stream)] }?.episode,
                )
            }.requirePlayableUrl()

            if (provider != null) {
                recordResolveSuccess(stream, provider)
            }
            reportPlaybackOutcome(stream, provider, success = true)
            resolved
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            reportPlaybackOutcome(stream, provider, success = false)
            throw e
        }
    }

override suspend fun resolveStreamWithFallback(
        stream: ParsedStream,
        providers: Map<DebridServiceType, String>,
    ): ResolvedStream {
        if (stream.canUseGenericStreamHandoff()) {
            return resolveStreamInternal(stream, provider = null, apiKey = "", trackPath = true)
        }

        // Addon-hosted streams (Panda's /u/<token>/nzb/..., /u/<token>/easynews/..., …)
        // bypass debrid entirely. The infoHash field, when present, just identifies
        // the release — the addon's own server handles fetching it. Skip the chain.
        if (stream.directUrl != null && stream.isAddonHostedUrl()) {
            return resolveStreamInternal(stream, provider = null, apiKey = "", trackPath = true)
        }

        if (providers.isEmpty()) {
            throw DebridMissingException(
                "No debrid provider is configured — playback can't unrestrict this source.",
            )
        }

        val attempts = mutableListOf<String>()
        var trackedProviderAttempt = false
        for ((provider, apiKey) in providers) {
            if (apiKey.isBlank()) continue
            try {
                val shouldTrack = !trackedProviderAttempt
                trackedProviderAttempt = true
                val resolved = resolveStreamInternal(
                    stream = stream,
                    provider = provider,
                    apiKey = apiKey,
                    trackPath = shouldTrack,
                )
                if (resolved.url.isNotBlank()) return resolved
                attempts += "${provider.name}: empty URL"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts += "${provider.name}: ${e.message ?: e::class.simpleName ?: "unknown"}"
            }
        }
        throw DebridNoCachedStreamException(
            "Tried " + providers.size + " debrid provider(s) — none produced a playable URL. " +
                "Common cause: the source isn't cached on any of them. Tried: " +
                attempts.joinToString("; "),
        )
    }

    private suspend fun resolveGenericStreamHandoffIfAvailable(stream: ParsedStream): ResolvedStream? {
        if (!stream.canUseGenericStreamHandoff()) return null
        val memoryId = stream.accelerationMemoryId?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val request = memoryMutex.withLock { lastRequestByStreamKey[streamMemoryKey(stream)] }
        val contentId = request?.contentId
            ?: request?.imdbId?.takeIf { it.isNotBlank() }?.let { "imdb:$it" }
            ?: throw StreamHandoffApiException(
                errorCode = "stream_reference_required",
                message = "This stream reference is no longer available.",
            )
        return accelerationApi
            .requestStreamHandoff(contentId = contentId, memoryId = memoryId)
            .toResolvedStream()
    }

    private fun playbackPathForStream(stream: ParsedStream): StreamPlaybackPath? = when {
        stream.canUseGenericStreamHandoff() -> StreamPlaybackPath.GENERIC_HANDOFF_MEMORY_ID
        stream.isUsenetStream() -> null
        stream.canUseLegacyDirectFallback() -> StreamPlaybackPath.LEGACY_DIRECT_NO_MEMORY_ID
        else -> null
    }

    private fun providerCategoryForStream(stream: ParsedStream): String = when {
        stream.isUsenetStream() -> "usenet"
        stream.isPandaStream() -> "panda"
        stream.isTorrentOrDebridStream() -> "debrid"
        stream.addonName.isNotBlank() -> "addon"
        else -> "unknown"
    }

    private fun MediaType.telemetryContentType(): String = when (this) {
        MediaType.MOVIE -> "movie"
        MediaType.SERIES -> "series"
    }

    override suspend fun reportPlaybackOutcome(
        stream: ParsedStream,
        provider: DebridServiceType?,
        success: Boolean,
    ) {
        val request = memoryMutex.withLock { lastRequestByStreamKey[streamMemoryKey(stream)] }
        val contentId = request?.contentId
            ?: request?.imdbId?.takeIf { it.isNotBlank() }?.let { "imdb:$it" }
            ?: return
        val sourceKey = stream.accelerationSourceKey
            ?: stream.accelerationMemoryId
            ?: stream.directUrl
            ?: stream.magnetUrl
            ?: stream.infoHash
            ?: return
        // The acceleration backend requires a provider label. Addon-hosted
        // flows (Panda etc.) resolve without a local provider; fall back to
        // the stream's own acceleration hint or skip reporting if neither
        // source is available.
        val providerLabel = stream.accelerationProviderType ?: provider?.apiValue ?: return
        accelerationApi.reportOutcome(
            AccelerationOutcomeDto(
                contentId = contentId,
                providerType = providerLabel,
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
            direct_url = stream.directUrlForResolveMemory(),
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
        val isDirect = directUrl != null
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
            streamKey = accelerationMemoryId ?: accelerationSourceKey ?: streamMemoryKey(this),
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
            memoryId = accelerationMemoryId,
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
            readinessState = if (direct_url != null) {
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
        // Panda polls the upstream cloud client for up to ~12s before
        // replying with 504. For large files (15GB+) the internal wait
        // sometimes stretches, so 30s gives Panda room to respond with an
        // accurate status rather than us timing out client-side and
        // falling back to Preparing on guesswork.
        const val PANDA_READINESS_PROBE_TIMEOUT_MS = 30_000L
        const val PANDA_READINESS_ERROR_BODY_MAX_BYTES = 64 * 1024
    }
}

private fun startupIdentityKey(stream: ParsedStream): String {
    return stream.accelerationMemoryId
        ?: stream.accelerationSourceKey
        ?: stream.directUrl
        ?: stream.magnetUrl
        ?: stream.infoHash
        ?: "${stream.addonName}|${stream.title}|${stream.quality}|${stream.source.orEmpty()}"
}

private fun streamMemoryKey(stream: ParsedStream): String {
    return stream.accelerationMemoryId
        ?: stream.directUrl
        ?: stream.magnetUrl
        ?: stream.infoHash
        ?: "${stream.addonName}|${stream.title}|${stream.quality}|${stream.source.orEmpty()}"
}

internal fun ParsedStream.directUrlForResolveMemory(): String? =
    if (canUseGenericStreamHandoff()) null else directUrl

internal fun StreamHandoffResponseDto.toResolvedStream(): ResolvedStream {
    return ResolvedStream(
        url = url,
        service = null,
        isTemporary = true,
        isDirect = isDirect,
        supportsRange = supportsRange,
        streamId = streamId,
        expiresInSeconds = expiresInSeconds,
    )
}

private fun debridSignature(accounts: Map<DebridServiceType, String>): String {
    if (accounts.isEmpty()) return "none"
    return accounts.entries
        .filter { it.value.isNotBlank() }
        .sortedBy { it.key.name }
        .joinToString("|") { (provider, key) -> "${provider.name}:${key.hashCode()}" }
        .ifBlank { "none" }
}

private fun ResolvedStream.requirePlayableUrl(): ResolvedStream {
    if (url.isBlank()) {
        // Most common cause: the selected torrent isn't cached on the active
        // debrid (instant-availability returned empty). Telling the user this
        // is more useful than "no playable URL" because the fix is to pick a
        // different source rather than re-configure anything.
        val providerLabel = service?.name?.takeIf { it.isNotBlank() } ?: "the active debrid"
        throw IllegalStateException(
            "$providerLabel returned no playable URL — this source likely isn't " +
                "cached. Try a different source or re-check provider connectivity.",
        )
    }
    return this
}
