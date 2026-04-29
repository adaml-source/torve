package com.torve.desktop.ui.v2.home

import com.torve.desktop.ui.components.TorveBadgeTone
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins behaviour of [classifyWatchlistHeat] (Sprint 5 #4 — watchlist
 * heat-map badging). Today is fixed at 2026-04-25 in every test so the
 * boundary cases are unambiguous.
 */
class WatchlistHeatBadgeTest {

    private val today: LocalDate = LocalDate.of(2026, 4, 25)

    @Test
    fun `today returns Out now`() {
        val badge = classifyWatchlistHeat("2026-04-25", today)
        assertEquals(WatchlistHeatBadge("Out now", TorveBadgeTone.Success), badge)
    }

    @Test
    fun `six days ago returns Out now`() {
        val badge = classifyWatchlistHeat(today.minusDays(6).toString(), today)
        assertEquals(WatchlistHeatBadge("Out now", TorveBadgeTone.Success), badge)
    }

    @Test
    fun `seven days ago is the inclusive boundary of Out now`() {
        val badge = classifyWatchlistHeat(today.minusDays(7).toString(), today)
        assertEquals(WatchlistHeatBadge("Out now", TorveBadgeTone.Success), badge)
    }

    @Test
    fun `eight days ago downgrades to New`() {
        val badge = classifyWatchlistHeat(today.minusDays(8).toString(), today)
        assertEquals(WatchlistHeatBadge("New", TorveBadgeTone.Accent), badge)
    }

    @Test
    fun `thirty days ago is the inclusive boundary of New`() {
        val badge = classifyWatchlistHeat(today.minusDays(30).toString(), today)
        assertEquals(WatchlistHeatBadge("New", TorveBadgeTone.Accent), badge)
    }

    @Test
    fun `thirty-one days ago returns no badge`() {
        assertNull(classifyWatchlistHeat(today.minusDays(31).toString(), today))
    }

    @Test
    fun `one day in future returns Soon`() {
        val badge = classifyWatchlistHeat(today.plusDays(1).toString(), today)
        assertEquals(WatchlistHeatBadge("Soon", TorveBadgeTone.Accent), badge)
    }

    @Test
    fun `thirty days in future is the inclusive boundary of Soon`() {
        val badge = classifyWatchlistHeat(today.plusDays(30).toString(), today)
        assertEquals(WatchlistHeatBadge("Soon", TorveBadgeTone.Accent), badge)
    }

    @Test
    fun `thirty-one days in future returns no badge`() {
        assertNull(classifyWatchlistHeat(today.plusDays(31).toString(), today))
    }

    @Test
    fun `null release date returns no badge`() {
        assertNull(classifyWatchlistHeat(null, today))
    }

    @Test
    fun `blank release date returns no badge`() {
        assertNull(classifyWatchlistHeat("", today))
        assertNull(classifyWatchlistHeat("   ", today))
    }

    @Test
    fun `malformed date string returns no badge without crashing`() {
        assertNull(classifyWatchlistHeat("not-a-date", today))
        assertNull(classifyWatchlistHeat("2026-13-99", today))
        assertNull(classifyWatchlistHeat("garbage", today))
    }

    @Test
    fun `accepts ISO date prefix even when string carries trailing time component`() {
        // TMDB occasionally emits "2026-04-25T00:00:00Z"-style values.
        val badge = classifyWatchlistHeat("2026-04-25T00:00:00Z", today)
        assertEquals(WatchlistHeatBadge("Out now", TorveBadgeTone.Success), badge)
    }
}
