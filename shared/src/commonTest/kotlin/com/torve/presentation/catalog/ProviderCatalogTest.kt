package com.torve.presentation.catalog

import com.torve.data.metadata.MetadataRepositoryImpl
import com.torve.data.metadata.TmdbApiClient
import com.torve.domain.discovery.DiscoveryRatingSource
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.settings.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.*

class ProviderCatalogTest {
    private val dispatcher = StandardTestDispatcher()
    private val requests = mutableListOf<Url>()
    private val models = mutableListOf<CatalogViewModel>()
    private var delayedPage: CompletableDeferred<Unit>? = null
    private lateinit var http: HttpClient

    @BeforeTest fun setup() {
        Dispatchers.setMain(dispatcher)
        http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = this@ProviderCatalogTest.dispatcher
            addHandler { request ->
            val url = request.url
            requests += url
            val page = url.parameters["page"]?.toIntOrNull() ?: 1
            val body = when {
                url.encodedPath.contains("/watch/providers/") -> """{"results":[{"provider_id":8,"provider_name":"Netflix"},{"provider_id":1796,"provider_name":"Netflix Standard with Ads"},{"provider_id":999,"provider_name":"Netflix Amazon Channel"}]}"""
                url.encodedPath.endsWith("/watch/providers") -> {
                    val id = url.encodedPath.split('/').dropLast(2).last().toInt()
                    val region = if (id == 102) "US" else "DE"
                    val provider = if (id == 103) 337 else 8
                    val offer = if (id == 104) "rent" else "flatrate"
                    """{"id":$id,"results":{"$region":{"$offer":[{"provider_id":$provider,"provider_name":"Service"}]}}}"""
                }
                url.encodedPath.contains("/discover/") -> {
                    if (page == 2) delayedPage?.await()
                    val type = url.encodedPath.substringAfterLast('/')
                    response(type, page, listOf(200 + page))
                }
                url.encodedPath.endsWith("/search/multi") -> response("movie", page, if (page == 1) listOf(101,102,103,104) else listOf(105))
                else -> error("Provider page requested an unscoped endpoint: ${url.encodedPath}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @AfterTest fun cleanup() {
        models.forEach { it.close() }
        http.close()
        Dispatchers.resetMain()
    }

    @Test fun movieAndTelevisionDiscoveryKeepProviderRegionFiltersAndPagination() = runTest(dispatcher) {
        for (type in listOf("movie", "tv")) {
            requests.clear()
            val vm = model(type)
            settle(vm)
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            assertEquals("DE", vm.state.value.providerRegion)
            assertTrue(vm.state.value.trendingItems.isEmpty())
            assertTrue(vm.state.value.continueWatching.isEmpty())
            assertEquals(if (type == "movie") MediaType.MOVIE else MediaType.SERIES, vm.state.value.items.single().type)
            val genre = if (type == "movie") 28 else 10759
            assertTrue(vm.genres.any { it.id == genre })
            vm.applyFilters(CatalogFilter(minRating = 7f, ratingSource = DiscoveryRatingSource.TMDB, year = 2020, yearTo = 2025, sortBy = SortOption.RELEASE_DATE_DESC), genre)
            settle(vm)
            vm.loadMore()
            settle(vm)
            val queries = requests.filter { it.encodedPath == "/3/discover/$type" }
            assertEquals(3, queries.size)
            for (query in queries) {
                assertEquals("8|1796", query.parameters["with_watch_providers"])
                assertEquals("DE", query.parameters["watch_region"])
                assertEquals("flatrate|free|ads", query.parameters["with_watch_monetization_types"])
                assertNull(query.parameters["with_networks"])
                assertNull(query.parameters["with_companies"])
            }
            for (query in queries.drop(1)) {
                assertEquals(genre.toString(), query.parameters["with_genres"])
                assertEquals("7.0", query.parameters["vote_average.gte"])
                val date = if (type == "tv") "first_air_date" else "primary_release_date"
                assertEquals("2020-01-01", query.parameters["$date.gte"])
                assertEquals("2025-12-31", query.parameters["$date.lte"])
                assertEquals("$date.desc", query.parameters["sort_by"])
            }
            assertEquals("2", queries.last().parameters["page"])
            assertEquals(2, vm.state.value.items.size)
            vm.clearFilters()
            settle(vm)
            assertEquals(8, vm.state.value.providerId)
            assertEquals(0, vm.state.value.activeFilterCount)
            assertEquals("8|1796", requests.last().parameters["with_watch_providers"])
        }
    }

    @Test fun textSearchExcludesOtherCountriesServicesAndRentalOnlyHitsOnEveryPage() = runTest(dispatcher) {
        val vm = model("movie")
        settle(vm)
        vm.updateSearchQuery("Example")
        settle(vm)
        assertEquals(listOf(101), vm.state.value.searchResults.map { it.tmdbId })
        vm.loadMore()
        settle(vm)
        assertEquals(listOf(101, 105), vm.state.value.searchResults.map { it.tmdbId })
        // A filter change while searching must reapply the current query, not show old hits.
        vm.applyFilters(CatalogFilter(year = 2024, yearTo = 2024, minRating = 9f), 18)
        settle(vm)
        assertTrue(vm.state.value.searchResults.isEmpty())
        assertFalse(vm.state.value.isSearching)
        vm.clearFilters()
        settle(vm)
        assertEquals(listOf(101), vm.state.value.searchResults.map { it.tmdbId })
    }

    @Test fun changingFilterCancelsAnOlderPageBeforeItCanAppendWrongTitles() = runTest(dispatcher) {
        val vm = model("movie")
        settle(vm)
        delayedPage = CompletableDeferred()
        vm.loadMore()
        runCurrent()
        vm.applyFilters(CatalogFilter(year = 2024), 18)
        runCurrent()
        delayedPage!!.complete(Unit)
        settle(vm)
        assertEquals(1, vm.state.value.currentPage)
        assertEquals(listOf(201), vm.state.value.items.map { it.tmdbId })
        assertFalse(vm.state.value.isLoadingMore)
        assertNull(vm.state.value.error)
    }

    @Test fun favouriteServicesKeepAllFiltersAndProviderScopeAcrossPagination() = runTest(dispatcher) {
        val vm = model("tv", mapOf(8 to "Netflix", 337 to "Disney+")); settle(vm)
        vm.applyFilters(CatalogFilter(year = 2020, yearTo = 2025, minRating = 7f,
            ratingSource = DiscoveryRatingSource.TMDB, sortBy = SortOption.RELEASE_DATE_DESC), 18)
        settle(vm); vm.loadMore(); settle(vm)
        val queries = requests.filter { it.encodedPath == "/3/discover/tv" }
        assertEquals(3, queries.size)
        queries.forEach { assertEquals("8|337|1796", it.parameters["with_watch_providers"]); assertEquals("DE", it.parameters["watch_region"]) }
        queries.drop(1).forEach {
            assertEquals("18", it.parameters["with_genres"]); assertEquals("2020-01-01", it.parameters["first_air_date.gte"])
            assertEquals("2025-12-31", it.parameters["first_air_date.lte"]); assertEquals("7.0", it.parameters["vote_average.gte"])
        }
        vm.clearFilters(); settle(vm)
        assertEquals("8|337|1796", requests.last().parameters["with_watch_providers"])
    }
    @Test fun emptyFavouriteGroupNeverRequestsAnUnscopedCatalogue() = runTest(dispatcher) {
        val vm = model("movie", emptyMap()); settle(vm)
        assertTrue(vm.state.value.items.isEmpty()); assertNotNull(vm.state.value.error)
        assertTrue(requests.none { it.encodedPath.contains("/discover/") })
    }

    @Test fun unknownMetadataCannotPassStrictGenreYearRatingOrRuntimeFilters() {
        val unknown = com.torve.domain.model.MediaItem(id = "1", type = MediaType.MOVIE, title = "Unknown")
        assertFalse(ProviderCatalogPolicy.matchesFilters(unknown, "tv", null, CatalogFilter()))
        assertFalse(ProviderCatalogPolicy.matchesFilters(unknown, "movie", 18, CatalogFilter()))
        assertFalse(ProviderCatalogPolicy.matchesFilters(unknown, "movie", null, CatalogFilter(year = 2020)))
        assertFalse(ProviderCatalogPolicy.matchesFilters(unknown, "movie", null, CatalogFilter(yearTo = 2025)))
        assertFalse(ProviderCatalogPolicy.matchesFilters(unknown, "movie", null, CatalogFilter(minRating = 7f, ratingSource = DiscoveryRatingSource.TMDB)))
        assertTrue(CatalogFilter(minRating = 7f).applyRatingOrder(listOf(unknown)).isEmpty())
        assertFalse(ProviderCatalogPolicy.matchesFilters(unknown, "movie", null, CatalogFilter(runtimeFilter = RuntimeFilter.SHORT)))
        assertTrue(ProviderCatalogPolicy.matchesFilters(unknown, "movie", null, CatalogFilter()))
    }

    private suspend fun TestScope.settle(vm: CatalogViewModel) {
        advanceUntilIdle()
        // Snapshot reads use the real I/O dispatcher; a virtual-clock drain
        // alone does not mean the first page or a filter request has finished.
        withContext(Dispatchers.Default) { withTimeout(5_000) {
            while (vm.state.value.let { it.isLoading || it.isLoadingMore || it.isSearching || it.isSearchingMore }) delay(10)
        } }
        advanceUntilIdle()
    }

    private fun model(type: String, group: Map<Int, String>? = null): CatalogViewModel = CatalogViewModel(
        metadataRepo = MetadataRepositoryImpl(TmdbApiClient(http, apiKeyProvider = { "fixture" }, resolvedHostProvider = { null })),
        mediaType = type, initialProviderId = 8, initialProviderName = "Netflix", initialProviderGroup = group,
        prefsRepo = object : PreferencesRepository {
            override suspend fun getString(key: String): String? = if (key == SettingsViewModel.KEY_REGION_CODE) "de" else null
            override suspend fun setString(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        },
        watchProgressRepo = object : WatchProgressRepository {
            override suspend fun getInProgress(limit: Long): List<WatchProgress> = error("Unscoped history must not appear on a provider page")
            override suspend fun getProgress(mediaId: String): WatchProgress? = null
            override suspend fun saveProgress(progress: WatchProgress) {}
            override suspend fun getAllProgress(): List<WatchProgress> = emptyList()
            override suspend fun deleteProgress(mediaId: String) {}
            override suspend fun clearAllProgress() {}
            override suspend fun syncFromTrakt() {}
        },
    ).also { models += it }

    private fun response(type: String, page: Int, ids: List<Int>): String {
        val items = ids.joinToString(",") { """{"id":$it,"media_type":"$type","title":"Example $it","name":"Example $it","release_date":"2024-01-01","first_air_date":"2024-01-01","genre_ids":[18],"vote_average":7.5}""" }
        return """{"page":$page,"total_pages":2,"total_results":5,"results":[$items]}"""
    }
}
