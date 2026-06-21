package com.torve.android.ui.player

import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.presentation.channels.ChannelsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePlaybackOverlayStateTest {

    @Test
    fun `back closes picker to settings first`() {
        val result = reduceMobilePlaybackBack(
            controlsVisible = true,
            sheetStack = listOf(
                MobilePlaybackSheet.QuickActions,
                MobilePlaybackSheet.Settings,
                MobilePlaybackSheet.Picker(MobilePlaybackPicker.AUDIO_DELAY),
            ),
        )

        val update = result as MobilePlaybackBackResult.Update
        assertTrue(update.controlsVisible)
        assertEquals(
            listOf(
                MobilePlaybackSheet.QuickActions,
                MobilePlaybackSheet.Settings,
            ),
            update.sheetStack,
        )
    }

    @Test
    fun `back closes settings to quick actions`() {
        val result = reduceMobilePlaybackBack(
            controlsVisible = true,
            sheetStack = listOf(
                MobilePlaybackSheet.QuickActions,
                MobilePlaybackSheet.Settings,
            ),
        )

        val update = result as MobilePlaybackBackResult.Update
        assertEquals(listOf(MobilePlaybackSheet.QuickActions), update.sheetStack)
    }

    @Test
    fun `back dismisses first-level sheet before exiting`() {
        val result = reduceMobilePlaybackBack(
            controlsVisible = true,
            sheetStack = listOf(MobilePlaybackSheet.StreamInfo),
        )

        val update = result as MobilePlaybackBackResult.Update
        assertTrue(update.controlsVisible)
        assertTrue(update.sheetStack.isEmpty())
    }

    @Test
    fun `back exits player when only chrome is visible`() {
        val result = reduceMobilePlaybackBack(
            controlsVisible = true,
            sheetStack = emptyList(),
        )

        assertEquals(MobilePlaybackBackResult.ExitPlayer, result)
    }

    @Test
    fun `back restores chrome when controls are hidden`() {
        val result = reduceMobilePlaybackBack(
            controlsVisible = false,
            sheetStack = emptyList(),
        )

        val update = result as MobilePlaybackBackResult.Update
        assertTrue(update.controlsVisible)
        assertTrue(update.sheetStack.isEmpty())
    }

    @Test
    fun `supported settings stay limited to real mobile capabilities`() {
        assertEquals(
            listOf(
                MobilePlaybackSetting.AUDIO_DELAY,
                MobilePlaybackSetting.PLAYBACK_SPEED,
                MobilePlaybackSetting.PICTURE_FORMAT,
            ),
            supportedMobilePlaybackSettings(
                isLivePlayback = false,
                supportsLiveBufferControl = false,
            ),
        )
        assertEquals(
            listOf(
                MobilePlaybackSetting.AUDIO_DELAY,
                MobilePlaybackSetting.PLAYBACK_SPEED,
                MobilePlaybackSetting.PICTURE_FORMAT,
                MobilePlaybackSetting.LIVE_AUDIO_MODE,
                MobilePlaybackSetting.LIVE_BUFFER,
            ),
            supportedMobilePlaybackSettings(
                isLivePlayback = true,
                supportsLiveBufferControl = true,
            ),
        )
    }

    @Test
    fun `resolve live playback context matches channel by url and favorite identity`() {
        val currentProgramme = EpgProgramme(
            channelId = "alpha",
            startTime = System.currentTimeMillis() - 60_000L,
            endTime = System.currentTimeMillis() + 60_000L,
            title = "Morning News",
        )
        val nextProgramme = currentProgramme.copy(
            startTime = currentProgramme.endTime,
            endTime = currentProgramme.endTime + 60_000L,
            title = "Weather",
        )
        val channel = Channel(
            name = "Alpha HD",
            url = "https://example.com/live/alpha.m3u8",
            groupTitle = "US| NEWS HD",
            playlistId = "playlist-1",
        )
        val favorite = channel.copy(name = "Alpha HD Favorite Copy")
        val state = ChannelsUiState(
            selectedGroup = null,
            channels = listOf(
                EnrichedChannel(
                    channel = channel,
                    currentProgramme = currentProgramme,
                    nextProgramme = nextProgramme,
                ),
            ),
            favorites = listOf(favorite),
        )

        val resolved = resolveLivePlaybackContext(
            url = channel.url,
            title = channel.name,
            channelsState = state,
        )

        assertEquals(channel, resolved.currentChannel)
        assertEquals(currentProgramme, resolved.currentProgramme)
        assertEquals(nextProgramme, resolved.nextProgramme)
        assertEquals("News", resolved.currentCategoryName)
        assertTrue(resolved.isFavorite)
    }

    @Test
    fun `resolve live playback context falls back cleanly when no channel is found`() {
        val resolved = resolveLivePlaybackContext(
            url = "https://example.com/missing.m3u8",
            title = "Missing",
            channelsState = ChannelsUiState(),
        )

        assertNull(resolved.currentChannel)
        assertNull(resolved.currentProgramme)
        assertNull(resolved.nextProgramme)
        assertNull(resolved.currentCategoryName)
        assertFalse(resolved.isFavorite)
    }
}
