package com.torve.presentation.detail

import com.torve.data.metadata.*
import com.torve.data.trakt.TraktClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ContentCommunityTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun movieFactsDecodeAllFieldsAndDoNotInventZeroFinancials() {
        val movie = json.decodeFromString<TmdbMovie>("""{"id":1,"original_title":"호프","original_language":"ko","tagline":"Hope","status":"Released","release_date":"2026-10-08","budget":46000000,"revenue":41212538,"production_companies":[{"id":2,"name":"Studio"}],"production_countries":[{"iso_3166_1":"KR","name":"South Korea"}]}""")
        val facts = movieFacts(movie).associate { it.label to it.value }
        assertEquals("호프", facts["Original title"])
        assertEquals("KO", facts["Original language"])
        assertEquals("South Korea", facts["Origin country"])
        assertEquals("46000000", facts["Budget (USD)"])
        assertEquals("41212538", facts["Revenue (USD)"])
        val absent = movieFacts(TmdbMovie(id = 1, budget = 0, revenue = -1))
        assertTrue(absent.isEmpty())
    }
    @Test fun showFactsUseOriginalNameFirstAirDateAndNetworks() {
        val facts = tvFacts(TmdbTv(id = 2, originalName = "Original", firstAirDate = "2026-10-20",
            networks = listOf(TmdbCompany(3, "Network")), originCountry = listOf("US"))).associate { it.label to it.value }
        assertEquals("2026-10-20", facts["First aired"])
        assertEquals("Network", facts["Networks"])
        assertEquals("US", facts["Origin country"])
        assertFalse(facts.containsKey("Budget (USD)"))
    }
    @Test fun commentsArePublicPagedAndPreserveSpoilerMetadata() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("2", request.headers["trakt-api-version"])
            assertEquals("10", request.url.parameters["limit"])
            requests += request.url.encodedPath
            respond("""[{"id":17,"comment":"A [spoiler]secret[/spoiler]","spoiler":false,"likes":3,"created_at":"2026-10-20T00:00:00Z","user_rating":9,"user":{"username":"viewer"}}]""",
                HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json"), "X-Pagination-Page-Count" to listOf("2")))
        })
        try {
            val client = TraktClient(http, json)
            val first = client.getContentComments("tt123", false, 1)
            assertTrue(first.hasMore)
            assertTrue(first.items.single().containsSpoilers)
            assertEquals("viewer", first.items.single().author)
            assertEquals("https://trakt.tv/comments/17", first.items.single().permalink)
            val last = client.getContentComments("tt124", true, 2)
            assertFalse(last.hasMore)
            assertEquals(listOf("/movies/tt123/comments/newest", "/shows/tt124/comments/newest"), requests)
        } finally { http.close() }
    }
    @Test fun unknownIdentityNeverSendsAnUnrelatedCommentsRequest() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; error("No usable identity") })
        try {
            val service = ContentCommunity(TmdbApiClient(http), TraktClient(http, json))
            val item = com.torve.domain.model.MediaItem(id = "opaque", title = "Provider title", type = com.torve.domain.model.MediaType.MOVIE)
            assertTrue(service.comments(item, 1).items.isEmpty())
            assertEquals(0, calls)
        } finally { http.close() }
    }
}
