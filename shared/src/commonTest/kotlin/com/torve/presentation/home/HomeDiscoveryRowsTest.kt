package com.torve.presentation.home

import com.torve.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HomeDiscoveryRowsTest {
    @Test fun preliminaryRailGrowthKeepsVisiblePostersAndHeroStable() {
        val first=row("trending-movies",1..8)
        val state=HomeUiState(shelves=listOf(first),heroItem=first.items.first())
        val grown=state.withInitialDiscoveryShelves(listOf(row("trending-movies",1..20)))
        assertEquals(20,grown.shelves.single().items.size)
        assertEquals(first.items,grown.shelves.single().items.take(8))
        assertEquals(state.heroItem,grown.heroItem)
        assertSame(grown,grown.withInitialDiscoveryShelves(listOf(first)))
        assertSame(grown,grown.withInitialDiscoveryShelves(listOf(row("trending-movies",1..30))))
        assertSame(state,state.withInitialDiscoveryShelves(listOf(row("trending-movies",10..35))))
    }
    @Test fun peopleRowsRefillBothEnabledRolesUntilTwentyAreAvailable() = runTest {
        val actors = (1..20).map { PersonSummary(it, "Actor $it", knownForDepartment = "Acting") }
        val directors = (21..40).map { PersonSummary(it, "Director $it", knownForDepartment = "Directing") }
        val result = fillHomePeopleRows(actors, actors = true, directors = true) { page ->
            assertEquals(2, page); directors
        }
        assertEquals(20, result.count { it.knownForDepartment == "Acting" })
        assertEquals(20, result.count { it.knownForDepartment == "Directing" })
    }
    @Test fun disabledPeopleRowsDoNotFetchAndExhaustedRowsKeepTheirPeople() = runTest {
        val small = listOf(PersonSummary(1, "Actor", knownForDepartment = "Acting"))
        assertEquals(small, fillHomePeopleRows(small, false, false) { error("disabled") })
        assertEquals(small, fillHomePeopleRows(small, true, false) { emptyList() })
    }
    private fun items(range: IntRange) = range.map { MediaItem("$it", tmdbId = it, type = MediaType.MOVIE, title = "Title $it") }
    private fun row(id: String, range: IntRange) = CatalogShelf(id, id, items(range))

    @Test fun trendingKeepsTheSameRankedMoviesWhenPersonalRowsFinishLoading() = runTest {
        val trending = row("trending-movies", 1..40)
        val firstPaint = deduplicateDiscoveryRows(listOf(trending), mutableSetOf()).single()
        val personalKeys = items(2..8).mapTo(mutableSetOf()) { it.stableKey() }
        val completed = fillHomeDiscoveryRows(listOf(trending), personalKeys) { _, _ ->
            error("The ranked source already contains enough items")
        }.single()
        assertEquals(firstPaint.items, completed.items)
        assertEquals(listOf("1", "2", "3"), completed.items.take(3).map { it.id })
    }

    @Test fun trendingShowsKeepTheirRankingRegardlessOfEarlierRowsOrPersonalState() = runTest {
        val shows = items(1..30).map { it.copy(type = MediaType.SERIES) }
        val trending = CatalogShelf("trending-tv", "Trending TV Shows", shows)
        val earlier = CatalogShelf("airing-today", "Airing today", shows.take(20))
        val personalKeys = shows.mapTo(mutableSetOf()) { it.stableKey() }
        assertEquals(shows, deduplicateDiscoveryRows(listOf(earlier, trending), personalKeys.toMutableSet()).last().items)
        assertEquals(shows, fillHomeDiscoveryRows(listOf(earlier, trending), personalKeys) { _, _ ->
            HomeDiscoveryPage(emptyList(), false)
        }.last().items)
    }

    @Test fun rankedRowsStillRemoveDuplicateCardsWithinTheSameRow() = runTest {
        val repeated = row("trending-movies", 1..25).let { it.copy(items = it.items + it.items) }
        assertEquals(items(1..25), deduplicateDiscoveryRows(listOf(repeated), mutableSetOf()).single().items)
        assertEquals(items(1..25), fillHomeDiscoveryRows(listOf(repeated)) { _, _ ->
            HomeDiscoveryPage(emptyList(), false)
        }.single().items)
    }

    @Test fun repeatedPreliminaryRefreshesCannotReinsertTitlesOrReplaceCachedArtwork() {
        val preliminary = row("trending-movies", 1..40)
        val completed = preliminary.copy(items = preliminary.items.filter { it.id != "2" }.map {
            it.copy(posterUrl = "cached-${it.id}.jpg", ratings = MediaRatings(imdbScore = 8f))
        })
        val visible = HomeUiState(shelves = listOf(completed), heroItem = completed.items.first(), isLoading = false)
        repeat(3) {
            assertSame(visible, visible.withInitialDiscoveryShelves(listOf(preliminary)))
        }
        assertFalse(visible.shelves.single().items.any { it.id == "2" })
    }

    @Test fun personalRowsDoNotHoldBackTheFirstColdDiscoveryPaint() {
        val saved = HomeUiState(watchlistItems = items(2..2), upcomingSchedule = items(3..3))
        val incoming = listOf(row("trending-movies", 1..25))
        val result = saved.withInitialDiscoveryShelves(incoming)
        assertEquals(incoming, result.shelves)
        assertEquals(saved.watchlistItems, result.watchlistItems)
        assertEquals(saved.upcomingSchedule, result.upcomingSchedule)
        assertEquals(incoming.first().items.first(), result.heroItem)
        assertFalse(result.isLoading)
    }

    @Test fun emptyPreliminaryResponseCannotClearAnExistingHomeOrItsError() {
        val visible = HomeUiState(shelves = listOf(row("trending-movies", 1..25)), error = "offline")
        assertSame(visible, visible.withInitialDiscoveryShelves(emptyList()))
        val cold = HomeUiState(error = "offline")
        assertSame(cold, cold.withInitialDiscoveryShelves(listOf(row("trending-movies", 1..0))))
    }

    @Test fun fullRefreshCanPublishNewRankingsWhileSubsequentPreliminaryUpdatesStaySuppressed() {
        val old = HomeUiState(shelves = listOf(row("trending-movies", 1..25)))
        val fresh = old.copy(shelves = listOf(row("trending-movies", 10..35)))
        assertSame(fresh, fresh.withInitialDiscoveryShelves(old.shelves))
        assertEquals("10", fresh.shelves.single().items.first().id)
    }

    @Test fun popularAndTopRatedNeverLoseTitlesToOtherRowsIncludingFirstPaint() = runTest {
        val shelves = listOf(row("trending-movies", 1..20), row("popular-movies", 1..20), row("top-rated", 1..20))
        val seen = items(1..20).mapTo(mutableSetOf()) { it.stableKey() }
        val firstPaint = deduplicateDiscoveryRows(shelves, seen.toMutableSet())
        assertEquals(20, firstPaint[1].items.size); assertEquals(20, firstPaint[2].items.size)
        val result = fillHomeDiscoveryRows(shelves, seen) { _, _ -> HomeDiscoveryPage(emptyList(), false) }
        assertTrue(result.all { it.items.size == 20 })
        assertEquals(result[1].items, result[2].items)
    }
    @Test fun emptiedRowsRefillFromTheirOwnSourceAndNeverSkipSecondPage() = runTest {
        val seen = items(1..20).mapTo(mutableSetOf()) { it.stableKey() }
        val requests = mutableListOf<Int>()
        val result = fillHomeDiscoveryRows(listOf(row("upcoming", 1..20)), seen) { id, page ->
            assertEquals("upcoming", id); requests += page
            HomeDiscoveryPage(items(21..40), false)
        }
        assertEquals(listOf(2), requests); assertEquals(items(21..40), result.single().items)
    }
    @Test fun smallerAndEvenFullyRepeatedResultsSurviveAfterSourceExhaustion() = runTest {
        val seen = items(1..50).mapTo(mutableSetOf()) { it.stableKey() }
        val result = fillHomeDiscoveryRows(listOf(row("upcoming", 1..7)), seen) { _, _ -> HomeDiscoveryPage(emptyList(), false) }
        assertEquals(items(1..7), result.single().items)
    }
    @Test fun emptyFilteredPagesDoNotEndTheUnderlyingCatalogueAndPolicyCannotBeBypassed() = runTest {
        val pages = mutableListOf<Int>()
        val result = fillHomeDiscoveryRows(listOf(row("upcoming", 1..0)), filter = { it.filter { item -> item.tmdbId != 99 } }) { _, page ->
            pages += page
            if (page == 1) HomeDiscoveryPage(emptyList(), true) else HomeDiscoveryPage(items(1..20) + items(99..99), false)
        }
        assertEquals(listOf(1, 2), pages); assertEquals(items(1..20), result.single().items)
    }
    @Test fun refillAlsoRunsWithDuplicateRemovalDisabledAndStopsAtTheTarget() = runTest {
        var fetched = 0
        val result = fillHomeDiscoveryRows(listOf(row("upcoming", 1..5)), deduplicate = false) { _, page ->
            fetched++; assertEquals(2, page); HomeDiscoveryPage(items(6..20), true)
        }
        assertEquals(1, fetched); assertEquals(20, result.single().items.size)
    }
    @Test fun failuresKeepValidContentAndCancellationStopsWork() = runTest {
        assertEquals(5, fillHomeDiscoveryRows(listOf(row("upcoming", 1..5))) { _, _ -> error("offline") }.single().items.size)
        assertFailsWith<CancellationException> {
            fillHomeDiscoveryRows(listOf(row("upcoming", 1..5))) { _, _ -> throw CancellationException("stop") }
        }
    }
    @Test fun missingEnabledDiscoverySourcesCanRetryWhileDisabledRowsStayDisabled() {
        val result = discoveryRowsWithMissingSources(emptyList(), listOf(
            HomeSectionConfig(HomeSection.TOP_RATED, true, 0), HomeSectionConfig(HomeSection.NEW_RELEASES, false, 1)))
        assertEquals(listOf("top-rated"), result.map { it.id })
    }
    @Test fun limitedSavedOrProviderRowsKeepActualContentWithoutInventingMoreOrDuplicates() {
        assertEquals(items(1..7), keepingAvailableRowMinimum(items(1..2), items(1..7)) { it.stableKey() })
        assertEquals(20, keepingAvailableRowMinimum(items(1..2), items(1..30)) { it.stableKey() }.size)
    }
}
