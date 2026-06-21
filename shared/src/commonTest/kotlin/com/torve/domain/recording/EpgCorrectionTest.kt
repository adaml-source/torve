package com.torve.domain.recording

import com.torve.domain.model.EpgProgramme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure transforms for EPG correction. Pinned so a future drift in the
 * model can't silently break "EPG was an hour off" / "rename my channel
 * id" flows.
 */
class EpgCorrectionTest {

    private fun p(channel: String, title: String, start: Long, end: Long) = EpgProgramme(
        channelId = channel, startTime = start, endTime = end, title = title,
    )

    @Test
    fun `applyEpgOffset is a no-op for zero offset`() {
        val input = listOf(p("a", "x", 100L, 200L))
        assertEquals(input, applyEpgOffset(input, 0))
    }

    @Test
    fun `applyEpgOffset shifts every programme forward by the configured minutes`() {
        val input = listOf(p("a", "x", 0L, 60_000L), p("b", "y", 1_000L, 60_000L))
        val out = applyEpgOffset(input, offsetMinutes = 60)
        // 60 min = 3_600_000 ms
        assertEquals(3_600_000L, out[0].startTime)
        assertEquals(3_660_000L, out[0].endTime)
        assertEquals(3_601_000L, out[1].startTime)
    }

    @Test
    fun `applyEpgOffset accepts negative offsets`() {
        val input = listOf(p("a", "x", 7_200_000L, 10_800_000L))
        val out = applyEpgOffset(input, offsetMinutes = -60)
        assertEquals(3_600_000L, out[0].startTime)
        assertEquals(7_200_000L, out[0].endTime)
    }

    @Test
    fun `applyTvgIdRemap rewrites channelId per the map`() {
        val input = listOf(p("playlist-bbc1", "T1", 0L, 0L), p("playlist-bbc2", "T2", 0L, 0L))
        val out = applyTvgIdRemap(input, mapOf("playlist-bbc1" to "bbc.one.hd"))
        assertEquals("bbc.one.hd", out[0].channelId)
        assertEquals("playlist-bbc2", out[1].channelId, "untouched ids stay")
    }

    @Test
    fun `applyTvgIdRemap is a no-op when remap is empty`() {
        val input = listOf(p("a", "T1", 0L, 0L))
        assertEquals(input, applyTvgIdRemap(input, emptyMap()))
    }

    @Test
    fun `EpgHealth flags stale when the latest programme ended before now`() {
        val nowMs = 1_700_000_000_000L
        val programmes = listOf(p("a", "T", nowMs - 7_200_000L, nowMs - 3_600_000L))
        val health = computeEpgHealth("p1", matchedChannelCount = 5, unmatchedChannelCount = 0, programmes, nowMs)
        assertTrue(health.isStale)
        assertEquals(100, health.matchPercent)
    }

    @Test
    fun `EpgHealth is not stale while a programme is still in progress`() {
        val nowMs = 1_700_000_000_000L
        val programmes = listOf(p("a", "T", nowMs - 60_000L, nowMs + 3_600_000L))
        val health = computeEpgHealth("p1", 1, 1, programmes, nowMs)
        assertFalse(health.isStale)
        assertEquals(50, health.matchPercent)
    }

    @Test
    fun `EpgCorrection isEmpty rejects defaults only`() {
        val empty = EpgCorrection(playlistId = "p1")
        assertTrue(empty.isEmpty)
        val withOffset = EpgCorrection(playlistId = "p1", offsetMinutes = 60)
        assertFalse(withOffset.isEmpty)
        val withMapping = EpgCorrection(
            playlistId = "p1",
            tvgIdRemap = mapOf("a" to "b"),
        )
        assertFalse(withMapping.isEmpty)
    }
}
