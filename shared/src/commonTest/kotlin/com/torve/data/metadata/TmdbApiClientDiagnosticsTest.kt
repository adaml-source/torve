package com.torve.data.metadata

import com.torve.data.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TmdbApiClientDiagnosticsTest {

    @Test
    fun instrumentationCapturesEndpointCategoryAndSuccess() = runTest {
        val diagnostics = mutableListOf<TmdbRequestDiagnostic>()
        val client = TmdbApiClient(
            httpClient = successClient(),
            resolvedHostProvider = { listOf("108.138.36.80") },
            diagnostics = diagnostics::add,
        )

        client.getTrending(type = "movie", requestCategory = "home.trending_movies")

        val success = diagnostics.last { it.outcome == "success" }
        assertEquals("home.trending_movies", success.category)
        assertEquals("api.themoviedb.org", success.host)
        assertEquals("108.138.36.80", success.resolvedHost)
        assertEquals("/trending/movie/week", success.endpoint)
        assertTrue(success.params.contains("page=1"))
        assertEquals(200, success.statusCode)
        assertTrue(success.executed)
        assertTrue(success.queueDelayMs != null)
        assertTrue(success.activeRequestMs != null)
        assertTrue(success.completedAtMs != null)
    }

    @Test
    fun instrumentationClassifiesTimeouts() = runTest {
        val diagnostics = mutableListOf<TmdbRequestDiagnostic>()
        val client = TmdbApiClient(
            httpClient = timeoutClient(),
            resolvedHostProvider = { listOf("108.138.36.80") },
            diagnostics = diagnostics::add,
        )

        assertFailsWith<HttpRequestTimeoutException> {
            client.getTrending(type = "movie", requestCategory = "home.trending_movies")
        }

        val failure = diagnostics.last { it.outcome == "failure" }
        assertEquals("timeout", failure.failureKind)
        assertEquals("home.trending_movies", failure.category)
        assertEquals("/trending/movie/week", failure.endpoint)
        assertTrue(failure.executed)
        assertTrue(failure.activeRequestMs != null)
        assertTrue(failure.exceptionMessage?.contains("<redacted-url>") != false)
    }

    @Test
    fun instrumentationClassifiesCancellationBeforeExecution() = runTest {
        val diagnostics = mutableListOf<TmdbRequestDiagnostic>()
        val client = TmdbApiClient(
            httpClient = baseClient(
                requestTimeoutMs = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
                engine = MockEngine {
                    throw CancellationException("cancelled by parent")
                },
            ),
            resolvedHostProvider = { listOf("108.138.36.80") },
            diagnostics = diagnostics::add,
        )

        assertFailsWith<CancellationException> {
            client.getTrending(type = "movie", requestCategory = "catalog.trending_paged.movie.page_1")
        }

        val failure = diagnostics.last { it.outcome == "failure" }
        assertEquals("cancelled", failure.failureKind)
        assertEquals("catalog.trending_paged.movie.page_1", failure.category)
        assertEquals(false, failure.executed)
    }

    private fun successClient(): HttpClient {
        return baseClient(
            requestTimeoutMs = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
            engine = MockEngine {
                respond(
                    content = movieResponse("Movie"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
    }

    private fun timeoutClient(): HttpClient {
        return baseClient(
            requestTimeoutMs = 50,
            engine = MockEngine {
                delay(200)
                respond(
                    content = movieResponse("Movie"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
    }

    private fun baseClient(
        requestTimeoutMs: Long = HttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS,
        engine: MockEngine,
    ): HttpClient {
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        coerceInputValues = true
                        explicitNulls = false
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = HttpClientFactory.DEFAULT_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = requestTimeoutMs
            }
        }
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
}
