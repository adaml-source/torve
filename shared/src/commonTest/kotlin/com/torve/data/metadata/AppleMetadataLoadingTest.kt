package com.torve.data.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AppleMetadataLoadingTest {
    @Test fun provisionalPresentationCannotFreezeGermanTextInEitherCacheLayer() = runTest {
        var calls=0
        val http=client {
            calls++
            val pending=calls==1
            respond("""{"id":42,"title":"${if(pending) "Disclosure Day" else "Disclosure Day – Der Tag der Wahrheit"}",
                "overview":"${if(pending) "English plot" else "Deutscher Inhalt"}",
                "poster_path":"https://api.torve.app/apple-metadata/v1/images/known",
                "torve_presentation_pending":$pending}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            val api=TmdbApiClient(http,resolvedHostProvider={null},baseUrl="https://fixture.test/apple-metadata/v1")
            val overlay=LocalizedMediaRepository(api,backgroundScope,presentations=MetadataRepositoryImpl(api))
            assertTrue(overlay.presentation("42","movie","de")!!.presentationPending)
            assertEquals("Deutscher Inhalt",overlay.presentation("42","movie","de")!!.overview)
            assertFalse(overlay.presentation("42","movie","de")!!.presentationPending)
            assertEquals(2,calls)
        } finally { http.close() }
    }

    @Test fun selectedPresentationRefreshReplacesPreviouslyCompletedEnglishFallback() = runTest {
        var calls=0
        val http=client { request ->
            calls++;assertEquals("de",request.url.parameters["language"])
            assertEquals("images,credits",request.url.parameters["append_to_response"])
            respond("""{"id":42,"title":"${if(calls==1) "English fallback" else "Deutscher Titel"}",
                "poster_path":"https://api.torve.app/apple-metadata/v1/images/known"}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            val api=TmdbApiClient(http,resolvedHostProvider={null},baseUrl="https://fixture.test/apple-metadata/v1")
            val overlay=LocalizedMediaRepository(api,backgroundScope)
            assertEquals("English fallback",overlay.presentation("42","movie","de",includeCast=true)!!.title)
            assertEquals("English fallback",overlay.presentation("42","movie","de",includeCast=true)!!.title)
            assertEquals("Deutscher Titel",overlay.presentation("42","movie","de",includeCast=true,refresh=true)!!.title)
            assertEquals("Deutscher Titel",overlay.presentation("42","movie","de",includeCast=true)!!.title)
            assertEquals(2,calls)
        } finally { http.close() }
    }

    @Test fun languageChangeDuringDetailRequestDoesNotChangeItsArtworkMappingOrCacheLocale() = runTest {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val http=client { request ->
            if(request.url.parameters["language"]=="en") { started.complete(Unit);release.await() }
            respond("""{"id":42,"title":"${request.url.parameters["language"]}","images":{"logos":[
                {"file_path":"/english.png","iso_639_1":"en"},{"file_path":"/german.png","iso_639_1":"de"}]}}""",
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            val api=TmdbApiClient(http,apiKeyProvider={"fixture"},resolvedHostProvider={null})
            api.contentLanguage="en"
            val repo=MetadataRepositoryImpl(api)
            val old=async { repo.getDetail("movie",42) };runCurrent()
            withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { started.await() } } ?: error("Request did not start")
            api.contentLanguage="de";release.complete(Unit)
            assertTrue(old.await().logoUrl!!.endsWith("english.png"))
            assertEquals("de",repo.getDetail("movie",42).title)
            assertTrue(repo.getDetail("movie",42).logoUrl!!.endsWith("german.png"))
            api.contentLanguage="en"
            assertTrue(repo.getDetail("movie",42).logoUrl!!.endsWith("english.png"))
        } finally { release.complete(Unit);http.close() }
    }

    @Test fun catalogueDetailDoesNotWaitForTwoSlowArtworkRepairs() = runTest {
        val release=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        val http=client { request ->
            if (request.url.parameters["append_to_response"] == "images,credits") {
                started.complete(Unit)
                respond("""{"id":550,"title":"Deutscher Titel"}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            } else {
                release.await()
                respond("""{"id":100,"title":"Background artwork"}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        try {
            val api=TmdbApiClient(http,resolvedHostProvider={null},baseUrl="https://fixture.test/apple-metadata/v1")
            val cards=(1..2).map { async { api.getMoviePresentation(it,"de") } };runCurrent()
            val detail=async { api.getMoviePresentation(550,"de",includeCast=true) };runCurrent()
            val reached=withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { started.await();true } } == true
            release.complete(Unit);cards.awaitAll();detail.await()
            assertTrue(reached,"Selected catalogue details must use the foreground lane")
        } finally { release.complete(Unit);http.close() }
    }

    @Test fun provisionalSeasonDoesNotFreezeTheRepositoryCache() = runTest {
        var calls=0
        val http=client { request ->
            assertEquals("true",request.url.parameters["progressive"]);calls++
            val pending=calls==1
            respond("""{"id":42,"season_number":31,"torve_localization_pending":$pending,"episodes":[{"id":1,"episode_number":1,"name":"${if(pending) "English episode" else "Deutsche Folge"}"}]}""",
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            val api=TmdbApiClient(http,resolvedHostProvider={null},baseUrl="https://fixture.test/apple-metadata/v1")
            val repo=MetadataRepositoryImpl(api)
            assertTrue(repo.getSeasonDetail(42,31).metadataPending)
            assertEquals("Deutsche Folge",repo.getSeasonDetail(42,31).episodes.single().name)
            assertFalse(repo.getSeasonDetail(42,31).metadataPending)
            assertEquals(2,calls)
        } finally { http.close() }
    }
    @Test fun emptyArtworkPresentationCannotFreezeLaterAvailablePoster() = runTest {
        var requests = 0
        val http = client {
            requests++
            val poster = if (requests == 1) "" else "\"poster_path\":\"https://api.torve.app/apple-metadata/v1/images/available\","
            respond("""{${poster}"id":155,"title":"The Dark Knight"}""", headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            val api = TmdbApiClient(http,resolvedHostProvider={null},baseUrl="https://fixture.test/apple-metadata/v1")
            val repository = MetadataRepositoryImpl(api)
            val localized = LocalizedMediaRepository(api,presentations=repository)
            assertNull(localized.presentation("155","movie","de")?.posterUrl)
            assertEquals("https://api.torve.app/apple-metadata/v1/images/available",localized.presentation("155","movie","de")?.posterUrl)
            localized.presentation("155","movie","de")
            assertEquals(2,requests)
        } finally { http.close() }
    }
    @Test fun cachedPosterReplySurvivesCellularLatencyBeyondPublicationBudget() = runTest {
        var requests=0
        val http=client {
            requests++
            assertEquals("primary",it.url.parameters["preview_scope"])
            kotlinx.coroutines.delay(650)
            respond("""{"page":1,"results":[{"id":155,"title":"Cached title","poster_path":"https://api.torve.app/apple-metadata/v1/images/cached"}]}""",
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            val api=TmdbApiClient(http,resolvedHostProvider={null},baseUrl="https://fixture.test/apple-metadata/v1")
            val overlay=LocalizedMediaRepository(api,backgroundScope,presentations=MetadataRepositoryImpl(api))
            val rows=withContext(Dispatchers.Default) { overlay.cachedPresentations(listOf("155"),"movie","de") }
            assertEquals("Cached title",rows.single().title)
            assertEquals(rows.single(),overlay.presentation("155","movie","de"))
            assertEquals(1,requests)
        } finally { http.close() }
    }

    @Test fun provisionalArtworkDoesNotSatisfyTheLocalizedMetadataCache() = runTest {
        val paths = mutableListOf<String>()
        val http = client { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.contains("/presentations/")) {
                assertEquals("true",request.url.parameters["artwork_fallback"])
                respond("""{"page":1,"results":[{"id":155,"title":"The Dark Knight","poster_path":"https://api.torve.app/apple-metadata/v1/images/fallback","torve_presentation_pending":true}]}""",
                    headers=headersOf(HttpHeaders.ContentType,"application/json"))
            } else respond("""{"id":155,"title":"Lokalisierter Titel","overview":"Deutscher Inhalt","poster_path":"https://api.torve.app/apple-metadata/v1/images/german"}""",
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            val api=TmdbApiClient(http,resolvedHostProvider={null},baseUrl="https://fixture.test/apple-metadata/v1")
            val repository=MetadataRepositoryImpl(api)
            val overlay=LocalizedMediaRepository(api,backgroundScope,presentations=repository)
            // The optional network budget uses real I/O. Do not let the test
            // scheduler advance its 500 ms clock before MockEngine's I/O runs.
            val preview=withContext(Dispatchers.Default) { overlay.cachedPresentations(listOf("155"),"movie","de") }.single()
            assertTrue(preview.presentationPending);assertTrue(preview.posterUrl!!.endsWith("fallback"))
            val full=overlay.presentation("155","movie","de")!!
            assertFalse(full.presentationPending);assertEquals("Lokalisierter Titel",full.title)
            assertEquals("Deutscher Inhalt",full.overview)
            assertEquals(2,paths.size)
            assertEquals(full,overlay.presentation("155","movie","de"));assertEquals(2,paths.size)
        } finally { http.close() }
    }

    @Test fun selectedDetailStartsWhileAllBrowseRequestsAreWaiting() = runTest {
        val release = CompletableDeferred<Unit>()
        val detailStarted = CompletableDeferred<Unit>()
        val http = client { request ->
            if (request.url.encodedPath.endsWith("/550")) {
                detailStarted.complete(Unit)
                respond("""{"id":550,"title":"Film"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                release.await()
                respond("""{"page":1,"results":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            val api = TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1")
            val rows = (1..3).map { async { api.discoverMovies(withGenres="35") } }
            runCurrent()
            val detail = async { api.getMovieDetail(550) }
            runCurrent()
            // Ktor dispatches network work outside the virtual test scheduler.
            val reached = withContext(Dispatchers.Default) {
                withTimeoutOrNull(2_000) { detailStarted.await(); true }
            } == true
            release.complete(Unit); rows.awaitAll(); detail.await()
            assertTrue(reached, "A selected detail must not queue behind catalogue rows")
        } finally { release.complete(Unit); http.close() }
    }
    @Test fun nextDiscoveryRowStartsWhileRatingBatchesAreStillWaiting() = runTest {
        val release = CompletableDeferred<Unit>()
        val discoveryStarted = CompletableDeferred<Unit>()
        val http = client { request ->
            if (request.url.encodedPath.endsWith("/ratings")) {
                release.await()
                respond("""{"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                discoveryStarted.complete(Unit)
                respond("""{"page":1,"results":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            val api = TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1")
            val item = com.torve.domain.model.MediaItem(id="550", tmdbId=550, title="Film", type=com.torve.domain.model.MediaType.MOVIE)
            val ratings = (1..3).map { async { api.getBackendRatings(listOf(item)) } }
            runCurrent()
            val row = async { api.discoverMovies(withGenres="35") }
            runCurrent()
            val reached = withContext(Dispatchers.Default) {
                withTimeoutOrNull(2_000) { discoveryStarted.await(); true }
            } == true
            release.complete(Unit)
            ratings.awaitAll(); row.await()
            assertTrue(reached, "An uncached rating batch must not occupy the next discovery row's request lanes")
        } finally { release.complete(Unit); http.close() }
    }
    @Test fun failedPosterCanUseSameLanguageGalleryWithoutRepeatingGalleryRequests() = runTest {
        var requests = 0
        val http = client { request ->
            requests++
            assertEquals("/apple-metadata/v1/tv/14188/images", request.url.encodedPath)
            assertEquals("de", request.url.parameters["language"])
            respond("""{"posters":[{"file_path":"https://api.torve.app/apple-metadata/v1/images/first","iso_639_1":"de"},{"file_path":"https://api.torve.app/apple-metadata/v1/images/second","iso_639_1":"de"}],"backdrops":[{"file_path":"https://api.torve.app/apple-metadata/v1/images/backdrop"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val api = TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1")
            api.contentLanguage = "tr"
            val repo = MetadataRepositoryImpl(api)
            val expected = listOf("first", "second", "backdrop")
            repeat(2) { assertEquals(expected, repo.getArtworkCandidates("tv", 14188, false, "de").map { it.substringAfterLast('/') }) }
            assertEquals(1, requests)
        } finally { http.close() }
    }
    @Test fun importedOverlayAndArtworkFallbackShareOneExplicitLanguagePresentation() = runTest {
        val languages = mutableListOf<String>()
        val http = client { request ->
            val language = request.url.parameters["language"]!!
            languages += language
            respond("""{"id":240,"title":"$language","poster_path":"https://api.torve.app/apple-metadata/v1/images/$language"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val api = TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1")
            api.contentLanguage = "tr"
            val shared = MetadataRepositoryImpl(api)
            val overlay = LocalizedMediaRepository(api, backgroundScope, presentations = shared)
            val visible = async { overlay.presentation("tmdb:240", "movie", "de") }
            val fallback = async { shared.getPresentation("movie", 240, "de") }
            assertEquals("de", visible.await()!!.title)
            assertEquals("de", fallback.await().title)
            assertEquals(listOf("de"), languages)
            assertEquals("en", shared.getPresentation("movie", 240, "en").title)
            assertEquals(listOf("de", "en"), languages)
        } finally { http.close() }
    }

    @Test fun throttledMetadataHonoursDelayAndStopsAfterOneRetry() = runTest {
        var requests = 0
        val http = client {
            requests++
            respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
        }
        try {
            val api = TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1")
            val start = testScheduler.currentTime
            val failure = assertFailsWith<MetadataHttpException> { api.getMoviePresentation(240, "en") }
            assertEquals(429, failure.statusCode)
            assertEquals(2, requests)
            assertTrue(testScheduler.currentTime - start >= 2_000)
        } finally { http.close() }
    }

    @Test fun temporaryProviderFailureDoesNotHideArtworkForNegativeCacheLifetime() = runTest {
        var requests = 0
        val http = client {
            requests++
            if (requests == 1) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond("""{"id":240,"title":"Recovered","poster_path":"https://api.torve.app/apple-metadata/v1/images/ok"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val api = TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1")
            val repo = LocalizedMediaRepository(api, backgroundScope, presentations = MetadataRepositoryImpl(api))
            assertNull(repo.presentation("240", "movie", "en"))
            assertEquals("Recovered", repo.presentation("240", "movie", "en")!!.title)
            assertEquals(2, requests)
        } finally { http.close() }
    }

    @Test fun retiredArtworkIsMissingOnlyOnApple() {
        val old = "https://image.tmdb.org/t/p/w500/saved.jpg"
        assertTrue(metadataArtworkNeedsRefresh(old, "apple"))
        assertTrue(metadataArtworkNeedsRefresh(null, "apple"))
        assertFalse(metadataArtworkNeedsRefresh(old, null))
        assertFalse(metadataArtworkNeedsRefresh("https://api.torve.app/apple-metadata/v1/images/hash", "apple"))
        assertFalse(metadataArtworkNeedsRefresh("https://other-catalogue.example/poster.jpg", "apple"))
    }

    @Test fun artworkRepairRequestsOnlyPresentationAndReusesIt() = runTest {
        val paths = mutableListOf<String>()
        val http = client { request ->
            paths += request.url.encodedPath
            assertEquals("images", request.url.parameters["append_to_response"])
            respond("""{"id":240,"title":"The Godfather Part II","poster_path":"https://api.torve.app/apple-metadata/v1/images/poster"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val repository = MetadataRepositoryImpl(TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1"))
            repeat(2) {
                assertEquals("https://api.torve.app/apple-metadata/v1/images/poster", repository.getPresentation("movie", 240).posterUrl)
            }
            assertEquals(listOf("/apple-metadata/v1/movie/240"), paths)
        } finally { http.close() }
    }

    @Test fun selectedSeasonStartsWhileBrowseAndArtworkLanesAreOccupied() = runTest {
        val release = CompletableDeferred<Unit>()
        var browseStarted = 0
        var presentationStarted = 0
        var seasonStarted = false
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                path.contains("/season/") -> {
                    seasonStarted = true
                    respond("""{"id":1,"season_number":3,"name":"Season 3","episodes":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                path.endsWith("/popular") -> {
                    browseStarted++; release.await()
                    respond("""{"page":1,"results":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> {
                    presentationStarted++; release.await()
                    respond("""{"id":240,"title":"Movie"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        try {
            val api = TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1")
            val background = (1..5).map { async { api.getMoviePresentation(it, "en") } }
            val browse = (1..5).map { async { api.getPopular("movie") } }
            runCurrent()
            assertEquals(2, presentationStarted)
            assertEquals(3, browseStarted)
            val season = async { api.getTvSeasonDetail(95480, 3) }
            assertEquals(3, season.await().seasonNumber)
            assertTrue(seasonStarted)
            release.complete(Unit)
            background.awaitAll(); browse.awaitAll()
        } finally { release.complete(Unit); http.close() }
    }

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) } }
}
