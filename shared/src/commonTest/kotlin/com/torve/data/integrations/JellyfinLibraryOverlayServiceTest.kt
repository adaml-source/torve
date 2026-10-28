package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinLibraryOverlayServiceTest {
    @Test fun externalCatalogueCanMatchLibraryByImdbWithoutTmdbEnrichment() = runTest {
        val service = mockService { request ->
            if (request.method == io.ktor.http.HttpMethod.Head) {
                assertEquals("/Videos/right/stream", request.url.encodedPath)
                ""
            } else """{"Items":[{"Id":"wrong","Name":"Same title","Type":"Movie","ProviderIds":{"Tmdb":"0","Imdb":"tt999"}},{"Id":"right","Name":"Movie","Type":"Movie","ProviderIds":{"Tmdb":"234","Imdb":"tt1234567"}}]}"""
        }
        assertEquals(setOf("movie"), service.playableCatalogCopies(0, MediaType.MOVIE, "tt1234567"))
        assertEquals("http://jellyfin.test/Videos/right/stream?static=true",
            service.verifiedCatalogPlaybackRoute(0, MediaType.MOVIE, null, null, "tt1234567")?.url)
    }
    @Test fun unreachableUserLookupDoesNotBlameTheKeyOrRepeatTheRequest() = runTest {
        var calls = 0
        val service = userLookupService { calls++; error("Network unavailable") }
        val failure = runCatching { service.getLibrarySectionsOrThrow() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("Cannot reach Jellyfin."))
        assertFalse(failure?.message.orEmpty().contains("permissions"))
        assertEquals(1, calls)
    }

    @Test fun currentUserTokenLoadsViewsWithoutListingOtherUsers() = runTest {
        val paths = mutableListOf<String>()
        val service = userLookupService {
            paths += it.url.encodedPath
            HttpStatusCode.OK to when (it.url.encodedPath) {
                "/Users/Me" -> """{"Id":"viewer"}"""
                "/Users/viewer/Views" -> """{"Items":[{"Id":"movies","Name":"Movies","CollectionType":"movies"}]}"""
                else -> error("Unexpected request")
            }
        }
        assertEquals(listOf("movies"), service.getLibrarySectionsOrThrow().map { it.id })
        assertEquals(listOf("/Users/Me", "/Users/viewer/Views"), paths)
    }

    @Test fun serverKeyFallsBackToUsersOnceAndKeepsTheSelectedProfile() = runTest {
        val paths = mutableListOf<String>()
        val service = userLookupService {
            paths += it.url.encodedPath
            when (it.url.encodedPath) {
                "/Users/Me" -> HttpStatusCode.Unauthorized to "{}"
                "/Users" -> HttpStatusCode.OK to """[{"Id":"viewer","Name":"Viewer"}]"""
                "/Users/viewer/Views" -> HttpStatusCode.OK to """{"Items":[]}"""
                else -> error("Unexpected request")
            }
        }
        service.getLibrarySectionsOrThrow()
        service.getLibrarySectionsOrThrow()
        assertEquals("viewer", service.getSelectedUserId())
        assertEquals(listOf("/Users/Me", "/Users", "/Users/viewer/Views", "/Users/viewer/Views"), paths)
    }

    @Test fun rejectedCredentialsAreDistinguishedFromAnUnavailableServer() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)) {
            var calls = 0
            val service = userLookupService {
                calls++
                (if (it.url.encodedPath == "/Users/Me") HttpStatusCode.Unauthorized else status) to "{}"
            }
            val message = runCatching { service.getLibrarySectionsOrThrow() }.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains(if (status == HttpStatusCode.Forbidden) "rejected access" else "HTTP 503"))
            assertEquals(2, calls)
        }
    }

    @Test fun cancelledUserLookupNeverStartsAFallbackRequest() = runTest {
        var calls = 0
        val service = userLookupService { calls++; throw CancellationException("Cancelled") }
        assertTrue(runCatching { service.getLibrarySectionsOrThrow() }.exceptionOrNull() is CancellationException)
        assertEquals(1, calls)
    }

    @Test fun malformedSuccessfulUserResponseIdentifiesTheServerUrlProblem() = runTest {
        val service = userLookupService { HttpStatusCode.OK to "<html>Not Jellyfin</html>" }
        val message = runCatching { service.getLibrarySectionsOrThrow() }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("invalid user response"))
    }

    private fun userLookupService(response: (io.ktor.client.request.HttpRequestData) -> Pair<HttpStatusCode, String>): JellyfinLibraryOverlayService {
        val client = HttpClient(MockEngine { request ->
            assertEquals("test-key", request.headers["X-Emby-Token"])
            val (status, content) = response(request)
            respond(content, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(HttpTimeout)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return JellyfinLibraryOverlayService(client, ConfiguredPrefs(selectedUserId = null), ConfiguredSecrets())
    }

    @Test fun missingPhysicalFileIsUnavailableButAuthenticationFailureIsUnknown() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Unauthorized)) {
            val service = mockService(headStatus = status) { request ->
                if (request.method == io.ktor.http.HttpMethod.Head) ""
                else """{"Items":[{"Id":"copy","Name":"Movie","Type":"Movie","ProviderIds":{"Tmdb":"42"}}]}"""
            }
            val result = runCatching { service.playableCatalogCopies(42, MediaType.MOVIE) }
            if (status == HttpStatusCode.NotFound) assertEquals(emptySet(), result.getOrThrow())
            else assertTrue(result.isFailure)
        }
    }
    @Test fun nativeAvailabilityAndPlaybackRetainTheStrictImdbFallback() = runTest {
        val service = mockService { request ->
            if (request.method == io.ktor.http.HttpMethod.Head) {
                assertEquals("/Videos/right/stream", request.url.encodedPath)
                ""
            } else """{"Items":[{"Id":"conflict","Name":"Same title","Type":"Movie","ProviderIds":{"Tmdb":"999","Imdb":"tt1234567"}},{"Id":"right","Name":"Movie","Type":"Movie","ProviderIds":{"Imdb":"tt1234567"}}]}"""
        }
        assertEquals(setOf("movie"), service.playableCatalogCopies(42, MediaType.MOVIE, "tt1234567"))
        assertEquals("http://jellyfin.test/Videos/right/stream?static=true",
            service.verifiedCatalogPlaybackRoute(42, MediaType.MOVIE, null, null, "tt1234567")?.url)
    }
    @Test fun movieAvailabilityVerifiesTheActualUserStreamWithoutDownloadingIt() = runTest {
        var headRequests = 0
        val service = mockService { request ->
            if (request.method == io.ktor.http.HttpMethod.Head) {
                headRequests++
                assertEquals("/Videos/copy/stream", request.url.encodedPath)
                ""
            } else """{"Items":[{"Id":"copy","Name":"Open film","Type":"Movie","ProviderIds":{"Tmdb":"42"}}]}"""
        }
        assertEquals(setOf("movie"), service.playableCatalogCopies(42, MediaType.MOVIE))
        assertEquals(1, headRequests)
    }
    @Test fun unavailableTitleNeverProbesAnotherMoviesStream() = runTest {
        val service = mockService { request ->
            assertEquals(io.ktor.http.HttpMethod.Get, request.method)
            """{"Items":[{"Id":"wrong","Name":"Same title","Type":"Movie","ProviderIds":{"Tmdb":"999"}}]}"""
        }
        assertEquals(emptySet(), service.playableCatalogCopies(42, MediaType.MOVIE))
    }
    @Test fun seriesAvailabilityExcludesMissingPlaceholderAndUnplayableEpisodes() = runTest {
        val service = mockService { request ->
            if (request.url.encodedPath.endsWith("/Episodes")) {
                assertEquals("user-1", request.url.parameters["UserId"])
                """{"Items":[
                    {"Id":"one","Name":"One","Type":"Episode","ParentIndexNumber":1,"IndexNumber":1,"MediaSources":[{"Id":"file"}]},
                    {"Id":"missing","Name":"Two","Type":"Episode","ParentIndexNumber":1,"IndexNumber":2,"IsMissing":true,"MediaSources":[{"Id":"file"}]},
                    {"Id":"placeholder","Name":"Three","Type":"Episode","ParentIndexNumber":1,"IndexNumber":3,"IsPlaceHolder":true,"MediaSources":[{"Id":"file"}]},
                    {"Id":"empty","Name":"Four","Type":"Episode","ParentIndexNumber":1,"IndexNumber":4}
                ]}"""
            } else """{"Items":[{"Id":"series","Name":"Series","Type":"Series","ProviderIds":{"Tmdb":"42"}}]}"""
        }
        assertEquals(setOf("s1e1"), service.playableCatalogCopies(42, MediaType.SERIES))
    }
    @Test fun connectionFailureRemainsUnknownAndDisabledLibraryMakesNoRequest() = runTest {
        val failed = mockService { error("Offline") }
        assertTrue(runCatching { failed.playableCatalogCopies(42, MediaType.MOVIE) }.isFailure)
        val disabled = mockService(enabled = false) { error("Must not request") }
        assertEquals(null, disabled.playableCatalogCopies(42, MediaType.MOVIE))
    }
    @Test fun nativeSeriesEpisodesUseAuthenticatedUserAndEpisodeOrdering() = runTest {
        val service = mockService { request ->
            assertEquals("/Shows/native-series/Episodes", request.url.encodedPath)
            assertEquals("user-1", request.url.parameters["UserId"])
            assertEquals("test-key", request.headers["X-Emby-Token"])
            """{"Items":[{"Id":"third","Name":"Third","Type":"Episode","ParentIndexNumber":2,"IndexNumber":1},{"Id":"second","Name":"Second","Type":"Episode","ParentIndexNumber":1,"IndexNumber":2},{"Id":"first","Name":"First","Type":"Episode","ParentIndexNumber":1,"IndexNumber":1},{"Id":"series","Name":"Series","Type":"Series"}]}"""
        }
        assertEquals(listOf("first", "second", "third"), service.getSeriesEpisodes("native-series").map { it.id })
    }
    @Test fun disabledNativeSeriesMakesNoEpisodeRequest() = runTest {
        var calls = 0
        val service = mockService(enabled = false) { calls++; "{}" }
        assertTrue(service.getSeriesEpisodes("native-series").isEmpty())
        assertEquals(0, calls)
    }
    @Test fun disabledJellyfinMakesNoLibraryResumePlaybackOrProbeRequests() = runTest {
        var calls = 0
        val service = mockService(enabled = false) { calls++; "{}" }
        assertFalse(service.isInLibrary(42, MediaType.MOVIE))
        assertTrue(service.getContinueWatching(20).isEmpty())
        assertEquals(null, service.buildAuthenticatedStream("item"))
        assertEquals(null, service.findPlaybackRoute(42, MediaType.MOVIE))
        assertFalse(service.testConnection("http://jellyfin.test", "test-key"))
        assertEquals(0, calls)
    }
    @Test
    fun playbackFindsExactIdBeyondFirstPageAndCachesTheIndex() = runTest {
        var calls = 0
        val service = mockService { request ->
            calls++
            assertEquals(null, request.url.parameters["AnyProviderIdEquals"])
            when (request.url.parameters["StartIndex"]) {
                "0" -> """{"TotalRecordCount":201,"Items":[${(1..200).joinToString(",") {
                    """{"Id":"wrong-$it","Name":"Resident Evil","Type":"Movie","ProviderIds":{"Tmdb":"${it + 1000}"}}"""
                }}]}"""
                "200" -> """{"TotalRecordCount":201,"Items":[{"Id":"right","Name":"Resident Evil","Type":"Movie","ProviderIds":{"Tmdb":"42"}}]}"""
                else -> error("Unexpected offset")
            }
        }
        assertEquals("http://jellyfin.test/Videos/right/stream?static=true", service.findPlaybackRoute(42, MediaType.MOVIE)?.url)
        assertTrue(service.isInLibrary(42, MediaType.MOVIE))
        assertEquals(2, calls)
    }

    @Test
    fun aNewlyImportedCopyIsFoundAfterAnEarlierMiss() = runTest {
        var imported = false
        val service = mockService {
            if (imported) """{"Items":[{"Id":"new","Name":"Movie","Type":"Movie","ProviderIds":{"Tmdb":"42"}}]}"""
            else """{"Items":[]}"""
        }
        assertEquals(null, service.findPlaybackRoute(42, MediaType.MOVIE))
        imported = true
        assertTrue(service.findPlaybackRoute(42, MediaType.MOVIE) != null)
    }

    @Test fun badgeMissesReuseTheIndexInsteadOfDownloadingItForEveryPoster() = runTest {
        var calls = 0
        val service = mockService { calls++; """{"Items":[]}""" }
        repeat(10) { assertFalse(service.isInLibrary(it + 1, MediaType.MOVIE)) }
        assertEquals(1, calls)
    }

    @Test
    fun imdbFallbackCannotOverrideAConflictingTmdbIdentity() = runTest {
        val service = mockService {
            """{"Items":[{"Id":"wrong","Name":"Movie","Type":"Movie","ProviderIds":{"Tmdb":"999","Imdb":"tt1234567"}},{"Id":"right","Name":"Movie","Type":"Movie","ProviderIds":{"Imdb":"tt1234567"}}]}"""
        }
        assertEquals("http://jellyfin.test/Videos/right/stream?static=true",
            service.findPlaybackRoute(42, MediaType.MOVIE, imdbId = "tt1234567")?.url)
    }

    @Test
    fun resumeKeepsNativeArtworkEvenWithoutCatalogueIds() = runTest {
        val service = mockService {
            """{"Items":[{"Id":"bunny","Name":"Big Buck Bunny","Type":"Movie","ImageTags":{"Primary":"poster"},"BackdropImageTags":["backdrop"],"RunTimeTicks":100000000,"UserData":{"PlaybackPositionTicks":20000000,"LastPlayedDate":"2026-10-14T12:00:00Z"}}]}"""
        }
        val item = service.getContinueWatching(20).single()
        assertEquals("jf_bunny", item.mediaId)
        assertEquals("http://jellyfin.test/Items/bunny/Images/Primary?maxHeight=400&quality=90", item.posterUrl)
        assertTrue(item.backdropUrl != null)
        assertEquals(2000L, item.positionMs)
        assertTrue(item.updatedAt > 0L)
    }

    @Test
    fun resumeUsesSeriesIdentityAndArtworkInsteadOfEpisodeProviderId() = runTest {
        val service = mockService { request ->
            if (request.url.encodedPath.endsWith("/Resume")) {
                """{"Items":[{"Id":"episode","Name":"Pilot","Type":"Episode","SeriesName":"Show","SeriesId":"show","ParentIndexNumber":2,"IndexNumber":3,"ProviderIds":{"Tmdb":"999"},"RunTimeTicks":100000000,"UserData":{"PlaybackPositionTicks":10000000}}]}"""
            } else {
                assertEquals("show", request.url.parameters["Ids"])
                """{"Items":[{"Id":"show","Name":"Show","Type":"Series","ProviderIds":{"Tmdb":"42"},"ImageTags":{"Primary":"poster"}}]}"""
            }
        }
        val item = service.getContinueWatching(20).single()
        assertEquals("42", item.mediaId)
        assertEquals("Show", item.title)
        assertEquals(2, item.seasonNumber)
        assertEquals(3, item.episodeNumber)
        assertTrue(item.posterUrl.orEmpty().contains("/Items/show/"))
        assertEquals(0L, item.updatedAt)
    }

    private fun mockService(enabled: Boolean = true, headStatus: HttpStatusCode = HttpStatusCode.OK, body: (io.ktor.client.request.HttpRequestData) -> String): JellyfinLibraryOverlayService {
        val client = HttpClient(MockEngine { request ->
            assertEquals("test-key", request.headers["X-Emby-Token"])
            respond(body(request), if (request.method == io.ktor.http.HttpMethod.Head) headStatus else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return JellyfinLibraryOverlayService(client, ConfiguredPrefs(enabled), ConfiguredSecrets())
    }

    @Test
    fun authenticatedPlaybackKeepsTokenInHeaders() = runTest {
        val service = serviceWithResponse("{}")
        val route = service.buildAuthenticatedStream("episode-1")
        assertEquals("http://jellyfin.test/Videos/episode-1/stream?static=true", route?.url)
        assertEquals(mapOf("X-Emby-Token" to "test-key"), route?.headers)
        assertFalse(route?.url.orEmpty().contains("test-key"))
    }

    @Test
    fun `primary poster tag is read from standard jellyfin image tags`() {
        val item = Json { ignoreUnknownKeys = true }.decodeFromString<JellyfinBrowseItem>(
            """{"Id":"movie-1","Name":"Movie","Type":"Movie","ImageTags":{"Primary":"poster-tag"}}""",
        )

        assertEquals("poster-tag", item.resolvedPrimaryImageTag)
    }

    @Test
    fun `legacy primary image tag remains a fallback`() {
        val item = Json { ignoreUnknownKeys = true }.decodeFromString<JellyfinBrowseItem>(
            """{"Id":"movie-1","Name":"Movie","Type":"Movie","PrimaryImageTag":"legacy-tag"}""",
        )

        assertEquals("legacy-tag", item.resolvedPrimaryImageTag)
    }

    @Test
    fun `non-empty response with another tmdb id is not in library`() = runTest {
        val service = serviceWithResponse(
            """{"Items":[{"Id":"wrong","Name":"Another movie","Type":"Movie","ProviderIds":{"Tmdb":"999"}}]}""",
        )

        assertFalse(service.isInLibrary(42, MediaType.MOVIE))
    }

    @Test
    fun `matching tmdb id and media type is in library`() = runTest {
        val service = serviceWithResponse(
            """{"Items":[{"Id":"movie","Name":"The movie","Type":"Movie","ProviderIds":{"Tmdb":"42"}}]}""",
        )

        assertTrue(service.isInLibrary(42, MediaType.MOVIE))
    }

    @Test
    fun episodeDisplayIncludesParentSeriesAndNumber() {
        val item = Json { ignoreUnknownKeys = true }.decodeFromString<JellyfinBrowseItem>(
            """{"Id":"episode-1","Name":"Faceless Men","Type":"Episode","SeriesName":"House of the Dragon","SeriesId":"series-1","ParentIndexNumber":3,"IndexNumber":6}""",
        )

        assertEquals("House of the Dragon", item.displayTitle)
        assertEquals("S03E06 · Faceless Men", item.displaySubtitle)
        assertEquals("series-1", item.seriesId)
    }

    private fun serviceWithResponse(body: String): JellyfinLibraryOverlayService {
        val client = HttpClient(MockEngine { request ->
            assertEquals(null, request.url.parameters["AnyProviderIdEquals"])
            assertEquals("ProviderIds", request.url.parameters["Fields"])
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return JellyfinLibraryOverlayService(client, ConfiguredPrefs(), ConfiguredSecrets())
    }

    private class ConfiguredPrefs(private val enabled: Boolean = true, private var selectedUserId: String? = "user-1") : PreferencesRepository {
        override suspend fun getString(key: String): String? = when (key) {
            "jellyfin_server_url" -> "http://jellyfin.test"
            "jellyfin_selected_user_id" -> selectedUserId
            "app_feature_jellyfin_enabled" -> enabled.toString()
            else -> null
        }

        override suspend fun setString(key: String, value: String) { if (key == "jellyfin_selected_user_id") selectedUserId = value }
        override suspend fun remove(key: String) = Unit
    }

    private class ConfiguredSecrets : IntegrationSecretStore {
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) = Unit
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
            if (key == IntegrationSecretKey.JELLYFIN_API_KEY) "test-key" else null

        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) = Unit
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
            IntegrationStorageMode.DEVICE_ONLY

        override suspend fun clearAllSecrets() = Unit
        override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> = emptyList()
    }
}
