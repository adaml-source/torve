package com.torve.data.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatchupResolverTest {

    private val resolver = CatchupResolver()

    @Test
    fun xtreamReplay_buildsTimeshiftUrlFromStandardLiveUrl() {
        val channel = Channel(
            name = "WOW SKY SPORT F1 HD",
            url = "https://iptv.example.com/live/user123/pass456/98765.ts",
            catchupType = "xc",
            catchupDays = 7,
            playlistId = "pl-1",
        )
        val programme = EpgProgramme(
            channelId = "pl-1::wowskysportf1hd",
            startTime = 1_711_722_000_000L,
            endTime = 1_711_725_600_000L,
            title = "Race Replay",
        )

        val replayUrl = resolver.resolve(channel, programme)

        assertEquals(
            "https://iptv.example.com/timeshift/user123/pass456/60/2024-03-29:14-20/98765.ts",
            replayUrl,
        )
    }

    @Test
    fun xtreamReplay_channelImportedWithArchiveMetadataIsReplayable() {
        val client = XtreamClient(HttpClient(), Json { ignoreUnknownKeys = true })
        val channels = client.mapLiveToChannels(
            streams = listOf(
                XtreamLiveStream(
                    name = "WOW SKY SPORT F1 HD",
                    streamId = "98765",
                    epgChannelId = "wow.sky.sport.f1.hd",
                    tvArchive = 1,
                    tvArchiveDuration = 7,
                ),
            ),
            categories = listOf(XtreamCategory(categoryId = "5", categoryName = "DE")),
            server = "https://iptv.example.com",
            username = "user123",
            password = "pass456",
            playlistId = "pl-1",
        )

        val channel = channels.single()

        assertEquals("xc", channel.catchupType)
        assertEquals(7, channel.catchupDays)
        assertTrue(resolver.canCatchup(channel))
    }
}
