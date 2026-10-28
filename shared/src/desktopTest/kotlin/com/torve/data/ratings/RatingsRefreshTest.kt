package com.torve.data.ratings

import com.torve.data.PersonalDeviceFixture
import com.torve.data.mdblist.*
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class RatingsRefreshTest {
    @Test fun focusedSavedCardRefreshesACompleteOldPairAndPreservesOtherSources() = runTest {
        verifyFocused(8f, 9.3f)
    }

    @Test fun focusedRefreshAlsoAcceptsALowerCurrentScore() = runTest {
        verifyFocused(9.3f, 8f)
    }

    private suspend fun verifyFocused(oldScore: Float, current: Float) {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("/meta/movie/tt45368240.json", request.url.encodedPath)
            calls++
            respond("""{"meta":{"id":"tt45368240","imdbRating":"$current"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        PersonalDeviceFixture(client).use { device ->
            val cache = RatingsCacheRepository(device.database)
            val old = MediaItem("42", tmdbId = 42, imdbId = "tt45368240", title = "Saved film", type = MediaType.MOVIE,
                ratings = MediaRatings(imdbScore = oldScore, imdbVotes = 203, rottenTomatoesScore = 100, tmdbScore = 9.1f))
            cache.put("MOVIE:42", old.ratings!!)
            val enricher = RatingsEnricher(MdbListApi(client), TmdbApiClient(client), TraktClient(client, Json), cache,
                OmdbClient(client, device.preferences, device.secrets), detailImdbApi = CinemetaImdbRatingsApi(client))
            repeat(2) {
                val result = assertNotNull(enricher.refreshImdb(old))
                assertEquals(current, result.ratings?.imdbScore)
                assertNull(result.ratings?.imdbVotes)
                assertEquals(100, result.ratings?.rottenTomatoesScore)
                assertEquals(9.1f, result.ratings?.tmdbScore)
                assertEquals(result.ratings, cache.getCached("MOVIE:42"))
            }
            assertEquals(1, calls, "Revisiting a focused title must reuse the bounded IMDb cache")
        }
    }

    @Test fun focusedProviderFailureDoesNotReplaceSavedRatings() = runTest {
        val client = HttpClient(MockEngine { respond("Unavailable", HttpStatusCode.ServiceUnavailable) })
        PersonalDeviceFixture(client).use { device ->
            val cache = RatingsCacheRepository(device.database)
            val old = MediaRatings(imdbScore = 8f, imdbVotes = 203, rottenTomatoesScore = 100)
            cache.put("MOVIE:42", old)
            val enricher = RatingsEnricher(MdbListApi(client), TmdbApiClient(client), TraktClient(client, Json), cache,
                OmdbClient(client, device.preferences, device.secrets), detailImdbApi = CinemetaImdbRatingsApi(client))
            assertNull(enricher.refreshImdb(MediaItem("42", tmdbId = 42, imdbId = "tt45368240", title = "Saved film", type = MediaType.MOVIE, ratings = old)))
            assertEquals(old, cache.getCached("MOVIE:42"))
        }
    }

    @Test fun detailRefreshesAnOldImdbScoreWithoutReplacingOtherSourcesOrInventingVotes() = runTest {
        verifyDetail(cached = true, expected = 9.3f, expectedCalls = 2)
    }

    @Test fun aJustFetchedButLaggedPrimaryResponseDoesNotBypassTheDetailRefresh() = runTest {
        verifyDetail(cached = false, expected = 9.3f, expectedCalls = 2)
    }

    private suspend fun verifyDetail(cached: Boolean, expected: Float, expectedCalls: Int) {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            val body = when (request.url.encodedPath) {
                "/me/ratings/batch" -> """{"items":[{"media_type":"movie","tmdb_id":42,"imdb_score":8.0,"imdb_votes":186,"cached":$cached}]}"""
                "/meta/movie/tt45368240.json" -> """{"meta":{"id":"tt45368240","imdbRating":"9.3"}}"""
                else -> error("Unexpected lookup")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        PersonalDeviceFixture(client).use { device ->
            device.signIn()
            val cache = RatingsCacheRepository(device.database)
            val old = MediaRatings(imdbScore = 8.0f, imdbVotes = 186, rottenTomatoesScore = 98, tmdbScore = 9.1f)
            cache.put("MOVIE:42", old)
            val enricher = RatingsEnricher(MdbListApi(client), TmdbApiClient(client, apiKeyProvider = { "fixture" }),
                TraktClient(client, Json), cache, OmdbClient(client, device.preferences, device.secrets),
                BackendRatingsApi(client, { "https://fixture.invalid" }, { "fixture-token" }), CinemetaImdbRatingsApi(client))
            val result = enricher.enrichDetail(MediaItem("42", tmdbId = 42, imdbId = "tt45368240", title = "New release", type = MediaType.MOVIE, ratings = old), "")
            assertEquals(expected, result.ratings?.imdbScore)
            assertEquals(98, result.ratings?.rottenTomatoesScore)
            assertEquals(9.1f, result.ratings?.tmdbScore)
            assertNull(result.ratings?.imdbVotes)
            assertEquals(result.ratings, cache.getCached("MOVIE:42"))
            assertEquals(expectedCalls, calls)
        }
    }

    @Test fun configuredProvidersCannotOverwriteTheEarlyImdbUpdate() = runTest {
        verifyConfigured(9.3f, 8f)
    }

    @Test fun aLowerCurrentImdbValueAlsoReplacesAnOlderHigherValue() = runTest {
        verifyConfigured(8f, 9.3f)
    }

    private suspend fun verifyConfigured(current: Float, older: Float) = coroutineScope {
        val releaseBackend = CompletableDeferred<Unit>()
        val early = CompletableDeferred<MediaItem>()
        val hosts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine { request ->
            hosts += request.url.host
            val body = when (request.url.host) {
                "fixture.invalid" -> {
                    releaseBackend.await()
                    """{"items":[{"media_type":"movie","tmdb_id":42,"imdb_score":$older,"cached":false}]}"""
                }
                "v3-cinemeta.strem.io" -> """{"meta":{"id":"tt45368240","imdbRating":"$current"}}"""
                "www.omdbapi.com" -> """{"Response":"True","imdbRating":"$older","Ratings":[{"Source":"Rotten Tomatoes","Value":"98%"}]}"""
                "api.mdblist.com" -> """{"imdbid":"tt45368240","ratings":[{"source":"imdb","value":$older},{"source":"trakt","value":90}]}"""
                else -> error("Unexpected host ${request.url.host}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        PersonalDeviceFixture(client).use { device ->
            device.signIn()
            device.preferences.setString(OmdbClient.KEY_OMDB_API_KEY, "fixture-key")
            val cache = RatingsCacheRepository(device.database)
            val enricher = RatingsEnricher(MdbListApi(client), TmdbApiClient(client), TraktClient(client, Json), cache,
                OmdbClient(client, device.preferences, device.secrets),
                BackendRatingsApi(client, { "https://fixture.invalid" }, { "fixture-token" }), CinemetaImdbRatingsApi(client))
            val item = MediaItem("42", tmdbId = 42, imdbId = "tt45368240", title = "Fixture", type = MediaType.MOVIE,
                ratings = MediaRatings(imdbScore = older, rottenTomatoesScore = 100))
            val result = async(Dispatchers.Default) { enricher.enrichDetail(item, "fixture-key", onUpdate = { early.complete(it) }) }
            try {
                assertEquals(current, withContext(Dispatchers.Default) { withTimeout(5_000) { early.await() } }.ratings?.imdbScore)
                assertFalse(result.isCompleted, "IMDb must update before the blocked provider finishes")
            } finally { releaseBackend.complete(Unit) }
            val final = result.await()
            assertEquals(current, final.ratings?.imdbScore)
            assertEquals(98, final.ratings?.rottenTomatoesScore)
            assertEquals(final.ratings, cache.getCached("MOVIE:42"))
            assertTrue(hosts.containsAll(listOf("fixture.invalid", "v3-cinemeta.strem.io", "www.omdbapi.com", "api.mdblist.com")))
        }
    }

    @Test fun refreshedBackendScoresReplaceOldCatalogueAndCacheValues() = runTest {
        verifyRefresh("\"imdb_score\":9.3,\"imdb_votes\":17097,\"rotten_tomatoes_score\":98", 9.3f, 17097)
    }

    @Test fun partialProviderRefreshRetainsScoresTheProviderDidNotReturn() = runTest {
        verifyRefresh("\"rotten_tomatoes_score\":98", 8.0f, 186)
    }

    private suspend fun verifyRefresh(payload: String, imdb: Float, votes: Int) {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("/me/ratings/batch", request.url.encodedPath)
            calls++
            respond("""{"items":[{"media_type":"movie","tmdb_id":42,$payload}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        PersonalDeviceFixture(client).use { device ->
            device.signIn()
            val cache = RatingsCacheRepository(device.database)
            val old = MediaRatings(imdbScore = 8.0f, imdbVotes = 186, rottenTomatoesScore = 100, tmdbScore = 9.1f)
            cache.put("MOVIE:42", old)
            val enricher = RatingsEnricher(MdbListApi(client), TmdbApiClient(client, apiKeyProvider = { "fixture" }),
                TraktClient(client, Json), cache, OmdbClient(client, device.preferences, device.secrets),
                BackendRatingsApi(client, { "https://fixture.invalid" }, { "fixture-token" }))
            val result = enricher.enrichList(listOf(MediaItem("42", tmdbId = 42, title = "New release", type = MediaType.MOVIE, ratings = old)), "").single()
            assertEquals(imdb, result.ratings?.imdbScore)
            assertEquals(votes, result.ratings?.imdbVotes)
            assertEquals(98, result.ratings?.rottenTomatoesScore)
            assertEquals(9.1f, result.ratings?.tmdbScore)
            assertEquals(result.ratings, cache.getCached("MOVIE:42"))
            assertEquals(1, calls)
        }
    }
}
