package com.torve.android.tv.screens

import com.torve.data.usenet.NewznabItem
import com.torve.domain.sports.SportBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSportsRefreshPolicyTest {
    @Test
    fun allIsTheOnlyFullCatalogRefreshPlan() {
        val all = tvSportsRefreshPlan(SPORTS_FILTER_ALL, "")
        val football = tvSportsRefreshPlan(SportBucket.AMERICAN_FOOTBALL.name, "")
        val basketball = tvSportsRefreshPlan(SportBucket.BASKETBALL.name, "")

        assertEquals(TvSportsRefreshKind.ALL, all.kind)
        assertEquals(TvSportsRefreshKind.BUCKET, football.kind)
        assertEquals(SportBucket.AMERICAN_FOOTBALL, football.bucket)
        assertEquals("NFL", football.remoteQuery)
        assertEquals(TvSportsRefreshKind.BUCKET, basketball.kind)
        assertEquals("NBA", basketball.remoteQuery)
    }

    @Test
    fun todayAndRecentUseBoundedProviderRequests() {
        val today = tvSportsRefreshPlan(SPORTS_FILTER_TODAY, "")
        val recent = tvSportsRefreshPlan(SPORTS_FILTER_RECENT, "")

        assertEquals(1, today.maxAgeDays)
        assertEquals(100, today.maxItems)
        assertEquals(80, recent.maxItems)
        assertTrue(today.kind != TvSportsRefreshKind.ALL)
        assertTrue(recent.kind != TvSportsRefreshKind.ALL)
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

    private fun item(guid: String, title: String) = NewznabItem(
        title = title,
        nzbUrl = "https://example.invalid/$guid",
        guid = guid,
        pubDate = "Thu, 27 Aug 2026 10:00:00 +0000",
    )
}
