package com.torve.data.addon

import com.torve.data.acceleration.StartupAccelerationCandidateDto
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.HashAvailabilityState
import com.torve.domain.model.InventoryMatchType
import com.torve.domain.model.StartupConfidenceReasonCode
import com.torve.domain.model.MediaType
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.SourceAccelerationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceAccelerationMapperTest {

    @Test
    fun startupCandidate_mapsClientSafeFieldsAndDefaultReasons() {
        val candidate = SourceAccelerationMapper.toStartupCandidate(
            StartupCandidateBackendModel(
                streamKey = "abc123",
                title = "Movie 2026",
                qualityLabel = "4K",
                addonName = "Addon",
                sourceLabel = "Indexer",
                readinessState = ReadinessState.READY_NOW,
                provenanceKind = CandidateProvenanceKind.STARTUP_FETCH,
                provenanceProviderLabel = "Addon",
                confidenceReasons = emptyList(),
                isDirectPlayback = true,
                isKnownCached = true,
                sizeBytes = 15_000L,
                seeds = 120,
            ),
        )

        assertEquals("abc123", candidate.streamKey)
        assertEquals("4K", candidate.qualityLabel)
        assertEquals(ReadinessState.READY_NOW, candidate.readinessState)
        assertEquals(CandidateProvenanceKind.STARTUP_FETCH, candidate.provenance.kind)
        assertTrue(StartupConfidenceReasonCode.NO_ACCELERATION_SIGNAL in candidate.confidenceReasons)
        assertTrue(candidate.isDirectPlayback)
        assertTrue(candidate.isKnownCached)
        assertEquals(emptyMap(), candidate.scoreBreakdown)
    }

    @Test
    fun inventoryAndHashModelsPreserveExplicitStates() {
        val inventory = SourceAccelerationMapper.toInventoryMatch(
            InventoryMatchBackendModel(
                matchKey = "provider:item:1",
                providerLabel = "Connected service",
                displayTitle = "Show S01E02",
                matchType = InventoryMatchType.EXACT_EPISODE,
                readinessState = ReadinessState.LOOKUP_ONLY,
                confidenceReasons = listOf(StartupConfidenceReasonCode.CONNECTED_SERVICE_MATCH),
                qualityLabel = "1080p",
                sizeBytes = 2_000L,
                lastSeenAt = 1234L,
            ),
        )
        val hash = SourceAccelerationMapper.toKnownHashAvailabilityObservation(
            KnownHashAvailabilityObservationBackendModel(
                infoHash = "deadbeef",
                providerLabel = "RD Cloud",
                availabilityState = HashAvailabilityState.AVAILABLE,
                readinessState = ReadinessState.READY_WITH_RESOLVE,
                confidenceReasons = listOf(
                    StartupConfidenceReasonCode.HASH_CACHED,
                    StartupConfidenceReasonCode.KNOWN_INFO_HASH,
                ),
                observedAt = 55L,
                expiresAt = 66L,
            ),
        )

        assertEquals(InventoryMatchType.EXACT_EPISODE, inventory.matchType)
        assertEquals(ReadinessState.LOOKUP_ONLY, inventory.readinessState)
        assertEquals(HashAvailabilityState.AVAILABLE, hash.availabilityState)
        assertEquals(ReadinessState.READY_WITH_RESOLVE, hash.readinessState)
    }

    @Test
    fun recentSuccessCandidate_defaultsToRecentSuccessReason() {
        val candidate = SourceAccelerationMapper.toRecentSuccessCandidate(
            RecentSuccessCandidateBackendModel(
                streamKey = "stream-key",
                title = "Movie 2026",
                providerLabel = "REAL_DEBRID",
                addonName = "Addon",
                qualityLabel = "1080p",
                sourceLabel = "Recent",
                readinessState = ReadinessState.READY_WITH_RESOLVE,
                confidenceReasons = emptyList(),
                lastSuccessfulAt = 1_000L,
                successCount = 3,
            ),
        )

        assertEquals("stream-key", candidate.streamKey)
        assertEquals(ReadinessState.READY_WITH_RESOLVE, candidate.readinessState)
        assertTrue(StartupConfidenceReasonCode.RECENT_SUCCESS in candidate.confidenceReasons)
    }

    @Test
    fun requestContentKey_isStableAcrossConsumers() {
        val request = SourceAccelerationRequest(
            mediaType = MediaType.SERIES,
            imdbId = "tt1234567",
            seasonNumber = 2,
            episodeNumber = 5,
        )

        assertEquals("SERIES:tt1234567:2:5", request.contentKey)
    }

    @Test
    fun backendCandidate_mapsToParsedStreamWithAccelerationSignals() {
        val stream = SourceAccelerationMapper.backendCandidateToParsedStream(
            StartupAccelerationCandidateDto(
                memoryId = "mem-abc",
                sourceKey = "torrentio:abc",
                providerType = "real_debrid",
                title = "Movie 2026",
                quality = "1080p",
                infoHash = "deadbeef",
                score = 0.92,
                isCached = true,
                reasons = listOf("recent_success", "cached", "in_your_cloud"),
                scoreBreakdown = mapOf("recent" to 0.6, "cache" to 0.32),
                sourceLabel = "Recommended",
            ),
        )

        assertNotNull(stream)
        assertEquals("mem-abc", stream.accelerationMemoryId)
        assertEquals("torrentio:abc", stream.accelerationSourceKey)
        assertEquals("real_debrid", stream.accelerationProviderType)
        assertEquals(92, stream.score)
        assertTrue(stream.isCached)
        assertTrue(StartupConfidenceReasonCode.RECENT_SUCCESS in stream.accelerationConfidenceReasons)
        assertTrue(StartupConfidenceReasonCode.HASH_CACHED in stream.accelerationConfidenceReasons)
        assertTrue(StartupConfidenceReasonCode.CONNECTED_SERVICE_MATCH in stream.accelerationConfidenceReasons)
    }

    @Test
    fun startupCandidateModelPreservesMemoryIdForPresentationState() {
        val candidate = SourceAccelerationMapper.toStartupCandidate(
            StartupCandidateBackendModel(
                streamKey = "source-key",
                title = "Movie 2026",
                qualityLabel = "1080p",
                addonName = "Panda",
                sourceLabel = "TorBox",
                readinessState = ReadinessState.READY_NOW,
                provenanceKind = CandidateProvenanceKind.STARTUP_FETCH,
                memoryId = "mem-456",
            ),
        )

        assertEquals("mem-456", candidate.memoryId)
    }

    @Test
    fun requestResolvedContentId_prefersExplicitTmdbValue() {
        val request = SourceAccelerationRequest(
            mediaType = MediaType.MOVIE,
            imdbId = "tt7654321",
            contentId = "tmdb:12345",
            title = "Movie 2026",
        )

        assertEquals("tmdb:12345", request.resolvedContentId)
    }

    // ── NzbDAV elevation ────────────────────────────────────────────────

    @Test
    fun nzbdavCandidate_emitsUsenetProvenanceAndPayloadWhenFlagsAndFieldsArePresent() {
        val stream = SourceAccelerationMapper.backendCandidateToParsedStream(
            StartupAccelerationCandidateDto(
                sourceKey = "nzbdav:c1",
                providerType = "nzbdav",
                title = "Movie 2026",
                quality = "1080p",
                provenanceKind = "usenet_nzbdav",
                hashKey = "deadbeefcafe",
                nzbUrl = "https://nzbdav.example.com/nzb/c1.nzb",
                addonName = "NzbDAV",
            ),
        )
        assertNotNull(stream)
        assertEquals(CandidateProvenanceKind.USENET_NZBDAV, stream.accelerationProvenanceKind)
        val payload = assertNotNull(stream.usenetCandidate)
        assertEquals("nzbdav:c1", payload.candidateId)
        assertEquals("deadbeefcafe", payload.hashKey)
        assertEquals("https://nzbdav.example.com/nzb/c1.nzb", payload.nzbUrl)
    }

    @Test
    fun nzbdavCandidate_acceptsMissingNzbUrlAsNull() {
        val stream = SourceAccelerationMapper.backendCandidateToParsedStream(
            StartupAccelerationCandidateDto(
                sourceKey = "nzbdav:c2",
                provenanceKind = "USENET_NZBDAV", // case-insensitive
                hashKey = "abc123",
                addonName = "NzbDAV",
            ),
        )
        val payload = assertNotNull(stream?.usenetCandidate)
        assertEquals("nzbdav:c2", payload.candidateId)
        assertEquals("abc123", payload.hashKey)
        assertEquals(null, payload.nzbUrl)
    }

    @Test
    fun nzbdavCandidate_missingHashKeyDropsToDefaultProvenanceWithoutPayload() {
        // hash_key is mandatory under the live contract. A row missing
        // it MUST NOT become a USENET_NZBDAV row — sending such a row
        // to /resolver/usenet/warm would yield a malformed body.
        val stream = SourceAccelerationMapper.backendCandidateToParsedStream(
            StartupAccelerationCandidateDto(
                sourceKey = "nzbdav:c3",
                provenanceKind = "usenet_nzbdav",
                hashKey = "  ", // blank
                addonName = "NzbDAV",
            ),
        )
        assertNotNull(stream)
        // Falls through to the default provenance derivation; payload absent.
        assertEquals(null, stream.usenetCandidate)
        assertTrue(stream.accelerationProvenanceKind != CandidateProvenanceKind.USENET_NZBDAV)
    }

    @Test
    fun nzbdavCandidate_missingSourceKeyReturnsNullAsExisting() {
        // The pre-existing rule (a row without any of source_key,
        // infoHash, direct_url is dropped entirely) still applies.
        val stream = SourceAccelerationMapper.backendCandidateToParsedStream(
            StartupAccelerationCandidateDto(
                sourceKey = null,
                infoHash = null,
                directUrl = null,
                provenanceKind = "usenet_nzbdav",
                hashKey = "h",
            ),
        )
        assertEquals(null, stream)
    }

    @Test
    fun nonNzbdavCandidate_unaffectedByNewFields() {
        // Regression guard: the existing addon/debrid mapping path is
        // unchanged when provenance_kind is null even if hash_key /
        // nzb_url happen to be sent (backend may include them defensively).
        val stream = SourceAccelerationMapper.backendCandidateToParsedStream(
            StartupAccelerationCandidateDto(
                sourceKey = "torrentio:abc",
                providerType = "real_debrid",
                title = "Movie 2026",
                quality = "1080p",
                infoHash = "deadbeef",
                score = 0.92,
                isCached = true,
                reasons = listOf("recent_success", "cached", "in_your_cloud"),
                hashKey = "h", // sent but ignored absent the provenance flag
                nzbUrl = "https://x", // ditto
            ),
        )
        assertNotNull(stream)
        assertEquals(null, stream.usenetCandidate)
        assertEquals(CandidateProvenanceKind.RECENT_SUCCESS, stream.accelerationProvenanceKind)
    }

    @Test
    fun nzbdavFlagWithUnrecognizedValue_isIgnored() {
        // Defensive against typos / future provenance values: only
        // exactly `usenet_nzbdav` (case-insensitive) elevates a row.
        val stream = SourceAccelerationMapper.backendCandidateToParsedStream(
            StartupAccelerationCandidateDto(
                sourceKey = "nzbdav:c5",
                provenanceKind = "usenet_v2", // unrecognized
                hashKey = "h",
            ),
        )
        assertNotNull(stream)
        assertEquals(null, stream.usenetCandidate)
    }
}
