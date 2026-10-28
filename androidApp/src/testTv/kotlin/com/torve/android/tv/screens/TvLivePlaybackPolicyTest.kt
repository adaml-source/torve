package com.torve.android.tv.screens

import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLivePlaybackPolicyTest {
    private val programme = EpgProgramme(
        channelId = "channel-1",
        startTime = 1_000L,
        endTime = 5_000L,
        title = "Programme",
    )

    @Test
    fun `valid initial replay preserves programme identity`() {
        val replay = TvLivePlaybackPolicy.initialReplayProgramme(
            replayUrl = "https://example.test/replay",
            startMs = 1_000L,
            endMs = 5_000L,
            title = "Programme",
        )

        assertNotNull(replay)
        assertEquals(1_000L, replay?.startTime)
        assertEquals(5_000L, replay?.endTime)
        assertEquals("Programme", replay?.title)
    }

    @Test
    fun `invalid initial replay is rejected`() {
        assertNull(TvLivePlaybackPolicy.initialReplayProgramme("", 1_000L, 5_000L, "Programme"))
        assertNull(TvLivePlaybackPolicy.initialReplayProgramme("url", -1L, 5_000L, "Programme"))
        assertNull(TvLivePlaybackPolicy.initialReplayProgramme("url", 5_000L, 5_000L, "Programme"))
    }

    @Test
    fun `current or past programme with resolved url offers replay`() {
        assertTrue(TvLivePlaybackPolicy.canOfferReplay(programme, "replay-url", nowMs = 2_000L))
        assertTrue(TvLivePlaybackPolicy.canOfferReplay(programme, "replay-url", nowMs = 6_000L))
    }

    @Test
    fun `future or unresolved programme does not offer replay`() {
        assertFalse(TvLivePlaybackPolicy.canOfferReplay(programme, "replay-url", nowMs = 500L))
        assertFalse(TvLivePlaybackPolicy.canOfferReplay(programme, "", nowMs = 2_000L))
        assertFalse(TvLivePlaybackPolicy.canOfferReplay(null, "replay-url", nowMs = 2_000L))
    }

    @Test
    fun `seekable live playback owns directional keys`() {
        assertTrue(
            TvLivePlaybackPolicy.shouldSeekTimeshift(
                isLive = true,
                isSeekable = true,
                replayActive = false,
                overlayOpen = false,
                multiviewActive = false,
            ),
        )
    }

    @Test
    fun `replay from beginning owns directional keys even though it is not a live window`() {
        assertTrue(
            TvLivePlaybackPolicy.shouldSeekTimeshift(
                isLive = false,
                isSeekable = false,
                replayActive = true,
                overlayOpen = false,
                multiviewActive = false,
            ),
        )
        assertTrue(TvLivePlaybackPolicy.hasTimeshiftTransport(false, false, replayActive = true))
    }

    @Test
    fun `overlay or multiview keeps directional keys from timeshift`() {
        assertFalse(TvLivePlaybackPolicy.shouldSeekTimeshift(true, true, false, overlayOpen = true, multiviewActive = false))
        assertFalse(TvLivePlaybackPolicy.shouldSeekTimeshift(true, true, false, overlayOpen = false, multiviewActive = true))
        assertFalse(TvLivePlaybackPolicy.shouldSeekTimeshift(true, false, false, overlayOpen = false, multiviewActive = false))
        assertFalse(TvLivePlaybackPolicy.shouldSeekTimeshift(false, false, true, overlayOpen = true, multiviewActive = false))
    }

    @Test
    fun `favorite state uses stable channel identity instead of exact stream url`() {
        val playing = Channel(
            name = "Channel",
            url = "https://stream.example/live?token=new",
            tvgId = "channel-id",
            playlistId = "playlist",
        )
        val storedFavorite = playing.copy(url = "https://stream.example/live?token=old", isFavorite = true)

        assertTrue(TvLivePlaybackPolicy.isChannelFavorite(playing, listOf(storedFavorite)))
        assertFalse(TvLivePlaybackPolicy.isChannelFavorite(playing, emptyList()))
    }

    @Test
    fun `replay timeline includes a provider url window offset`() {
        assertEquals(
            150_000L,
            TvLivePlaybackPolicy.replayTimelinePositionMs(
                windowStartOffsetMs = 120_000L,
                playerPositionMs = 30_000L,
                durationMs = 600_000L,
            ),
        )
    }

    @Test
    fun `native seekable live window supplies the OSD timeline`() {
        val timeline = TvLivePlaybackPolicy.timeshiftTimeline(
            replayActive = false,
            replayPositionMs = 0L,
            replayDurationMs = 0L,
            playerPositionMs = 420_000L,
            playerDurationMs = 600_000L,
        )

        assertEquals(420_000L, timeline.positionMs)
        assertEquals(600_000L, timeline.durationMs)
    }

    @Test
    fun `replay timeline takes precedence and clamps to the available live edge`() {
        val timeline = TvLivePlaybackPolicy.timeshiftTimeline(
            replayActive = true,
            replayPositionMs = 700_000L,
            replayDurationMs = 600_000L,
            playerPositionMs = 50_000L,
            playerDurationMs = 100_000L,
        )

        assertEquals(600_000L, timeline.positionMs)
        assertEquals(600_000L, timeline.durationMs)
    }

    @Test
    fun `replay media seek clamps to programme boundaries`() {
        assertEquals(180_000L, TvLivePlaybackPolicy.replaySeekTargetMs(120_000L, 60_000L, 300_000L))
        assertEquals(0L, TvLivePlaybackPolicy.replaySeekTargetMs(30_000L, -60_000L, 300_000L))
        assertEquals(300_000L, TvLivePlaybackPolicy.replaySeekTargetMs(280_000L, 60_000L, 300_000L))
    }

    @Test
    fun `current programme replay duration stops at the live edge`() {
        val currentProgramme = programme.copy(startTime = 1_000L, endTime = 10_000L)

        assertEquals(
            5_000L,
            TvLivePlaybackPolicy.replayAvailableDurationMs(currentProgramme, nowMs = 6_000L),
        )
        assertEquals(
            9_000L,
            TvLivePlaybackPolicy.replayAvailableDurationMs(currentProgramme, nowMs = 20_000L),
        )
    }
}
