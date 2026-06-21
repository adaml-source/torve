package com.torve.domain.sports

import kotlin.test.Test
import kotlin.test.assertEquals

class SportsReleaseDisplayParserTest {
    @Test
    fun parsesMlbMatchupFromReleaseName() {
        val display = SportsReleaseDisplayParser.parse(
            "MLB.2026.05.18.Los.Angeles.Dodgers.vs.San.Diego.Padres.1080p.WEB.h264-NiGHTNiNJAS",
            SportBucket.BASEBALL,
        )

        assertEquals("Los Angeles Dodgers vs San Diego Padres", display.title)
        assertEquals("MLB", display.leagueLabel)
        assertEquals("18 May 2026", display.dateLabel)
        assertEquals("1080p", display.qualityLabel)
        assertEquals("WEB", display.sourceLabel)
        assertEquals("NiGHTNiNJAS", display.releaseGroup)
    }

    @Test
    fun parsesFormulaOneRoundTitle() {
        val display = SportsReleaseDisplayParser.parse(
            "Formula1.2026.Round.7.Monaco.Grand.Prix.Qualifying.1080p.WEB.h264-GROUP",
            SportBucket.F1,
        )

        assertEquals("Monaco Grand Prix Qualifying", display.title)
        assertEquals("Formula 1", display.leagueLabel)
        assertEquals("Round 7", display.roundLabel)
        assertEquals("1080p", display.qualityLabel)
        assertEquals("GROUP", display.releaseGroup)
    }

    @Test
    fun fallsBackToCleanedTitleWhenPatternIsUnknown() {
        val display = SportsReleaseDisplayParser.parse(
            "Random.Sports.Special.Release.720p.HDTV.x264-GRP",
            SportBucket.OTHER,
        )

        assertEquals("Random Sports Special Release", display.title)
        assertEquals("720p", display.qualityLabel)
        assertEquals("HDTV", display.sourceLabel)
    }
}
