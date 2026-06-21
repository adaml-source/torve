package com.torve.data.sourceavailability

import com.torve.data.addon.ParsedStream
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.CandidateProvenance
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.Channel
import com.torve.domain.model.ContentWarmupResult
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.ContentWarmupDisposition
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.InventoryMatchesSnapshot
import com.torve.domain.model.KnownHashAvailabilitySnapshot
import com.torve.domain.model.MediaType
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.RecentSuccessfulSourcesSnapshot
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StartupCandidatesSnapshot
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.StreamReadiness
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.panda.PandaSetupUiState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Per-provider tests for the five Prompt 8 availability providers.
 *
 * Each provider gets at minimum:
 *   1. an UNCONFIGURED-silent case (no creds / no config → null);
 *   2. a positive case (real signal returned);
 *   3. a negative case (configured but no match → null);
 *   4. provider-specific edge cases where relevant.
 *
 * No real network — every dependency is faked.
 */
class Prompt8AvailabilityProvidersTest {

    // ── DEBRID_CACHE ───────────────────────────────────────────────────

    @Test
    fun `debrid cache returns null when no debrid api key on file`() = runTest {
        val provider = DebridCacheSourceAvailabilityProvider(
            streamRepository = StubStreamRepository(),
            secretStore = EmptySecretStore(),
            tmdbToImdbResolver = { _, _ -> "tt0123456" },
        )
        assertNull(provider.probe(42, MediaType.MOVIE))
    }

    @Test
    fun `debrid cache returns null when imdb id cannot be resolved`() = runTest {
        val provider = DebridCacheSourceAvailabilityProvider(
            streamRepository = StubStreamRepository(),
            secretStore = SecretStore(mapOf(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID to "rdkey")),
            tmdbToImdbResolver = { _, _ -> null },
        )
        assertNull(provider.probe(42, MediaType.MOVIE))
    }

    @Test
    fun `debrid cache fires when a non-usenet candidate is cached`() = runTest {
        val candidate = candidate(provenance = CandidateProvenanceKind.STARTUP_FETCH, isCached = true)
        val provider = DebridCacheSourceAvailabilityProvider(
            streamRepository = StubStreamRepository(snapshot = snapshotOf(candidate)),
            secretStore = SecretStore(mapOf(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID to "rdkey")),
            tmdbToImdbResolver = { _, _ -> "tt0468569" },
        )
        val signal = provider.probe(155, MediaType.MOVIE)
        assertNotNull(signal)
        assertEquals("Cached", signal.badge)
    }

    @Test
    fun `debrid cache stays silent when only usenet candidates are present`() = runTest {
        val candidate = candidate(
            provenance = CandidateProvenanceKind.USENET_NZBDAV,
            isCached = true,
        )
        val provider = DebridCacheSourceAvailabilityProvider(
            streamRepository = StubStreamRepository(snapshot = snapshotOf(candidate)),
            secretStore = SecretStore(mapOf(IntegrationSecretKey.DEBRID_API_KEY_TORBOX to "tbkey")),
            tmdbToImdbResolver = { _, _ -> "tt0468569" },
        )
        assertNull(provider.probe(155, MediaType.MOVIE))
    }

    // ── STREMIO_ADDON ─────────────────────────────────────────────────

    @Test
    fun `addon provider returns null when no addons are enabled`() = runTest {
        val provider = StremioAddonSourceAvailabilityProvider(
            addonRepository = StubAddonRepository(emptyList()),
            streamRepository = StubStreamRepository(),
            tmdbToImdbResolver = { _, _ -> "tt0123" },
        )
        assertNull(provider.probe(42, MediaType.MOVIE))
    }

    @Test
    fun `addon provider fires when a non-usenet candidate is reachable`() = runTest {
        val provider = StremioAddonSourceAvailabilityProvider(
            addonRepository = StubAddonRepository(listOf(stubAddon())),
            streamRepository = StubStreamRepository(
                snapshot = snapshotOf(candidate(CandidateProvenanceKind.STARTUP_FETCH, isCached = false)),
            ),
            tmdbToImdbResolver = { _, _ -> "tt0468569" },
        )
        val signal = provider.probe(155, MediaType.MOVIE)
        assertNotNull(signal)
        assertEquals("Addon source", signal.badge)
    }

