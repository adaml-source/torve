package com.torve.data.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChannelSnapshotSignatureTest {
    private val channel = Channel(
        name = "News HD",
        url = "https://example.test/live/1.ts",
        tvgId = "news.example",
        groupTitle = "News",
        playlistId = "playlist-1",
        contentType = ChannelContentType.LIVE,
    )

    @Test
    fun identicalSnapshotsHaveSameSignature() {
        assertEquals(
            channelSnapshotSignature(listOf(channel)),
            channelSnapshotSignature(listOf(channel.copy())),
        )
    }

    @Test
    fun persistedFieldChangeInvalidatesSignature() {
        assertNotEquals(
            channelSnapshotSignature(listOf(channel)),
            channelSnapshotSignature(listOf(channel.copy(name = "News UHD"))),
        )
    }

    @Test
    fun orderingChangeInvalidatesSignature() {
        val other = channel.copy(name = "Sports", url = "https://example.test/live/2.ts")
        assertNotEquals(
            channelSnapshotSignature(listOf(channel, other)),
            channelSnapshotSignature(listOf(other, channel)),
        )
    }
}
