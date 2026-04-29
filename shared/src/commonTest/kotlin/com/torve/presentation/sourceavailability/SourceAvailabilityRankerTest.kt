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
}
