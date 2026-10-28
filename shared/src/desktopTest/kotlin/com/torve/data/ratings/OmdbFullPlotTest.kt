package com.torve.data.ratings

import com.torve.data.PersonalDeviceFixture
import com.torve.domain.integrations.IntegrationSecretKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class OmdbFullPlotTest {
    private suspend fun fixture(body: String, check: suspend (PersonalDeviceFixture, OmdbClient, MutableList<Url>) -> Unit) {
        val requests = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        PersonalDeviceFixture(client).use { device ->
            device.signIn()
            check(device, OmdbClient(client, device.preferences, device.secrets), requests)
        }
    }
    @Test fun fullMovieUsesExactIdAndCachesRepeatedDetailAndPlaybackReads() = runTest {
        fixture("""{"Response":"True","imdbID":"tt42","Type":"movie","Plot":"The complete plot."}""") { d, api, requests ->
            d.secrets.put(IntegrationSecretKey.OMDB_API_KEY, "fixture-key")
            assertEquals("The complete plot.", api.fetchFullPlot("tt42", "movie"))
            assertEquals("The complete plot.", api.fetchFullPlot("tt42", "movie"))
            assertEquals(1, requests.size)
            assertEquals("full", requests.single().parameters["plot"])
            assertEquals("tt42", requests.single().parameters["i"])
        }
    }
    @Test fun exactEpisodeIncludesSeriesAndEpisodeAndRejectsWrongEpisode() = runTest {
        fixture("""{"Response":"True","imdbID":"tt99","seriesID":"tt42","Type":"episode","Season":"2","Episode":"1","Plot":"Only this episode."}""") { d, api, requests ->
            d.secrets.put(IntegrationSecretKey.OMDB_API_KEY, "fixture-key")
            assertEquals("Only this episode.", api.fetchFullPlot("tt42", "episode", 2, 1))
            assertEquals("2", requests.first().parameters["Season"])
            assertEquals("1", requests.first().parameters["Episode"])
            assertNull(api.fetchFullPlot("tt42", "episode", 2, 2))
        }
    }
    @Test fun absentKeyAndMalformedIdentityMakeNoRequest() = runTest {
        fixture("{}") { d, api, requests ->
            assertNull(api.fetchFullPlot("tt42", "movie"))
            d.secrets.put(IntegrationSecretKey.OMDB_API_KEY, "fixture-key")
            assertNull(api.fetchFullPlot("42", "movie"))
            assertNull(api.fetchFullPlot("tt42", "episode", 2, null))
            assertTrue(requests.isEmpty())
        }
    }
    @Test fun wrongMovieAndUnavailablePlotCannotOverwriteSynopsis() = runTest {
        fixture("""{"Response":"True","imdbID":"tt99","Type":"movie","Plot":"Wrong film."}""") { d, api, _ ->
            d.secrets.put(IntegrationSecretKey.OMDB_API_KEY, "fixture-key")
            assertNull(api.fetchFullPlot("tt42", "movie"))
        }
        fixture("""{"Response":"True","imdbID":"tt42","Type":"movie","Plot":"N/A"}""") { d, api, _ ->
            d.secrets.put(IntegrationSecretKey.OMDB_API_KEY, "fixture-key")
            assertNull(api.fetchFullPlot("tt42", "movie"))
        }
    }
    @Test fun changedProviderKeyInvalidatesThePlotCache() = runTest {
        fixture("""{"Response":"True","imdbID":"tt42","Type":"movie","Plot":"Plot."}""") { d, api, requests ->
            d.secrets.put(IntegrationSecretKey.OMDB_API_KEY, "first")
            api.fetchFullPlot("tt42", "movie")
            d.secrets.put(IntegrationSecretKey.OMDB_API_KEY, "second")
            api.fetchFullPlot("tt42", "movie")
            assertEquals(2, requests.size)
        }
    }
}
