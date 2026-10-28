package com.torve.domain.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SportBucketTest {
    @Test fun videoGamesAreNotEventsEvenWithLeagueNamesOrSportsCategories() {
        listOf("NBA.2K27.PS5", "NBA2K27.2026.10.12", "NBA 2K27", "WWE.2K26", "EA.Sports.FC.26",
            "FIFA.23", "Madden.NFL.26", "NHL.26", "F1.26", "UFC.5.PS5", "NBA.2026.10.12.PlayStation.5")
            .forEach { assertFalse(SportBucket.isSportsRelease(it, "5060"), it) }
    }

    @Test fun filmsSeriesAndStudioProgrammesAreNotEvents() {
        listOf("F1.The.Movie.2025.1080p", "Fury.2014.1080p", "Space.Jam.1996", "NBA.Documentary.2026.10.12",
            "UFC.Embedded.2026.10.12", "UFC.Countdown.2026.10.12", "Inside.the.NBA.2026.10.12",
            "NFL.Total.Access.2026.10.12", "Formula.1.Drive.to.Survive.S01E01", "Basketball.Wives.S01E01",
            "WWE.Biography.S01E01", "SportsCenter.2026.10.12", "Unrelated.Movie.2026.1080p")
            .forEach { assertFalse(SportBucket.isSportsRelease(it, "5060"), it) }
    }

    @Test fun actualMatchesAndRaceSessionsRemainAvailable() {
        listOf("NBA.2026.10.12.Lakers.vs.Celtics", "NBA2026.Lakers.vs.Celtics", "UFC318.Main.Card",
            "WWE.Wrestlemania.42", "AEW.Dynamite.S06E42", "Formula.1.2026.Monaco.Qualifying",
            "Euroleague.2026.10.02.Monaco.vs.Barcelona", "Darts.World.Championship.2026.10.02",
            "MotoGP.2026.Japan.Race", "NHL.2026.10.12.Canadiens.vs.Bruins", "NBA-2026-05-23.ECF.G3_NYK@CLE")
            .forEach { assertTrue(SportBucket.isSportsRelease(it, "5060"), it) }
    }

    @Test fun explicitNonVideoAndMovieCategoriesOverrideEventLikeNames() {
        for (category in listOf("1080", "2000", "2040", "3000", "4000", "4050", "5070", "5080", "7000")) {
            assertFalse(SportBucket.isSportsRelease("NBA.2026.10.12.Lakers.vs.Celtics", category), category)
        }
        assertTrue(SportBucket.isSportsRelease("NBA.2026.10.12.Lakers.vs.Celtics", "5160"))
        assertTrue(SportBucket.isSportsRelease("NBA.2026.10.12.Lakers.vs.Celtics", null))
    }

    @Test
    fun cachedEncodedLeagueNamesAndEpisodeMarkersUseDecodedText() {
        assertEquals(SportBucket.BASKETBALL, SportBucket.classify("Euro%20League.Monaco.vs.Bayern.M%C3%BCnchen"))
        assertEquals(SportsReleaseDate(2026, 10, 2), SportsReleaseDateParser.eventDate("Euroleague.2026%2E10%2E02.Bayern.M%C3%BCnchen"))
        assertFalse(SportBucket.isSportsRelease("Tanked%2ES03E04%2ENBA%2EStar"))
    }

    @Test
    fun compactLeagueAndEventNumbersStillMatchAtWordBoundaries() {
        assertEquals(SportBucket.MMA, SportBucket.classify("UFC318.Main.Card.2026.10.02"))
        assertEquals(SportBucket.BASKETBALL, SportBucket.classify("NBA2026.Lakers.vs.Celtics"))
        assertEquals(SportBucket.OTHER, SportBucket.classify("Snowbank.2026.Documentary"))
    }

    @Test
    fun europeanBasketballIsNotClassifiedAsSoccerOrFormulaOne() {
        listOf(
            "Euroleague.2026.10.02.Monaco.vs.Barcelona.1080p",
            "Euro.League.02.10.2026.Real.Madrid.vs.Bayern.Munich",
            "EuroCup.Basketball.Valencia.vs.Hapoel",
            "FIBA.World.Cup.Germany.vs.Spain",
            "Basketball.ACB.Real.Madrid.vs.Barcelona",
        ).forEach { assertEquals(SportBucket.BASKETBALL, SportBucket.classify(it), it) }
    }

    @Test
    fun leagueWordsInRealityShowEpisodeNamesDoNotBecomeMatches() {
        listOf("Tanked.S03E07.NBA.Tank.1080p", "Tanked_S12E08_NBA_Star", "Tanked.2x03.Basketball").forEach {
            assertEquals(SportBucket.OTHER, SportBucket.classify(it), it)
            assertFalse(SportBucket.isSportsRelease(it), it)
        }
        assertEquals(SportBucket.OTHER, SportBucket.classify("Snowbank.2026.Documentary"))
    }

    @Test
    fun actualSportSeriesAndUnrecognizedSportsRemainAvailable() {
        assertTrue(SportBucket.isSportsRelease("WWE.Raw.S01E03.1080p"))
        assertEquals(SportBucket.WRESTLING, SportBucket.classify("WWE.Raw.S01E03.1080p"))
        assertTrue(SportBucket.isSportsRelease("Darts.World.Championship.2026.10.02"))
        assertEquals(SportBucket.BASKETBALL, SportBucket.classify("WNBA.2026.10.02.Final"))
    }
}
