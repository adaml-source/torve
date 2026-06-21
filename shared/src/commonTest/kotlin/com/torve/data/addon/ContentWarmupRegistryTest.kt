package com.torve.data.addon

import com.torve.domain.model.AddonManifest
import com.torve.domain.model.ContentWarmupDisposition
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.SourceAccelerationContext
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StartupCandidatesSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentWarmupRegistryTest {

    @Test
    fun warmup_reusesRecentSnapshotWithinTriggerWindow() = runTest {
        var now = 1_000L
        val registry = ContentWarmupRegistry(nowMs = { now })
        val request = request()
        var loadCount = 0

        val first = registry.warmup(
            request = request,
            trigger = ContentWarmupTrigger.DETAIL_OPEN,
            hasWarmupContext = true,
        ) {
            loadCount += 1
            snapshot(request)
        }
        val second = registry.warmup(
            request = request,
            trigger = ContentWarmupTrigger.DETAIL_OPEN,
            hasWarmupContext = true,
        ) {
            loadCount += 1
            snapshot(request)
        }

        assertEquals(ContentWarmupDisposition.WARMED, first.disposition)
        assertEquals(ContentWarmupDisposition.REUSED_RECENT, second.disposition)
        assertEquals(1, loadCount)
        assertEquals(2, second.snapshot?.candidates?.size)
    }

    @Test
    fun warmup_skipsConcurrentDuplicateRequests() = runTest {
        val registry = ContentWarmupRegistry(nowMs = { 5_000L })
        val request = request(imdbId = "tt9999999")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val owner = async {
            registry.warmup(
                request = request,
                trigger = ContentWarmupTrigger.TV_PLAY_ACTION_FOCUS,
                hasWarmupContext = true,
            ) {
                started.complete(Unit)
                release.await()
                snapshot(request)
            }
        }
        started.await()

        val duplicate = registry.warmup(
            request = request,
            trigger = ContentWarmupTrigger.TV_PLAY_ACTION_FOCUS,
            hasWarmupContext = true,
        ) {
            snapshot(request)
        }

        assertEquals(ContentWarmupDisposition.SKIPPED_IN_FLIGHT, duplicate.disposition)
        release.complete(Unit)
        assertEquals(ContentWarmupDisposition.WARMED, owner.await().disposition)
    }

    @Test
    fun freshSnapshot_expiresWhenOlderThanRequestedAge() = runTest {
        var now = 10_000L
        val registry = ContentWarmupRegistry(nowMs = { now })
        val request = request(imdbId = "tt5555555")

        registry.warmup(
            request = request,
            trigger = ContentWarmupTrigger.NEXT_EPISODE_AUTOPLAY,
            hasWarmupContext = true,
        ) {
            snapshot(request)
        }
        assertEquals(2, registry.getFreshSnapshot(request, maxAgeMs = 1_000L)?.candidates?.size)

        now += 1_500L
        assertNull(registry.getFreshSnapshot(request, maxAgeMs = 1_000L))
    }

    @Test
    fun warmup_skipsWhenNoWarmupContextExists() = runTest {
        val registry = ContentWarmupRegistry(nowMs = { 1L })
        val request = request(addons = emptyList())

        val result = registry.warmup(
            request = request,
            trigger = ContentWarmupTrigger.DETAIL_OPEN,
            hasWarmupContext = false,
        ) {
            snapshot(request)
        }

        assertEquals(ContentWarmupDisposition.SKIPPED_NO_CONTEXT, result.disposition)
        assertNull(result.snapshot)
    }

    private fun request(
        imdbId: String = "tt1234567",
        addons: List<InstalledAddon> = listOf(installedAddon()),
    ): SourceAccelerationRequest {
        return SourceAccelerationRequest(
            mediaType = MediaType.MOVIE,
            imdbId = imdbId,
            context = SourceAccelerationContext(
                addons = addons,
            ),
        )
    }

    private fun snapshot(request: SourceAccelerationRequest): StartupCandidatesSnapshot {
        return StartupCandidatesSnapshot(
            request = request,
            readinessState = ReadinessState.READY_NOW,
            candidates = listOf(
                SourceAccelerationMapper.toStartupCandidate(
                    StartupCandidateBackendModel(
                        streamKey = "${request.contentKey}:1",
                        title = "Candidate 1",
                        qualityLabel = "1080p",
                        addonName = "Addon",
                        sourceLabel = null,
                        readinessState = ReadinessState.READY_NOW,
                        provenanceKind = com.torve.domain.model.CandidateProvenanceKind.STARTUP_FETCH,
                    ),
                ),
                SourceAccelerationMapper.toStartupCandidate(
                    StartupCandidateBackendModel(
                        streamKey = "${request.contentKey}:2",
                        title = "Candidate 2",
                        qualityLabel = "4K",
                        addonName = "Addon",
                        sourceLabel = null,
                        readinessState = ReadinessState.READY_WITH_RESOLVE,
                        provenanceKind = com.torve.domain.model.CandidateProvenanceKind.STARTUP_FETCH,
                    ),
                ),
            ),
        )
    }

    private fun installedAddon(): InstalledAddon {
        return InstalledAddon(
            manifestUrl = "https://example.com/manifest.json",
            manifest = AddonManifest(
                id = "addon.test",
                name = "Test Addon",
                version = "1.0.0",
            ),
        )
    }
}
