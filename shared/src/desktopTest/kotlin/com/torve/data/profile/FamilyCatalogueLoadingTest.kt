package com.torve.data.profile

import com.torve.data.PersonalDeviceFixture
import com.torve.data.metadata.*
import com.torve.domain.model.*
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class FamilyCatalogueLoadingTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }
    private suspend fun settle(predicate: () -> Boolean) {
        repeat(300) {
            testDispatcherAdvance()
            if (predicate()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        error("Catalogue loaders did not settle")
    }
    private fun testDispatcherAdvance() { dispatcher.scheduler.advanceUntilIdle() }
    private val progress = object : WatchProgressRepository {
        override suspend fun getInProgress(limit: Long) = emptyList<WatchProgress>()
        override suspend fun getProgress(mediaId: String): WatchProgress? = null
        override suspend fun saveProgress(progress: WatchProgress) {}
        override suspend fun getAllProgress() = emptyList<WatchProgress>()
        override suspend fun deleteProgress(mediaId: String) {}
        override suspend fun clearAllProgress() {}
        override suspend fun syncFromTrakt() {}
    }
    private class Fixture(val scope: CoroutineScope, var rating: ContentRating? = ContentRating.G, val noCertificates: Boolean = false) : AutoCloseable {
        val requests = mutableListOf<Url>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = Dispatchers.Main; addHandler { request ->
            requests += request.url
            delay(30)
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("release_dates") -> """{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"${if (path.contains("/1/")) "G" else "R"}"}]}]}"""
                path.endsWith("content_ratings") -> """{"results":[{"iso_3166_1":"US","rating":"${if (path.contains("/11/")) "TV-Y" else "TV-MA"}"}]}"""
                path.endsWith("discover/movie") -> """{"page":1,"total_pages":1,"total_results":3,"results":[{"id":1,"title":"Verified G","adult":false},{"id":2,"title":"R-rated","adult":false},{"id":3,"title":"Unrated","adult":false}]}"""
                path.endsWith("discover/tv") -> """{"page":1,"total_pages":1,"total_results":3,"results":[{"id":11,"name":"Verified TV-Y","adult":false},{"id":12,"name":"Mature show","adult":false},{"id":13,"name":"Unrated show","adult":false}]}"""
                else -> """{"page":1,"total_pages":1,"total_results":1,"results":[{"id":2,"title":"General adult trending","adult":false}]}"""
            }
            respond(if (noCertificates && (path.endsWith("release_dates") || path.endsWith("content_ratings")) || path.endsWith("/3/release_dates") || path.endsWith("/13/content_ratings")) """{"results":[]}""" else body,
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        } })) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val device = PersonalDeviceFixture(http)
        val api = TmdbApiClient(http, apiKeyProvider = { "fixture-key" }, resolvedHostProvider = { emptyList() }, diagnostics = {})
        val family = FamilyControls(device.preferences,device.secure,null,SettingsRefreshNotifier(),
            lookup = { api.getAgeCertifications(it) },scope = scope,accountId = { "fixture" },profileRatingLimit = { rating })
        val repository = MetadataRepositoryImpl(api,family)
        override fun close() { device.close() }
    }
    @Test fun legacyGPopulatesActualMovieAndTvLoadersAndHomeShelves() = runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            f.family.initialize()
            val movies = CatalogViewModel(f.repository,"movie",watchProgressRepo = progress,contentPolicyFilter = ContentPolicyFilter(f.family))
            val shows = CatalogViewModel(f.repository,"tv",watchProgressRepo = progress,contentPolicyFilter = ContentPolicyFilter(f.family))
            try {
                settle { !movies.state.value.isLoading && !shows.state.value.isLoading && movies.state.value.shelvesLoaded && shows.state.value.shelvesLoaded }
                for ((vm, title) in listOf(movies to "Verified G", shows to "Verified TV-Y")) {
                    assertFalse(vm.state.value.isLoading)
                    assertTrue(vm.state.value.shelvesLoaded)
                    assertEquals(listOf(title),vm.state.value.items.map { it.title })
                    assertEquals(listOf(title),vm.state.value.popularItems.map { it.title })
                    assertTrue(vm.state.value.trendingItems.all { f.family.allows(it) })
                }
                val home = f.repository.getHomeShelves()
                assertTrue(home.map { it.id }.containsAll(listOf("trending-movies", "trending-tv", "popular-movies", "top-rated")))
                assertTrue(home.all { it.items.isNotEmpty() && it.items.all { item -> f.family.allows(item) } })
                assertTrue(home.flatMap { it.items }.all { it.ageCertifications.isNotEmpty() })
                val moviesRequest = f.requests.first { it.encodedPath.endsWith("discover/movie") }
                assertEquals("G", moviesRequest.parameters["certification.lte"])
                assertEquals("US", moviesRequest.parameters["certification_country"])
                assertEquals("10762|10751",f.requests.first { it.encodedPath.endsWith("discover/tv") }.parameters["with_genres"])
                // Concurrent Home/shelf/grid loads reuse one official proof per title.
                assertEquals(1,f.requests.count { it.encodedPath.endsWith("/1/release_dates") })
                assertEquals(1,f.requests.count { it.encodedPath.endsWith("/11/content_ratings") })
            } finally { movies.close(); shows.close() }
        }
    }
    @Test fun unratedCatalogueSettlesWithNoUnsafeArtwork() = runTest(dispatcher) {
        Fixture(backgroundScope,noCertificates = true).use { f ->
            f.family.initialize()
            val vm = CatalogViewModel(f.repository,"movie",watchProgressRepo=progress,contentPolicyFilter=ContentPolicyFilter(f.family))
            try {
                settle { !vm.state.value.isLoading && vm.state.value.shelvesLoaded }
                assertFalse(vm.state.value.isLoading); assertTrue(vm.state.value.shelvesLoaded)
                assertTrue(vm.state.value.items.isEmpty()); assertTrue(vm.state.value.trendingItems.isEmpty())
                assertTrue(f.repository.getHomeShelves().isEmpty())
            } finally { vm.close() }
        }
    }
    @Test fun explicitGenresAndProviderFiltersRemainInTheQuery() = runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            f.repository.discover("tv",withGenres="99",year=2020,withWatchProviders="8",watchRegion="DE")
            val query=f.requests.first().parameters
            assertEquals("99",query["with_genres"]); assertEquals("8",query["with_watch_providers"])
            assertEquals("2020-01-01",query["first_air_date.gte"]); assertEquals("DE",query["watch_region"])
        }
    }
    @Test fun adultCacheCannotBypassGWhenTheProfileLimitChanges() = runTest(dispatcher) {
        Fixture(backgroundScope,rating=null).use { f ->
            val adult=f.repository.getTrending("movie")
            assertEquals(listOf("General adult trending"),adult.map { it.title })
            f.rating=ContentRating.G;f.family.updateSession()
            assertEquals(listOf("Verified G"),f.repository.getTrending("movie").map { it.title })
            // A raw cached search/list also goes through certification after the switch.
            assertTrue(f.repository.searchMulti("test").isEmpty())
        }
    }
    @Test fun offlineCertificationHasABoundedWaitAndConcurrentLookupsAreShared() = runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            var calls=0
            val gate=FamilyControls(f.device.preferences,f.device.secure,null,SettingsRefreshNotifier(),lookup={ calls++; delay(100_000); emptyList() },
                scope=backgroundScope,accountId={ "fixture" },profileRatingLimit={ContentRating.G})
            gate.initialize()
            val item=MediaItem("99",title="Offline",type=MediaType.MOVIE,adult=false)
            val results=coroutineScope { List(3) { async { gate.prepare(listOf(item)) } }.awaitAll() }
            assertTrue(results.all { it.isEmpty() });assertEquals(1,calls)
            assertTrue(testScheduler.currentTime <= 8_001)
        }
    }
}
