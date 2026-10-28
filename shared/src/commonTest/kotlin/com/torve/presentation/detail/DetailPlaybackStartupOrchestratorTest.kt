package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DetailPlaybackStartupOrchestratorTest {
    @Test
    fun prepared_torbox_file_keeps_original_source_and_requires_a_credential_free_cdn_url() {
        val source = ParsedStream(addonName = "AIOStreams", quality = "1080p", addonBaseUrl = "https://addon.example",
            directUrl = "https://addon.example/playback/selected", title = "Film.1968.RELEASE")
        val ready = DetailPlaybackStartupOrchestrator.preparedTorBoxStream(source,
            "https://nexus-123.ceur.tb-cdn.st/full.mkv", "Film.1968.RELEASE.mkv", 17_912_837_565L)
        assertNotNull(ready)
        assertEquals(17_912_837_565L, ready.fileSize)
        assertEquals(ResolvedStreamDispatch.PROBE_READINESS,
            DetailPlaybackStartupOrchestrator.classifyResolvedStream(ready.url, addonHosted = true))
        for (url in listOf("http://nexus-123.ceur.tb-cdn.st/full.mkv", "https://evil.example/full.mkv",
            "https://tb-cdn.st.evil.example/full.mkv", "https://api.torbox.app/v1/api/usenet/requestdl?token=private",
            "https://user:password@nexus-123.ceur.tb-cdn.st/full.mkv", "https://nexus-123.ceur.tb-cdn.st:8443/full.mkv")) {
            assertNull(DetailPlaybackStartupOrchestrator.preparedTorBoxStream(source, url, "Film.mkv", 100))
        }
        assertNull(DetailPlaybackStartupOrchestrator.preparedTorBoxStream(source.copy(addonBaseUrl = "https://other.example"),
            ready.url, "Film.mkv", 100))
        assertNull(DetailPlaybackStartupOrchestrator.preparedTorBoxStream(source, ready.url, "", 100))
        assertNull(DetailPlaybackStartupOrchestrator.preparedTorBoxStream(source, ready.url, "Film.mkv", 0))
    }

    @Test
    fun resolved_stream_dispatch_rejects_blank_urls_for_every_source_type() {
        assertEquals(
            ResolvedStreamDispatch.REJECT_EMPTY,
            DetailPlaybackStartupOrchestrator.classifyResolvedStream("", addonHosted = false),
        )
        assertEquals(
            ResolvedStreamDispatch.REJECT_EMPTY,
            DetailPlaybackStartupOrchestrator.classifyResolvedStream("   ", addonHosted = true),
        )
    }

    @Test
    fun resolved_stream_dispatch_never_skips_addon_readiness_probe() {
        assertEquals(
            ResolvedStreamDispatch.PROBE_READINESS,
            DetailPlaybackStartupOrchestrator.classifyResolvedStream(
                "https://provider.example/stream",
                addonHosted = true,
            ),
        )
        assertEquals(
            ResolvedStreamDispatch.PLAY_IMMEDIATELY,
            DetailPlaybackStartupOrchestrator.classifyResolvedStream(
                "https://cdn.example/video.mkv",
                addonHosted = false,
            ),
        )
    }

    @Test
    fun reducer_tracks_progressive_startup_phases_in_order() {
        var status = PlaybackStartupStatus()

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.LoadingStartupCandidates,
        )
        assertEquals(PlaybackStartupPhase.LOADING_STARTUP_CANDIDATES, status.phase)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.StartupCandidatesAvailable(candidateCount = 2),
        )
        assertEquals(PlaybackStartupPhase.STARTUP_CANDIDATES_AVAILABLE, status.phase)
        assertEquals(2, status.startupCandidateCount)
        assertEquals(2, status.mergedResultCount)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.AttemptingStartupAutoplay,
        )
        assertEquals(PlaybackStartupPhase.ATTEMPTING_STARTUP_AUTOPLAY, status.phase)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.StartupCandidateFailed,
        )
        assertEquals(PlaybackStartupPhase.STARTUP_CANDIDATE_FAILED, status.phase)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.FallingBackToFullFetch,
        )
        assertEquals(PlaybackStartupPhase.FALLING_BACK_TO_FULL_FETCH, status.phase)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.FullResultsAvailable(mergedResultCount = 5),
        )
        assertEquals(PlaybackStartupPhase.FULL_RESULTS_AVAILABLE, status.phase)
        assertEquals(5, status.mergedResultCount)
    }

    @Test
    fun mergeStreams_keeps_startup_order_and_replaces_duplicates_in_place() {
        val startupA = stream(key = "hash-a", title = "Startup A", quality = "720p")
        val startupB = stream(key = "hash-b", title = "Startup B", quality = "1080p")
        val fullB = stream(key = "hash-b", title = "Full B", quality = "4K")
        val fullC = stream(key = "hash-c", title = "Full C", quality = "1080p")

        val merged = DetailPlaybackStartupOrchestrator.mergeStreams(
            startupStreams = listOf(startupA, startupB),
            fullStreams = listOf(fullB, fullC),
            keySelector = { it.infoHash ?: it.title },
        )

        assertEquals(listOf("Startup A", "Full B", "Full C"), merged.map { it.title })
    }

    @Test
    fun mergeStreams_returns_single_copy_when_startup_and_full_match() {
        val startup = stream(key = "hash-a", title = "Startup", quality = "720p")
        val full = stream(key = "hash-a", title = "Full", quality = "1080p")

        val merged = DetailPlaybackStartupOrchestrator.mergeStreams(
            startupStreams = listOf(startup),
            fullStreams = listOf(full),
            keySelector = { it.infoHash ?: it.title },
        )

        assertEquals(1, merged.size)
        assertEquals("Full", merged.single().title)
    }

    private fun stream(
        key: String,
        title: String,
        quality: String,
    ) = ParsedStream(
        addonName = "Test",
        quality = quality,
        title = title,
        infoHash = key,
    )
}
