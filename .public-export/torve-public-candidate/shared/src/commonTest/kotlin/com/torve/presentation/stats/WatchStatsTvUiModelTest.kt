package com.torve.presentation.stats

import com.torve.domain.model.MediaType
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.WatchStatsAdvancedAvailability
import com.torve.domain.stats.WatchStatsAdvancedGroup
import com.torve.domain.stats.WatchStatsAdvancedSection
import com.torve.domain.stats.WatchStatsAdvancedSummary
import com.torve.domain.stats.WatchStatsRatingBand
import com.torve.domain.stats.WatchStatsRatingGroup
import com.torve.domain.stats.WatchStatsRuntimeConfidenceBreakdown
import com.torve.domain.stats.WatchStatsSourceBreakdown
import com.torve.domain.stats.WatchStatsSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchStatsTvUiModelTest {
    @Test
    fun sectionChipsHaveStableReadableIds() {
        val sections = WatchStatsTvUiModel.sectionItems()

        assertEquals(
            listOf(
                "section_overview",
                "section_time",
                "section_completed",
                "section_sources",
                "section_activity",
                "section_advanced",
            ),
            sections.map { it.id },
        )
        assertFalse(WatchStatsTvUiModel.hasDuplicateIds(sections))
        assertTrue(sections.all { it.label.length >= 4 })
    }

    @Test
    fun overviewCardsUseStableIdsAndNoEllipsisLabels() {
        val cards = WatchStatsTvUiModel.overviewCards(
            summary = WatchStatsSummary(
                totalWatchMs = 25_200_000L,
                measuredWatchMs = 0L,
                estimatedWatchMs = 25_200_000L,
                completedMovies = 1,
                partialCount = 10,
                sourceBreakdown = listOf(
                    WatchStatsSourceBreakdown(WatchSessionSource.TRAKT, 3, 10_000L),
                    WatchStatsSourceBreakdown(WatchSessionSource.MANUAL, 1, 0L),
                ),
                runtimeConfidenceBreakdown = listOf(
                    WatchStatsRuntimeConfidenceBreakdown(RuntimeConfidence.ESTIMATED, 4, 25_200_000L),
                ),
            ),
            isPremium = true,
        )

        assertEquals(
            listOf("stat_time", "stat_completed", "stat_sources", "stat_truthful"),
            cards.map { it.id },
        )
        assertTrue(cards.none { it.title.contains("...") || it.value.contains("...") })
        assertTrue(cards.none { it.value == "0h watched" || it.subtitle.contains("0h watched") })
        assertEquals("Trakt + Manual", cards.single { it.id == "stat_sources" }.value)
    }

    @Test
    fun activityRowsUseSessionIdsInsteadOfTitlesOrIndexes() {
        val sessions = listOf(
            session(id = "first", title = "Same Title"),
            session(id = "second", title = "Same Title"),
        )
        val rows = WatchStatsTvUiModel.activityItems(sessions)

        assertEquals(listOf("activity_first", "activity_second"), rows.map { it.id })
        assertFalse(WatchStatsTvUiModel.hasDuplicateIds(rows))
    }

    @Test
    fun contentScopeAndInsightModeAreIndependent() {
        val summary = WatchStatsSummary(
            totalWatchMs = 3_600_000L,
            measuredWatchMs = 1_200_000L,
            estimatedWatchMs = 2_400_000L,
            completedMovies = 2,
            completedEpisodes = 8,
            partialCount = 3,
            importedCompletedCount = 5,
            sourceBreakdown = listOf(
                WatchStatsSourceBreakdown(WatchSessionSource.TRAKT, 5, 2_400_000L),
                WatchStatsSourceBreakdown(WatchSessionSource.TORVE_PLAYER, 1, 1_200_000L),
            ),
        )

        val allSource = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.ALL,
            insightMode = WatchStatsInsightMode.SOURCE,
            summary = summary,
            isPremium = true,
        )
        val movieSource = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.MOVIES,
            insightMode = WatchStatsInsightMode.SOURCE,
            summary = summary,
            isPremium = true,
        )
        val showSource = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.SHOWS,
            insightMode = WatchStatsInsightMode.SOURCE,
            summary = summary,
            isPremium = true,
        )
        val movieOverview = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.MOVIES,
            insightMode = WatchStatsInsightMode.OVERVIEW,
            summary = summary,
            isPremium = true,
        )

        assertEquals("All source breakdown", allSource.title)
        assertEquals("Movie source breakdown", movieSource.title)
        assertEquals("Show source breakdown", showSource.title)
        assertTrue(movieSource.metricCards.any { it.id == "stat_source_dominant" })
        assertTrue(movieOverview.metricCards.any { it.id == "stat_movie_completed" })
        assertFalse(movieSource.metricCards.map { it.id } == movieOverview.metricCards.map { it.id })
    }

    @Test
    fun ratingsModeUsesUnavailableStateWhenRatingMetadataIsMissing() {
        val model = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.ALL,
            insightMode = WatchStatsInsightMode.RATINGS,
            summary = WatchStatsSummary(),
            isPremium = true,
        )

        assertEquals("All ratings", model.title)
        assertTrue(model.isUnavailable)
        assertEquals("Ratings unavailable", model.unavailableTitle)
    }

    @Test
    fun genreModeBecomesAvailableWhenLocalGenreMetadataExists() {
        val model = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.MOVIES,
            insightMode = WatchStatsInsightMode.GENRES,
            summary = WatchStatsSummary(
                advanced = WatchStatsAdvancedSummary(
                    availability = WatchStatsAdvancedAvailability(hasGenres = true),
                    genreGroups = listOf(
                        WatchStatsAdvancedGroup(
                            section = WatchStatsAdvancedSection.GENRE,
                            key = "drama",
                            label = "Drama",
                            sessionCount = 2,
                            titleCount = 1,
                            completedCount = 1,
                            partialCount = 1,
                            attributedWatchMs = 7_200_000L,
                        ),
                    ),
                ),
            ),
            isPremium = true,
        )

        assertFalse(model.isUnavailable)
        assertEquals("Movie genre insights", model.title)
        assertTrue(model.metricCards.any { it.value == "Drama" })
    }

    @Test
    fun metadataModeStillExposesLockedDashboardState() {
        val model = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.SHOWS,
            insightMode = WatchStatsInsightMode.RATINGS,
            summary = WatchStatsSummary(
                advanced = WatchStatsAdvancedSummary(
                    ratingDistribution = listOf(
                        WatchStatsRatingGroup(
                            band = WatchStatsRatingBand.EXCELLENT,
                            sessionCount = 1,
                            titleCount = 1,
                            attributedWatchMs = 5_400_000L,
                        ),
                    ),
                ),
            ),
            isPremium = false,
        )

        assertTrue(model.isLocked)
        assertEquals("Deeper watch insights", model.lockedTitle)
        assertFalse(model.isUnavailable)
    }

    @Test
    fun actorDirectorStudioModesAreNotExposedAsTvInsightChips() {
        val labels = WatchStatsTvUiModel.insightModeItems().map { it.label }

        assertEquals(listOf("Overview", "Source", "Ratings", "Genres", "Years", "Activity"), labels)
        assertFalse(labels.contains("Actors"))
        assertFalse(labels.contains("Directors"))
        assertFalse(labels.contains("Studios"))
    }

    @Test
    fun practicalTvFilterRowExposesOnlyExpectedStableIds() {
        val filters = WatchStatsTvUiModel.filterItems()

        assertEquals(
            listOf(
                "filter_all",
                "filter_movies",
                "filter_shows",
                "filter_insight_all",
                "filter_source",
                "filter_ratings",
                "filter_genres",
                "filter_years",
                "filter_activity",
            ),
            filters.map { it.id },
        )
        assertEquals(
            listOf("All", "Movies", "Shows", "All", "Source", "Ratings", "Genres", "Years", "Activity"),
            filters.map { it.label },
        )
        assertFalse(WatchStatsTvUiModel.hasDuplicateIds(filters))
        assertFalse(filters.any { it.label in listOf("Actors", "Directors", "Studios") })
    }

    @Test
    fun movieAndShowOverviewExposeScopedPrimaryMetrics() {
        val summary = WatchStatsSummary(
            totalWatchMs = 7_200_000L,
            estimatedWatchMs = 7_200_000L,
            completedMovies = 1,
            completedEpisodes = 99,
            partialCount = 10,
        )

        val movieModel = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.MOVIES,
            insightMode = WatchStatsInsightMode.OVERVIEW,
            summary = summary,
            isPremium = true,
        )
        val showModel = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.SHOWS,
            insightMode = WatchStatsInsightMode.OVERVIEW,
            summary = summary,
            isPremium = true,
        )

        assertEquals("stat_movie_time", movieModel.metricCards.first().id)
        assertTrue(movieModel.metricCards.any { it.id == "stat_movie_completed" })
        assertFalse(movieModel.metricCards.any { it.id == "stat_show_completed" })
        assertEquals("stat_show_time", showModel.metricCards.first().id)
        assertTrue(showModel.metricCards.any { it.id == "stat_show_completed" })
        assertFalse(showModel.metricCards.any { it.id == "stat_movie_completed" })
    }

    @Test
    fun sourceModeExposesSourceVisualTitles() {
        val model = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.ALL,
            insightMode = WatchStatsInsightMode.SOURCE,
            summary = WatchStatsSummary(
                sourceBreakdown = listOf(
                    WatchStatsSourceBreakdown(WatchSessionSource.TRAKT, 3, 7_200_000L),
                ),
            ),
            isPremium = true,
        )

        assertEquals("Source split", model.primaryTitle)
        assertEquals("Runtime confidence", model.secondaryTitle)
        assertTrue(model.metricCards.any { it.title == "Dominant source" })
    }

    @Test
    fun yearsModeUsesScopeSpecificLabels() {
        val movieYears = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.MOVIES,
            insightMode = WatchStatsInsightMode.YEARS,
            summary = WatchStatsSummary(),
            isPremium = true,
        )
        val showYears = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.SHOWS,
            insightMode = WatchStatsInsightMode.YEARS,
            summary = WatchStatsSummary(),
            isPremium = true,
        )

        assertEquals("Movie release years", movieYears.title)
        assertEquals("Show year insights", showYears.title)
        assertTrue(movieYears.isUnavailable)
        assertTrue(showYears.isUnavailable)
    }

    @Test
    fun overviewExposesHeroInsightModel() {
        val model = WatchStatsTvUiModel.dashboardModel(
            contentScope = WatchStatsContentScope.ALL,
            insightMode = WatchStatsInsightMode.OVERVIEW,
            summary = WatchStatsSummary(totalWatchMs = 7_200_000L, estimatedWatchMs = 7_200_000L),
            isPremium = true,
        )

        assertEquals("All watch stats", model.title)
        assertEquals("hero_time", model.heroInsight?.id)
        assertTrue(model.heroInsight?.chips?.contains("Unknown excluded") == true)
    }

    private fun session(
        id: String,
        title: String,
    ): WatchSession =
        WatchSession(
            id = id,
            userId = "u1",
            mediaId = "m1",
            mediaType = MediaType.MOVIE,
            title = title,
            startedAt = 1L,
            source = WatchSessionSource.TRAKT,
            status = WatchSessionStatus.IMPORTED_COMPLETED,
            countedWatchMs = 1L,
            runtimeConfidence = RuntimeConfidence.ESTIMATED,
            createdAt = 1L,
            updatedAt = 1L,
        )
}
