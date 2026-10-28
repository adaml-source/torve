package com.torve.domain.model

import kotlinx.datetime.*
import kotlin.test.*

class EpisodeReleasePolicyTest {
    private val now = Instant.parse("2026-10-08T09:00:00Z")
    private val zone = TimeZone.of("Europe/Berlin")
    private fun release(episode: Int, date: String = "2026-10-08", air: String? = null) =
        EpisodeRelease(12, "Bayern München", 2, episode, LocalDate.parse(date), air?.let(Instant::parse))
    @Test fun futureAirTimesOldDatesAndAlreadySeenEpisodesNeverAlert() {
        val pending = pendingEpisodeReleases(listOf(release(1), release(2), release(3),
            release(4, "2026-10-09"), release(5, "2026-07-01"), release(6, air = "2026-10-08T20:00:00Z")),
            setOf("12:2:1"), setOf("12:2:2"), now, zone)
        assertEquals(listOf(3), pending.map { it.episode })
    }
    @Test fun aSeasonDropIsDeduplicatedAndASecondRunCannotRepeatIt() {
        val batch = listOf(release(1), release(1), release(2), release(3))
        val first = pendingEpisodeReleases(batch, emptySet(), emptySet(), now, zone)
        assertEquals(3, first.size)
        assertTrue(pendingEpisodeReleases(batch, emptySet(), first.map { it.key }.toSet(), now, zone).isEmpty())
    }
    @Test fun dateOnlyReleasesUseTheUsersDayRatherThanUtc() {
        val midnight = Instant.parse("2026-10-07T22:05:00Z")
        assertEquals(1, pendingEpisodeReleases(listOf(release(1)), emptySet(), emptySet(), midnight, zone).size)
        assertTrue(pendingEpisodeReleases(listOf(release(1)), emptySet(), emptySet(), midnight, TimeZone.UTC).isEmpty())
    }
    @Test fun invalidIdentityAndUnknownDatesDoNotGenerateFalseAlerts() {
        assertTrue(pendingEpisodeReleases(listOf(release(1).copy(showId = 0), release(0), release(2).copy(date = null)),
            emptySet(), emptySet(), now, zone).isEmpty())
    }
    @Test fun knownAirTimeOverridesDateOnlyAndHandlesYesterday() {
        val pending = pendingEpisodeReleases(listOf(release(1, "2026-10-07", "2026-10-07T20:00:00Z"),
            release(2, "2026-10-06", "2026-10-06T20:00:00Z")), emptySet(), emptySet(), now, zone)
        assertEquals(listOf(1), pending.map { it.episode })
    }
}
