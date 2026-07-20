package com.torve.presentation.tvhome

import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilityRecord
import com.torve.domain.sourceavailability.SourceAvailabilitySignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvHomeRailsBuilderTest {

    private fun item(tmdbId: Int, title: String): MediaItem = MediaItem(
        id = "movie:$tmdbId",
        tmdbId = tmdbId,
        type = MediaType.MOVIE,
        title = title,
    )

    private fun signal(
        kind: SourceAvailabilityKind,
        boost: Int = 100,
    ): SourceAvailabilitySignal = SourceAvailabilitySignal(
        kind = kind, badge = kind.name, rankBoost = boost,
    )

    private fun record(tmdbId: Int, vararg signals: SourceAvailabilitySignal): SourceAvailabilityRecord =
        SourceAvailabilityRecord(tmdbId = tmdbId, mediaType = MediaType.MOVIE, signals = signals.toList())

    private fun health(category: ProviderHealthCategory, status: ProviderHealthStatus): ProviderHealthEntry =
        ProviderHealthEntry(
            category = category,
            providerKey = category.name,
            label = category.name,
            status = status,
        )

    // ── availableNow ────────────────────────────────────────────────

    @Test
    fun `availableNow returns only items with a real playback signal`() {
        val items = listOf(item(1, "A"), item(2, "B"), item(3, "C"))
        val records = mapOf(
            // "B" has only watch-history → not playable.
            2 to record(2, signal(SourceAvailabilityKind.WATCH_HISTORY, SourceAvailabilityRankBoost.WATCH_HISTORY)),
            // "C" has a real LOCAL_DOWNLOAD path.
            3 to record(3, signal(SourceAvailabilityKind.LOCAL_DOWNLOAD, SourceAvailabilityRankBoost.LOCAL_DOWNLOAD)),
        )
        val out = TvHomeRailsBuilder.availableNow(items, records)
        assertEquals(listOf(3), out.map { it.tmdbId })
    }

    @Test
    fun `availableNow preserves caller order among playable items`() {
        val items = listOf(item(2, "Two"), item(1, "One"))
        val records = mapOf(
            1 to record(1, signal(SourceAvailabilityKind.PLEX, SourceAvailabilityRankBoost.PLEX)),
            2 to record(2, signal(SourceAvailabilityKind.LOCAL_DOWNLOAD, SourceAvailabilityRankBoost.LOCAL_DOWNLOAD)),
        )
        val out = TvHomeRailsBuilder.availableNow(items, records)
        // Caller-order preserved — TvHomeRailsBuilder is filter-only,
        // not a re-ranker. The caller does ordering separately.
        assertEquals(listOf(2, 1), out.map { it.tmdbId })
    }

    @Test
    fun `availableNow returns empty when no candidates have records`() {
        val items = listOf(item(1, "A"))
        assertEquals(emptyList(), TvHomeRailsBuilder.availableNow(items, emptyMap()))
    }

    // ── downloadsOnDesktop ──────────────────────────────────────────

    @Test
    fun `downloadsOnDesktop returns titles present in the LAN set`() {
        val items = listOf(item(1, "Sherlock"), item(2, "Severance"), item(3, "The Bear"))
        val lanTitles = setOf("sherlock", "the bear")
        val out = TvHomeRailsBuilder.downloadsOnDesktop(items, lanTitles)
        assertEquals(listOf(1, 3), out.map { it.tmdbId })
    }

    @Test
    fun `downloadsOnDesktop is case- and whitespace-insensitive`() {
        val items = listOf(item(1, "  Sherlock  "), item(2, "SEVERANCE"))
        val lanTitles = setOf("sherlock", "severance")
        val out = TvHomeRailsBuilder.downloadsOnDesktop(items, lanTitles)
        assertEquals(2, out.size)
    }

    @Test
    fun `downloadsOnDesktop empty when no LAN matches`() {
        val items = listOf(item(1, "Sherlock"))
        assertEquals(emptyList(), TvHomeRailsBuilder.downloadsOnDesktop(items, emptySet()))
    }

    // ── providerBanner ─────────────────────────────────────────────

    @Test
    fun `providerBanner returns null when everything is green`() {
        val rows = listOf(
            health(ProviderHealthCategory.DEBRID, ProviderHealthStatus.GREEN),
            health(ProviderHealthCategory.PLEX_JELLYFIN, ProviderHealthStatus.GREEN),
        )
        assertNull(TvHomeRailsBuilder.providerBanner(rows))
    }

    @Test
    fun `providerBanner reports red count over yellow when both present`() {
        val rows = listOf(
            health(ProviderHealthCategory.DEBRID, ProviderHealthStatus.RED),
            health(ProviderHealthCategory.IPTV, ProviderHealthStatus.YELLOW),
        )
        val banner = TvHomeRailsBuilder.providerBanner(rows)
        assertEquals(TvProviderBannerTone.ERROR, banner?.tone)
        assertEquals(1, banner?.redCount)
        assertEquals(1, banner?.yellowCount)
    }

    @Test
    fun `providerBanner reports warning when only yellow rows exist`() {
        val rows = listOf(
            health(ProviderHealthCategory.IPTV, ProviderHealthStatus.YELLOW),
            health(ProviderHealthCategory.DEBRID, ProviderHealthStatus.GREEN),
        )
        val banner = TvHomeRailsBuilder.providerBanner(rows)
        assertEquals(TvProviderBannerTone.WARNING, banner?.tone)
        assertEquals(1, banner?.yellowCount)
        assertEquals(0, banner?.redCount)
    }

    @Test
    fun `providerBanner pluralizes the title when multiple red`() {
        val rows = listOf(
            health(ProviderHealthCategory.DEBRID, ProviderHealthStatus.RED),
            health(ProviderHealthCategory.IPTV, ProviderHealthStatus.RED),
        )
        val banner = TvHomeRailsBuilder.providerBanner(rows)
        assertTrue(banner?.title?.contains("2 providers") == true)
    }

    // ── onNow ───────────────────────────────────────────────────────

    private fun ch(name: String, currentProgramme: EpgProgramme?): EnrichedChannel =
        EnrichedChannel(
            channel = Channel(name = name, url = "http://stream/$name"),
            currentProgramme = currentProgramme,
        )

    private fun prog(title: String): EpgProgramme = EpgProgramme(
        channelId = "ch", startTime = 0L, endTime = 1L, title = title,
    )

    @Test
    fun `onNow drops channels with no current programme`() {
        val channels = listOf(
            ch("BBC One", prog("News")),
            ch("Channel 4", null),
            ch("ITV", prog("Drama")),
        )
        val out = TvHomeRailsBuilder.onNow(channels)
        assertEquals(listOf("BBC One", "ITV"), out.map { it.channel.name })
    }

    @Test
    fun `onNow returns empty for empty input - rail must be hidden`() {
        // The TV layer relies on this to hide the rail entirely. A
        // visible "On Now" row with zero tiles would break the
        // outcome promise.
        assertTrue(TvHomeRailsBuilder.onNow(emptyList()).isEmpty())
    }

    @Test
    fun `onNow preserves caller order`() {
        val a = ch("Alpha", prog("A"))
        val b = ch("Bravo", prog("B"))
        val c = ch("Charlie", prog("C"))
        val out = TvHomeRailsBuilder.onNow(listOf(c, a, b))
        // Caller already sorts by user pinning + recency — onNow
        // is a filter, not a re-ranker.
        assertEquals(listOf("Charlie", "Alpha", "Bravo"), out.map { it.channel.name })
    }

    @Test
    fun `onNow drops every channel when none are currently airing`() {
        val out = TvHomeRailsBuilder.onNow(
            listOf(ch("BBC One", null), ch("ITV", null)),
        )
        assertTrue(out.isEmpty())
    }
}
