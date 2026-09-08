package com.torve.domain.player

import com.torve.domain.repository.CachedSegmentAnalysis
import com.torve.domain.repository.PlaybackSegmentRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSegmentEngineTest {
    @Test
    fun noEvidenceProducesNoSegments() = runTest {
        val result = PlaybackSegmentEngine(MemoryRepository()).analyze(request())

        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun exactProviderAndChapterFuseToAutomaticConfidenceWithConservativeEnd() = runTest {
        val provider = provider(
            ProviderMarker(
                type = SegmentType.INTRO,
                startMs = 64_000,
                endMs = 88_000,
                confidence = 0.94,
                providerId = "test-community",
                providerVersion = 2,
                referenceRuntimeMs = RUNTIME,
                referenceFingerprint = "source-a",
            ),
        )
        val result = PlaybackSegmentEngine(MemoryRepository(), listOf(provider)).analyze(
            request(
                fingerprint = "source-a",
                chapters = listOf(MediaChapter("Opening Credits", 65_000, 87_000)),
            ),
        )

        val intro = result.segments.single()
        assertEquals(SegmentType.INTRO, intro.type)
        assertTrue(intro.confidence >= 0.88)
        assertEquals(87_000, intro.endMs, "skip end must choose the earlier safe boundary")
        assertEquals(SegmentConfidence.VERY_HIGH, intro.confidenceLevel)
    }

    @Test
    fun structurallyDifferentReleaseCannotSurfaceCommunityMarker() = runTest {
        val provider = provider(
            ProviderMarker(
                SegmentType.INTRO,
                60_000,
                90_000,
                0.95,
                "community",
                1,
                referenceRuntimeMs = RUNTIME - 45_000,
                referenceFingerprint = "other-source",
            ),
        )
        val result = PlaybackSegmentEngine(MemoryRepository(), listOf(provider)).analyze(request())
        val marker = result.segments.single()

        assertTrue(marker.confidence < PlaybackSegmentConfig().manualActionThreshold)
        val ui = PlaybackSegmentStateMachine().update(
            70_000,
            RUNTIME,
            result.segments,
            PlaybackSegmentSettings(),
        )
        assertFalse(ui.showSkipAction)
    }

    @Test
    fun minorRuntimeVarianceCanSurfaceManualMarkerButNeverAutomaticAction() = runTest {
        val provider = provider(
            ProviderMarker(
                SegmentType.INTRO,
                60_000,
                90_000,
                0.82,
                "community",
                1,
                referenceRuntimeMs = RUNTIME - 4_000,
            ),
        )
        val result = PlaybackSegmentEngine(MemoryRepository(), listOf(provider)).analyze(request())
        val intro = result.segments.single()

        assertTrue(intro.confidence >= PlaybackSegmentConfig().manualActionThreshold)
        assertTrue(intro.confidence < PlaybackSegmentConfig().automaticActionThreshold)
        assertNotNull(PlaybackSegmentStateMachine().manualSkipTarget(intro))
        assertNull(PlaybackSegmentStateMachine().automaticSkipTarget(intro, SegmentActionMode.AUTOMATIC))
    }

    @Test
    fun nearbySourceRuntimeSurfacesUnshiftedManualMarkerButNeverAutomaticAction() = runTest {
        val provider = provider(
            ProviderMarker(
                SegmentType.INTRO,
                60_000,
                90_000,
                0.82,
                "community",
                1,
                referenceRuntimeMs = RUNTIME - 6_000,
            ),
        )

        val intro = PlaybackSegmentEngine(MemoryRepository(), listOf(provider))
            .analyze(request())
            .segments
            .single()

        assertEquals(60_000, intro.startMs, "runtime delta must not be added to the marker")
        assertEquals(90_000, intro.endMs)
        assertTrue(intro.confidence >= PlaybackSegmentConfig().manualActionThreshold)
        assertTrue(intro.confidence < PlaybackSegmentConfig().automaticActionThreshold)
    }

    @Test
    fun providerWithoutReliableRuntimeCannotSurfaceMarker() = runTest {
        val provider = provider(
            ProviderMarker(
                SegmentType.INTRO,
                60_000,
                90_000,
                0.88,
                "community",
                1,
                referenceRuntimeMs = RUNTIME,
                referenceRuntimeReliable = false,
            ),
        )
        val intro = PlaybackSegmentEngine(MemoryRepository(), listOf(provider)).analyze(request()).segments.single()

        assertTrue(intro.confidence < PlaybackSegmentConfig().manualActionThreshold)
    }

    @Test
    fun eofAnchoredCreditsWithoutSourceRuntimeSurfaceManualPromptWithoutCountdown() = runTest {
        val provider = provider(
            ProviderMarker(
                type = SegmentType.CREDITS,
                startMs = 1_162_000,
                endMs = RUNTIME,
                confidence = 0.79,
                providerId = "theintrodb-v3",
                providerVersion = 1,
                referenceRuntimeMs = RUNTIME,
                referenceRuntimeReliable = false,
                endsAtMediaEnd = true,
            ),
        )

        val credits = PlaybackSegmentEngine(MemoryRepository(), listOf(provider))
            .analyze(request())
            .segments
            .single()
        val state = PlaybackSegmentStateMachine().update(
            positionMs = credits.startMs,
            durationMs = RUNTIME,
            segments = listOf(credits),
            settings = PlaybackSegmentSettings(playNextMode = SegmentActionMode.AUTOMATIC),
        )

        assertTrue(credits.confidence >= PlaybackSegmentConfig().manualActionThreshold)
        assertTrue(credits.confidence < PlaybackSegmentConfig().automaticActionThreshold)
        assertTrue(state.showNextPrompt)
        assertFalse(state.allowNextCountdown)
    }

    @Test
    fun conflictingEofCreditProvidersChooseLaterBoundaryAndRemainManualOnly() = runTest {
        val earlier = provider(
            ProviderMarker(
                SegmentType.CREDITS,
                1_117_000,
                RUNTIME,
                0.82,
                "introdb-community",
                1,
                referenceRuntimeMs = RUNTIME,
            ),
        )
        val later = provider(
            ProviderMarker(
                SegmentType.CREDITS,
                1_162_000,
                RUNTIME,
                0.79,
                "theintrodb-v3",
                1,
                referenceRuntimeMs = RUNTIME,
                referenceRuntimeReliable = false,
                endsAtMediaEnd = true,
            ),
        )

        val credits = PlaybackSegmentEngine(MemoryRepository(), listOf(earlier, later))
            .analyze(request())
            .segments
            .single()

        assertEquals(1_162_000L, credits.startMs)
        assertTrue(credits.confidence >= PlaybackSegmentConfig().manualActionThreshold)
        assertTrue(credits.confidence < PlaybackSegmentConfig().automaticActionThreshold)
        assertEquals(SegmentEvidenceSource.CONSENSUS, credits.source)
    }

    @Test
    fun providerMarkersAreClampedRejectedAndCannotManipulatePlayback() = runTest {
        val provider = provider(
            ProviderMarker(SegmentType.INTRO, -1, 20_000, 1.0, "bad", 1, RUNTIME, "source-a"),
            ProviderMarker(SegmentType.CREDITS, RUNTIME + 1, RUNTIME + 99_000, 1.0, "bad", 1, RUNTIME, "source-a"),
            ProviderMarker(SegmentType.INTRO, 0, 20 * 60_000, 1.0, "bad", 1, RUNTIME, "source-a"),
        )

        assertTrue(PlaybackSegmentEngine(MemoryRepository(), listOf(provider)).analyze(request()).segments.isEmpty())
    }

    @Test
    fun providerFailureDoesNotBlockChapterAnalysis() = runTest {
        val failing = object : SegmentMarkerProvider {
            override val id: String = "offline-provider"
            override suspend fun getSegments(request: PlaybackSegmentRequest): List<ProviderMarker> = error("offline")
        }
        val result = PlaybackSegmentEngine(MemoryRepository(), listOf(failing)).analyze(
            request(chapters = listOf(MediaChapter("Previously On", 0, 42_000))),
        )

        assertEquals(SegmentType.RECAP, result.segments.single().type)
    }

    @Test
    fun palSpeedConversionScalesMarkerRatherThanAddingRuntimeDelta() = runTest {
        val referenceRuntime = 25L * 60_000L
        val currentRuntime = 24L * 60_000L
        val provider = provider(
            ProviderMarker(SegmentType.INTRO, 100_000, 125_000, 0.9, "community", 1, referenceRuntime, "other"),
        )
        val result = PlaybackSegmentEngine(MemoryRepository(), listOf(provider)).analyze(
            request(runtime = currentRuntime),
        )

        val intro = result.segments.single()
        assertEquals(96_000, intro.startMs)
        assertEquals(120_000, intro.endMs)
        assertTrue(intro.confidence >= 0.7)
    }

    @Test
    fun cacheIsExactFingerprintAndAnalysisVersionScoped() = runTest {
        val cache = MemoryRepository()
        val provider = provider(
            ProviderMarker(SegmentType.INTRO, 20_000, 40_000, 0.95, "provider", 1, RUNTIME, "source-a"),
        )
        val engine = PlaybackSegmentEngine(cache, listOf(provider))

        engine.analyze(request(fingerprint = "source-a"))
        val second = engine.analyze(request(fingerprint = "source-a"))
        val other = PlaybackSegmentEngine(cache).analyze(request(fingerprint = "source-b"))

        assertTrue(second.fromCache)
        assertFalse(other.fromCache)
        assertTrue(other.segments.isEmpty())
    }

    @Test
    fun repeatedSkipUndoEvidenceConservativelyShortensExactSourceBoundary() = runTest {
        val cache = MemoryRepository()
        val provider = provider(
            ProviderMarker(SegmentType.INTRO, 60_000, 90_000, 0.95, "provider", 1, RUNTIME, "source-a"),
        )
        val engine = PlaybackSegmentEngine(cache, listOf(provider))
        val request = request(fingerprint = "source-a")
        val original = engine.analyze(request).segments.single()

        engine.recordSkipUndo(request.media, original.id, 87_900)
        assertEquals(90_000, engine.analyze(request).segments.single().endMs, "one action must not alter a marker")
        engine.recordSkipUndo(request.media, original.id, 88_100)
        engine.recordSkipUndo(request.media, original.id, 88_000)

        val learned = engine.analyze(request).segments.single()
        assertEquals(88_000, learned.endMs)
        assertEquals(SegmentEvidenceSource.USER_VALIDATED, learned.source)
        assertEquals(3, learned.evidence.count { it.detectorId == "local-skip-undo" })
    }

    private fun request(
        fingerprint: String = "source-a",
        runtime: Long = RUNTIME,
        chapters: List<MediaChapter> = emptyList(),
    ) = PlaybackSegmentRequest(
        media = MediaIdentity(
            canonicalEpisodeId = "show:s1e2",
            runtimeMs = runtime,
            fingerprint = fingerprint,
        ),
        chapters = chapters,
    )

    private fun provider(vararg markers: ProviderMarker) = object : SegmentMarkerProvider {
        override val id: String = "fake"
        override suspend fun getSegments(request: PlaybackSegmentRequest): List<ProviderMarker> = markers.toList()
    }

    private class MemoryRepository : PlaybackSegmentRepository {
        private val entries = mutableListOf<CachedSegmentAnalysis>()
        override suspend fun get(canonicalEpisodeId: String, mediaFingerprint: String, analysisVersion: Int) =
            entries.firstOrNull { it.canonicalEpisodeId == canonicalEpisodeId && it.mediaFingerprint == mediaFingerprint && it.analysisVersion == analysisVersion }

        override suspend fun put(analysis: CachedSegmentAnalysis) {
            entries.removeAll { it.canonicalEpisodeId == analysis.canonicalEpisodeId && it.mediaFingerprint == analysis.mediaFingerprint && it.analysisVersion == analysis.analysisVersion }
            entries += analysis
        }

        override suspend fun getEpisodeAnalyses(canonicalEpisodeId: String, analysisVersion: Int) =
            entries.filter { it.canonicalEpisodeId == canonicalEpisodeId && it.analysisVersion == analysisVersion }
    }

    private companion object { const val RUNTIME = 22L * 60_000L }
}