    @Test
    fun `addon provider stays silent when every candidate is UNAVAILABLE`() = runTest {
        val provider = StremioAddonSourceAvailabilityProvider(
            addonRepository = StubAddonRepository(listOf(stubAddon())),
            streamRepository = StubStreamRepository(
                snapshot = snapshotOf(
                    candidate(CandidateProvenanceKind.STARTUP_FETCH, isCached = false, readiness = ReadinessState.UNAVAILABLE),
                ),
            ),
            tmdbToImdbResolver = { _, _ -> "tt0468569" },
        )
        assertNull(provider.probe(155, MediaType.MOVIE))
    }

    // ── USENET_READY ──────────────────────────────────────────────────

    @Test
    fun `usenet provider returns null when Panda is not in edit mode`() = runTest {
        val provider = UsenetReadySourceAvailabilityProvider(
            streamRepository = StubStreamRepository(),
            pandaStateSource = { PandaSetupUiState(isEditMode = false) },
            tmdbToImdbResolver = { _, _ -> "tt0123" },
        )
        assertNull(provider.probe(42, MediaType.MOVIE))
    }

    @Test
    fun `usenet provider fires only when a USENET_NZBDAV candidate is ready`() = runTest {
        val ready = candidate(CandidateProvenanceKind.USENET_NZBDAV, readiness = ReadinessState.READY_NOW)
        val provider = UsenetReadySourceAvailabilityProvider(
            streamRepository = StubStreamRepository(snapshot = snapshotOf(ready)),
            pandaStateSource = { PandaSetupUiState(isEditMode = true) },
            tmdbToImdbResolver = { _, _ -> "tt0468569" },
        )
        val signal = provider.probe(155, MediaType.MOVIE)
        assertNotNull(signal)
        assertEquals("Usenet ready", signal.badge)
    }

    @Test
    fun `usenet provider stays silent when only a debrid candidate exists`() = runTest {
        val provider = UsenetReadySourceAvailabilityProvider(
            streamRepository = StubStreamRepository(
                snapshot = snapshotOf(candidate(CandidateProvenanceKind.STARTUP_FETCH, isCached = true)),
            ),
            pandaStateSource = { PandaSetupUiState(isEditMode = true) },
            tmdbToImdbResolver = { _, _ -> "tt0468569" },
        )
        assertNull(provider.probe(155, MediaType.MOVIE))
    }

    // ── IPTV_LIVE ─────────────────────────────────────────────────────

    @Test
    fun `iptv provider returns null when no playlists are loaded`() = runTest {
        val provider = IptvLiveSourceAvailabilityProvider(
            channelsStateSource = { ChannelsUiState() },
            titleSource = { _, _ -> "Sherlock" },
        )
        assertNull(provider.probe(19885, MediaType.SERIES))
    }

    @Test
    fun `iptv provider fires on exact EPG programme match`() = runTest {
        val provider = IptvLiveSourceAvailabilityProvider(
            channelsStateSource = {
                ChannelsUiState(
                    playlists = listOf(stubPlaylist()),
                    channels = listOf(
                        enriched("BBC One", currentTitle = "Sherlock"),
                        enriched("BBC Two", currentTitle = "Newsnight"),
                    ),
                )
            },
            titleSource = { _, _ -> "Sherlock" },
        )
        val signal = provider.probe(19885, MediaType.SERIES)
        assertNotNull(signal)
        assertEquals("On now: BBC One", signal.badge)
    }

    @Test
    fun `iptv provider falls back to channel name contains match`() = runTest {
        val provider = IptvLiveSourceAvailabilityProvider(
            channelsStateSource = {
                ChannelsUiState(
                    playlists = listOf(stubPlaylist()),
                    channels = listOf(enriched("Discovery Channel", currentTitle = null)),
                )
            },
            titleSource = { _, _ -> "Discovery" },
        )
        val signal = provider.probe(1, MediaType.SERIES)
        assertNotNull(signal)
    }

    @Test
    fun `iptv provider stays silent on a too-short search title`() = runTest {
        val provider = IptvLiveSourceAvailabilityProvider(
            channelsStateSource = {
                ChannelsUiState(
                    playlists = listOf(stubPlaylist()),
                    channels = listOf(enriched("BBC One", currentTitle = "It Movie")),
                )
            },
            titleSource = { _, _ -> "It" },
        )
        // 2 chars is below MIN_MATCH_LEN=4 → no signal even though "It" appears.
        assertNull(provider.probe(1, MediaType.MOVIE))
    }

