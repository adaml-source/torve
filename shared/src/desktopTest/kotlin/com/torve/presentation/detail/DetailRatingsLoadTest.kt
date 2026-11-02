package com.torve.presentation.detail

import com.torve.data.PersonalDeviceFixture
import com.torve.data.addon.StreamScorer
import com.torve.data.addon.StreamSelector
import com.torve.data.kodi.KodiClient
import com.torve.data.mdblist.*
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.ratings.*
import com.torve.data.simkl.SimklClient
import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.model.*
import com.torve.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.*

/** Real detail loader -> real rating clients -> state observed by the Swift wrapper. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailRatingsLoadTest {
    private val film = MediaItem("tt45368240", imdbId = "tt45368240", type = MediaType.MOVIE,
        title = "You Can See Everything", posterUrl = "https://fixture.invalid/original.jpg",
        ratings = MediaRatings(imdbScore = 8.0f, imdbVotes = 186, rottenTomatoesScore = 100))

    private inline fun <reified T> stub(noinline call: (String, Array<out Any?>) -> Any? = { name, _ -> error("Unexpected ${T::class.simpleName}.$name") }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private suspend fun TestScope.withDetail(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
        metadata: MetadataRepository = stub(),
        block: suspend (DetailViewModel) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val http = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        PersonalDeviceFixture(http).use { device ->
            device.signIn()
            val trakt = TraktClient(http, Json)
            val enricher = RatingsEnricher(MdbListApi(http), TmdbApiClient(http), trakt,
                RatingsCacheRepository(device.database), OmdbClient(http, device.preferences, device.secrets),
                BackendRatingsApi(http, { "https://fixture.invalid" }, { "fixture-token" }), CinemetaImdbRatingsApi(http))
            val vm = DetailViewModel(metadata, stub(), stub<WatchProgressRepository> { _, _ -> null },
                TraktAuthorizedApi(trakt, TraktTokenStore(device.secrets, Json)), stub(), stub(), stub(), stub(),
                device.preferences, stub(), StreamSelector(StreamScorer()), enricher, device.secrets, KodiClient(http), SimklClient(http))
            try { block(vm) }
            finally { vm.clear(); Dispatchers.resetMain() }
        }
    }

    private suspend fun awaitScore(vm: DetailViewModel, score: Float) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { vm.state.first { it.mediaItem?.ratings?.imdbScore == score }.mediaItem!! }
    }

    @Test fun extensionDetailRefreshesTheScoreDisplayedByTheSwiftWrapper() = runTest {
        var requests = 0
        withDetail({ request ->
            assertEquals("/meta/movie/tt45368240.json", request.url.encodedPath)
            requests++
            respond("""{"meta":{"id":"tt45368240","imdbRating":"9.3"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { vm ->
            vm.loadExtensionDetail(Json.encodeToString(film))
            val displayed = awaitScore(vm, 9.3f)
            assertEquals(film.id, displayed.id)
            assertEquals(film.posterUrl, displayed.posterUrl)
            assertEquals(100, displayed.ratings?.rottenTomatoesScore)
            assertNull(displayed.ratings?.imdbVotes)
            assertEquals(1, requests)
        }
    }

    @Test fun resolvedCatalogueDetailRefreshesPastTheBackendCache() = runTest {
        val paths = mutableListOf<String>()
        withDetail({ request ->
            paths += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/me/ratings/batch" -> """{"items":[{"media_type":"movie","tmdb_id":42,"imdb_score":8.0,"imdb_votes":186,"cached":true}]}"""
                "/meta/movie/tt45368240.json" -> """{"meta":{"id":"tt45368240","imdbRating":"9.3"}}"""
                else -> error("Unexpected metadata lookup")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { vm ->
            vm.loadExtensionDetail(Json.encodeToString(film.copy(tmdbId = 42)))
            assertEquals(9.3f, awaitScore(vm, 9.3f).ratings?.imdbScore)
            withContext(Dispatchers.Default) { withTimeout(5_000) { while (paths.size < 2) delay(10) } }
            assertEquals(setOf("/me/ratings/batch", "/meta/movie/tt45368240.json"), paths.toSet())
        }
    }

    @Test fun reopeningTheSameCatalogueTitleDoesNotRestoreItsOldScore() = runTest {
        var requests = 0
        withDetail({ _ ->
            requests++
            respond("""{"meta":{"id":"tt45368240","imdbRating":"9.3"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { vm ->
            repeat(2) {
                vm.loadExtensionDetail(Json.encodeToString(film))
                awaitScore(vm, 9.3f)
            }
            assertEquals(1, requests)
        }
    }

    @Test fun seasonTranslationsUpdateTheOpenPageWithoutReentry() = runTest {
        var calls = 0
        val metadata = object : MetadataRepository by stub<MetadataRepository>() {
            override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season {
                calls++
                return Season(seasonNumber,1,episodes=listOf(Episode(1,if(calls==1) "English episode" else "Deutsche Folge")),metadataPending=calls==1)
            }
        }
        withDetail({ respond("{}", headers = headersOf(HttpHeaders.ContentType,"application/json")) },metadata) { vm ->
            vm.loadExtensionDetail(Json.encodeToString(film.copy(tmdbId=42)))
            vm.loadSeasonDetail(42,31)
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.state.first { it.seasonDetail?.episodes?.firstOrNull()?.name == "Deutsche Folge" }
                }
            }
            assertEquals(2,calls)
            assertFalse(vm.state.value.isLoadingSeasonDetail)
            assertEquals(31,vm.state.value.selectedSeason)
        }
    }

    @Test fun localizedDetailRefreshKeepsSeason31AndRefreshesItsExistingEpisodes() = runTest {
        val next=CompletableDeferred<Season>();var calls=0
        val metadata=object : MetadataRepository by stub<MetadataRepository>() {
            override suspend fun getSeasonDetail(tvId: Int,seasonNumber: Int): Season {
                assertEquals(31,seasonNumber);calls++
                return next.await()
            }
        }
        withDetail({ respond("{}",headers=headersOf(HttpHeaders.ContentType,"application/json")) },metadata) { vm ->
            val show=film.copy(type=MediaType.SERIES,tmdbId=42,seasons=listOf(Season(31,1,episodes=listOf(Episode(1,"English episode")))))
            vm.loadExtensionDetail(Json.encodeToString(show));vm.selectSeason(31)
            vm.completeExtensionDetail(Json.encodeToString(show.copy(title="Lokalisierte Serie",seasons=listOf(Season(31,1)))))
            assertEquals(31,vm.state.value.selectedSeason)
            assertEquals("English episode",vm.state.value.seasonDetail?.episodes?.single()?.name)
            next.complete(Season(31,1,episodes=listOf(Episode(1,"Deutsche Folge"))))
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { it.seasonDetail?.episodes?.singleOrNull()?.name == "Deutsche Folge" } } }
            assertEquals("Lokalisierte Serie",vm.state.value.mediaItem?.title)
            assertFalse(vm.state.value.isLoadingSeasonDetail)
            assertTrue(calls>=1)
        }
    }

    @Test fun latePreviewForTheSameSelectionCannotEraseFullMetadataOrResetSeason31() = runTest {
        withDetail({ respond("{}",headers=headersOf(HttpHeaders.ContentType,"application/json")) }) { vm ->
            val full=film.copy(type=MediaType.SERIES,title="Vollständiger Titel",overview="Deutsche Handlung",
                seasons=listOf(Season(1,1),Season(31,1,episodes=listOf(Episode(1,"Deutsche Folge")))))
            vm.loadExtensionDetail(Json.encodeToString(full));vm.selectExtensionSeason(31)
            val old=full.copy(title="Old English card",overview=null,seasons=emptyList())
            vm.showExtensionDetailPreview(Json.encodeToString(old))
            assertEquals("Vollständiger Titel",vm.state.value.mediaItem?.title)
            assertEquals("Deutsche Handlung",vm.state.value.mediaItem?.overview)
            assertEquals(31,vm.state.value.selectedSeason)
            assertEquals("Deutsche Folge",vm.state.value.seasonDetail?.episodes?.single()?.name)
        }
    }

    @Test fun switchingTitlesCancelsTheOldDetailRefresh() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        withDetail({ request ->
            if (request.url.encodedPath.endsWith("tt45368240.json")) {
                firstStarted.complete(Unit)
                try { awaitCancellation() }
                finally { firstCancelled.complete(Unit) }
            } else {
                assertEquals("/meta/movie/tt0111161.json", request.url.encodedPath)
                respond("""{"meta":{"id":"tt0111161","imdbRating":"9.3"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { vm ->
            vm.loadExtensionDetail(Json.encodeToString(film))
            withContext(Dispatchers.Default) { withTimeout(5_000) { firstStarted.await() } }
            vm.loadExtensionDetail(Json.encodeToString(film.copy(id = "tt0111161", imdbId = "tt0111161", title = "Second title")))
            val displayed = awaitScore(vm, 9.3f)
            withContext(Dispatchers.Default) { withTimeout(5_000) { firstCancelled.await() } }
            assertEquals("tt0111161", displayed.id)
        }
    }

    @Test fun builtInDetailRefreshesBeforeSlowRecommendationsFinish() = runTest {
        val recommendationsStarted = CompletableDeferred<Unit>()
        val metadata = object : MetadataRepository by stub<MetadataRepository>() {
            override suspend fun getDetail(type: String, id: Int) = film.copy(id = "42", tmdbId = 42)
            override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> {
                recommendationsStarted.complete(Unit)
                awaitCancellation()
            }
        }
        withDetail({ request ->
            val body = when (request.url.encodedPath) {
                "/me/ratings/batch" -> """{"items":[]}"""
                "/meta/movie/tt45368240.json" -> """{"meta":{"id":"tt45368240","imdbRating":"9.3"}}"""
                else -> error("Unexpected HTTP lookup")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }, metadata) { vm ->
            vm.loadDetail("movie", 42)
            val displayed = awaitScore(vm, 9.3f)
            assertTrue(recommendationsStarted.isCompleted)
            assertTrue(vm.state.value.isLoadingSimilar)
            assertEquals("42", displayed.id)
        }
    }

    @Test fun selectedCardIsVisibleWhileTheFirstFullDetailIsStillPending() = runTest {
        val full = CompletableDeferred<MediaItem>()
        val metadata = object : MetadataRepository by stub<MetadataRepository>() {
            override suspend fun getDetail(type: String, id: Int) = full.await()
            override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> = awaitCancellation()
        }
        withDetail({ respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }, metadata) { vm ->
            val seed = film.copy(id = "tmdb:42", tmdbId = 42)
            vm.loadDetailWithSeed("movie",42,Json.encodeToString(seed))
            assertEquals("42",vm.state.value.mediaItem?.id)
            assertEquals(seed.title,vm.state.value.mediaItem?.title)
            assertEquals(seed.posterUrl,vm.state.value.mediaItem?.posterUrl)
            assertTrue(vm.state.value.isLoading)
            assertFalse(full.isCompleted)
            full.complete(seed.copy(id="42",overview="Full description"))
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { !it.isLoading } } }
            assertEquals("Full description",vm.state.value.mediaItem?.overview)
        }
    }

    @Test fun firstDetailPreviewRejectsAnUnrelatedTitleOrWrongMediaType() = runTest {
        val metadata = object : MetadataRepository by stub<MetadataRepository>() {
            override suspend fun getDetail(type: String,id: Int): MediaItem = awaitCancellation()
        }
        withDetail({ error("Preview must not call a rating provider") },metadata) { vm ->
            vm.loadDetailWithSeed("movie",42,Json.encodeToString(film.copy(tmdbId=43)))
            assertNull(vm.state.value.mediaItem)
            vm.loadDetailWithSeed("tv",42,Json.encodeToString(film.copy(tmdbId=42)))
            assertNull(vm.state.value.mediaItem)
        }
    }

    @Test fun lateExtensionMetadataCannotReplaceANewSelectionOrResetItsSeason() = runTest {
        withDetail({ respond("{}",headers=headersOf(HttpHeaders.ContentType,"application/json")) }) { vm ->
            val series=film.copy(type=MediaType.SERIES,seasons=listOf(Season(1,0,"First"),Season(3,0,"Third")))
            vm.showExtensionDetailPreview(Json.encodeToString(series))
            vm.selectExtensionSeason(3)
            vm.completeExtensionDetail(Json.encodeToString(series.copy(overview="Enriched description")))
            assertEquals(3,vm.state.value.selectedSeason)
            assertEquals("Enriched description",vm.state.value.mediaItem?.overview)
            vm.loadExtensionDetail(Json.encodeToString(film.copy(id="tt0111161",title="Next title")))
            vm.completeExtensionDetail(Json.encodeToString(series))
            assertEquals("Next title",vm.state.value.mediaItem?.title)
        }
    }
}
