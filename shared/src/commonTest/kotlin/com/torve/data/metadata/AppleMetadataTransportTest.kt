package com.torve.data.metadata

import com.torve.domain.model.MediaRatings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AppleMetadataTransportTest {
    @Test fun appleUsesBackendWithoutReadingOrSendingProviderKey() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            assertEquals("api.torve.app", request.url.host)
            assertEquals("/apple-metadata/v1/trending/movie/week", request.url.encodedPath)
            assertEquals("de", request.url.parameters["language"])
            assertNull(request.url.parameters["api_key"])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("apple", request.headers["X-Torve-Platform"])
            respond("""{"page":1,"results":[{"id":550,"title":"Beispiel","poster_path":"https://api.torve.app/apple-metadata/v1/images/abc","torve_ratings":{"imdbScore":8.8,"traktScore":82},"imdb_id":"tt0137523"}],"total_pages":1,"total_results":1}""",
                headers = headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val api = TmdbApiClient(http, apiKeyProvider={ error("Apple must not read TMDB credentials") },
                languageProvider={ "de" }, baseUrl="https://api.torve.app/apple-metadata/v1")
            val dto=api.getTrending("movie").results.single()
            val item=TmdbMappers.movieToMediaItem(dto)
            assertEquals(550,item.tmdbId)
            assertEquals("tt0137523",item.imdbId)
            assertEquals(MediaRatings(imdbScore=8.8f,traktScore=82f),item.ratings)
            assertEquals(82f, com.torve.domain.model.calculateTorveScore(item.ratings!!,
                mapOf(com.torve.domain.model.RatingSource.TRAKT to 1)))
            assertTrue(item.posterUrl!!.startsWith("https://api.torve.app/"))
            assertTrue(api.titleCompletions("example").isEmpty())
            assertEquals(1,requests)
        } finally { http.close() }
    }

    @Test fun existingPlatformsRetainTmdbTransport() = runTest {
        val http=HttpClient(MockEngine { request ->
            assertEquals("api.themoviedb.org",request.url.host)
            assertEquals("fixture",request.url.parameters["api_key"])
            assertNull(request.headers["X-Torve-Platform"])
            respond("""{"page":1,"results":[]}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val api=TmdbApiClient(http,apiKeyProvider={ "fixture" },baseUrl=TmdbApiClient.BASE_URL)
            assertFalse(api.usesTorveBackend)
            api.getTrending("tv")
        } finally { http.close() }
    }
}
