package com.torve.data.trakt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TraktClientWatchedContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun watchedShowsRequestsProgressAndFollowsPaginationHeaders() = runTest {
        val requestedPages = mutableListOf<String?>()
        val client = TraktClient(
            httpClient = HttpClient(MockEngine { request ->
                requestedPages += request.url.parameters["page"]
                assertEquals("100", request.url.parameters["limit"])
                assertEquals("progress", request.url.parameters["extended"])
                assertEquals("Bearer access", request.headers["Authorization"])
                val page = request.url.parameters["page"]
                respond(
                    content = if (page == "1") watchedShowJson(1, 101) else watchedShowJson(2, 202),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        "X-Pagination-Page-Count" to listOf("2"),
                    ),
                )
            }) {
                install(ContentNegotiation) { json(json) }
            },
            json = json,
        )

        val result = client.getWatchedShows("access")

        assertEquals(listOf<String?>("1", "2"), requestedPages)
        assertEquals(listOf(101, 202), result.mapNotNull { it.show?.ids?.tmdb })
        assertEquals(2, result.last().seasons.single().episodes.single().number)
    }

    @Test
    fun watchedShowsStopsOnEmptyPageWhenPaginationHeaderIsMissing() = runTest {
        var calls = 0
        val client = TraktClient(
            httpClient = HttpClient(MockEngine {
                calls += 1
                respond(
                    content = if (calls == 1) watchedShowJson(3, 303) else "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }) {
                install(ContentNegotiation) { json(json) }
            },
            json = json,
        )

        assertEquals(1, client.getWatchedShows("access").size)
        assertEquals(2, calls)
    }

    private fun watchedShowJson(episode: Int, tmdbId: Int): String =
        """[{"last_watched_at":"2026-09-01T00:00:00Z","show":{"title":"Show","runtime":30,"ids":{"trakt":7,"tmdb":$tmdbId}},"seasons":[{"number":1,"episodes":[{"number":$episode,"plays":1,"last_watched_at":"2026-09-01T00:00:00Z"}]}]}]"""
}
