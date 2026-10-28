package com.torve.data.addon

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class StreamContinuationSelectorTest {
    private val selector = StreamContinuationSelector(reliabilityAdjustment = { 0 })

    @Test
    fun episodeGroupWinsEvenWhenNextEpisodeHasDifferentReleaseNaming() {
        val current = stream("Show.S01E10.1080p.WEB-DL.H264-GROUP", "1.4 GB", "1080p")
            .copy(bingeGroup = "variant|1080p|de", addonBaseUrl = "https://addon.example/config/", languages = listOf("DE"))
        val matching = current.copy(title = "Episode eleven", addonBaseUrl = "https://addon.example/config", size = "1.2 GB", score = 10)
        val other = current.copy(title = "Show.S02E01.1080p.WEB-DL.H264-GROUP", bingeGroup = "other", score = 100)
        val ranked = selector.rankSourcesForContinuation(listOf(other, matching), SourceProfile.from(current))
        assertEquals(matching, ranked.first().stream)
        assertEquals(ContinuationFallbackTier.SAME_EPISODE_GROUP, ranked.first().tier)
    }

    @Test
    fun episodeGroupsCannotCollideAcrossAddonConfigurationsOrCase() {
        val current = stream("Show.S01E01.1080p.H264", "1 GB", "1080p")
            .copy(bingeGroup = "HD", addonBaseUrl = "https://addon.example/config-a")
        val candidates = listOf(
            current.copy(addonBaseUrl = "https://addon.example/config-b"),
            current.copy(addonBaseUrl = "https://different.example/config-a"),
            current.copy(addonBaseUrl = null),
            current.copy(bingeGroup = "hd"),
            current.copy(bingeGroup = "  "),
        )
        assertTrue(selector.rankSourcesForContinuation(candidates, SourceProfile.from(current))
            .none { it.tier == ContinuationFallbackTier.SAME_EPISODE_GROUP })
    }

    @Test
    fun misleadingGroupCannotOverrideKnownQualityOrLanguage() {
        val current = stream("Episode 1", "1 GB", "1080p")
            .copy(bingeGroup = "default", addonBaseUrl = "https://addon.example", languages = listOf("de"))
        val changedQuality = current.copy(quality = "720p")
        val changedLanguage = current.copy(languages = listOf("en"))
        val fallback = current.copy(bingeGroup = null)
        val ranked = selector.rankSourcesForContinuation(listOf(changedQuality, changedLanguage, fallback), SourceProfile.from(current))
        assertEquals(fallback, ranked.first().stream)
        assertTrue(ranked.none { it.tier == ContinuationFallbackTier.SAME_EPISODE_GROUP })
    }

    @Test
    fun hintStillMatchesWhenReleaseMetadataIsMissing() {
        val current = stream("Episode 1", "1 GB", "1080p")
            .copy(bingeGroup = "variant", addonBaseUrl = "https://addon.example")
        val next = current.copy(title = "Episode 2", quality = "Unknown", languages = emptyList(), size = null)
        assertEquals(ContinuationFallbackTier.SAME_EPISODE_GROUP,
            selector.rankSourcesForContinuation(listOf(next), SourceProfile.from(current)).single().tier)
    }

    @Test
    fun partialResultsWaitForEpisodeGroupButFullResultsStillProvideFallback() {
        val current = stream("Show.S01E01.1080p.H264", "1 GB", "1080p")
            .copy(bingeGroup = "group", addonBaseUrl = "https://addon.example")
        val reference = SourceProfile.from(current)
        val fallback = current.copy(title = "Show.S01E02.1080p.H264", bingeGroup = null)
        val matched = fallback.copy(bingeGroup = "group")
        assertFalse(selector.canStartFromPartialResults(listOf(fallback), reference))
        assertFalse(selector.canStartFromPartialResults(listOf(matched.copy(languages = listOf("de"))), reference))
        assertTrue(selector.canStartFromPartialResults(listOf(fallback, matched), reference))
        assertTrue(selector.canStartFromPartialResults(listOf(fallback), reference.copy(bingeGroup = null)))
        assertEquals(fallback, selector.rankSourcesForContinuation(listOf(fallback), reference).first().stream)
    }

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
        val first = stream("Show.S01E02.1080p.WEB-DL.H264-GROUP", "1.4 GB", "1080p").copy(bingeGroup = "hd-en")
        val manual = stream("Show.S01E03.2160p.WEB-DL.DV.HEVC-GROUP", "5.0 GB", "4K").copy(bingeGroup = "uhd-de", languages = listOf("de"))

        session.stageResolvedSource(first, "https://video.example/episode2", origin = ContinuationSelectionOrigin.AUTOMATIC)
        session.recordPlaybackStarted("https://video.example/episode2", durationMs = 2_400_000L)
        assertEquals(1080, session.currentProfile()?.resolutionHeight)

        session.stageResolvedSource(manual, "https://video.example/episode3", origin = ContinuationSelectionOrigin.MANUAL)
        assertEquals("hd-en", session.currentProfile()?.bingeGroup, "A resolve alone must not replace the playing source")
        val updated = session.recordPlaybackStarted("https://video.example/episode3", durationMs = 2_400_000L)

        assertNotNull(updated)
        assertEquals(2160, updated.resolutionHeight)
        assertEquals("uhd-de", updated.bingeGroup)
        assertEquals(setOf("de"), updated.languages)
        assertEquals(null, session.profileForPlayback("https://video.example/episode2"))
        assertEquals(updated, session.profileForPlayback("https://video.example/episode3"))
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
