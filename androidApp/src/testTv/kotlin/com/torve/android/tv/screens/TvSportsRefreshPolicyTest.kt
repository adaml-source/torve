package com.torve.android.tv.screens

import com.torve.data.usenet.NewznabItem
import com.torve.domain.sports.SportBucket
import java.time.LocalDate
import java.time.ZoneId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import com.torve.data.usenet.NewznabClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.torve.android.catalog.SportsIndexer
import io.ktor.http.HttpStatusCode

class TvSportsRefreshPolicyTest {
    @Test fun everyConfiguredIndexerIsRequestedWithItsOwnKeyAndCategory() = runBlocking {
        val requests = ConcurrentLinkedQueue<Triple<String, String?, String?>>()
        val http = HttpClient(MockEngine { request ->
            requests += Triple(request.url.host, request.url.parameters["apikey"], request.url.parameters["cat"])
            respond("<rss><channel><item><title>NBA.2026.10.12.Final</title><guid>same-id</guid>" +
                "<link>https://${request.url.host}/api?t=get&amp;id=same-id</link></item></channel></rss>")
        })
        try {
            val result = fetchTvSportsRefresh(NewznabClient(http), listOf(
                SportsIndexer("scenenzbs", "https://treasure-maps.com", "treasure-key"),
                SportsIndexer("custom", "https://videothek.io/api", "video-key")),
                tvSportsRefreshPlan(SPORTS_FILTER_ALL, ""))
            assertTrue(requests.contains(Triple("treasure-maps.com", "treasure-key", "5060,5160,5260,5360")))
            assertTrue(requests.contains(Triple("videothek.io", "video-key", "5060")))
            assertEquals(2, result.items.size)
            assertEquals(2, result.items.map { it.sportsStableId() }.distinct().size)
            assertEquals(null, result.warning)
        } finally { http.close() }
    }

    @Test fun quotaFailureLeavesTheOtherIndexersSearchResultsAvailable() = runBlocking {
        val queries = ConcurrentHashMap.newKeySet<String>()
        val http = HttpClient(MockEngine { request ->
            queries += "${request.url.host}:${request.url.parameters["q"]}"
            if (request.url.host == "videothek.io") respond("quota", HttpStatusCode.TooManyRequests)
            else respond("<rss><channel><item><title>Euroleague.2026.10.12.Final</title><guid>one</guid>" +
                "<link>https://treasure-maps.com/api?t=get&amp;id=one</link></item></channel></rss>")
        })
        try {
            val result = fetchTvSportsRefresh(NewznabClient(http), listOf(
                SportsIndexer("custom", "https://videothek.io/api", "video-key"),
                SportsIndexer("scenenzbs", "https://treasure-maps.com", "treasure-key")),
                tvSportsRefreshPlan(SPORTS_FILTER_ALL, "Euroleague"))
            assertTrue(queries.containsAll(listOf("videothek.io:Euroleague", "treasure-maps.com:Euroleague")))
            assertTrue(result.anySucceeded)
            assertEquals(1, result.items.size)
            assertTrue(result.warning!!.contains("videothek.io"))
            assertTrue(result.warning!!.contains("HTTP 429"))
        } finally { http.close() }
    }

    @Test
    fun allIsTheOnlyFullCatalogRefreshPlan() {
        val all = tvSportsRefreshPlan(SPORTS_FILTER_ALL, "")
        val football = tvSportsRefreshPlan(SportBucket.AMERICAN_FOOTBALL.name, "")
        val basketball = tvSportsRefreshPlan(SportBucket.BASKETBALL.name, "")

        assertEquals(TvSportsRefreshKind.ALL, all.kind)
        assertEquals(7, all.maxAgeDays)
        assertEquals(200, all.maxItems)
        assertEquals(TvSportsRefreshKind.BUCKET, football.kind)
        assertEquals(SportBucket.AMERICAN_FOOTBALL, football.bucket)
        assertEquals("NFL", football.remoteQuery)
        assertEquals(TvSportsRefreshKind.BUCKET, basketball.kind)
        assertTrue(basketball.remoteQueries.containsAll(listOf("NBA", "Euroleague", "Basketball", "Eurocup")))
    }

