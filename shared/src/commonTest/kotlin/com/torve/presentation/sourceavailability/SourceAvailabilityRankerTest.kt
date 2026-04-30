package com.torve.presentation.sourceavailability

import com.torve.domain.model.MediaType
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilityRecord
import com.torve.domain.sourceavailability.SourceAvailabilitySignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceAvailabilityRankerTest {

    private data class FakeItem(val tmdbId: Int, val title: String)

    private fun record(tmdbId: Int, vararg signals: SourceAvailabilitySignal): SourceAvailabilityRecord =
        SourceAvailabilityRecord(tmdbId = tmdbId, mediaType = MediaType.MOVIE, signals = signals.toList())

    private val downloaded = SourceAvailabilitySignal(
        SourceAvailabilityKind.LOCAL_DOWNLOAD, "Downloaded", SourceAvailabilityRankBoost.LOCAL_DOWNLOAD,
    )
    private val plex = SourceAvailabilitySignal(
        SourceAvailabilityKind.PLEX, "In Plex", SourceAvailabilityRankBoost.PLEX,
    )
    private val jellyfin = SourceAvailabilitySignal(
        SourceAvailabilityKind.JELLYFIN, "In Jellyfin", SourceAvailabilityRankBoost.JELLYFIN,
    )

    @Test
    fun `available items come before unavailable ones`() {
        val items = listOf(FakeItem(1, "A"), FakeItem(2, "B"), FakeItem(3, "C"))
        val records = mapOf(2 to record(2, plex))
        val out = SourceAvailabilityRanker.rerank(items, records) { it.tmdbId }
        assertEquals(listOf(2, 1, 3), out.map { it.item.tmdbId })
    }

    @Test
    fun `higher score wins inside the available group`() {
        val items = listOf(FakeItem(10, "x"), FakeItem(20, "y"), FakeItem(30, "z"))
        val records = mapOf(
            10 to record(10, jellyfin),
            20 to record(20, downloaded),
            30 to record(30, plex),
        )
        val out = SourceAvailabilityRanker.rerank(items, records) { it.tmdbId }
        // 20 (Downloaded=300) wins. 10 and 30 tie at 200 → input order 10, 30.
        assertEquals(listOf(20, 10, 30), out.map { it.item.tmdbId })
    }

    @Test
    fun `ties inside the available group fall back to original order`() {
        val items = listOf(FakeItem(1, "a"), FakeItem(2, "b"))
        val records = mapOf(
            1 to record(1, plex),
            2 to record(2, jellyfin),
        )
        val out = SourceAvailabilityRanker.rerank(items, records) { it.tmdbId }
        // Both score 200; stable sort keeps input order.
        assertEquals(listOf(1, 2), out.map { it.item.tmdbId })
    }

    @Test
    fun `unavailable items keep their original order at the bottom`() {
        val items = listOf(FakeItem(5, "a"), FakeItem(6, "b"), FakeItem(7, "c"))
        val out = SourceAvailabilityRanker.rerank(items, emptyMap()) { it.tmdbId }
        assertEquals(listOf(5, 6, 7), out.map { it.item.tmdbId })
        assertTrue(out.none { it.isAvailable })
    }

    @Test
    fun `null tmdbId means unavailable`() {
        val items = listOf(FakeItem(0, "no-id"))
        val out = SourceAvailabilityRanker.rerank(items, mapOf(99 to record(99, plex))) { null }
        assertEquals(1, out.size)
        assertNull(out.single().record)
        assertTrue(!out.single().isAvailable)
    }

    @Test
    fun `primaryBadge picks the highest-rank signal`() {
        val rec = record(42, jellyfin, downloaded, plex)
        val out = SourceAvailabilityRanker.rerank(
            listOf(FakeItem(42, "x")),
            mapOf(42 to rec),
        ) { it.tmdbId }
        assertEquals("Downloaded", out.single().primaryBadge)
    }

    // ── Prompt 8: new-kind ordering ────────────────────────────────

    private val debridCache = SourceAvailabilitySignal(
        SourceAvailabilityKind.DEBRID_CACHE, "Cached", SourceAvailabilityRankBoost.DEBRID_CACHE,
    )
    private val addonSrc = SourceAvailabilitySignal(
        SourceAvailabilityKind.STREMIO_ADDON, "Addon source", SourceAvailabilityRankBoost.STREMIO_ADDON,
    )
    private val usenetReady = SourceAvailabilitySignal(
        SourceAvailabilityKind.USENET_READY, "Usenet ready", SourceAvailabilityRankBoost.USENET_READY,
    )
    private val iptvLive = SourceAvailabilitySignal(
        SourceAvailabilityKind.IPTV_LIVE, "On now: BBC", SourceAvailabilityRankBoost.IPTV_LIVE,
    )
    private val watched = SourceAvailabilitySignal(
        SourceAvailabilityKind.WATCH_HISTORY, "Watched", SourceAvailabilityRankBoost.WATCH_HISTORY,
    )

    @Test
    fun `prompt 8 ordering — local download beats every Phase-3 source`() {
        val items = listOf(
            FakeItem(1, "addon"),
            FakeItem(2, "debrid"),
            FakeItem(3, "local"),
            FakeItem(4, "usenet"),
            FakeItem(5, "iptv"),
        )
        val records = mapOf(
            1 to record(1, addonSrc),
            2 to record(2, debridCache),
            3 to record(3, downloaded),
            4 to record(4, usenetReady),
            5 to record(5, iptvLive),
        )
        val out = SourceAvailabilityRanker.rerank(items, records) { it.tmdbId }
        assertEquals(listOf(3, 2, 1, 4, 5), out.map { it.item.tmdbId })
    }

    @Test
    fun `available sources beat generic TMDB results`() {
        // 5 plain TMDB rows + 1 with a debrid-cache hit. The hit must
        // surface to the top regardless of the original index — this
        // is the headline acceptance criterion for Prompt 8.
        val items = (1..5).map { FakeItem(it, "tmdb-$it") } + FakeItem(99, "debrid-only")
        val records = mapOf(99 to record(99, debridCache))
        val out = SourceAvailabilityRanker.rerank(items, records) { it.tmdbId }
        assertEquals(99, out.first().item.tmdbId)
        assertEquals("Cached", out.first().primaryBadge)
    }

    @Test
    fun `watch history alone surfaces the row but lowest among available`() {
        val items = listOf(FakeItem(1, "watched-only"), FakeItem(2, "iptv-only"))
        val records = mapOf(
            1 to record(1, watched),
            2 to record(2, iptvLive),
        )
        val out = SourceAvailabilityRanker.rerank(items, records) { it.tmdbId }
        // IPTV (130) > Watched (50) → 2 first.
        assertEquals(listOf(2, 1), out.map { it.item.tmdbId })
        // Both are available — watched is still surfaced, just last.
        assertTrue(out.all { it.isAvailable })
    }

    @Test
    fun `multiple signals on one item — primary badge picks the strongest`() {
        // Same row has both debrid-cache and addon-source signals;
        // primary badge is the higher boost.
        val rec = record(7, addonSrc, debridCache, watched)
        val out = SourceAvailabilityRanker.rerank(listOf(FakeItem(7, "x")), mapOf(7 to rec)) { it.tmdbId }
        assertEquals("Cached", out.single().primaryBadge)
    }
}
