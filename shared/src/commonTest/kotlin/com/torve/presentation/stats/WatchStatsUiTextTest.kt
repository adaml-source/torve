package com.torve.presentation.stats

import com.torve.domain.model.MediaType
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.WatchStatsRuntimeConfidenceBreakdown
import com.torve.domain.stats.WatchStatsSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStatsUiTextTest {
    @Test
    fun aboutCardDoesNotShowZeroHoursForEmptyStats() {
        val copy = WatchStatsUiText.aboutCard(WatchStatsSummary())

        assertEquals("Watch Stats", copy.title)
        assertEquals("Start watching to build your stats", copy.body)
        assertEquals("Open full stats", copy.cta)
    }

    @Test
    fun aboutCardUsesRebuildingCopyForUnknownRuntimeHistory() {
        val copy = WatchStatsUiText.aboutCard(
            WatchStatsSummary(
                recentActivity = listOf(session(confidence = RuntimeConfidence.UNKNOWN)),
                runtimeConfidenceBreakdown = listOf(
                    WatchStatsRuntimeConfidenceBreakdown(
                        confidence = RuntimeConfidence.UNKNOWN,
                        sessionCount = 1,
                        countedWatchMs = 0L,
                    ),
                ),
            ),
        )

        assertEquals("History available - watch time being rebuilt", copy.body)
    }

    @Test
    fun aboutCardShowsCompletedAndKnownWatchTimeWhenAvailable() {
        val copy = WatchStatsUiText.aboutCard(
            WatchStatsSummary(
                totalWatchMs = 6_300_000L,
                completedMovies = 2,
                completedEpisodes = 1,
                recentActivity = listOf(session(countedWatchMs = 6_300_000L)),
            ),
        )

        assertEquals("3 completed - 1h 45m watched", copy.body)
    }

    @Test
    fun sourceStatusAndConfidenceLabelsAreClosedUiCopy() {
        assertEquals("Torve", WatchStatsUiText.sourceLabel(WatchSessionSource.TORVE_PLAYER))
        assertEquals("Manual completed", WatchStatsUiText.statusLabel(WatchSessionStatus.MANUAL_COMPLETED))
        assertEquals("Unknown runtime", WatchStatsUiText.confidenceLabel(RuntimeConfidence.UNKNOWN))
    }

    private fun session(
        countedWatchMs: Long = 0L,
        confidence: RuntimeConfidence = RuntimeConfidence.ESTIMATED,
    ): WatchSession =
        WatchSession(
            id = "s1",
            userId = "u1",
            mediaId = "m1",
            mediaType = MediaType.MOVIE,
            title = "Movie",
            startedAt = 1L,
            source = WatchSessionSource.MIGRATED_HISTORY,
            status = WatchSessionStatus.PARTIAL,
            countedWatchMs = countedWatchMs,
            runtimeConfidence = confidence,
            createdAt = 1L,
            updatedAt = 1L,
        )
}
