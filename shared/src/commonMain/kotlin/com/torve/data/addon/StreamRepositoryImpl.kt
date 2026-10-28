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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock

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
    private val family: com.torve.data.profile.FamilyControls? = null,
    private val configuredUsenet: com.torve.data.usenet.ConfiguredUsenetSources? = null,
) : StreamRepository {
    override suspend fun hasConfiguredUsenetSources(): Boolean = configuredUsenet?.hasSources() == true
    private data class FamilyStreamGrant(val stamp: String, val source: String, val item: com.torve.domain.model.MediaItem)
    private val familyStreams = mutableMapOf<String, FamilyStreamGrant>()
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
        family: com.torve.data.profile.FamilyControls? = null,
        configuredUsenet: com.torve.data.usenet.ConfiguredUsenetSources? = null,
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
        family = family,
        configuredUsenet = configuredUsenet,
    ) {
        streamAggregationSourceOverride = streamAggregationSource
    }

    private inline fun repositoryDebugLog(message: () -> String) {
        if (TorveRuntimeDebug.verboseLoggingEnabled) {
            println(DiagnosticsRedactor.redact(message()))
        }
    }

    private val playbackProbe by lazy { AddonPlaybackProbe(httpClient) }

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
        val addons: List<InstalledAddon>,
        val preferences: StreamPreferences,
        val providerStats: List<com.torve.domain.model.ProviderSourceStat> = emptyList(),
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
    ): StreamFetchResult = fetchStreamsInternal(type, imdbId, contentId, title, season, episode, addons, debridAccounts, preferences, fetchPolicy, null)

    override suspend fun fetchStreamsProgressively(
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
        onPartialResults: suspend (StreamFetchResult) -> Unit,
    ): StreamFetchResult = fetchStreamsInternal(type, imdbId, contentId, title, season, episode, addons, debridAccounts, preferences, fetchPolicy, onPartialResults)

    private suspend fun fetchStreamsInternal(
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
        onPartialResults: (suspend (StreamFetchResult) -> Unit)?,
    ): StreamFetchResult = coroutineScope {
        family?.initialize()
        val familyStamp = family?.sessionKey()
        val familyAddons = addons.filter { family?.allowsSource("extension:${it.manifest.id}") != false }
        val allowUsenet = configuredUsenet != null && family?.allowsSource("usenet") != false &&
            familyAddons.none { isPandaExtension(it.manifest.id, it.manifestUrl) }
        if (family?.state?.value?.isKids == true && familyAddons.isEmpty() && !allowUsenet) return@coroutineScope StreamFetchResult(emptyList())
        val familySource = familyAddons.firstOrNull()?.let { "extension:${it.manifest.id}" } ?: if (allowUsenet) "usenet" else "catalogue"
        family?.requireMediaAccess(type, contentId ?: imdbId, imdbId, familySource)
        // Explicit saved connections are searched independently of extension installation and
        // the extension-only startup cache. Settings changes cannot leave an indexer cached out.
        val usenetSearch = async {
            if (allowUsenet && fetchPolicy == StreamFetchPolicy.FULL)
                configuredUsenet!!.search(type, imdbId, season, episode, preferences)
            else StreamFetchResult(emptyList())
        }
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
        var providerStats = emptyList<com.torve.domain.model.ProviderSourceStat>()
        val cached = memoryMutex.withLock {
            startupCache[request.contentKey]
                ?.takeIf { it.isUsableFor(fetchPolicy, now) && it.addons == familyAddons && it.preferences == preferences }
                ?.also { batch -> providerStats = batch.providerStats.map { it.copy(fromCache = true) } }?.streams
        }

        val baseStreams = cached ?: run {
            val localStreams = streamAggregationSourceOverride?.resolveStreams(
                addons = familyAddons,
                type = type,
                imdbId = imdbId,
                season = season,
                episode = episode,
                debridAccounts = debridAccounts,
                preferences = preferences,
                fetchPolicy = fetchPolicy,
            ) ?: streamAggregator.resolveStreams(
                addons = familyAddons,
                type = type,
                imdbId = imdbId,
                season = season,
                episode = episode,
                debridAccounts = debridAccounts,
                preferences = preferences,
                fetchPolicy = fetchPolicy,
                onStatistics = { providerStats = it },
                onPartialStreams = onPartialResults?.let { publish -> { rows ->
                    if (familyStamp == family?.sessionKey()) {
                        val allowed = grantFamilyStreams(rows.filterNot { preferences.excludeSeasonPacks && it.isSeasonPack() }, familyAddons, type, contentId, imdbId, title, familyStamp)
                        rememberStreamRequest(request, allowed)
                        publish(applyRuntimeStreamFilters(allowed).copy(providerStats = providerStats))
                    }
                } },
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
                        addons = familyAddons,
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
            // Empty aggregation can mean every remote addon timed out. Do not
            // turn that transient failure into a two-minute authoritative
            // "no sources" result; the next user attempt must perform a real
            // lookup.
            if (mergedStreams.isNotEmpty()) {
                memoryMutex.withLock {
                    startupCache[request.contentKey] = PrefetchedStreamBatch(
                        streams = mergedStreams,
                        fetchedAt = now,
                        policy = fetchPolicy,
                        addons = familyAddons.toList(),
                        preferences = preferences, providerStats = providerStats,
                    )
                }
            }
            mergedStreams
        }

        val usenet = usenetSearch.await()
        val combined = (baseStreams + usenet.streams).distinctBy { it.directUrl ?: it.infoHash ?: it.title }
        val allowedStreams = grantFamilyStreams(combined.filterNot { preferences.excludeSeasonPacks && it.isSeasonPack() }, familyAddons, type, contentId, imdbId, title, familyStamp)
        rememberStreamRequest(request, allowedStreams)
        val memoryAdjustedStreams = applyResolveMemory(request, allowedStreams)
        applyRuntimeStreamFilters(memoryAdjustedStreams.sortedByDescending { it.score }).copy(
            providerStats = providerStats + usenet.providerStats, sourceErrors = usenet.sourceErrors)
    }

    private fun grantFamilyStreams(streams: List<ParsedStream>, familyAddons: List<InstalledAddon>,
        type: MediaType, contentId: String?, imdbId: String, title: String?, familyStamp: String?): List<ParsedStream> {
        if (familyStamp != family?.sessionKey()) return emptyList()
        return if (family?.state?.value?.isKids == true) {
            if (familyStamp != family.sessionKey()) return emptyList()
            streams.filter { stream ->
                val addon = familyAddons.firstOrNull { candidate ->
                    stream.addonBaseUrl?.trimEnd('/') == candidate.manifestUrl.removeSuffix("/manifest.json").trimEnd('/')
                }
                val source = if (stream.configuredNzb != null) "usenet" else
                    addon?.let { "extension:${it.manifest.id}" } ?: return@filter false
                if (!family.allowsSource(source)) return@filter false
                val item = com.torve.domain.model.MediaItem(id = contentId ?: imdbId, imdbId = imdbId, type = type, title = title.orEmpty(), familySourceKey = source)
                familyStreams[streamMemoryKey(stream)] = FamilyStreamGrant(family.sessionKey(), source, item)
                true
            }
        } else streams
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
     * Resolve a selected addon URL once with a bounded Range GET. Ready
     * redirects are handed directly to the player; provider error videos
     * remain errors rather than being played or counted as watched content.
     */
    override suspend fun probeStreamReadiness(url: String): StreamReadiness {
        val result = playbackProbe.check(url)
        if (result is StreamReadiness.Ready) family?.inheritPlayback(url, result.finalUrl)
        return result
    }

    private fun isNzbDownloadLink(url: String): Boolean {
        val path = runCatching { io.ktor.http.Url(url).encodedPath }.getOrDefault(url.substringBefore('?'))
        return url.contains("/getnzb/") ||
            path.endsWith(".nzb", ignoreCase = true) ||
            url.contains("nzbindex") ||
            url.contains("nzbgeek") ||
            url.contains("scenenzbs") ||
            url.contains("treasure-maps")
    }

    override suspend fun resolveStream(
        stream: ParsedStream,
        provider: DebridServiceType?,
        apiKey: String,
    ): ResolvedStream = resolveStreamInternal(stream, provider, apiKey, trackPath = true)

    private suspend fun resolveStreamInternal(stream: ParsedStream, provider: DebridServiceType?, apiKey: String, trackPath: Boolean): ResolvedStream {
        family?.initialize()
        val stamp = family?.sessionKey()
        requireFamilyStreamGrant(stream)
        val result = resolveStreamUnchecked(stream, provider, apiKey, trackPath)
        check(stamp == family?.sessionKey()) { "Profile changed during playback preparation." }
        return authorizePreparedStream(stream, result)
    }

    private fun requireFamilyStreamGrant(stream: ParsedStream): FamilyStreamGrant? {
        val grant = familyStreams[streamMemoryKey(stream)]
        if (family?.state?.value?.isKids == true) {
            check(grant != null && grant.stamp == family.sessionKey() && family.allows(grant.item, grant.source)) {
                "This source is not available in this profile."
            }
            return grant
        }
        check(family?.allowsSource("playback") != false) { "Unlock an adult profile to play this title." }
        return null
    }

    override suspend fun authorizePreparedStream(stream: ParsedStream, resolved: ResolvedStream): ResolvedStream {
        family?.initialize()
        val grant = requireFamilyStreamGrant(stream)
        if (grant != null) {
            // Apple prefers HLS when available; fallback and recovery paths can
            // select any of these provider-returned URLs for the same title.
            listOfNotNull(resolved.url, resolved.transcodeUrls?.hls,
                resolved.transcodeUrls?.mp4, resolved.transcodeUrls?.webm)
                .filter { it.isNotBlank() }.distinct().forEach { url ->
                    family?.registerPlayback(url, grant.source, grant.item)
                }
        }
        return resolved
    }

    private suspend fun resolveStreamUnchecked(
        stream: ParsedStream,
        provider: DebridServiceType?,
        apiKey: String,
        trackPath: Boolean,
    ): ResolvedStream {
        return try {
            if (stream.configuredNzb != null) {
                return (configuredUsenet ?: error("Configured Usenet playback is unavailable."))
                    .resolve(stream).requirePlayableUrl()
            }
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
            if (ExtensionRuntimePolicy.userManagedOnly && magnetUrl == null &&
                stream.directUrl != null && isNzbDownloadLink(stream.directUrl)
            ) {
                throw Exception("This source returns an NZB file. Configure a compatible download service with your extension provider so it returns a playable video link.")
            }
            val resolved = if (stream.directUrl != null && stream.isAddonHostedUrl()) {
                // Don't probe here â€” the caller (DetailViewModel) does the
                // readiness check via probeStreamReadiness so it can render
                // a preparing UI while we wait.
                ResolvedStream(url = stream.directUrl, service = null)
            } else if (magnetUrl == null && stream.directUrl != null) {
                if (stream.hasPreResolvedAddonPlaybackUrl()) {
                    // Configured Torrentio already returned the provider's
                    // playback URL. Wrapping it through WebDL again creates a
                    // nested temporary URL which can expire during playback.
                    ResolvedStream(url = stream.directUrl, service = provider)
                } else if (isNzbDownloadLink(stream.directUrl)) {
                    // Raw .nzb link from a non-Panda addon â€” can't be played
                    // without a download client resolving it first.
                    throw Exception("This source returns an NZB file. Configure a compatible download service with your extension provider so it returns a playable video link.")
                } else if (stream.infoHash == null) {
                    // Hoster URL that needs unrestricting (Real-Debrid, etc.).
                    // Without a provider we can't proceed.
                    if (provider == null || apiKey.isBlank()) {
                        throw DebridMissingException(
                            "Configure a compatible playback service with your extension provider, then add its configured manifest URL.",
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
                        "This torrent source needs a compatible playback service. Configure it with your extension provider and add its configured manifest URL.",
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
        if (stream.configuredNzb != null) return resolveStreamInternal(stream, null, "", trackPath = true)
        if (stream.canUseGenericStreamHandoff()) {
            return resolveStreamInternal(stream, provider = null, apiKey = "", trackPath = true)
        }

        // Addon-hosted streams (Panda's /u/<token>/nzb/..., /u/<token>/easynews/..., â€¦)
        // bypass debrid entirely. The infoHash field, when present, just identifies
        // the release â€” the addon's own server handles fetching it. Skip the chain.
        if (stream.directUrl != null && stream.isAddonHostedUrl()) {
            return resolveStreamInternal(stream, provider = null, apiKey = "", trackPath = true)
        }

        if (providers.isEmpty()) {
            throw DebridMissingException(
                "No debrid provider is configured â€” playback can't unrestrict this source.",
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
            "Tried " + providers.size + " debrid provider(s) â€” none produced a playable URL. " +
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
        // This only feeds source acceleration; watched history and progress
        // have their own synchronization. Never delay a manual extension link
        // on an optional backend report before handing it to the player.
        if (ExtensionRuntimePolicy.userManagedOnly) return
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
        if (family?.state?.value?.isKids == true) return null
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
            "$providerLabel returned no playable URL â€” this source likely isn't " +
                "cached. Try a different source or re-check provider connectivity.",
        )
    }
    return this
}
