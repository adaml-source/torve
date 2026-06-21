package com.torve.domain.recording

import com.torve.domain.model.EpgProgramme
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks in the EPG-driven series-pass resolution Prompt 21 ships:
 *  - Title match is case-insensitive substring.
 *  - Past programmes are dropped.
 *  - seasonMin filters by parsed season number; programmes with no
 *    parseable season are kept (otherwise IPTV guides without season
 *    tags would silently lose every match).
 *  - recordOnlyNew dedups against existing recordings for the pass.
 *  - Missing stream URL → resolver returns empty without throwing.
 */
class EpgSeriesPassResolverTest {

    private val now: Long = 1_700_000_000_000L
    private val hour: Long = 60L * 60L * 1000L

    private fun programme(
        title: String,
        startsInHours: Int,
        durationHours: Int = 1,
        channelId: String = "ch1",
    ) = EpgProgramme(
        channelId = channelId,
        title = title,
        startTime = now + startsInHours * hour,
        endTime = now + (startsInHours + durationHours) * hour,
    )

    private fun pass(
        titleMatch: String,
        recordOnlyNew: Boolean = true,
        seasonMin: Int? = null,
        keepCount: Int? = null,
        channelId: String = "ch1",
        playlistId: String = "pl1",
    ) = RecordingSeriesPass(
        id = "pass-A",
        playlistId = playlistId,
        channelId = channelId,
        titleMatch = titleMatch,
        recordOnlyNew = recordOnlyNew,
        seasonMin = seasonMin,
        keepCount = keepCount,
        createdAtMs = now,
    )

    private fun resolver(
        programmes: List<EpgProgramme>,
        existing: List<Recording> = emptyList(),
        streamUrl: String? = "https://provider/ch1.ts",
    ) = EpgSeriesPassResolver(
        programmesForChannel = { _, _ -> programmes },
        existingRecordingsForPass = { _ -> existing },
        channelNameForId = { _, _ -> "Test Channel" },
        streamUrlForChannelId = { _, _ -> streamUrl },
        newId = { "rec-${programmes.indexOfFirst { false } + 1}" }, // overridden per call below
    )

    @Test
    fun `case-insensitive substring title match returns each future programme`() = runTest {
        val programmes = listOf(
            programme("THE EXPANSE S04E01", startsInHours = 1),
            programme("the expanse S04E02", startsInHours = 25),
            programme("Unrelated show", startsInHours = 5),
        )
        val r = EpgSeriesPassResolver(
            programmesForChannel = { _, _ -> programmes },
            existingRecordingsForPass = { _ -> emptyList() },
            channelNameForId = { _, _ -> "Test Channel" },
            streamUrlForChannelId = { _, _ -> "https://provider/ch1.ts" },
            newId = run { var i = 0; { "rec-${++i}" } },
        )
        val result = r.resolve(pass(titleMatch = "expanse"), nowMs = now)
        assertEquals(2, result.size)
        assertTrue(result.all { it.scheduleKind == RecordingScheduleKind.SERIES })
        assertTrue(result.all { it.seriesPassId == "pass-A" })
        assertTrue(result.all { it.status == RecordingStatus.SCHEDULED })
    }

    @Test
    fun `programmes that already ended are dropped`() = runTest {
        val programmes = listOf(
            programme("Show", startsInHours = -3, durationHours = 2), // ended 1h ago
            programme("Show", startsInHours = 5),
        )
        val result = resolver(programmes).resolve(pass("Show"), nowMs = now)
        assertEquals(1, result.size)
        assertEquals(now + 5 * hour, result.single().startMs)
    }

    @Test
    fun `seasonMin drops parseable seasons below the floor`() = runTest {
        val programmes = listOf(
            programme("Show S01E01", startsInHours = 1),
            programme("Show S02E03", startsInHours = 2),
            programme("Show 3x04", startsInHours = 3),
            // No season info — kept by design (IPTV guide may have stripped it).
            programme("Show", startsInHours = 4),
        )
        val result = resolver(programmes).resolve(
            pass = pass("Show", seasonMin = 2),
            nowMs = now,
        )
        // S01 dropped; S02, 3x04, untagged kept.
        assertEquals(3, result.size)
        val titles = result.map { it.programmeTitle }
        assertTrue("Show S02E03" in titles)
        assertTrue("Show 3x04" in titles)
        assertTrue("Show" in titles)
    }

    @Test
    fun `recordOnlyNew dedups against existing recordings for the pass`() = runTest {
        val programmes = listOf(
            programme("Show S01E01", startsInHours = 1),
            programme("Show S01E02", startsInHours = 25),
        )
        val existing = listOf(
            Recording(
                id = "old",
                playlistId = "pl1",
                channelId = "ch1",
                channelName = "Test Channel",
                streamUrl = "https://provider/ch1.ts",
                programmeTitle = "Show S01E01",
                startMs = now + 1 * hour,
                endMs = now + 2 * hour,
                status = RecordingStatus.COMPLETED,
                seriesPassId = "pass-A",
                createdAtMs = now,
            ),
        )
        val result = resolver(programmes, existing = existing).resolve(
            pass = pass("Show", recordOnlyNew = true),
            nowMs = now,
        )
        assertEquals(1, result.size)
        assertEquals("Show S01E02", result.single().programmeTitle)
    }

    @Test
    fun `missing stream URL returns empty without throwing`() = runTest {
        val r = resolver(
            programmes = listOf(programme("Show", startsInHours = 1)),
            streamUrl = null,
        )
        val result = r.resolve(pass("Show"), nowMs = now)
        assertEquals(emptyList(), result)
    }

    @Test
    fun `parseSeasonNumber recognises S01E02 1x05 and lowercase variants`() {
        assertEquals(1, parseSeasonNumber("Show S01E02"))
        assertEquals(2, parseSeasonNumber("Show s2e5"))
        assertEquals(3, parseSeasonNumber("Show 3x04"))
        assertEquals(10, parseSeasonNumber("Big.Bang.Theory.S10E22.HDTV"))
        assertEquals(null, parseSeasonNumber("No season info"))
        assertEquals(null, parseSeasonNumber("Random 2020 release"))
    }
}
