package com.torve.presentation.catalog

import com.torve.data.PersonalDeviceFixture
import com.torve.data.mdblist.*
import com.torve.data.metadata.*
import com.torve.data.ratings.*
import com.torve.data.trakt.TraktClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderCatalogPresentationTest {
    @Test fun postersPublishBeforeSlowRatingsAndReopenFromCacheWhileDiscoveryIsBlocked() = runTest {
        checkPresentation(strictMinimum = false)
    }

    @Test fun minimumImdbRatingNeverPublishesUnverifiedTitlesDuringSlowRatings() = runTest {
        checkPresentation(strictMinimum = true)
    }

    private suspend fun TestScope.checkPresentation(strictMinimum: Boolean) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ratingsStarted = CompletableDeferred<Unit>()
        val releaseRatings = CompletableDeferred<Unit>()
        var blockedDiscovery: CompletableDeferred<Unit>? = null
        var providerLookups = 0
        val client = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.contains("/watch/providers/") -> {
                    providerLookups++
                    """{"results":[{"provider_id":9,"provider_name":"Amazon Prime Video"}]}"""
                }
                request.url.encodedPath.contains("/discover/") -> {
                    blockedDiscovery?.await()
                    """{"page":1,"total_pages":3,"total_results":40,"results":[{"id":1,"title":"First film","poster_path":"/first.jpg","vote_average":9.9},{"id":2,"title":"Second film","poster_path":"/second.jpg","vote_average":1.0}]}"""
                }
                request.url.encodedPath == "/me/ratings/batch" -> {
                    ratingsStarted.complete(Unit); releaseRatings.await()
                    val ids = Json.parseToJsonElement((request.body as TextContent).text).jsonObject.getValue("items").jsonArray
                        .map { it.jsonObject.getValue("tmdb_id").jsonPrimitive.int }
                    """{"items":[${ids.joinToString(",") { """{"media_type":"movie","tmdb_id":$it,"imdb_score":${if (it == 1) 4 else 9},"imdb_votes":1000,"rotten_tomatoes_score":90}""" }}]}"""
                }
                else -> error("Unexpected provider request: ${request.url.encodedPath}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            PersonalDeviceFixture(client).use { device ->
                device.signIn()
                val tmdb = TmdbApiClient(client, apiKeyProvider = { "fixture" }, resolvedHostProvider = { null })
                val enricher = RatingsEnricher(MdbListApi(client), tmdb, TraktClient(client, Json),
                    RatingsCacheRepository(device.database), OmdbClient(client, device.preferences, device.secrets),
                    BackendRatingsApi(client, { "https://fixture.invalid" }, { "fixture-token" }))
                fun model() = CatalogViewModel(MetadataRepositoryImpl(tmdb), "movie", prefsRepo = device.preferences,
                    ratingsEnricher = enricher, initialProviderId = 9, initialProviderName = "Prime Video")
                val vm = model()
                try {
                    if (strictMinimum) vm.applyFilters(CatalogFilter(minRating = 8f), null)
                    withContext(Dispatchers.Default) { withTimeout(8_000) { ratingsStarted.await() } }
                    if (strictMinimum) assertTrue(vm.state.value.items.isEmpty())
                    else {
                        assertFalse(vm.state.value.isLoading, "Artwork must not wait for the ratings response")
                        assertEquals(2, vm.state.value.items.size)
                        assertTrue(vm.state.value.items.all { !it.posterUrl.isNullOrBlank() })
                    }
                    releaseRatings.complete(Unit)
                    withContext(Dispatchers.Default) { withTimeout(8_000) {
                        while (vm.state.value.items.firstOrNull()?.ratings?.imdbScore != 9f) delay(10)
                    } }
                    assertEquals(if (strictMinimum) listOf(2) else listOf(2, 1), vm.state.value.items.map { it.tmdbId })
                    if (!strictMinimum) {
                        val snapshots = ProviderCatalogSnapshotStore(device.preferences, "movie")
                        val key = snapshots.fingerprint(vm.state.value, "US")
                        withContext(Dispatchers.Default) { withTimeout(8_000) {
                            while (snapshots.read(9, key)?.items?.firstOrNull()?.ratings?.imdbScore != 9f) delay(10)
                        } }
                        vm.close(); blockedDiscovery = CompletableDeferred()
                        val previousLookups = providerLookups
                        val reopened = model()
                        try {
                            withContext(Dispatchers.Default) { withTimeout(8_000) {
                                while (reopened.state.value.items.size != 2) delay(10)
                            } }
                            assertTrue(reopened.state.value.isLoading, "Background discovery should still be blocked")
                            assertEquals(listOf(2, 1), reopened.state.value.items.map { it.tmdbId })
                            assertEquals(previousLookups, providerLookups, "Saved provider IDs need no blocking lookup")
                        } finally { reopened.close() }
                    }
                } finally { vm.close(); releaseRatings.complete(Unit); blockedDiscovery?.complete(Unit) }
            }
        } finally { client.close(); Dispatchers.resetMain() }
    }
}
