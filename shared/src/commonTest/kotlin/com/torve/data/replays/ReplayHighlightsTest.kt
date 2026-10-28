package com.torve.data.replays

import kotlin.test.*

class ReplayHighlightsTest {
    private val event = ReplayEvent("game", "site", "NBA Lakers vs Celtics Full Game Replay", "https://example.com/game",
        competition = "NBA", date = "2026-10-23")
    private val nba = "UCWJ2lWNubArHWmf3FIHbfcQ"
    private fun renderer(id: String, length: String = "3:42", channel: String = nba,
        title: String = "Lakers vs Celtics | FULL GAME HIGHLIGHTS | October 23, 2026") = """
        {"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"lengthText":{"simpleText":"$length"},
        "ownerText":{"runs":[{"text":"NBA","navigationEndpoint":{"browseEndpoint":{"browseId":"$channel"}}}]}}}
    """.trimIndent()
    private fun page(vararg videos: String) = "<script>var ytInitialData = {\"contents\":[${videos.joinToString(",")}]};</script>"

    @Test fun offersActualLengthsIncludingSeveralVersionsWithoutAssumingBySport() {
        val videos = ReplayHighlights.parse(page(renderer("short123456", "3:42"), renderer("long1234567", "20:14"),
            renderer("middle12345", "9:50")), event, "Lakers vs Celtics")
        assertEquals(listOf("3:42", "9:50", "20:14"), videos.map { it.durationLabel })
        assertEquals(listOf("Short highlights", "Highlights", "Extended highlights"), videos.map { it.lengthLabel })
        assertTrue(videos.all { it.channel == "NBA" && it.url.startsWith("https://www.youtube.com/watch?v=") })
    }
    @Test fun spoofedChannelNamesAndUnknownDurationsAreNeverOffered() {
        assertTrue(ReplayHighlights.parse(page(renderer("spoof123456", channel = "some-other-channel"),
            renderer("live1234567", "LIVE")), event, "Lakers vs Celtics").isEmpty())
    }
    @Test fun rejectsWrongOpponentDifferentYearAndExplicitDifferentDate() {
        listOf("Lakers vs Warriors highlights October 23, 2026", "Lakers vs Celtics highlights October 23, 2025",
            "Lakers vs Celtics highlights October 21, 2026", "Lakers vs Celtics reaction highlights October 23, 2026").forEach {
            assertFalse(ReplayHighlights.matchesEvent(it, event, "Lakers vs Celtics"), it)
        }
    }
    @Test fun dailyListingsNeedTheSelectedMatchup() {
        val daily = event.copy(title = "EuroLeague Basketball Full Game Replays October 23, 2026", competition = "EuroLeague")
        assertFalse(ReplayHighlights.matchesEvent("Paris Basketball - Valencia Basket | R7 Basketball Highlights 2026-27", daily, null))
        assertTrue(ReplayHighlights.matchesEvent("Paris Basketball - Valencia Basket | R7 Basketball Highlights 2026-27", daily, "Paris Basketball vs Valencia Basket"))
        assertFalse(ReplayHighlights.matchesEvent("Paris Basketball - Hapoel | Highlights 2026-27", daily, "Paris Basketball vs Valencia Basket"))
    }
    @Test fun uploadAgeRejectsOldRematchesAndUnknownDates() {
        val now = kotlinx.datetime.Instant.parse("2026-10-25T11:00:00Z").toEpochMilliseconds()
        assertTrue(ReplayHighlights.publicationMatches("Lakers vs Celtics highlights", "1 day ago", event, now))
        assertTrue(ReplayHighlights.publicationMatches("Lakers vs Celtics highlights", "1d ago", event, now))
        assertFalse(ReplayHighlights.publicationMatches("Lakers vs Celtics highlights", "1 year ago", event, now))
        assertFalse(ReplayHighlights.publicationMatches("Lakers vs Celtics highlights", "", event, now))
        assertTrue(ReplayHighlights.publicationMatches("Lakers vs Celtics highlights October 23, 2026", "1 year ago", event, now))
    }
    @Test fun fallbackSearchUsesPinnedOfficialChannelAndSelectedTeams() {
        val url = ReplayHighlights.searchUrl(event, "Lakers vs Celtics")
        assertTrue(url.startsWith("https://www.youtube.com/channel/$nba/search?"))
        assertTrue(url.contains("Lakers")); assertTrue(url.contains("2026")); assertTrue(url.contains("october"))
        assertTrue(ReplayHighlights.searchUrl(event.copy(competition = "", title = "Other race"), null).contains("/results?"))
    }
    @Test fun durationAndMalformedPagesAreBounded() {
        assertNull(ReplayHighlights.duration("3:99")); assertNull(ReplayHighlights.duration("LIVE"))
        assertEquals(3661, ReplayHighlights.duration("1:01:01"))
        assertTrue(ReplayHighlights.parse("var ytInitialData = {broken", event, null).isEmpty())
        assertTrue(ReplayHighlights.parse("x".repeat(2_000_001), event, null).isEmpty())
    }
    @Test fun eventTitlesDoNotDuplicateTheDateOrSendReplayLabelsToSearch() {
        val bucks = event.copy(title = "Milwaukee Bucks vs Philadelphia 76ers - NBA - Full Game Replay - October 24, 2026", date = "2026-10-24")
        val query = ReplayHighlights.query(bucks, null)
        assertEquals(1, Regex("2026").findAll(query).count())
        assertEquals(1, Regex("october", RegexOption.IGNORE_CASE).findAll(query).count())
        assertFalse(query.contains("replay", true))
        assertFalse(query.contains("--"))
        assertTrue(query.contains("Bucks") && query.contains("76ers"))
    }
    @Test fun nicknamesAbbreviationsAndSponsorNamesMatchWithoutTheOriginalHeadline() {
        val bucks = event.copy(title = "Milwaukee Bucks vs Philadelphia 76ers - NBA - Full Game Replay - October 24, 2026", date = "2026-10-24")
        assertTrue(ReplayHighlights.matchesEvent("SIXERS at BUCKS | FULL GAME HIGHLIGHTS | October 24, 2026", bucks, null))
        assertFalse(ReplayHighlights.matchesEvent("Bucks vs Philadelphia Eagles highlights October 24, 2026", bucks, null))
        val la = event.copy(title = "Los Angeles Lakers vs Boston Celtics")
        assertFalse(ReplayHighlights.matchesEvent("Los Angeles Clippers vs Boston Celtics highlights October 23, 2026", la, null))
        val euro = event.copy(competition = "EuroLeague", title = "Olympiacos Piraeus vs Fenerbahce Istanbul")
        assertTrue(ReplayHighlights.matchesEvent("Olympiacos - Fenerbahçe Beko | R7 Highlights 2026-27", euro, null))
    }
    @Test fun searchUsesSeveralShortVariantsButEveryResultStillNeedsTheCorrectDate() {
        val bucks = event.copy(title = "Milwaukee Bucks vs Philadelphia 76ers Full Game Replay")
        val urls = ReplayHighlights.searchUrls(bucks, null)
        assertEquals(3, urls.size)
        assertTrue(urls.all { it.contains("Bucks") && it.contains("76ers") })
        assertTrue(urls.any { "/results?" in it })
        assertFalse(ReplayHighlights.matchesEvent("Bucks 76ers highlights 2026-10-21", bucks, null))
        assertFalse(ReplayHighlights.matchesEvent("Bucks 76ers player highlights October 23, 2026", bucks, null))
        assertFalse(ReplayHighlights.matchesEvent("NBA 2K27 Bucks 76ers highlights October 23, 2026", bucks, null))
    }
    @Test fun raceVenueAliasesStillDistinguishRaceFromQualifyingOrSprint() {
        val race = event.copy(title = "F1 USA Grand Prix Full Race Replay", competition = "Formula 1")
        assertTrue(ReplayHighlights.matchesEvent("Race Highlights | 2026 United States Grand Prix", race, null))
        assertFalse(ReplayHighlights.matchesEvent("Qualifying Highlights | 2026 United States Grand Prix", race, null))
        assertFalse(ReplayHighlights.matchesEvent("Sprint Race Highlights | 2026 United States Grand Prix", race, null))
    }
    @Test fun spoilerFreeLabelsNeverRepeatTheUploadersResultOrThumbnail() {
        val video = ReplayHighlight("safe1234567", "Lakers WIN 123-99! Celtics eliminated!", "NBA", 1200)
        assertEquals("Extended highlights · 20:00", video.spoilerFreeTitle)
    }
    @Test fun adjacentUsGameDateNeedsANearbyUploadAndDistantRematchesAreRejected() {
        val now = kotlinx.datetime.Instant.parse("2026-10-25T11:00:00Z").toEpochMilliseconds()
        assertTrue(ReplayHighlights.matchesEvent("Lakers Celtics highlights October 22, 2026", event, "Lakers vs Celtics"))
        assertTrue(ReplayHighlights.publicationMatches("Lakers Celtics highlights October 22, 2026", "2 days ago", event, now))
        assertFalse(ReplayHighlights.publicationMatches("Lakers Celtics highlights October 22, 2026", "1 year ago", event, now))
    }
    @Test fun creditsCreatorsWithoutCallingThemTheOfficialLeague() {
        val creator = renderer("other123456", channel = "UC1234567890123456789012")
            .replace("\"text\":\"NBA\",\"navigationEndpoint\"", "\"text\":\"Game Recaps\",\"navigationEndpoint\"")
        val result = ReplayHighlights.parse(page(creator, renderer("nba12345678")), event, "Lakers vs Celtics")
        assertEquals(2, result.size)
        assertTrue(result.first().isOfficial)
        assertEquals("Game Recaps", result.last().channel)
        assertFalse(result.last().isOfficial)
    }
    @Test fun choicesCarrySelectedMatchupAndDateRatherThanUploadersSpoilerHeadline() {
        val title = "Lakers WIN 123-99! Celtics eliminated! FULL GAME HIGHLIGHTS October 23, 2026"
        val video = ReplayHighlights.parse(page(renderer("safe1234567", "12:25", title = title)), event, null).single()
        assertEquals("Lakers vs Celtics", video.matchupLabel)
        assertEquals("NBA · 2026-10-23", video.contextLabel)
        assertEquals("Full game highlights", video.contentLabel)
        assertEquals("Lakers vs Celtics — Full game highlights · 12:25", video.spoilerFreeTitle)
        listOf("123", "99", "WIN", "eliminated", "!").forEach { assertFalse(it in video.spoilerFreeTitle) }
    }
    @Test fun quarterAndPartNumbersStayVisibleWithoutTheRestOfTheHeadline() {
        val video = ReplayHighlight("safe1234567", "Lakers win! Celtics Full Highlights 1st Qtr P2 | Oct. 23", "Creator", 365)
        assertEquals("1st quarter highlights · Part 2", video.contentLabel)
        assertEquals("1st quarter highlights · Part 2 · 6:05", video.spoilerFreeTitle)
        assertEquals("4th quarter highlights", video.copy(title = "Q4 highlights").contentLabel)
        assertEquals("2nd half highlights", video.copy(title = "Second half highlights").contentLabel)
    }
    @Test fun recapAndCondensedLabelsNeedExplicitMetadataAndNeverInventCompleteCoverage() {
        fun label(title: String, seconds: Int = 650) = ReplayHighlight("safe1234567", title, "Creator", seconds).contentLabel
        assertEquals("Game recap", label("Game Recap: Lakers 123, Celtics 99"))
        assertEquals("Condensed game", label("Lakers Celtics Condensed Game"))
        assertEquals("Full game highlights", label("Lakers Celtics FULL GAME HIGHLIGHTS"))
        assertEquals("Extended highlights", label("Lakers Celtics Highlights"))
        assertEquals("Short highlights", label("Lakers Celtics Highlights", 140))
        assertFalse(label("Lakers Celtics Highlights", 1250).contains("Full game"))
        assertTrue(ReplayHighlights.matchesEvent("Lakers Celtics condensed game October 23, 2026", event, null))
    }
    @Test fun plausibleHeadlinesFromGamingChannelsAreNotRealGameChoices() {
        val gaming = renderer("fake1234567", channel = "UC1234567890123456789012")
            .replace("\"text\":\"NBA\",\"navigationEndpoint\"", "\"text\":\"MANTRA Gaming\",\"navigationEndpoint\"")
        assertTrue(ReplayHighlights.parse(page(gaming), event, null).isEmpty())
        assertTrue(ReplayHighlightLabels.isSimulation("Lakers Celtics highlights", "Sports Channel", "NBA 2K27 gameplay simulation", false))
        assertFalse(ReplayHighlightLabels.isSimulation("Lakers Celtics highlights", "Game Recaps", "Game highlights", false))
        assertFalse(ReplayHighlightLabels.isSimulation("Lakers Celtics highlights", "Gaming", "", true), "Official status is pinned by channel ID")
    }
}
