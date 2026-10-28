package com.torve.data.replays

import java.io.File
import kotlin.test.*

class ReplayHighlightsLiveProbeTest {
    @Test fun fetchesOfficialHighlightsWithoutMediaExtraction() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue("Live metadata is opt-in", System.getenv("TORVE_HIGHLIGHTS_LIVE") == "1")
        val repository = WebReplayRepository(com.torve.data.network.HttpClientFactory.create())
        try {
            val event = ReplayEvent("live", "site", "EuroLeague Basketball Full Game Replays October 23, 2026",
                "https://example.com/euroleague", competition = "EuroLeague", date = "2026-10-23")
            val videos = repository.highlights(event, "Paris Basketball vs Valencia Basket")
            assertEquals("9:50", videos.single { it.id == "gtfEW_f1-b8" }.durationLabel)
        } finally { repository.close() }
    }
    @Test fun parsesCapturedOfficialEuroleagueSearchAndUploadDate() {
        org.junit.Assume.assumeTrue("Captured live metadata is opt-in", System.getenv("TORVE_HIGHLIGHTS_LIVE_FIXTURE_DIR") != null)
        val directory = System.getenv("TORVE_HIGHLIGHTS_LIVE_FIXTURE_DIR")!!
        val event = ReplayEvent("live", "site", "EuroLeague Basketball Full Game Replays October 23, 2026",
            "https://example.com/euroleague", competition = "EuroLeague", date = "2026-10-23")
        val rows = ReplayHighlights.parse(File(directory, "euroleague-live.html").readText(), event, "Paris Basketball vs Valencia Basket", kotlinx.datetime.Instant.parse("2026-10-25T11:00:00Z").toEpochMilliseconds())
        val game = rows.single { it.id == "gtfEW_f1-b8" }
        assertEquals("9:50", game.durationLabel)
        assertEquals("EuroLeague", game.channel)
        assertTrue(game.publishedLabel.isNotEmpty())
    }
}