    @Test
    fun todayAndRecentUseBoundedProviderRequests() {
        val today = tvSportsRefreshPlan(SPORTS_FILTER_TODAY, "")
        val recent = tvSportsRefreshPlan(SPORTS_FILTER_RECENT, "")

        assertEquals(1, today.maxAgeDays)
        assertEquals(200, today.maxItems)
        assertEquals(7, recent.maxAgeDays)
        assertEquals(200, recent.maxItems)
        assertTrue(today.kind != TvSportsRefreshKind.ALL)
        assertTrue(recent.kind != TvSportsRefreshKind.ALL)
    }

    @Test
    fun anyDateSearchCanFindOlderEventsWhileDefaultSearchStaysRecent() {
        val search = tvSportsRefreshPlan(SPORTS_FILTER_ALL, "Wimbledon", selectedDateMode = SPORTS_FILTER_ANY_DATE)

        assertEquals(null, search.maxAgeDays)
        assertEquals(200, search.maxItems)
        assertEquals(7, tvSportsRefreshPlan(SPORTS_FILTER_ALL, "Wimbledon").maxAgeDays)
    }

    @Test
    fun sportAndDateRequestsIntersectWithoutAddingAnUnrelatedLeagueToSearch() {
        val today = LocalDate.of(2026, 10, 6)
        val plan = tvSportsRefreshPlan(SportBucket.BASKETBALL.name, "Euroleague",
            selectedDateMode = SPORTS_FILTER_YESTERDAY, today = today)
        assertEquals(SportBucket.BASKETBALL, plan.bucket)
        assertEquals(listOf("Euroleague"), plan.remoteQueries)
        assertEquals(2, plan.maxAgeDays)
        assertFalse(plan.scopeId == tvSportsRefreshPlan(SportBucket.BASKETBALL.name, "NBA",
            selectedDateMode = SPORTS_FILTER_YESTERDAY, today = today).scopeId)
    }

    @Test
    fun recentIsAnInclusiveCalendarWeekAndDoesNotShowOldOrFutureMatches() {
        val today = LocalDate.of(2026, 10, 6)
        assertTrue(sportsMatchesDate(item("boundary", "NBA.2026.09.30.Final"), SPORTS_FILTER_RECENT, today))
        assertTrue(sportsMatchesDate(item("today", "Euroleague.2026.10.06.Final"), SPORTS_FILTER_RECENT, today))
        assertFalse(sportsMatchesDate(item("old", "NBA.2026.09.29.Final"), SPORTS_FILTER_RECENT, today))
        assertFalse(sportsMatchesDate(item("future", "NBA.2026.10.07.Final"), SPORTS_FILTER_RECENT, today))
        assertFalse(sportsMatchesDate(item("archive", "NBA.2026.07.01.Final",
            "Tue, 06 Oct 2026 08:00:00 +0000"), SPORTS_FILTER_TODAY, today))
    }

    @Test
    fun missingMatchDatesUseLocalPublicationDateWithoutMistakingSeasonForDate() {
        val release = item("undated", "Euroleague.25-26.Maccabi.vs.Efes", "Mon, 05 Oct 2026 23:30:00 +0000")
        assertEquals(LocalDate.of(2026, 10, 6), release.sportsEffectiveDate(ZoneId.of("Europe/Berlin")))
        assertEquals(null, sportsEventDate(release.title))
        assertFalse(sportsMatchesDate(release.copy(pubDate = null), SPORTS_FILTER_RECENT, LocalDate.of(2026, 10, 6)))
        assertTrue(sportsMatchesDate(release.copy(pubDate = null), SPORTS_FILTER_ANY_DATE, LocalDate.of(2026, 10, 6)))
        assertFalse(sportsMatchesDate(release.copy(title = "Euroleague.2017-18.Final.Four"),
            SPORTS_FILTER_RECENT, LocalDate.of(2026, 10, 6)))
    }

    @Test
    fun europeanDatesAndSingleDigitComponentsAreParsedAndInvalidDatesIgnored() {
        val expected = LocalDate.of(2026, 10, 2)
        assertEquals(expected, sportsEventDate("Euroleague_02_10_2026_Final"))
        assertEquals(expected, sportsEventDate("Euroleague.2026.10.2.Final"))
        assertEquals(expected, sportsEventDate("Euroleague.2026.99.99.Repack.02.10.2026"))
        assertEquals(null, sportsEventDate("Euroleague.31.02.2026.Final"))
    }

