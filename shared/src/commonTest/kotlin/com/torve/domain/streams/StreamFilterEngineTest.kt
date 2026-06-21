package com.torve.domain.streams

import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamFilterEngineTest {

    private data class Row(
        val title: String,
        val score: Int = 0,
    )

    @Test
    fun `enabled exclusion regex removes matching stream titles`() {
        val result = StreamFilterEngine.apply(
            streams = listOf(Row("Movie.2026.HDCAM.x264"), Row("Movie.2026.1080p.WEB-DL")),
            regexPatterns = listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM")),
            streamGroups = emptyList(),
            customRulesEnabled = true,
            textOf = Row::title,
        )

        assertEquals(listOf("Movie.2026.1080p.WEB-DL"), result.visible.map { it.title })
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `disabled exclusion regex does not remove anything`() {
        val streams = listOf(Row("Movie.2026.HDCAM.x264"))
        val result = StreamFilterEngine.apply(
            streams = streams,
            regexPatterns = listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM", enabled = false)),
            streamGroups = emptyList(),
            customRulesEnabled = true,
            textOf = Row::title,
        )

        assertEquals(streams, result.visible)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun `multiple exclusion regexes remove matching streams`() {
        val result = StreamFilterEngine.apply(
            streams = listOf(
                Row("Movie.2026.HDCAM.x264"),
                Row("Movie.2026.1080p.HC.SUBS"),
                Row("Movie.2026.2160p.WEB-DL"),
            ),
            regexPatterns = listOf(
                RegexPattern(label = "No cams", pattern = "(?i)HDCAM"),
                RegexPattern(label = "No hardcoded subs", pattern = "(?i)HC\\.SUBS"),
            ),
            streamGroups = emptyList(),
            customRulesEnabled = true,
            textOf = Row::title,
        )

        assertEquals(listOf("Movie.2026.2160p.WEB-DL"), result.visible.map { it.title })
        assertEquals(2, result.excludedCount)
    }

    @Test
    fun `invalid exclusion regex is ignored and does not crash`() {
        val streams = listOf(Row("Movie.2026.1080p.WEB-DL"))
        val result = StreamFilterEngine.apply(
            streams = streams,
            regexPatterns = listOf(RegexPattern(label = "Broken", pattern = "[")),
            streamGroups = emptyList(),
            customRulesEnabled = true,
            textOf = Row::title,
        )

        assertEquals(streams, result.visible)
        assertEquals(listOf("Broken"), result.invalidPatterns)
    }

    @Test
    fun `enabled stream group matches expected streams`() {
        val web = Row("Movie.2026.2160p.WEB-DL.DV.Atmos")
        val remux = Row("Movie.2026.2160p.REMUX")
        val result = StreamFilterEngine.apply(
            streams = listOf(web, remux),
            regexPatterns = emptyList(),
            streamGroups = listOf(StreamGroup(name = "Dolby Vision", matchPattern = "(?i)(DV|Dolby Vision)", priority = 0)),
            customRulesEnabled = true,
            textOf = Row::title,
        )

        assertEquals(listOf(web), result.groupMatches["Dolby Vision"])
        assertEquals("Dolby Vision", result.matchedGroups[web]?.name)
    }

    @Test
    fun `disabled stream group is ignored`() {
        val row = Row("Movie.2026.2160p.WEB-DL.DV.Atmos")
        val result = StreamFilterEngine.apply(
            streams = listOf(row),
            regexPatterns = emptyList(),
            streamGroups = listOf(StreamGroup(name = "Dolby Vision", matchPattern = "(?i)DV", priority = 0, enabled = false)),
            customRulesEnabled = true,
            textOf = Row::title,
        )

        assertTrue(result.groupMatches.isEmpty())
        assertTrue(result.matchedGroups.isEmpty())
    }

    @Test
    fun `invalid stream group regex is ignored and does not crash`() {
        val row = Row("Movie.2026.2160p.WEB-DL")
        val result = StreamFilterEngine.apply(
            streams = listOf(row),
            regexPatterns = emptyList(),
            streamGroups = listOf(StreamGroup(name = "Broken group", matchPattern = "(", priority = 0)),
            customRulesEnabled = true,
            textOf = Row::title,
        )

        assertEquals(listOf(row), result.visible)
        assertEquals(listOf("Broken group"), result.invalidPatterns)
        assertTrue(result.groupMatches.isEmpty())
    }

    @Test
    fun `group priority changes ordering only within the allowed secondary sort layer`() {
        val highScore = Row("Movie.2026.1080p.WEB-DL", score = 90)
        val lowScorePreferred = Row("Movie.2026.2160p.WEB-DL.DV.Atmos", score = 70)
        val sameScoreLowPriority = Row("Movie.2026.1080p.BluRay", score = 80)
        val sameScoreHighPriority = Row("Movie.2026.2160p.WEB-DL.DV.Atmos", score = 80)
        val result = StreamFilterEngine.apply(
            streams = listOf(highScore, sameScoreLowPriority, sameScoreHighPriority, lowScorePreferred),
            regexPatterns = emptyList(),
            streamGroups = listOf(
                StreamGroup(name = "4K", matchPattern = "(?i)(2160p|4k|DV)", priority = 0),
                StreamGroup(name = "1080p", matchPattern = "(?i)1080p", priority = 1),
            ),
            customRulesEnabled = true,
            textOf = Row::title,
        )
        val ordered = StreamFilterEngine.orderByGroupPriorityWithinPrimaryBuckets(
            streams = result.visible,
            matchedGroups = result.matchedGroups,
            primaryKeyOf = Row::score,
        )

        assertEquals(
            listOf(highScore, sameScoreHighPriority, sameScoreLowPriority, lowScorePreferred),
            ordered,
        )
    }

    @Test
    fun `existing stream score order is preserved when no groups match`() {
        val streams = listOf(
            Row("Movie.2026.SourceA", score = 100),
            Row("Movie.2026.SourceB", score = 80),
            Row("Movie.2026.SourceC", score = 80),
        )
        val result = StreamFilterEngine.apply(
            streams = streams,
            regexPatterns = emptyList(),
            streamGroups = listOf(StreamGroup(name = "No match", matchPattern = "(?i)XYZ", priority = 0)),
            customRulesEnabled = true,
            textOf = Row::title,
        )
        val ordered = StreamFilterEngine.orderByGroupPriorityWithinPrimaryBuckets(
            streams = result.visible,
            matchedGroups = result.matchedGroups,
            primaryKeyOf = Row::score,
        )

        assertEquals(streams, ordered)
    }

    @Test
    fun `non-premium behavior ignores custom regex and stream groups`() {
        val streams = listOf(Row("Movie.2026.HDCAM.x264"), Row("Movie.2026.2160p.WEB-DL"))
        val result = StreamFilterEngine.apply(
            streams = streams,
            regexPatterns = listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM")),
            streamGroups = listOf(StreamGroup(name = "4K", matchPattern = "(?i)2160p", priority = 0)),
            customRulesEnabled = false,
            textOf = Row::title,
        )

        assertEquals(streams, result.visible)
        assertEquals(0, result.excludedCount)
        assertTrue(result.groupMatches.isEmpty())
    }
}
