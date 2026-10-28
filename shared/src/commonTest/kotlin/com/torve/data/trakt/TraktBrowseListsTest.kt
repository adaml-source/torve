package com.torve.data.trakt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class TraktBrowseListsTest {
    @Test fun pastedListUrlsCannotChangeTheApiHostOrPathShape() {
        assertEquals("/users/jane/lists/family-films", TraktListLinks.parse("https://trakt.tv/users/jane/lists/family-films")?.path)
        assertEquals("/lists/123", TraktListLinks.parse("https://app.trakt.tv/lists/123?sort=rank")?.path)
        listOf("https://trakt.tv.evil.test/lists/123", "https://trakt.tv@evil.test/lists/123", "https://user:secret@trakt.tv/lists/123", "file:///lists/123", "https://trakt.tv/users/jane/lists/..", "https://trakt.tv/users/jane/lists/a%2Fb", "https://trakt.tv/lists/-1").forEach { assertNull(TraktListLinks.parse(it), it) }
    }
    @Test fun searchAndPaginationUseTheOfficialListEndpoints() = runTest {
        val requests = mutableListOf<Url>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url
            val body = when (request.url.encodedPath) {
                "/search/list", "/lists/trending" -> """[{"list":{"name":"Mystery","ids":{"trakt":12},"item_count":42},"like_count":90}]"""
                "/users/jane/lists/mystery" -> """{"name":"Mystery","ids":{"trakt":12}}"""
                "/lists/12/items/movie,show" -> """[{"type":"movie","movie":{"title":"Example","ids":{"tmdb":1},"images":{"poster":["example.test/poster.jpg"]}}}]"""
                else -> "[]"
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = TraktClient(http, Json { ignoreUnknownKeys = true })
            assertEquals(90, api.browsePublicLists("mystery", 2).single().likes)
            assertEquals("2", requests.last().parameters["page"])
            assertEquals("mystery", requests.last().parameters["query"])
            assertEquals(12, api.browsePublicLists("https://trakt.tv/users/jane/lists/mystery").single().ids.trakt)
            assertEquals("Example", api.getPublicListItems(12, 3).single().movie?.title)
            assertEquals("3", requests.last().parameters["page"])
            assertEquals("30", requests.last().parameters["limit"])
            assertTrue(requests.all { it.host == "api.trakt.tv" })
        } finally { http.close() }
    }
}
