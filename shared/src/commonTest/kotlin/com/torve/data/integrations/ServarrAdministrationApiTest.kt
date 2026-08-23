package com.torve.data.integrations

import com.torve.domain.integrations.AutomationAdminErrorCode
import com.torve.domain.integrations.AutomationAdminResult
import com.torve.domain.integrations.AutomationCapability
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationQueueRemoval
import com.torve.domain.integrations.AutomationReleaseQuery
import com.torve.domain.integrations.AutomationIndexerCreateRequest
import com.torve.domain.integrations.AutomationIndexerProtocol
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.AutomationSubtitleKind
import com.torve.domain.integrations.AutomationSubtitleTarget
import com.torve.domain.integrations.TdarrScanRequest
import com.torve.domain.integrations.TdarrJobAction
import com.torve.domain.integrations.TdarrJobActionRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServarrAdministrationApiTest {
    @Test
    fun `capabilities are service specific`() {
        val api = ServarrAdministrationApi(client { jsonResponse("[]") })

        assertTrue(AutomationCapability.RELEASE_SEARCH in api.capabilities(instance(AutomationServiceType.SONARR)))
        assertTrue(AutomationCapability.INDEXER_CONTROL in api.capabilities(instance(AutomationServiceType.PROWLARR)))
        assertTrue(AutomationCapability.SUBTITLE_SEARCH in api.capabilities(instance(AutomationServiceType.BAZARR)))
        assertTrue(AutomationCapability.TDARR_CONTROL in api.capabilities(instance(AutomationServiceType.TDARR)))
        assertFalse(AutomationCapability.LIBRARY_ADD in api.capabilities(instance(AutomationServiceType.TDARR)))
    }

    @Test
    fun `sonarr lookup uses v3 contract and parses safe media fields`() = runTest {
        var request: HttpRequestData? = null
        val api = ServarrAdministrationApi(client {
            request = it
            jsonResponse(
                """[{"id":0,"title":"Example Show","year":2025,"tvdbId":123,"overview":"Summary","images":[{"coverType":"poster","remoteUrl":"https://images.example/poster.jpg"}]}]""",
            )
        })

        val result = api.lookupMedia(instance(AutomationServiceType.SONARR), "secret", "Example")

        val item = assertIs<AutomationAdminResult.Success<*>>(result).value as List<*>
        assertEquals("Example Show", (item.single() as com.torve.domain.integrations.AutomationLibraryItem).title)
        assertEquals(123, (item.single() as com.torve.domain.integrations.AutomationLibraryItem).externalId)
        assertEquals("/api/v3/series/lookup", request?.url?.encodedPath)
        assertEquals("Example", request?.url?.parameters?.get("term"))
        assertEquals("secret", request?.headers?.get("X-Api-Key"))
    }

    @Test
    fun `sonarr library exposes monitoring and episode progress`() = runTest {
        val api = ServarrAdministrationApi(client {
            jsonResponse(
                """[{"id":12,"title":"Example Show","year":2003,"monitored":true,"seasons":[{"seasonNumber":0,"monitored":false},{"seasonNumber":1,"monitored":true},{"seasonNumber":2,"monitored":false}],"statistics":{"episodeFileCount":3,"episodeCount":24}}]""",
            )
        })

        val result = api.listLibrary(instance(AutomationServiceType.SONARR), "secret")

        val items = assertIs<AutomationAdminResult.Success<*>>(result).value as List<*>
        val item = items.single() as com.torve.domain.integrations.AutomationLibraryItem
        assertTrue(item.monitored)
        assertEquals(2, item.seasonCount)
        assertEquals(1, item.monitoredSeasonCount)
        assertEquals(24, item.episodeCount)
        assertEquals(3, item.episodeFileCount)
    }

    @Test
    fun `sonarr series-only interactive release lookup is rejected before rss fallback`() = runTest {
        var requestCount = 0
        val api = ServarrAdministrationApi(client {
            requestCount += 1
            jsonResponse("[]")
        })

        val result = api.interactiveSearch(
            instance(AutomationServiceType.SONARR),
            "secret",
            AutomationReleaseQuery(mediaId = 12),
        )

        val failure = assertIs<AutomationAdminResult.Failure>(result)
        assertEquals(AutomationAdminErrorCode.INVALID_REQUEST, failure.code)
        assertEquals(0, requestCount)
    }

    @Test
    fun `sonarr missing search submits series search command`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ServarrAdministrationApi(client { request ->
            requests += request
            jsonResponse("""{"id":44,"name":"SeriesSearch","status":"queued"}""", HttpStatusCode.Created)
        })

        val result = api.searchMissingEpisodes(instance(AutomationServiceType.SONARR), "key", 12)

        assertIs<AutomationAdminResult.Success<*>>(result)
        assertEquals(listOf(HttpMethod.Post), requests.map { it.method })
        assertEquals("/api/v3/command", requests.single().url.encodedPath)
        val body = (requests.single().body as TextContent).text
        assertTrue(body.contains("\"name\":\"SeriesSearch\""))
        assertTrue(body.contains("\"seriesId\":12"))
    }

    @Test
    fun `monitor and search enables regular seasons but leaves specials unchanged`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ServarrAdministrationApi(client { request ->
            requests += request
            when {
                request.method == HttpMethod.Get -> jsonResponse(
                    """{"id":12,"title":"Example Show","monitored":false,"qualityProfileId":1,"path":"/tv/example","seasons":[{"seasonNumber":0,"monitored":false},{"seasonNumber":1,"monitored":false}]}""",
                )
                request.method == HttpMethod.Put -> jsonResponse("""{"id":12,"title":"Example Show"}""")
                else -> jsonResponse("""{"id":44,"name":"SeriesSearch","status":"queued"}""", HttpStatusCode.Created)
            }
        })

        val result = api.searchMissingEpisodes(
            instance(AutomationServiceType.SONARR),
            "key",
            12,
            monitorRegularSeasons = true,
        )

        assertIs<AutomationAdminResult.Success<*>>(result)
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Put, HttpMethod.Post), requests.map { it.method })
        val updateBody = (requests[1].body as TextContent).text
        assertTrue(updateBody.contains("\"monitored\":true"))
        assertTrue(updateBody.contains("\"monitorNewItems\":\"all\""))
        assertTrue(updateBody.contains("\"seasonNumber\":0,\"monitored\":false"))
        assertTrue(updateBody.contains("\"seasonNumber\":1,\"monitored\":true"))
        assertTrue(updateBody.contains("\"path\":\"/tv/example\""))
    }

    @Test
    fun `radarr queue parses progress and removal flags`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ServarrAdministrationApi(client { request ->
            requests += request
            if (request.method == HttpMethod.Get) {
                jsonResponse("""{"records":[{"id":7,"title":"Movie","status":"downloading","size":1000,"sizeleft":250}]}""")
            } else {
                jsonResponse("", HttpStatusCode.NoContent)
            }
        })
        val instance = instance(AutomationServiceType.RADARR)

        val queue = assertIs<AutomationAdminResult.Success<*>>(api.queue(instance, "key")).value as List<*>
        assertEquals(75.0, (queue.single() as com.torve.domain.integrations.AutomationQueueItem).progressPercent)
        assertIs<AutomationAdminResult.Success<*>>(
            api.removeQueueItem(instance, "key", 7, AutomationQueueRemoval(blocklistRelease = true, searchAgain = true)),
        )
        val delete = requests.last()
        assertEquals(HttpMethod.Delete, delete.method)
        assertEquals("true", delete.url.parameters["blocklist"])
        assertEquals("false", delete.url.parameters["skipRedownload"])
    }

    @Test
    fun `prowlarr indexer toggle reads then updates the existing resource`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ServarrAdministrationApi(client { request ->
            requests += request
            jsonResponse("""{"id":4,"name":"Legal test indexer","implementation":"Newznab","enable":true,"priority":1}""")
        })

        val result = api.setIndexerEnabled(instance(AutomationServiceType.PROWLARR), "key", 4, false)

        assertIs<AutomationAdminResult.Success<*>>(result)
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Put), requests.map { it.method })
        assertEquals("/api/v1/indexer/4", requests.last().url.encodedPath)
    }

    @Test
    fun `prowlarr creates a generic torznab indexer from the current server schema`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ServarrAdministrationApi(client { request ->
            requests += request
            if (request.method == HttpMethod.Get) {
                jsonResponse(
                    """[{"id":0,"name":"Generic Torznab","implementation":"Torznab","configContract":"TorznabSettings","protocol":"torrent","fields":[{"name":"baseUrl","value":""},{"name":"apiPath","value":"/api"},{"name":"apiKey","value":""},{"name":"torrentBaseSettings.appMinimumSeeders","value":1}]}]""",
                )
            } else {
                jsonResponse("""{"id":9,"name":"Legal fixture","implementation":"Torznab","protocol":"torrent","enable":true}""")
            }
        })

        val result = api.createIndexer(
            instance(AutomationServiceType.PROWLARR),
            "key",
            AutomationIndexerCreateRequest(
                name = "Legal fixture",
                protocol = AutomationIndexerProtocol.TORRENT,
                baseUrl = "http://indexer.local",
            ),
        )

        val created = assertIs<AutomationAdminResult.Success<*>>(result).value as com.torve.domain.integrations.AutomationIndexer
        assertEquals(9, created.id)
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Post), requests.map { it.method })
        assertEquals("/api/v1/indexer/schema", requests.first().url.encodedPath)
        assertEquals("/api/v1/indexer", requests.last().url.encodedPath)
    }

    @Test
    fun `bazarr manual search parses opaque candidate without exposing a download url`() = runTest {
        val api = ServarrAdministrationApi(client { request ->
            assertEquals("/api/providers/movies", request.url.encodedPath)
            assertEquals("22", request.url.parameters["radarrid"])
            jsonResponse(
                """{"data":[{"subtitle":"opaque-selection","provider":"OpenSubtitles.com","language":{"name":"English"},"score":91.5,"hearing_impaired":false,"forced":false}]}""",
            )
        })

        val result = api.searchSubtitles(
            instance(AutomationServiceType.BAZARR),
            "key",
            AutomationSubtitleTarget(AutomationSubtitleKind.MOVIE, 22, title = "Movie"),
        )

        val candidates = assertIs<AutomationAdminResult.Success<*>>(result).value as List<*>
        val candidate = candidates.single() as com.torve.domain.integrations.AutomationSubtitleCandidate
        assertEquals("opaque-selection", candidate.selectionToken)
        assertEquals("English", candidate.language)
    }

    @Test
    fun `tdarr overview combines libraries jobs automations and nodes`() = runTest {
        var crudCalls = 0
        val api = ServarrAdministrationApi(client { request ->
            when (request.url.encodedPath) {
                "/api/v2/cruddb" -> {
                    crudCalls += 1
                    when (crudCalls) {
                        1 -> jsonResponse("""[{"_id":"lib-1","name":"Movies","folder":"/media","transcode_enabled":true}]""")
                        else -> jsonResponse("""[{"_id":"auto-1","name":"Nightly","enabled":true}]""")
                    }
                }
                "/api/v2/client/staged" -> jsonResponse("""{"array":[{"_id":"/media/test.mkv","job":{"jobId":"job-1","start":123},"originalLibraryFile":{"file":"/media/test.mkv","DB":"lib-1"},"status":"transcodeSuccess"}]}""")
                "/api/v2/get-nodes" -> jsonResponse("""{"node-1":{"nodeName":"CPU node","online":true,"workers":{}}}""")
                else -> jsonResponse("{}", HttpStatusCode.NotFound)
            }
        })

        val result = api.tdarrOverview(instance(AutomationServiceType.TDARR), "")

        val overview = assertIs<AutomationAdminResult.Success<*>>(result).value as com.torve.domain.integrations.TdarrOverview
        assertEquals("Movies", overview.libraries.single().name)
        assertEquals("CPU node", overview.nodes.single().name)
        assertEquals("job-1", overview.jobs.single().id)
        assertEquals("Nightly", overview.automations.single().name)
    }

    @Test
    fun `tdarr staged job action resolves the current object and sends a typed verdict`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ServarrAdministrationApi(client { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/v2/client/staged" -> jsonResponse("""{"array":[{"_id":"/media/test.mkv","job":{"jobId":"job-1"},"status":"transcodeSuccess"}]}""")
                "/api/v2/transcode-user-verdict" -> jsonResponse("{}")
                else -> jsonResponse("{}", HttpStatusCode.NotFound)
            }
        })

        val result = api.actOnTdarrJob(
            instance(AutomationServiceType.TDARR),
            "",
            TdarrJobActionRequest("job-1", TdarrJobAction.ACCEPT),
        )

        assertIs<AutomationAdminResult.Success<*>>(result)
        val verdictBody = (requests.last().body as TextContent).text
        assertTrue(verdictBody.contains("\"verdict\":\"accept\""))
        assertTrue(verdictBody.contains("\"jobId\":\"job-1\""))
    }

    @Test
    fun `tdarr scan resolves and sends the library source path`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ServarrAdministrationApi(client { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/v2/cruddb" -> jsonResponse("""[{"_id":"lib-1","folder":"/media/movies"}]""")
                "/api/v2/scan-files" -> jsonResponse("{}")
                else -> jsonResponse("{}", HttpStatusCode.NotFound)
            }
        })

        val result = api.scanTdarrLibrary(
            instance(AutomationServiceType.TDARR),
            "",
            TdarrScanRequest("lib-1"),
        )

        assertIs<AutomationAdminResult.Success<*>>(result)
        assertEquals(listOf("/api/v2/cruddb", "/api/v2/scan-files"), requests.map { it.url.encodedPath })
        val scanBody = (requests.last().body as TextContent).text
        assertTrue(scanBody.contains("\"arrayOrPath\":\"/media/movies\""))
    }

    @Test
    fun `server errors are neutral and retryable`() = runTest {
        val api = ServarrAdministrationApi(client { jsonResponse("sensitive upstream body", HttpStatusCode.BadGateway) })

        val failure = assertIs<AutomationAdminResult.Failure>(
            api.listLibrary(instance(AutomationServiceType.SONARR), "super-secret"),
        )

        assertEquals(AutomationAdminErrorCode.SERVER_ERROR, failure.code)
        assertTrue(failure.retryable)
        assertFalse(failure.message.contains("sensitive"))
        assertFalse(failure.message.contains("secret"))
    }

    private fun instance(type: AutomationServiceType) = AutomationInstance(
        id = type.name.lowercase(),
        serviceType = type,
        name = type.name,
        serverUrl = "http://automation.local",
    )

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }

    private fun MockRequestHandleScope.jsonResponse(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
