package com.torve.domain.sports

import kotlin.test.Test
import kotlin.test.assertEquals

class SportsReleaseDisplayParserTest {
    @Test
    fun separatorEscapedBayernTitlesFromTheLiveTvDisplayAsUnicode() {
        listOf("M.C3.BCnchen", "M_c3_bcnchen", "M C3 Bcnchen").forEach { team ->
            val display = SportsReleaseDisplayParser.parse("Euroleague.24-25.Maccabi.Tel.Aviv.vs.Bayern.$team.1080p.HDTV-Silver007")
            assertEquals("24-25 Maccabi Tel Aviv vs Bayern München", display.title, team)
            assertEquals("EuroLeague", display.leagueLabel)
        }
    }

    @Test
    fun hyphenatedAndSlashedDatesAreShownInMetadataRatherThanTeamNames() {
        listOf("2026-10-02", "02-10-2026", "2026/10/2", "2/10/2026").forEach { date ->
            val display = SportsReleaseDisplayParser.parse("Euroleague.$date.Bayern.M%C3%BCnchen.vs.Fenerbah%C3%A7e.1080p.WEB-GRP")
            assertEquals("Bayern München vs Fenerbahçe", display.title, date)
            assertEquals("2 Oct 2026", display.dateLabel, date)
        }
    }

    @Test
    fun cachedEncodedTeamNamesDisplayAsUnicodeBeforeReleaseFormatting() {
        listOf("M%C3%BCnchen", "M%c3%bcnchen", "M%25C3%25BCnchen", "M&uuml;nchen", "M&#252;nchen", "M&#xFC;nchen", "M&amp;uuml;nchen", "München").forEach { team ->
            val display = SportsReleaseDisplayParser.parse("Euroleague.2026.10.02.Bayern.$team.vs.Fenerbah%C3%A7e.1080p.WEB-GRP")
            assertEquals("Bayern München vs Fenerbahçe", display.title, team)
            assertEquals("EuroLeague", display.leagueLabel)
            assertEquals("2 Oct 2026", display.dateLabel)
        }
    }

    @Test
    fun parsesEuroleagueAndEuropeanDayFirstDate() {
        val display = SportsReleaseDisplayParser.parse("Euroleague.02.10.2026.Monaco.vs.Barcelona.1080p.WEB-GRP")
        assertEquals("EuroLeague", display.leagueLabel)
        assertEquals("2 Oct 2026", display.dateLabel)
        assertEquals("Monaco vs Barcelona", display.title)
    }

    @Test
    fun seasonNamesAreNotMistakenForMatchDates() {
        assertEquals(null, SportsReleaseDateParser.eventDate("Euroleague.25-26.Maccabi.vs.Efes"))
        assertEquals(2026, SportsReleaseDateParser.seasonEndYear("Euroleague.25-26.Maccabi.vs.Efes"))
        assertEquals(2018, SportsReleaseDateParser.seasonEndYear("Euroleague.2017-18.Final.Four"))
        assertEquals(null, SportsReleaseDateParser.seasonEndYear("Euroleague.2026-10-02.Monaco.vs.Barcelona"))
    }

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
