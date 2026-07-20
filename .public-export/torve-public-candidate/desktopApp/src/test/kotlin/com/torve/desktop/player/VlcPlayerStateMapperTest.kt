package com.torve.desktop.player

import com.torve.desktop.playback.DesktopPlaybackEngineEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VlcPlayerStateMapperTest {

    @Test
    fun `mapper emits events`() {
        val emitted = mutableListOf<DesktopPlaybackEngineEvent>()
        val mapper = VlcPlayerStateMapper { emitted.add(it) }

        mapper.updateContext("https://example.com/video.mp4", 2, "English", "Stable Stream")

        assertEquals("https://example.com/video.mp4", mapper.lastMediaPath)
        assertEquals(2, mapper.lastHeaderCount)
        assertEquals("English", mapper.lastSubtitleLabel)
        assertEquals("Stable Stream", mapper.lastBufferingModeLabel)
    }

    @Test
    fun `mapper initial state has null context`() {
        val mapper = VlcPlayerStateMapper { }
        assertNull(mapper.lastMediaPath)
        assertEquals(0, mapper.lastHeaderCount)
        assertNull(mapper.lastSubtitleLabel)
        assertNull(mapper.lastBufferingModeLabel)
    }

    @Test
    fun `mapper updateContext overwrites previous values`() {
        val mapper = VlcPlayerStateMapper { }
        mapper.updateContext("url1", 1, "French", "Auto")
        mapper.updateContext("url2", 3, "German", "Low Latency")

        assertEquals("url2", mapper.lastMediaPath)
        assertEquals(3, mapper.lastHeaderCount)
        assertEquals("German", mapper.lastSubtitleLabel)
        assertEquals("Low Latency", mapper.lastBufferingModeLabel)
    }

    @Test
    fun `mapper creates event adapter`() {
        val mapper = VlcPlayerStateMapper { }
        val adapter = mapper.createEventAdapter()
        assertIs<uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter>(adapter)
    }
}
