package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvEpgResolverTest {
    private val base = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L

    @Test
    fun `detail now-next and guide range use same channel lookup keys`() {
        val channel = Channel(
            name = "DE: ZDF RAW amz",
            url = "https://example.com/live/zdf.m3u8",
            tvgId = "zdf.raw.amz",
            tvgName = "ZDF",
            playlistId = "p1",
            contentType = ChannelContentType.LIVE,
        )
        val nowProgramme = programme("p1::zdf", "hallo deutschland", base + 10 * minute, base + hour)
        val nextProgramme = programme("p1::zdf", "SOKO Potsdam", base + hour, base + 2 * hour)
        val epg = mapOf("p1::zdf" to listOf(nowProgramme, nextProgramme))

        val nowNext = LiveTvEpgResolver.resolveNowNext(channel, "p1", epg, base + 20 * minute)
        val guide = LiveTvEpgResolver.resolveProgrammesForRange(
            channel = channel,
            playlistId = "p1",
            programmesByChannelKey = epg,
            rangeStartMs = base,
            rangeEndMs = base + 6 * hour,
        )

        assertEquals("hallo deutschland", nowNext.now?.title)
        assertEquals("SOKO Potsdam", nowNext.next?.title)
        assertEquals(listOf("hallo deutschland", "SOKO Potsdam"), guide.map { it.title })
    }

    @Test
    fun `range resolver includes every programme that overlaps the visible range`() {
        val channel = channel()
        val rangeStart = base
        val rangeEnd = base + hour
        val included = listOf(
            programme("p1::zdf", "starts before", base - 30 * minute, base + 10 * minute),
            programme("p1::zdf", "inside", base + 10 * minute, base + 30 * minute),
            programme("p1::zdf", "ends after", base + 45 * minute, base + 90 * minute),
        )
        val excluded = listOf(
            programme("p1::zdf", "ends at start", base - 30 * minute, rangeStart),
            programme("p1::zdf", "starts at end", rangeEnd, rangeEnd + 30 * minute),
        )
        val epg = mapOf("p1::zdf" to included + excluded)

        val resolved = LiveTvEpgResolver.resolveProgrammesForRange(channel, "p1", epg, rangeStart, rangeEnd)

        assertEquals(included.map { it.title }, resolved.map { it.title })
        assertTrue(included.all { LiveTvEpgResolver.overlapsRange(it, rangeStart, rangeEnd) })
        assertFalse(excluded.any { LiveTvEpgResolver.overlapsRange(it, rangeStart, rangeEnd) })
    }

    private fun channel() = Channel(
        name = "ZDF",
        url = "https://example.com/live/zdf.m3u8",
        tvgId = "zdf",
        playlistId = "p1",
        contentType = ChannelContentType.LIVE,
    )

    private fun programme(channelId: String, title: String, start: Long, end: Long) = EpgProgramme(
        channelId = channelId,
        startTime = start,
        endTime = end,
        title = title,
    )
}
