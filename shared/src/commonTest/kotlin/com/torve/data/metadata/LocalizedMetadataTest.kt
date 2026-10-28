package com.torve.data.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class LocalizedMetadataTest {
    @Test fun heroesPreferNeutralScenesToTitleBearingLocalizedBackdrops() {
        val images = TmdbImages(backdrops = listOf(
            TmdbImageItem("/branded.jpg", "en", voteAverage = 10.0, voteCount = 100),
            TmdbImageItem("/scene-small.jpg", width = 400),
            TmdbImageItem("/scene.jpg", width = 1920),
            TmdbImageItem("", width = 4000)))
        val movie = TmdbMappers.movieToMediaItem(TmdbMovie(id = 1, title = "Movie", images = images))
        val series = TmdbMappers.tvToMediaItem(TmdbTv(id = 2, name = "Show", images = images))
        for (item in listOf(movie, series)) {
            assertTrue(item.titlelessBackdropUrl!!.endsWith("/scene.jpg"))
            assertEquals(item.titlelessBackdropUrl, item.backdropUrl)
        }
    }

    @Test fun titleBearingAndUnverifiedBackdropsCannotBeUsedBehindTitleLogos() {
        val images = TmdbImages(backdrops = listOf(TmdbImageItem("/branded.jpg", "en")))
        val movie = TmdbMappers.movieToMediaItem(TmdbMovie(id = 1, title = "Movie", images = images))
        val series = TmdbMappers.tvToMediaItem(TmdbTv(id = 2, name = "Show", backdropPath = "/unknown.jpg"))
        assertNull(movie.titlelessBackdropUrl)
        assertNull(series.titlelessBackdropUrl)
        assertTrue(movie.backdropUrl!!.endsWith("/branded.jpg"))
        assertTrue(series.backdropUrl!!.endsWith("/unknown.jpg"))
    }

    @Test fun logosPreferRequestedLanguageThenEnglishWithoutChangingVoteRanking() {
        val logos = TmdbImages(logos = listOf(
            TmdbImageItem("/en.png", "en", voteAverage = 10.0, voteCount = 100),
            TmdbImageItem("/neutral.png"), TmdbImageItem("/de-small.png", "de", width = 100),
            TmdbImageItem("/de.png", "de", width = 400), TmdbImageItem("", "fr")))
        assertEquals("/de.png", TmdbMappers.bestLogoPath(logos, "de-DE"))
        assertEquals("/en.png", TmdbMappers.bestLogoPath(logos, "fr"))
        assertEquals("/en.png", TmdbMappers.bestLogoPath(logos.copy(logos = logos.logos.filter { !it.iso6391.isNullOrBlank() }), "fr"))
    }

    @Test fun untaggedKoreanSpiderManLogoCannotOverrideKnownLanguageOnAnySupportedLocale() {
        val korean = "/a8v8fKTV4LWGWOBGGaOLNVBuplF.png"
        val english = "/dGMb0iSnNp7UMAHkKX63Kh6AJvy.png"
        val images = TmdbImages(logos = listOf(
            TmdbImageItem(korean, null, voteAverage = 10.0, voteCount = 100),
            TmdbImageItem("/ko.png", "ko", voteAverage = 10.0, voteCount = 100),
            TmdbImageItem(english, "en", voteAverage = 1.0, voteCount = 1)))
        for (language in listOf("en", "de", "es", "fr", "it", "pt", "tr")) {
            val movie = TmdbMappers.movieToMediaItem(TmdbMovie(id = 969681, title = "Spider-Man", images = images), language)
            val series = TmdbMappers.tvToMediaItem(TmdbTv(id = 1, name = "Show", images = images), language)
            for (item in listOf(movie, series)) assertTrue(item.logoUrl!!.endsWith(english), language)
            if (language != "en") assertEquals("/localized.png", TmdbMappers.bestLogoPath(images.copy(logos = images.logos +
                TmdbImageItem("/localized.png", language)), "$language-${language.uppercase()}"))
        }
    }

    @Test fun missingSupportedLogoFallsBackToTextInsteadOfUnlabelledOrForeignTitle() {
        val images = TmdbImages(logos = listOf(TmdbImageItem("/unlabelled.png"), TmdbImageItem("/ko.png", "ko")))
        assertNull(TmdbMappers.bestLogoPath(images, "de"))
        assertNull(TmdbMappers.movieToMediaItem(TmdbMovie(id = 1, title = "Movie", images = images), "de").logoUrl)
        assertNull(TmdbMappers.tvToMediaItem(TmdbTv(id = 1, name = "Show", images = images), "de").logoUrl)
    }
    @Test fun changingLanguageDoesNotReuseAnEnglishTitleFromTheMetadataCache() = runTest {
        var requests = 0
        var language = "en"
        val http = HttpClient(MockEngine { request ->
            requests++
            val title = if (request.url.parameters["language"] == "de") "München" else "Munich"
            val poster = if (request.url.parameters["language"] == "de") "/de.jpg" else "/en.jpg"
            respond("""{"page":1,"total_pages":1,"total_results":1,"results":[{"id":1,"title":"$title","poster_path":"$poster"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val repo = MetadataRepositoryImpl(TmdbApiClient(http, apiKeyProvider = { "fixture" }, resolvedHostProvider = { null }, languageProvider = { language }))
            assertEquals("Munich", repo.getTrending("movie").single().title)
            assertTrue(repo.getTrending("movie").single().posterUrl!!.endsWith("/en.jpg"))
            language = "de"
            assertEquals("München", repo.getTrending("movie").single().title)
            assertTrue(repo.getTrending("movie").single().posterUrl!!.endsWith("/de.jpg"))
            assertEquals("München", repo.getTrending("movie").single().title)
            language = "en"
            assertEquals("Munich", repo.getTrending("movie").single().title)
            assertEquals(2, requests)
        } finally { http.close() }
    }

    @Test fun localizedDetailArtworkBeatsHigherRatedEnglishAndFallsBackWithoutLosingPosters() {
        val images = TmdbImages(posters = listOf(
            TmdbImageItem("/en.jpg", "en", voteAverage = 10.0, voteCount = 100),
            TmdbImageItem("/de.jpg", "de", voteAverage = 1.0, voteCount = 1)))
        val movie = TmdbMovie(id = 1, title = "München", posterPath = "/default.jpg", images = images)
        val series = TmdbTv(id = 2, name = "Die Serie", posterPath = "/default.jpg", images = images)
        assertTrue(TmdbMappers.movieToMediaItem(movie, "de").posterUrl!!.endsWith("/de.jpg"))
        assertTrue(TmdbMappers.tvToMediaItem(series, "de").posterUrl!!.endsWith("/de.jpg"))
        assertTrue(TmdbMappers.movieToMediaItem(movie, "fr").posterUrl!!.endsWith("/en.jpg"))
        assertTrue(TmdbMappers.movieToMediaItem(movie.copy(images = null), "de").posterUrl!!.endsWith("/default.jpg"))
        assertTrue(TmdbMappers.tvToMediaItem(series.copy(images = TmdbImages()), "de").posterUrl!!.endsWith("/default.jpg"))
    }

    @Test fun detailRequestsIncludeSelectedLanguageAndArtworkFallbacks() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("de", request.url.parameters["language"])
            assertEquals("de,en,null", request.url.parameters["include_image_language"])
            respond("""{"id":1,"title":"München","name":"Die Serie","poster_path":"/default.jpg",
                "images":{"posters":[{"file_path":"/en.jpg","iso_639_1":"en"},
                {"file_path":"/de.jpg","iso_639_1":"de"}]}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val api = TmdbApiClient(http, apiKeyProvider = { "fixture" }, resolvedHostProvider = { null })
            api.contentLanguage = "de"
            val repo = MetadataRepositoryImpl(api)
            assertEquals("München", repo.getDetail("movie", 1).title)
            assertTrue(repo.getDetail("movie", 1).posterUrl!!.endsWith("/de.jpg"))
            assertEquals("Die Serie", repo.getDetail("tv", 1).title)
            assertTrue(repo.getDetail("tv", 1).posterUrl!!.endsWith("/de.jpg"))
        } finally { http.close() }
    }
}