    @Test
    fun exactDaySearchTargetsTheDayRatherThanOnlyLimitingUploadAge() {
        val day = LocalDate.of(2026, 10, 2)
        val plan = tvSportsRefreshPlan(SportBucket.BASKETBALL.name, "", selectedDateMode = sportsDateMode(day),
            today = LocalDate.of(2026, 10, 6))
        assertEquals(5, plan.maxAgeDays)
        assertEquals(listOf("2026.10.02", "02.10.2026"), plan.remoteQueries)
        assertEquals(SportBucket.BASKETBALL, plan.bucket)
    }

    @Test
    fun euroleagueIsFetchedEvenWhenTheNbaQueryFillsItsAllowance() = runTest {
        val queried = mutableSetOf<String>()
        val queryCategories = mutableSetOf<String>()
        val http = HttpClient(MockEngine { request ->
            val query = request.url.parameters["q"].orEmpty()
            queried += query
            if (query.isNotBlank()) queryCategories += request.url.parameters["cat"].orEmpty()
            val count = if (query == "NBA") request.url.parameters["limit"]!!.toInt() else if (query == "Euroleague") 1 else 0
            val items = (0 until count).joinToString("") { index ->
                "<item><title>$query.2026.10.02.Match.$index</title><guid>$query-$index</guid><link>https://indexer.example/$query-$index</link></item>"
            }
            respond("<rss><channel>$items</channel></rss>")
        })
        try {
            val plan = tvSportsRefreshPlan(SportBucket.BASKETBALL.name, "")
            val results = fetchTvSportsRefresh(NewznabClient(http), "https://indexer.example", "test-key", "5060", plan,
                searchCategories = "5000")
            assertEquals(setOf("5000"), queryCategories)
            assertTrue(queried.containsAll(listOf("NBA", "Euroleague", "Eurocup", "Basketball")))
            assertTrue(results.any { it.title.startsWith("Euroleague.") })
            assertTrue(results.any { it.title.startsWith("NBA.") })
        } finally { http.close() }
    }

    @Test
    fun otherCategoriesCannotEvictTheSelectedBucketsResults() {
        val plan = tvSportsRefreshPlan(SportBucket.BASKETBALL.name, "").copy(maxItems = 2)
        val merged = mergeTvSportsRefresh(
            listOf(item("football", "NFL.2026.10.06.Final"), item("hockey", "NHL.2026.10.06.Final")),
            listOf(item("euro", "Euroleague.2026.10.02.Final"), item("nba", "NBA.2026.10.01.Final"),
                item("noise", "Tanked.S03E04.NBA.Star")), plan)
        assertEquals(setOf("euro", "nba"), merged.filter { SportBucket.classify(it.title) == SportBucket.BASKETBALL }.map { it.guid }.toSet())
        assertFalse(merged.any { it.guid == "noise" })
    }

    @Test
    fun resultLimitDefaultsToTwoHundredAndCyclesThroughExplicitOptions() {
        assertEquals(listOf(200, 500, 1_000, 2_000), SPORTS_RESULT_LIMIT_OPTIONS)
        assertEquals(200, normalizeSportsResultLimit(37))
        assertEquals(500, nextSportsResultLimit(200))
        assertEquals(1_000, nextSportsResultLimit(500))
        assertEquals(2_000, nextSportsResultLimit(1_000))
        assertEquals(200, nextSportsResultLimit(2_000))
        assertEquals(1_000, tvSportsRefreshPlan(SPORTS_FILTER_ALL, "", 1_000).maxItems)
    }

    @Test
    fun refreshJobsCoalescePerCategoryWithoutGloballyLockingSports() {
        assertEquals(
            tvSportsRefreshJobKey("tv_sports", SportBucket.SOCCER.name),
            tvSportsRefreshJobKey("tv_sports", SportBucket.SOCCER.name),
        )
        assertFalse(
            tvSportsRefreshJobKey("tv_sports", SportBucket.SOCCER.name) ==
                tvSportsRefreshJobKey("tv_sports", SportBucket.BASKETBALL.name),
        )
    }

    @Test
    fun bucketRefreshReplacesOnlyThatBucket() {
        val oldFootball = item("old-football", "NFL Week 1")
        val basketball = item("basketball", "NBA Finals")
        val tennis = item("tennis", "Wimbledon Final")
        val newFootball = item("new-football", "NFL Week 2")
        val plan = tvSportsRefreshPlan(SportBucket.AMERICAN_FOOTBALL.name, "")

        val merged = mergeTvSportsRefresh(
            existing = listOf(oldFootball, basketball, tennis),
            fetched = listOf(newFootball),
            plan = plan,
        )

        assertFalse(merged.any { it.guid == "old-football" })
        assertTrue(merged.any { it.guid == "new-football" })
        assertTrue(merged.any { it.guid == "basketball" })
        assertTrue(merged.any { it.guid == "tennis" })
    }

