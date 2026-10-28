package com.torve.data.metadata

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class LocalizedMediaRepositoryTest {
    private fun http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    private fun MockRequestHandleScope.movie(language: String) = respond(
        """{"id":46738,"title":"${if (language == "de") "Die Frau die singt - Incendies" else "Incendies"}",
        "poster_path":"/old-fr.jpg","images":{"posters":[{"file_path":"/fr.jpg","iso_639_1":"fr","vote_average":10,"vote_count":100},
        {"file_path":"/de.jpg","iso_639_1":"de"},{"file_path":"/en.jpg","iso_639_1":"en"}]}}""",
        headers = headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun importedIdentityGetsRequestedTitleAndPosterEvenWhenGlobalLanguageDiffers() = runTest {
        val languages = mutableListOf<String>()
        val client = http { request ->
            val language = request.url.parameters["language"]!!; languages += language
            assertEquals("images", request.url.parameters["append_to_response"])
            assertEquals("$language,en,null".split(',').distinct().joinToString(","), request.url.parameters["include_image_language"])
            movie(language)
        }
        try {
            val api = TmdbApiClient(client, apiKeyProvider = { "fixture" }, resolvedHostProvider = { null })
            api.contentLanguage = "fr"
            val repo = LocalizedMediaRepository(api, backgroundScope)
            val german = repo.presentation("tmdb:46738", "movie", "de-DE")!!
            assertEquals("Die Frau die singt - Incendies", german.title)
            assertTrue(german.posterUrl!!.endsWith("/de.jpg"))
            assertEquals("46738", german.id)
            val english = repo.presentation("46738", "movie", "en")!!
            assertEquals("Incendies", english.title); assertTrue(english.posterUrl!!.endsWith("/en.jpg"))
            assertEquals(german, repo.presentation("46738", "movie", "de"))
            assertEquals(listOf("de", "en"), languages)
        } finally { client.close() }
    }

    @Test fun simultaneousCardsCoalesceAndOneCancelledCardDoesNotCancelOtherCards() = runTest {
        val gate = CompletableDeferred<Unit>(); var requests = 0
        val started = CompletableDeferred<Unit>()
        val client = http { requests++; started.complete(Unit); gate.await(); movie("de") }
        try {
            val repo = LocalizedMediaRepository(TmdbApiClient(client, { "fixture" }, { null }), backgroundScope)
            val first = async { repo.presentation("46738", "movie", "de") }
            started.await()
            val second = async { repo.presentation("46738", "movie", "de") }
            yield(); first.cancelAndJoin(); gate.complete(Unit)
            assertEquals("Die Frau die singt - Incendies", second.await()!!.title)
            assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test fun failedLookupRetriesAfterNegativeCacheExpiresAndUnsupportedSourcesNeverFetch() = runTest {
        var time = 0L; var requests = 0
        val client = http { requests++; if (requests == 1) respond("{}", HttpStatusCode.NotFound) else movie("de") }
        try {
            val repo = LocalizedMediaRepository(TmdbApiClient(client, { "fixture" }, { null }), backgroundScope, { time })
            assertNull(repo.presentation("46738", "movie", "de")); assertNull(repo.presentation("46738", "movie", "de"))
            assertEquals(1, requests); time = 60_001
            assertNotNull(repo.presentation("46738", "movie", "de")); assertEquals(2, requests)
            assertNull(repo.presentation("direct:46738", "movie", "de"))
            assertNull(repo.presentation("https://example.invalid/46738", "movie", "de"))
            assertNull(repo.presentation("46738", "person", "de")); assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun imdbEpisodeAliasesResolveShowAndCalendarUsesLocalizedEpisodeWithoutConfusingDateOrEpisodeId() = runTest {
        val paths = mutableListOf<String>()
        val client = http { request ->
            paths += request.url.encodedPath
            assertEquals("de", request.url.parameters["language"])
            val body = when (request.url.encodedPath) {
                "/3/find/tt0944947" -> """{"tv_results":[{"id":1399,"name":"Game of Thrones"}]}"""
                "/3/tv/1399" -> """{"id":1399,"name":"Game of Thrones","images":{"posters":[{"file_path":"/de-show.jpg","iso_639_1":"de"}]}}"""
                "/3/tv/1399/season/1" -> """{"id":1,"season_number":1,"episodes":[{"id":9,"episode_number":1,"season_number":1,"name":"Der Winter naht"}]}"""
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val repo = LocalizedMediaRepository(TmdbApiClient(client, { "fixture" }, { null }), backgroundScope)
            assertEquals("1399", repo.presentation("imdb:tt0944947:1:1", "tv", "de")!!.id)
            val calendar = repo.presentation("trakt-calendar:1399:1:1:2026-10-12T13:00:00+00:00", "tv", "de")!!
            assertEquals("Game of Thrones - S1E1 - Der Winter naht", calendar.title)
            assertTrue(calendar.posterUrl!!.endsWith("/de-show.jpg"))
            assertEquals(listOf("/3/find/tt0944947", "/3/tv/1399", "/3/tv/1399/season/1"), paths)
        } finally { client.close() }
    }
}
