package com.torve.domain.recording

import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpgRecordingMetadataResolverTest {

    private val channel = Channel(
        name = "ZDF HD (LOW BIT)",
        url = "http://example/live.ts",
        tvgId = "zdf.de",
        tvgLogo = "https://img/zdf.png",
        groupTitle = "DE",
    )

    @Test
    fun `scheduled EPG recording stores programme title and channel metadata`() {
        val programme = EpgProgramme(
            channelId = "zdf.de",
            startTime = 1_000L,
            endTime = 2_000L,
            title = "heute journal",
            subTitle = "Late news",
            description = "News and analysis.",
            category = "News",
            iconUrl = "https://img/heute.png",
        )

        val snapshot = EpgRecordingMetadataResolver.scheduled(channel, programme)

        assertEquals("heute journal", snapshot.programmeTitle)
        assertEquals("heute journal", snapshot.epgProgrammeTitle)
        assertEquals("Late news", snapshot.epgProgrammeSubtitle)
        assertEquals("News", snapshot.epgProgrammeCategory)
        assertEquals("https://img/heute.png", snapshot.epgProgrammeIconUrl)
        assertEquals(RecordingKind.SCHEDULED_EPG, snapshot.recordingKind)
        assertEquals(RecordingEpgMatchStatus.MATCHED, snapshot.epgMatchStatus)
    }

    @Test
    fun `live recording snapshots current EPG programme when available`() {
        val current = EpgProgramme(
            channelId = "zdf.de",
            startTime = 1_000L,
            endTime = 5_000L,
            title = "heute journal",
            description = "News.",
        )

        val snapshot = EpgRecordingMetadataResolver.live(channel, listOf(current), nowMs = 2_000L)

        assertEquals("heute journal", snapshot.programmeTitle)
        assertEquals("heute journal", snapshot.epgProgrammeTitle)
        assertEquals("News.", snapshot.programmeDescription)
        assertEquals(RecordingKind.LIVE, snapshot.recordingKind)
        assertEquals(RecordingEpgMatchStatus.MATCHED, snapshot.epgMatchStatus)
    }

    @Test
    fun `live recording without EPG falls back to channel title`() {
        val snapshot = EpgRecordingMetadataResolver.live(channel, emptyList(), nowMs = 2_000L)

        assertEquals("ZDF HD (LOW BIT)", snapshot.programmeTitle)
        assertNull(snapshot.epgProgrammeTitle)
        assertEquals(RecordingKind.LIVE, snapshot.recordingKind)
        assertEquals(RecordingEpgMatchStatus.NO_MATCH_AT_START, snapshot.epgMatchStatus)
    }
}