    @Test
    fun `iptv provider stays silent when title isn't on any channel`() = runTest {
        val provider = IptvLiveSourceAvailabilityProvider(
            channelsStateSource = {
                ChannelsUiState(
                    playlists = listOf(stubPlaylist()),
                    channels = listOf(enriched("HBO", currentTitle = "Succession")),
                )
            },
            titleSource = { _, _ -> "Sherlock" },
        )
        assertNull(provider.probe(19885, MediaType.SERIES))
    }

    // ── WATCH_HISTORY ─────────────────────────────────────────────────

    @Test
    fun `watch history provider fires when a row exists for the tmdb id`() = runTest {
        val repo = StubWatchHistoryRepository(
            rows = listOf(historyEntry(mediaId = "603", mediaType = "movie", title = "The Matrix")),
        )
        val provider = WatchHistorySourceAvailabilityProvider(repo)
        val signal = provider.probe(603, MediaType.MOVIE)
        assertNotNull(signal)
        assertEquals("Watched", signal.badge)
    }

    @Test
    fun `watch history provider also accepts tmdb-prefixed mediaIds`() = runTest {
        val repo = StubWatchHistoryRepository(
            rows = listOf(historyEntry(mediaId = "tmdb:603", mediaType = "movie", title = "The Matrix")),
        )
        val provider = WatchHistorySourceAvailabilityProvider(repo)
        assertNotNull(provider.probe(603, MediaType.MOVIE))
    }

    @Test
    fun `watch history provider stays silent on wrong media type`() = runTest {
        val repo = StubWatchHistoryRepository(
            rows = listOf(historyEntry(mediaId = "603", mediaType = "movie", title = "x")),
        )
        val provider = WatchHistorySourceAvailabilityProvider(repo)
        assertNull(provider.probe(603, MediaType.SERIES))
    }

