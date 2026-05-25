package com.torve.domain.repository

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.ContentWarmupResult
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.InventoryMatchesSnapshot
import com.torve.domain.model.KnownHashAvailabilitySnapshot
import com.torve.domain.model.RecentSuccessfulSourcesSnapshot
import com.torve.domain.model.StartupCandidatesSnapshot
import com.torve.domain.streams.StreamRuntimeFilterFeedback

/**
 * Result of a [StreamRepository.probeStreamReadiness] call. For addon-hosted
 * URLs (notably Panda's `/u/<token>/nzb/<payload>`) the upstream server may be
 * serving the stream now, still fetching it, or failing. We split these into
 * three branches so the UI can show a "preparing" state instead of letting
 * ExoPlayer see a 504 and crash.
 */
sealed class StreamReadiness {
    /** The URL is serving playable content right now — hand it to the player. */
    data class Ready(val finalUrl: String) : StreamReadiness()
    /** Server replied with a recognised "still working on it" signal (Panda's 504 + `nzb_not_ready`). */
    data object Preparing : StreamReadiness()
    /** Anything else — real failure. The caller should surface [reason] and move on. */
    data class Failed(val reason: String) : StreamReadiness()
}

data class StreamFetchResult(
    val streams: List<ParsedStream>,
    val filterFeedback: StreamRuntimeFilterFeedback = StreamRuntimeFilterFeedback(),
)

interface StreamRepository {
    /**
     * Fetch available streams from all configured addons for a given media item.
     * When [addons], [debridAccounts], or [preferences] are provided, the full
     * aggregation pipeline runs: deduplicate, cache check, filter, score, sort.
     */
    suspend fun fetchStreams(
        type: MediaType,
        imdbId: String,
        contentId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        addons: List<InstalledAddon> = emptyList(),
        debridAccounts: Map<DebridServiceType, String> = emptyMap(),
        preferences: StreamPreferences = StreamPreferences(),
        fetchPolicy: StreamFetchPolicy = StreamFetchPolicy.FULL,
    ): List<ParsedStream>

    suspend fun fetchStreamsWithFeedback(
        type: MediaType,
        imdbId: String,
        contentId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        addons: List<InstalledAddon> = emptyList(),
        debridAccounts: Map<DebridServiceType, String> = emptyMap(),
        preferences: StreamPreferences = StreamPreferences(),
        fetchPolicy: StreamFetchPolicy = StreamFetchPolicy.FULL,
    ): StreamFetchResult = StreamFetchResult(
        streams = fetchStreams(
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
        ),
    )

    /**
     * Resolve a stream to a playable URL.
     *
     * - Hash-based streams (infoHash) always require a [provider] + [apiKey].
     * - Non-Usenet streams with a backend memory reference resolve through
     *   `/resolver/stream/handoff`; legacy direct/provider fallback is only
     *   allowed when the backend did not provide that reference.
     * - Direct URLs served by the owning addon (e.g. Panda's /u/<token>/… paths)
     *   are returned verbatim and played directly — no unrestrict. The resolver
     *   detects these via [com.torve.data.addon.isAddonHostedUrl].
     * - Direct URLs pointing at external hosts still go through the debrid
     *   unrestrict path and require a [provider] + [apiKey].
     *
     * [provider] is nullable so callers without a local debrid key can still
     * play addon-hosted streams; the resolver throws when a non-addon-hosted
     * URL is supplied without a provider.
     */
    suspend fun resolveStream(
        stream: ParsedStream,
        provider: DebridServiceType?,
        apiKey: String,
    ): ResolvedStream

    /**
     * Resolve a stream by walking [providers] as a fallback chain.
     *
     * The map is iterated in order — caller is expected to put the user's
     * preferred provider first. For each provider we call the single-provider
     * [resolveStream]; if it returns an empty URL or throws a recoverable error
     * (e.g. "not cached"), we move on to the next provider. The first non-empty
     * resolution wins. Throws [NoSuchElementException] only if every provider
     * has been tried and none produced a playable URL.
     *
     * Pure-direct (addon-hosted) URLs short-circuit the chain — they don't need
     * a debrid round-trip and the first call returns immediately.
     */
    suspend fun resolveStreamWithFallback(
        stream: ParsedStream,
        providers: Map<DebridServiceType, String>,
    ): ResolvedStream {
        // Default fallback for any pre-existing implementation: pick the first
        // provider and delegate to the single-provider path. Implementations
        // can override for proper chain semantics.
        val (provider, key) = providers.entries.firstOrNull()?.toPair() ?: (null to "")
        return resolveStream(stream, provider, key)
    }

    suspend fun reportPlaybackOutcome(
        stream: ParsedStream,
        provider: DebridServiceType?,
        success: Boolean,
    )

    /**
     * Probe an addon-hosted URL to find out whether it's actually ready for
     * playback. Single HEAD with redirect-follow disabled so we see Panda's
     * own status code, not a downstream CDN's. See [StreamReadiness] for the
     * three outcomes. Only call this for URLs where `ParsedStream.isAddonHostedUrl()`
     * is true — torrent/debrid URLs are playable the moment the debrid client
     * returns them and don't need a second round-trip.
     */
    suspend fun probeStreamReadiness(url: String): StreamReadiness

    suspend fun getStartupCandidates(request: SourceAccelerationRequest): StartupCandidatesSnapshot

    suspend fun getWarmStartupCandidates(
        request: SourceAccelerationRequest,
        maxAgeMs: Long = 60_000L,
    ): StartupCandidatesSnapshot?

    suspend fun warmupStartupCandidates(
        request: SourceAccelerationRequest,
        trigger: ContentWarmupTrigger,
    ): ContentWarmupResult

    suspend fun getRecentSuccessfulSources(request: SourceAccelerationRequest): RecentSuccessfulSourcesSnapshot

    suspend fun getInventoryMatches(request: SourceAccelerationRequest): InventoryMatchesSnapshot

    suspend fun getKnownHashAvailability(request: SourceAccelerationRequest): KnownHashAvailabilitySnapshot
}
