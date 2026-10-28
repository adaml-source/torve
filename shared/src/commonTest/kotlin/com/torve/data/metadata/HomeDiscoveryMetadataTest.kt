package com.torve.data.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class HomeDiscoveryMetadataTest {
    @Test fun topRatedUsesItsOwnRankedEndpointsForHomeAndSeeAll() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"page":1,"total_pages":3,"total_results":60,"results":[{"id":1,"title":"Movie","name":"Show","release_date":"2099-01-01"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val repo = MetadataRepositoryImpl(TmdbApiClient(http, { "fixture" }, { null }))
            assertEquals(1, repo.getTopRatedPaged("movie").items.size)
            assertEquals(1, repo.getTopRatedPaged("tv").items.size)
            val home = repo.getHomeShelves()
            assertEquals(1, home.first { it.id == "top-rated" }.items.size)
            assertEquals(2, paths.count { it == "/3/movie/top_rated" })
            assertEquals(1, paths.count { it == "/3/tv/top_rated" })
        } finally { http.close() }
    }
    @Test fun upcomingRetainsPaginationWhenFirstPageIsFilteredEmpty() = runTest {
        val http = HttpClient(MockEngine { request ->
            val page = request.url.parameters["page"]!!.toInt()
            val date = if (page == 1) "1900-01-01" else "2099-01-01"
            respond("""{"page":$page,"total_pages":2,"total_results":40,"results":[{"id":$page,"title":"Movie","release_date":"$date"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val repo = MetadataRepositoryImpl(TmdbApiClient(http, { "fixture" }, { null }))
            val first = repo.getUpcomingPaged(1)
            assertTrue(first.items.isEmpty()); assertEquals(2, first.totalPages)
            val second = repo.getUpcomingPaged(2)
            assertEquals("2", second.items.single().id); assertEquals(2, second.page)
        } finally { http.close() }
    }
}