    @Test
    fun `watch history provider stays silent when there's no row`() = runTest {
        val repo = StubWatchHistoryRepository(rows = emptyList())
        val provider = WatchHistorySourceAvailabilityProvider(repo)
        assertNull(provider.probe(603, MediaType.MOVIE))
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun candidate(
        provenance: CandidateProvenanceKind,
        isCached: Boolean = false,
        readiness: ReadinessState = if (isCached) ReadinessState.READY_NOW else ReadinessState.READY_WITH_RESOLVE,
    ): StartupCandidate = StartupCandidate(
        streamKey = "k",
        title = "x",
        qualityLabel = "1080p",
        quality = StreamQuality.FHD_1080P,
        addonName = "fake-addon",
        readinessState = readiness,
        provenance = CandidateProvenance(kind = provenance),
        isKnownCached = isCached,
    )

    private fun snapshotOf(vararg candidates: StartupCandidate): StartupCandidatesSnapshot =
        StartupCandidatesSnapshot(
            request = SourceAccelerationRequest(mediaType = MediaType.MOVIE, imdbId = "tt0468569"),
            readinessState = ReadinessState.READY_NOW,
            candidates = candidates.toList(),
        )

    private fun stubAddon(): InstalledAddon = InstalledAddon(
        manifestUrl = "https://example/manifest.json",
        manifest = com.torve.domain.model.AddonManifest(
            id = "x", name = "x", version = "1", description = "",
            types = listOf("movie"), resources = listOf("stream"),
        ),
        isEnabled = true,
    )

    private fun stubPlaylist(): com.torve.domain.model.ChannelPlaylist =
        com.torve.domain.model.ChannelPlaylist(id = "p1", name = "test", url = "https://x")

    private fun enriched(channelName: String, currentTitle: String?): EnrichedChannel = EnrichedChannel(
        channel = Channel(name = channelName, url = "https://stream/$channelName"),
        currentProgramme = currentTitle?.let {
            EpgProgramme(
                channelId = channelName,
                startTime = 0L,
                endTime = 0L,
                title = it,
            )
        },
    )

    private fun historyEntry(mediaId: String, mediaType: String, title: String): WatchHistoryEntry =
        WatchHistoryEntry(
            id = "h-$mediaId",
            mediaId = mediaId,
            mediaType = mediaType,
            title = title,
            posterUrl = null,
            backdropUrl = null,
            watchedAt = 0L,
            durationWatchedMs = 60_000L,
            seasonNumber = null,
            episodeNumber = null,
            showTitle = null,
        )

    // ── stubs ────────────────────────────────────────────────────────

    private open class EmptySecretStore : IntegrationSecretStore {
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = null
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {}
        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {}
        override suspend fun setStorageMode(
            key: IntegrationSecretKey,
            mode: com.torve.domain.integrations.IntegrationStorageMode,
        ) {}
        override suspend fun getStorageMode(key: IntegrationSecretKey) =
            com.torve.domain.integrations.IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() {}
    }

    private class SecretStore(private val rows: Map<IntegrationSecretKey, String>) : EmptySecretStore() {
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = rows[key]
    }

    private class StubAddonRepository(private val enabled: List<InstalledAddon>) : AddonRepository {
        override suspend fun installAddon(
            url: String, enabled: Boolean, priority: Int?, serverId: String?,
            syncedAt: Long?, installedFrom: String,
        ): InstalledAddon = error("not used")
        override suspend fun removeAddon(manifestUrl: String) {}
        override suspend fun getInstalledAddons(): List<InstalledAddon> = enabled
        override suspend fun getEnabledAddons(): List<InstalledAddon> = enabled
        override suspend fun toggleAddon(manifestUrl: String, enabled: Boolean) {}
        override suspend fun reorderAddons(orderedUrls: List<String>) {}
        override suspend fun getManifest(url: String) = error("not used")
        override suspend fun getAddon(manifestUrl: String): InstalledAddon? = null
        override suspend fun markAddonSynced(
            manifestUrl: String, serverId: String, syncedAt: Long?, installedFrom: String,
        ) {}
        override suspend fun syncRemoteState(
            manifestUrl: String, serverId: String, enabled: Boolean,
            priority: Int, syncedAt: Long, installedFrom: String,
        ) {}
        override suspend fun clearSyncMetadata() {}
        override suspend fun setAddonConfigId(manifestUrl: String, configId: String?) {}
    }

    private class StubStreamRepository(
        private val snapshot: StartupCandidatesSnapshot? = null,
    ) : StreamRepository {
        override suspend fun fetchStreams(
            type: MediaType, imdbId: String, contentId: String?, title: String?,
            season: Int?, episode: Int?, addons: List<InstalledAddon>,
            debridAccounts: Map<DebridServiceType, String>, preferences: StreamPreferences,
            fetchPolicy: StreamFetchPolicy,
        ): List<ParsedStream> = emptyList()

        override suspend fun resolveStream(stream: ParsedStream, provider: DebridServiceType?, apiKey: String): ResolvedStream =
            ResolvedStream(url = "", service = provider)

        override suspend fun reportPlaybackOutcome(stream: ParsedStream, provider: DebridServiceType?, success: Boolean) {}

        override suspend fun probeStreamReadiness(url: String): StreamReadiness =
            StreamReadiness.Failed("not used")

        override suspend fun getStartupCandidates(request: SourceAccelerationRequest): StartupCandidatesSnapshot =
            snapshot ?: StartupCandidatesSnapshot(
                request = request, readinessState = ReadinessState.EMPTY, candidates = emptyList(),
            )

        override suspend fun getWarmStartupCandidates(request: SourceAccelerationRequest, maxAgeMs: Long) = null

        override suspend fun warmupStartupCandidates(request: SourceAccelerationRequest, trigger: ContentWarmupTrigger): ContentWarmupResult =
            ContentWarmupResult(request, trigger, ContentWarmupDisposition.SKIPPED_NO_CONTEXT)

        override suspend fun getRecentSuccessfulSources(request: SourceAccelerationRequest) =
            RecentSuccessfulSourcesSnapshot(request, ReadinessState.EMPTY)

        override suspend fun getInventoryMatches(request: SourceAccelerationRequest) =
            InventoryMatchesSnapshot(request, ReadinessState.EMPTY)

        override suspend fun getKnownHashAvailability(request: SourceAccelerationRequest) =
            KnownHashAvailabilitySnapshot(request, ReadinessState.EMPTY)
    }

    private class StubWatchHistoryRepository(private val rows: List<WatchHistoryEntry>) : WatchHistoryRepository {
        override suspend fun getRecent(limit: Int): List<WatchHistoryEntry> = rows.take(limit)
        override suspend fun getByDateRange(startMs: Long, endMs: Long): List<WatchHistoryEntry> = rows
        override suspend fun getAll(): List<WatchHistoryEntry> = rows
        override suspend fun getForMedia(mediaId: String): List<WatchHistoryEntry> =
            rows.filter { it.mediaId == mediaId || it.mediaId == "tmdb:$mediaId" }
        override suspend fun record(entry: WatchHistoryEntry) {}
        override suspend fun delete(id: String) {}
        override suspend fun clearAll() {}
        override suspend fun getCount(): Long = rows.size.toLong()
        override suspend fun syncFromTrakt() {}
    }
}
