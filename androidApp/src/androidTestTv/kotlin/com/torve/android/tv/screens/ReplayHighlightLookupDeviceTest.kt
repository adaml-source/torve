package com.torve.android.tv.screens

import androidx.test.platform.app.InstrumentationRegistry
import com.torve.data.network.HttpClientFactory
import com.torve.data.replays.ReplayEvent
import com.torve.data.replays.WebReplayRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit live-device check for the matchup in the user's report. No video or results are opened. */
class ReplayHighlightLookupDeviceTest {
    @Test fun publicLookupFindsSeveralDurationsForTheReportedMatchup() = runBlocking {
        val repository = WebReplayRepository(HttpClientFactory.create())
        try {
            val event = ReplayEvent("reported-match", "test", "Milwaukee Bucks vs Philadelphia 76ers - NBA - Full Game Replay - October 24, 2026",
                "https://example.com/replay", competition = "NBA", date = "2026-10-24")
            val found = repository.highlights(event, null)
            val durations = found.map { it.durationSeconds }.distinct()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.getExternalFilesDir(null), "highlight-lookup-observation.json")
                .writeText("{\"matches\":${found.size},\"durationsSeconds\":${durations},\"officialMatches\":${found.count { it.isOfficial }},\"titlesExposed\":false}")
            assertTrue("Expected multiple actual highlight durations", durations.size >= 2)
            assertTrue(found.all { it.matchupLabel == "Bucks vs 76ers" && it.eventDate == "2026-10-24" })
            assertTrue(found.none { it.channel.contains("Gaming", true) })
        } finally { repository.close() }
    }
}
