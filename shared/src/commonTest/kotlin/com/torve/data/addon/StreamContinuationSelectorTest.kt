package com.torve.data.addon

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamContinuationSelectorTest {
    private val selector = StreamContinuationSelector(reliabilityAdjustment = { 0 })

    @AfterTest
    fun tearDown() {
        SourceContinuationSessionStore.session.clear()
    }

    @Test
    fun sameReleaseFamilyWinsAcrossEpisodeNumbers() {
        val current = stream(
            title = "The.Big.Bang.Theory.S05E12.1080p.WEB-DL.DDP5.1.H264-GROUP-A",
            size = "1.40 GB",
            quality = "1080p",
        )
        val expected = stream(
            title = "The.Big.Bang.Theory.S05E13.1080p.WEB-DL.DDP5.1.H264-GROUP-A",
            size = "1.38 GB",
            quality = "1080p",
        )
        val candidates = listOf(
            stream("Other.Release.S05E13.1080p.WEBRip.x264-RANDOM", "350 MB", "1080p", score = 99),
            stream("The.Big.Bang.Theory.S05E13.720p.WEB-DL.DDP5.1.H264-GROUP-A", "1.20 GB", "720p", score = 99),
            expected,
        )

        val ranked = selector.rankSourcesForContinuation(candidates, SourceProfile.from(current))

        assertEquals(expected, ranked.first().stream)
        assertEquals(ContinuationFallbackTier.SAME_RELEASE_FAMILY, ranked.first().tier)
        assertTrue(ranked.first().factors.any { it.label == "same release family" })
    }

    @Test
    fun substantialSizeDegradationCannotBeatComparableSource() {
        val reference = SourceProfile.from(
            stream("Show.S01E01.1080p.WEB-DL.H264-GROUP", "1.40 GB", "1080p"),
        )
        val comparable = stream("Show.S01E02.1080p.WEB-DL.H264-GROUP", "1.30 GB", "1080p", score = 45)
        val tiny = stream("Show.S01E02.1080p.WEB-DL.H264-GROUP", "350 MB", "1080p", score = 100)

        val ranked = selector.rankSourcesForContinuation(listOf(tiny, comparable), reference)

        assertEquals(comparable, ranked.first().stream)
        assertTrue(ranked.last().factors.any { it.label.startsWith("severe size degradation") })
    }

    @Test
    fun sameResolutionIsTriedBeforeHighlyScoredLowerResolution() {
        val reference = SourceProfile.from(
            stream("Show.S01E01.1080p.WEB-DL.H264-GROUP", "1.40 GB", "1080p"),
        )
        val fullHd = stream("Other.Show.S01E02.1080p.WEBRip.H264-X", "900 MB", "1080p", score = 10)
        val hd = stream("Show.S01E02.720p.WEB-DL.H264-GROUP", "1.30 GB", "720p", score = 100)

        val ranked = selector.rankSourcesForContinuation(listOf(hd, fullHd), reference)

        assertEquals(fullHd, ranked.first().stream)
        assertTrue(ranked.first().tier.priority < ranked.last().tier.priority)
    }

    @Test
    fun failedFirstCandidateAdvancesToSecondWithoutRetryLoop() {
        val first = stream("Show.S01E02.1080p.WEB-DL-GROUP", "1.3 GB", "1080p")
        val second = stream("Show.S01E02.1080p.WEBRip-OTHER", "1.1 GB", "1080p")
        val plan = ContinuationRetryPlan(listOf(first, second))

        assertEquals(first, plan.nextCandidate())
        plan.markFailed(first)
        assertEquals(second, plan.nextCandidate())
        plan.markFailed(second)
        assertEquals(null, plan.nextCandidate())
        assertEquals(0, plan.remainingCount())
    }

    @Test
    fun bestLowerResolutionIsUsedWhenNoComparable4kExists() {
        val reference = SourceProfile.from(
            stream("Show.S01E01.2160p.WEB-DL.DV.HEVC-GROUP", "5.0 GB", "4K"),
        )
        val weak = stream("Show.S01E02.1080p.WEBRip.H264-OTHER", "700 MB", "1080p", score = 80)
        val best = stream("Show.S01E02.1080p.WEB-DL.HEVC-GROUP", "2.2 GB", "1080p", score = 65)

        val ranked = selector.rankSourcesForContinuation(listOf(weak, best), reference)

        assertEquals(best, ranked.first().stream)
        assertEquals(ContinuationFallbackTier.NEAREST_LOWER_RESOLUTION, ranked.first().tier)
    }

    @Test
    fun confirmedManualSourceChangeReplacesAutoplayBaseline() {
        val session = SourceContinuationSession()
        val first = stream("Show.S01E02.1080p.WEB-DL.H264-GROUP", "1.4 GB", "1080p")
        val manual = stream("Show.S01E03.2160p.WEB-DL.DV.HEVC-GROUP", "5.0 GB", "4K")

        session.stageResolvedSource(first, "https://video.example/episode2", origin = ContinuationSelectionOrigin.AUTOMATIC)
        session.recordPlaybackStarted("https://video.example/episode2", durationMs = 2_400_000L)
        assertEquals(1080, session.currentProfile()?.resolutionHeight)

        session.stageResolvedSource(manual, "https://video.example/episode3", origin = ContinuationSelectionOrigin.MANUAL)
        val updated = session.recordPlaybackStarted("https://video.example/episode3", durationMs = 2_400_000L)

        assertNotNull(updated)
        assertEquals(2160, updated.resolutionHeight)
        assertEquals("dolby-vision", updated.dynamicRange)
        assertEquals(parseStreamSizeBytes("5.0 GB"), updated.sizeBytes)
    }

    @Test
    fun releaseNormalizationSurvivesSeasonBoundaryAndEpisodeTitle() {
        val seasonFinale = SourceProfile.from(
            stream("Show.Name.S01E10.The.Finale.1080p.WEB-DL.DDP5.1.H264-GROUP", "1.4 GB", "1080p"),
        )
        val premiere = SourceProfile.from(
            stream("Show.Name.S02E01.A.New.Start.1080p.WEB-DL.DDP5.1.H264-GROUP", "1.5 GB", "1080p"),
        )

        assertEquals(seasonFinale.releaseFamily, premiere.releaseFamily)
        assertEquals("show name", seasonFinale.seriesStem)
        assertEquals("group", seasonFinale.releaseGroup)
    }

    @Test
    fun recentlyFailingSourceIsPenalizedWithoutBeingBlacklisted() {
        val session = SourceContinuationSession()
        val failed = stream("Show.S01E02.1080p.WEB-DL.H264-GROUP", "1.3 GB", "1080p").copy(addonName = "Flaky")
        val reliable = failed.copy(addonName = "Reliable")
        session.recordOutcome(failed, ContinuationPlaybackOutcome.PLAYBACK_ERROR)
        session.recordOutcome(failed, ContinuationPlaybackOutcome.STARTUP_TIMEOUT)
        session.recordOutcome(reliable, ContinuationPlaybackOutcome.STARTED)
        val reliabilityAwareSelector = StreamContinuationSelector(session::reliabilityAdjustment)
        val reference = SourceProfile.from(
            stream("Show.S01E01.1080p.WEB-DL.H264-GROUP", "1.4 GB", "1080p"),
        )

        val ranked = reliabilityAwareSelector.rankSourcesForContinuation(listOf(failed, reliable), reference)

        assertEquals(reliable, ranked.first().stream)
        assertTrue(ranked.last().factors.any { it.label == "recent failures" })
        assertEquals(2, ranked.size)
    }

    private fun stream(
        title: String,
        size: String,
        quality: String,
        score: Int = 60,
    ) = ParsedStream(
        addonName = "Panda",
        quality = quality,
        title = title,
        size = size,
        codec = when {
            title.contains("HEVC", ignoreCase = true) -> "HEVC"
            title.contains("H264", ignoreCase = true) -> "H264"
            else -> null
        },
        hdr = when {
            title.contains("DV", ignoreCase = true) -> "DV"
            title.contains("HDR", ignoreCase = true) -> "HDR"
            else -> null
        },
        audioCodec = if (title.contains("DDP", ignoreCase = true)) "DDP" else null,
        languages = listOf("EN"),
        source = "real-debrid",
        isCached = true,
        score = score,
    )
}
