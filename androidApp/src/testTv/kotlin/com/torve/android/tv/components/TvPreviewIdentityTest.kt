package com.torve.android.tv.components

import com.torve.data.trakt.TraktClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TvPreviewIdentityTest {
    private fun lookup(body: String, series: Boolean = false): String? = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/search/tmdb/1726", request.url.encodedPath)
            assertEquals(if (series) "show" else "movie", request.url.parameters["type"])
            assertNull(request.headers["Authorization"])
            respond(body)
        })
        try {
            TraktClient(http, Json { ignoreUnknownKeys = true }).getImdbIdByTmdb(1726, series)
        } finally { http.close() }
    }

    @Test fun numericMovieAndSeriesUseExactPublicIdentityWithoutUserAuth() {
        assertEquals("tt0371746", lookup("""[{"type":"movie","movie":{"ids":{"tmdb":1726,"imdb":"tt0371746"}}}]"""))
        assertEquals("tt1234567", lookup("""[{"type":"show","show":{"ids":{"tmdb":1726,"imdb":"tt1234567"}}}]""", true))
    }

    @Test fun mismatchedTypesIdsAndAmbiguousIdentitiesAreRejected() {
        assertNull(lookup("""[{"type":"show","show":{"ids":{"tmdb":1726,"imdb":"tt1234567"}}}]"""))
        assertNull(lookup("""[{"type":"movie","movie":{"ids":{"tmdb":999,"imdb":"tt0371746"}}}]"""))
        assertNull(lookup("""[{"type":"movie","movie":{"ids":{"tmdb":1726,"imdb":"tt0371746"}}},{"type":"movie","movie":{"ids":{"tmdb":1726,"imdb":"tt0043678"}}}]"""))
        assertNull(lookup("""[{"type":"movie","movie":{"ids":{"tmdb":1726,"imdb":"1726"}}}]"""))
    }
}
