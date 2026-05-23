package com.torve.data.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetadataRepositoryImplRegressionTest {

    @Test
    fun homeLoadsContentWhenTmdbResponsesSucceed() = runTest {
        val repo = MetadataRepositoryImpl(
            api = TmdbApiClient(
                httpClientWithRoutes(successRoutes = true),
                resolvedHostProvider = { listOf("127.0.0.1") },
            ),
        )

        val shelves = repo.getHomeShelves()

        assertTrue(shelves.isNotEmpty())
        assertTrue(shelves.any { it.id == "trending-movies" })
        assertTrue(shelves.any { it.id == "trending-tv" })
    }

    @Test
    fun homeLoaderSurfacesErrorWhenAllBackendRequestsFail() = runTest {
        val repo = MetadataRepositoryImpl(
            api = TmdbApiClient(
                httpClientWithRoutes(successRoutes = false),
                resolvedHostProvider = { listOf("127.0.0.1") },
            ),
        )

        val error = assertFailsWith<HomeShelvesUnavailableException> {
            repo.getHomeShelves()
        }
        assertTrue(error.failedSegments.isNotEmpty())
        assertTrue(error.message?.contains("trending_movies") == true)
    }

    @Test
    fun oneHomeSegmentFailureDoesNotMarkAllSegmentsFailed() = runTest {
        val repo = MetadataRepositoryImpl(
            api = TmdbApiClient(
                httpClientWithRoutes(
                    successRoutes = true,
                    failingPaths = setOf("/3/trending/movie/week"),
                ),
                resolvedHostProvider = { listOf("127.0.0.1") },
            ),
        )

        val shelves = repo.getHomeShelves()

        assertTrue(shelves.isNotEmpty())
        assertTrue(shelves.none { it.id == "trending-movies" })
        assertTrue(shelves.any { it.id == "trending-tv" })
        assertTrue(shelves.any { it.id == "popular-movies" })
    }

    @Test
    fun repeatedIdenticalMetadataRequestUsesMemoryCache() = runTest {
        var requests = 0
        val repo = MetadataRepositoryImpl(
            api = TmdbApiClient(
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            content = movieResponse("Cached Movie"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(jsonConfig())
                    }
                },
                resolvedHostProvider = { listOf("127.0.0.1") },
            ),
        )

        repo.getTrending("movie", page = 1)
        repo.getTrending("movie", page = 1)

        assertEquals(1, requests)
    }

    @Test
    fun concurrentIdenticalMetadataRequestsJoinInFlightFetch() = runTest {
        var requests = 0
        val repo = MetadataRepositoryImpl(
            api = TmdbApiClient(
                HttpClient(
                    MockEngine {
                        requests++
                        delay(50)
                        respond(
                            content = movieResponse("Coalesced Movie"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(jsonConfig())
                    }
                },
                resolvedHostProvider = { listOf("127.0.0.1") },
            ),
        )

        val first = async { repo.getTrending("movie", page = 1) }
        val second = async { repo.getTrending("movie", page = 1) }
        first.await()
        second.await()

        assertEquals(1, requests)
    }

    private fun httpClientWithRoutes(
        successRoutes: Boolean,
        failingPaths: Set<String> = emptySet(),
    ): HttpClient {
        return HttpClient(
            MockEngine { request ->
                if (!successRoutes) {
                    return@MockEngine respond(
                        content = "backend down",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                    )
                }

                val path = request.url.encodedPath
                if (path in failingPaths) {
                    return@MockEngine respond(
                        content = "segment down",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                    )
                }
                val body = when (path) {
                    "/3/trending/movie/week",
                    "/3/movie/now_playing",
                    "/3/movie/popular",
                    "/3/movie/upcoming",
                    "/3/movie/top_rated",
                    -> movieResponse("Movie")
                    "/3/trending/tv/week",
                    "/3/tv/popular",
                    "/3/tv/airing_today",
                    -> tvResponse("Show")
                    else -> error("Unexpected path: $path")
                }

                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(
                    jsonConfig(),
                )
            }
        }
    }

    private fun jsonConfig(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun movieResponse(title: String): String {
        return """
            {
              "page": 1,
              "results": [
                {
                  "id": 101,
                  "title": "$title",
                  "overview": "Overview",
                  "poster_path": "/poster.jpg",
                  "backdrop_path": "/backdrop.jpg",
                  "vote_average": 7.8,
                  "vote_count": 100,
                  "release_date": "2025-01-01",
                  "genre_ids": [28],
                  "popularity": 120.0
                }
              ],
              "total_pages": 1,
              "total_results": 1
            }
        """.trimIndent()
    }

    private fun tvResponse(title: String): String {
        return """
            {
              "page": 1,
              "results": [
                {
                  "id": 202,
                  "name": "$title",
                  "overview": "Overview",
                  "poster_path": "/poster.jpg",
                  "backdrop_path": "/backdrop.jpg",
                  "vote_average": 8.1,
                  "vote_count": 150,
                  "first_air_date": "2025-01-01",
                  "genre_ids": [10765],
                  "popularity": 99.0
                }
              ],
              "total_pages": 1,
              "total_results": 1
            }
        """.trimIndent()
    }
}
