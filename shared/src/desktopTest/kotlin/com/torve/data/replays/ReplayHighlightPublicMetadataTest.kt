package com.torve.data.replays

import kotlinx.serialization.json.*
import kotlin.test.*

/** Sanitized metadata observed on public search pages, not invented video matches. */
class ReplayHighlightPublicMetadataTest {
    private val data = javaClass.getResourceAsStream("/replays/public-search-2026-10-25.json")!!.use {
        Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
    }
    private val now = kotlinx.datetime.Instant.parse("2026-10-25T13:00:00Z").toEpochMilliseconds()
    private fun parse(key: String, event: ReplayEvent) = ReplayHighlights.parse(
        "<script>var ytInitialData = {\"contents\":${data[key]}};</script>", event, null, now)
    private val nba = ReplayEvent("nba", "site", "Milwaukee Bucks vs Philadelphia 76ers - NBA - Full Game Replay - October 24, 2026",
        "https://example.com/nba", competition = "NBA", date = "2026-10-24")
    @Test fun broaderSearchFindsRealBucksSixersHighlightsThatLeagueOnlySearchMissed() {
        assertTrue(parse("nba-short", nba).isEmpty())
        assertTrue(parse("nba-year", nba).isEmpty(), "Summer league rematch must be excluded")
        val found = parse("nba-global", nba)
        assertTrue(found.size >= 3, "Expected several observed matchup highlights; got ${found.size}")
        assertTrue(found.map { it.durationSeconds }.distinct().size >= 3)
        assertTrue(found.all { it.spoilerFreeTitle.none { char -> char == '!' } })
        assertTrue(found.all { it.matchupLabel == "Bucks vs 76ers" && it.eventDate == "2026-10-24" })
        assertTrue(found.none { it.channel.contains("Gaming", true) }, "Gaming uploads with plausible real-game titles must be excluded")
        assertTrue(found.any { it.contentLabel == "Full game highlights" })
    }
    @Test fun officialEuroleagueMatchSurvivesSponsoredTeamNamesAndCompactUploadAge() {
        val euro = nba.copy(title = "Paris Basketball vs Valencia Basket", competition = "EuroLeague", date = "2026-10-23")
        val found = parse("euro-short", euro)
        assertTrue(found.any { it.id == "gtfEW_f1-b8" && it.durationSeconds == 590 && it.isOfficial })
    }
}