    @Test
    fun fullRefreshRanksByEventDateBeforeUploadDate() {
        val olderEventUploadedToday = item(
            guid = "older-event",
            title = "MLS.2026.09.05.Team.One.vs.Team.Two",
            pubDate = "Thu, 10 Sep 2026 08:00:00 +0000",
        )
        val newerEventUploadedYesterday = item(
            guid = "newer-event",
            title = "NFL.2026.09.09.Seattle.Seahawks.vs.New.England.Patriots",
            pubDate = "Wed, 09 Sep 2026 23:00:00 +0000",
        )

        val merged = mergeTvSportsRefresh(
            existing = emptyList(),
            fetched = listOf(olderEventUploadedToday, newerEventUploadedYesterday),
            plan = tvSportsRefreshPlan(SPORTS_FILTER_ALL, ""),
        )

        assertEquals(listOf("newer-event", "older-event"), merged.mapNotNull { it.guid })
    }

    @Test
    fun fullRefreshCutsOldestItemsFromTheBottom() {
        val newest = item("newest", "NFL.2026.09.10.Newest")
        val middle = item("middle", "NFL.2026.09.09.Middle")
        val oldest = item("oldest", "NFL.2026.09.08.Oldest")
        val plan = tvSportsRefreshPlan(SPORTS_FILTER_ALL, "").copy(maxItems = 2)

        val merged = mergeTvSportsRefresh(
            existing = emptyList(),
            fetched = listOf(oldest, newest, middle),
            plan = plan,
        )

        assertEquals(listOf("newest", "middle"), merged.mapNotNull { it.guid })
    }

    @Test
    fun specificDateModesRoundTripAndParseReleaseSeparators() {
        val date = LocalDate.of(2026, 9, 9)

        assertEquals(date, sportsDateFromMode(sportsDateMode(date)))
        assertEquals(date, sportsEventDate("NFL.2026.09.09.Seahawks.vs.Patriots"))
        assertEquals(date, sportsEventDate("NFL 2026 09 09 Seahawks vs Patriots"))
        assertEquals(date, sportsEventDate("NFL-2026-09-09-Seahawks-vs-Patriots"))
    }

    @Test
    fun emptyBucketRefreshDoesNotDestroyOtherCategoryCaches() {
        val football = item("football", "NFL Week 1")
        val basketball = item("basketball", "NBA Finals")

        val merged = mergeTvSportsRefresh(
            existing = listOf(football, basketball),
            fetched = emptyList(),
            plan = tvSportsRefreshPlan(SportBucket.AMERICAN_FOOTBALL.name, ""),
        )

        assertEquals(listOf("basketball"), merged.mapNotNull { it.guid })
    }

    @Test
    fun fallbackStableIdDoesNotExposeCredentialBearingUrl() {
        val item = NewznabItem(
            title = "NBA",
            nzbUrl = "https://indexer.example/api?t=get&apikey=secret",
        )

        assertTrue(item.sportsStableId().startsWith("nzb_"))
        assertFalse(item.sportsStableId().contains("secret"))
    }

    @Test
    fun resolvingAndFailedSourceRowsAlwaysRemainFocusable() {
        assertEquals(
            TvSportsEventInteraction(canFocus = true, acceptsActivation = false),
            tvSportsEventInteraction(torboxConfigured = true, isWorking = true),
        )
        assertEquals(
            TvSportsEventInteraction(canFocus = true, acceptsActivation = true),
            tvSportsEventInteraction(torboxConfigured = true, isWorking = false),
        )
        assertEquals(
            TvSportsEventInteraction(canFocus = true, acceptsActivation = false),
            tvSportsEventInteraction(torboxConfigured = false, isWorking = false),
        )
    }

    private fun item(
        guid: String,
        title: String,
        pubDate: String = "Thu, 27 Aug 2026 10:00:00 +0000",
    ) = NewznabItem(
        title = title,
        nzbUrl = "https://example.invalid/$guid",
        guid = guid,
        pubDate = pubDate,
    )
}
