package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelPlaybackCapabilitiesTest {
    @Test
    fun `archive metadata exposes the canonical timeshift capability`() {
        val channel = Channel(
            name = "Archive channel",
            url = "https://example.test/live",
            catchupType = "xc",
            catchupDays = 7,
        )

        assertTrue(channel.supportsCatchupArchive)
    }

    @Test
    fun `live-only and incomplete archive metadata do not expose timeshift`() {
        val liveOnly = Channel(name = "Live", url = "https://example.test/live")

        assertFalse(liveOnly.supportsCatchupArchive)
        assertFalse(liveOnly.copy(catchupType = "xc", catchupDays = 0).supportsCatchupArchive)
        assertFalse(liveOnly.copy(catchupDays = 7).supportsCatchupArchive)
    }

    @Test
    fun `capability reflects asynchronously refreshed channel metadata`() {
        val before = Channel(name = "Channel", url = "https://example.test/live")
        val after = before.copy(catchupType = "xc", catchupDays = 3)

        assertFalse(before.supportsCatchupArchive)
        assertTrue(after.supportsCatchupArchive)
    }
}
