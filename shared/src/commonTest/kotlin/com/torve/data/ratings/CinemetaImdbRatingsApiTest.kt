package com.torve.data.ratings

import com.torve.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class CinemetaImdbRatingsApiTest {
    @Test fun imdbRefreshCachesForOneHourThenReadsTheUpdatedProviderValue() = runTest {
        var clock = 0L
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("https://v3-cinemeta.strem.io/meta/movie/tt45368240.json", request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            calls++
            respond("""{"meta":{"id":"tt45368240","imdbRating":"${if (calls == 1) "9.3" else "9.2"}","name":"Title"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val api = CinemetaImdbRatingsApi(client) { clock }
            assertEquals(9.3f, api.fetch("tt45368240", MediaType.MOVIE)?.score)
            assertEquals(9.3f, api.fetch("tt45368240", MediaType.MOVIE)?.score)
            assertEquals(1, calls)
            clock = 3_600_001
            assertEquals(9.2f, api.fetch("tt45368240", MediaType.MOVIE)?.score)
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun invalidIdentityIsNeverSentToTheProvider() = runTest {
        val client = HttpClient(MockEngine { error("No lookup expected") })
        try { assertNull(CinemetaImdbRatingsApi(client).fetch("tmdb:42", MediaType.MOVIE)) }
        finally { client.close() }
    }

    @Test fun aDifferentTitleOrAnInvalidScoreCannotReplaceTheImdbRating() = runTest {
        for ((id, score) in listOf("tt99" to "9.3", "tt42" to "11", "tt42" to "NaN", "tt42" to "N/A")) {
            val client = HttpClient(MockEngine {
                respond("""{"meta":{"id":"$id","imdbRating":"$score"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try { assertNull(CinemetaImdbRatingsApi(client).fetch("tt42", MediaType.MOVIE)) }
            finally { client.close() }
        }
    }

    @Test fun providerFailureKeepsTheExistingRatingAvailable() = runTest {
        val client = HttpClient(MockEngine { respond("Unavailable", HttpStatusCode.ServiceUnavailable) })
        try { assertNull(CinemetaImdbRatingsApi(client).fetch("tt42", MediaType.SERIES)) }
        finally { client.close() }
    }
}
