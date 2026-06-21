package com.torve.domain.stats

import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStatsEngineTest {
    private val engine = WatchStatsEngine()

    @Test
    fun startedPartialAndAbandonedDoNotCountAsCompleted() {
        val summary = engine.aggregate(
            listOf(
                session("started", status = WatchSessionStatus.STARTED),
                session("partial", status = WatchSessionStatus.PARTIAL, countedWatchMs = 600_000L),
                session("abandoned", status = WatchSessionStatus.ABANDONED),
            ),
        )

        assertEquals(0, summary.completedMovies)
        assertEquals(0, summary.completedEpisodes)
        assertEquals(1, summary.startedCount)
        assertEquals(1, summary.partialCount)
        assertEquals(1, summary.abandonedCount)
        assertEquals(600_000L, summary.partialWatchMs)
    }

    @Test
    fun manualAndImportedCompletedCountButUnknownRuntimeDoesNotInflateTime() {
        val summary = engine.aggregate(
            listOf(
                session(
                    "manual",
                    status = WatchSessionStatus.MANUAL_COMPLETED,
                    source = WatchSessionSource.MANUAL,
                    countedWatchMs = 0L,
                    confidence = RuntimeConfidence.UNKNOWN,
                ),
                session(
                    "imported",
                    mediaType = MediaType.SERIES,
                    season = 1,
                    episode = 1,
                    status = WatchSessionStatus.IMPORTED_COMPLETED,
                    source = WatchSessionSource.TRAKT,
                    countedWatchMs = 0L,
                    confidence = RuntimeConfidence.UNKNOWN,
                ),
            ),
        )

        assertEquals(1, summary.completedMovies)
        assertEquals(1, summary.completedEpisodes)
        assertEquals(0L, summary.totalWatchMs)
        assertEquals(1, summary.manualCompletedCount)
        assertEquals(1, summary.importedCompletedCount)
    }

    @Test
    fun measuredAndEstimatedWatchTimeAreExposedSeparately() {
        val summary = engine.aggregate(
            listOf(
                session("measured", countedWatchMs = 3_600_000L, confidence = RuntimeConfidence.MEASURED),
                session("estimated", countedWatchMs = 1_800_000L, confidence = RuntimeConfidence.ESTIMATED),
                session("unknown", countedWatchMs = 9_999_999L, confidence = RuntimeConfidence.UNKNOWN),
            ),
        )

        assertEquals(3_600_000L, summary.measuredWatchMs)
        assertEquals(1_800_000L, summary.estimatedWatchMs)
        assertEquals(5_400_000L, summary.totalWatchMs)
    }

    @Test
    fun episodeSessionsDoNotCollapseIntoShowLevelStats() {
        val summary = engine.aggregate(
            listOf(
                session("s1e1", mediaType = MediaType.SERIES, season = 1, episode = 1),
                session("s1e2", mediaType = MediaType.SERIES, season = 1, episode = 2),
            ),
        )

        assertEquals(0, summary.completedMovies)
        assertEquals(2, summary.completedEpisodes)
    }

    @Test
    fun advancedFiltersStayUnavailableWhenMetadataIsMissing() {
        val summary = engine.aggregate(listOf(session("movie")))

        assertEquals(false, summary.advanced.availability.hasAnyMetadata)
        assertEquals(0, summary.advanced.genreGroups.size)
        assertEquals(0, summary.advanced.ratingDistribution.size)
    }

    @Test
    fun genreAttributionDoesNotChangeHeadlineWatchTime() {
        val movie = session("movie", countedWatchMs = 3_600_000L)
        val summary = engine.aggregate(
            sessions = listOf(movie),
            metadataBySessionId = mapOf(
                movie.id to WatchStatsMetadata(genres = listOf("Action", "Sci-Fi")),
            ),
        )

        assertEquals(3_600_000L, summary.totalWatchMs)
        assertEquals(2, summary.advanced.genreGroups.size)
        assertEquals(3_600_000L, summary.advanced.genreGroups.first { it.label == "Action" }.attributedWatchMs)
        assertEquals(3_600_000L, summary.advanced.genreGroups.first { it.label == "Sci-Fi" }.attributedWatchMs)
    }

    @Test
    fun actorAggregationAvoidsDuplicateSameTitleCounting() {
        val first = session("first", mediaId = "movie-1", title = "Same Movie")
        val second = session("second", mediaId = "movie-1", title = "Same Movie")
        val summary = engine.aggregate(
            sessions = listOf(first, second),
            metadataBySessionId = mapOf(
                first.id to WatchStatsMetadata(actors = listOf("Actor One", "Actor One")),
                second.id to WatchStatsMetadata(actors = listOf("Actor One")),
            ),
        )

        val actor = summary.advanced.actorGroups.single()
        assertEquals("Actor One", actor.label)
        assertEquals(2, actor.sessionCount)
        assertEquals(1, actor.titleCount)
    }

    @Test
    fun ratingDistributionIgnoresMissingRatings() {
        val rated = session("rated")
        val missing = session("missing")
        val summary = engine.aggregate(
            sessions = listOf(rated, missing),
            metadataBySessionId = mapOf(
                rated.id to WatchStatsMetadata(ratingImdb = 8.4),
                missing.id to WatchStatsMetadata(genres = listOf("Drama")),
            ),
        )

        assertEquals(1, summary.advanced.ratingDistribution.size)
        assertEquals(WatchStatsRatingBand.EXCELLENT, summary.advanced.ratingDistribution.single().band)
        assertEquals(1, summary.advanced.ratingDistribution.single().sessionCount)
    }

    @Test
    fun yearAndDecadeGroupingHandlesUnknownYearSafely() {
        val known = session("known")
        val unknown = session("unknown")
        val summary = engine.aggregate(
            sessions = listOf(known, unknown),
            metadataBySessionId = mapOf(
                known.id to WatchStatsMetadata(year = 1999),
                unknown.id to WatchStatsMetadata(genres = listOf("Mystery")),
            ),
        )

        assertEquals("1999", summary.advanced.yearGroups.single().label)
        assertEquals("1990s", summary.advanced.decadeGroups.single().label)
    }

    @Test
    fun advancedFiltersUseMetadataWithoutGlobalRefill() {
        val action = session("action")
        val drama = session("drama")
        val summary = engine.aggregate(
            sessions = listOf(action, drama),
            filters = WatchStatsFilters(genres = setOf("action")),
            metadataBySessionId = mapOf(
                action.id to WatchStatsMetadata(genres = listOf("Action")),
                drama.id to WatchStatsMetadata(genres = listOf("Drama")),
            ),
        )

        assertEquals(1, summary.recentActivity.size)
        assertEquals("action", summary.recentActivity.single().id)
    }

    @Test
    fun playbackClassificationUsesThresholdAndAbandonedRules() {
        assertEquals(
            WatchSessionStatus.ABANDONED,
            classifyPlaybackSession(maxPositionMs = 60_000L, durationMs = 7_200_000L),
        )
        assertEquals(
            WatchSessionStatus.ABANDONED,
            classifyPlaybackSession(maxPositionMs = 240_000L, durationMs = 7_200_000L),
        )
        assertEquals(
            WatchSessionStatus.PARTIAL,
            classifyPlaybackSession(maxPositionMs = 600_000L, durationMs = 7_200_000L),
        )
        assertEquals(
            WatchSessionStatus.COMPLETED,
            classifyPlaybackSession(maxPositionMs = 6_120_000L, durationMs = 7_200_000L),
        )
    }

    private fun session(
        id: String,
        mediaType: MediaType = MediaType.MOVIE,
        mediaId: String = if (mediaType == MediaType.SERIES) "show-1" else id,
        title: String = id,
        season: Int? = null,
        episode: Int? = null,
        status: WatchSessionStatus = WatchSessionStatus.COMPLETED,
        source: WatchSessionSource = WatchSessionSource.TORVE_PLAYER,
        countedWatchMs: Long = 1_000L,
        confidence: RuntimeConfidence = RuntimeConfidence.ESTIMATED,
    ): WatchSession =
        WatchSession(
            id = id,
            userId = "user",
            mediaId = mediaId,
            mediaType = mediaType,
            title = title,
            showId = if (mediaType == MediaType.SERIES) "show-1" else null,
            showTitle = if (mediaType == MediaType.SERIES) "Show" else null,
            seasonNumber = season,
            episodeNumber = episode,
            startedAt = 1L,
            source = source,
            status = status,
            countedWatchMs = countedWatchMs,
            runtimeConfidence = confidence,
            createdAt = 1L,
            updatedAt = 1L,
        )
}
