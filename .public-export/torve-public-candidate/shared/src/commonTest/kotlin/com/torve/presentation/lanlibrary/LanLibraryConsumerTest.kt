package com.torve.presentation.lanlibrary

import com.torve.data.lanlibrary.LanHubRegistryApi
import com.torve.data.lanlibrary.LanLibraryHttpClient
import com.torve.domain.lanlibrary.LanHub
import com.torve.domain.lanlibrary.LanLibraryManifest
import com.torve.domain.lanlibrary.LanMediaEntry
import com.torve.domain.lanlibrary.LanMediaType
import com.torve.domain.lanlibrary.MANIFEST_VERSION
import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.lanlibrary.PlaybackRoutePreference
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * End-to-end coverage for the consumer side of Prompt 9 / 9B:
 *   - registry returns a hub
 *   - secret endpoint returns its auth secret
 *   - manifest fetch returns a known title
 *   - LanLibraryConsumer aggregates into a title-keyed map
 *   - PlaybackRoutePreference picks the LAN route on Wi-Fi
 *   - Cellular + wifiOnlyForLan suppresses the LAN route
 *   - The aggregated map carries no filesystem paths
 */
class LanLibraryConsumerTest {

    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // The Ktor HttpClient with no plugins is enough — we read the body
    // as text and decode manually, which avoids pulling in the test
    // module's content-negotiation typealias surface.
    private fun newRegistryClient(routeHandler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine(routeHandler))

    private fun newLanHttpClient(routeHandler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine(routeHandler))

    private fun manifest(): LanLibraryManifest = LanLibraryManifest(
        version = MANIFEST_VERSION,
        publisherId = "pub-1",
        generatedAtEpochMs = 1L,
        entries = listOf(
            LanMediaEntry(
                id = "abcd1234",
                title = "The Matrix",
                mediaType = LanMediaType.MOVIE,
                sizeBytes = 1_000_000L,
                containerExtension = "mkv",
                mimeType = "video/x-matroska",
                durationSeconds = 8190L,
            ),
            LanMediaEntry(
                id = "ef567890",
                title = "Severance",
                mediaType = LanMediaType.SHOW_EPISODE,
                seasonNumber = 1,
                episodeNumber = 1,
                sizeBytes = 500_000L,
                containerExtension = "mp4",
                mimeType = "video/mp4",
            ),
        ),
    )

    @Test
    fun consumerAggregatesManifestEntriesByTitleAndSeasonEpisode() = runTest {
        val registryClient = newRegistryClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/me/lan/hubs") -> respond(
                    content = """{"hubs":[{"publisher_id":"pub-1","device_label":"Mac","lan_host":"192.168.1.10","lan_port":41122,"protocol_version":1,"published_at_epoch_ms":42}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                path.endsWith("/secret") -> respond(
                    content = """{"publisher_id":"pub-1","auth_secret":"shh"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val lanClient = newLanHttpClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/local/manifest") -> respond(
                    content = testJson.encodeToString(LanLibraryManifest.serializer(), manifest()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                path.contains("/local/stream-token/") -> {
                    // Echo back a fixed token shape; entryId stays in
                    // the path so the consumer's URL builder picks it up.
                    val entryId = path.substringAfterLast("/local/stream-token/")
                    respond(
                        content = """{"path":"/local/stream/$entryId?token=mint-$entryId","token":"mint-$entryId","expires_at_epoch_ms":99999}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val consumer = LanLibraryConsumer(
            registry = LanHubRegistryApi(registryClient, tokenProvider = { "tok" }),
            httpClient = LanLibraryHttpClient(lanClient),
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )
        consumer.refreshOnce()

        val matrix = consumer.findLanRoute("The Matrix")
        assertNotNull(matrix)
        assertEquals(
            "http://192.168.1.10:41122/local/stream/abcd1234?token=mint-abcd1234",
            matrix.url,
        )
        assertEquals("shh", matrix.headers["X-Torve-Lan-Auth"])
        assertFalse(
            matrix.url.contains("token=shh"),
            "URL must NOT reuse the hub auth secret as the per-item token",
        )

        val severance = consumer.findLanRoute("Severance", seasonNumber = 1, episodeNumber = 1)
        assertNotNull(severance)
        assertTrue("ef567890" in severance.url)
        assertTrue(severance.url.contains("token=mint-ef567890"))

        // Wrong (season, episode) shouldn't match the same entry.
        assertNull(consumer.findLanRoute("Severance", seasonNumber = 1, episodeNumber = 2))
    }

    @Test
    fun `findLanRoute returns null when token issuance fails`() = runTest {
        val registryClient = newRegistryClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/me/lan/hubs") -> respond(
                    content = """{"hubs":[{"publisher_id":"pub-1","device_label":"Mac","lan_host":"192.168.1.10","lan_port":41122,"protocol_version":1,"published_at_epoch_ms":42}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                path.endsWith("/secret") -> respond(
                    content = """{"publisher_id":"pub-1","auth_secret":"shh"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val lanClient = newLanHttpClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/local/manifest") -> respond(
                    content = testJson.encodeToString(LanLibraryManifest.serializer(), manifest()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                // Stream-token endpoint refuses every request — simulates
                // a publisher that rotated tokens or restarted.
                path.contains("/local/stream-token/") ->
                    respond("", HttpStatusCode.NotFound)
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val consumer = LanLibraryConsumer(
            registry = LanHubRegistryApi(registryClient, tokenProvider = { "tok" }),
            httpClient = LanLibraryHttpClient(lanClient),
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )
        consumer.refreshOnce()
        // Manifest entry exists, but token issuance 404s — caller must
        // fall back to provider/ReDownload.
        assertTrue(consumer.hasLanMatch("The Matrix"))
        assertNull(consumer.findLanRoute("The Matrix"))
    }

    @Test
    fun `findLanRoute is null when no hub matches`() = runTest {
        val empty = newRegistryClient { _ ->
            respond(
                content = """{"hubs":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val unused = newLanHttpClient { _ -> respond("nope", HttpStatusCode.NotFound) }
        val consumer = LanLibraryConsumer(
            registry = LanHubRegistryApi(empty, tokenProvider = { "tok" }),
            httpClient = LanLibraryHttpClient(unused),
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )
        consumer.refreshOnce()
        assertNull(consumer.findLanRoute("Anything"))
    }

    @Test
    fun `route preference picks LAN over provider on Wi-Fi`() = runTest {
        val (consumer, _) = primedConsumer(this)
        val lan = consumer.findLanRoute("The Matrix")!!
        val pref = PlaybackRoutePreference.of(
            lanStream = lan,
            providerStream = PlaybackRoute.ProviderStream("https://upstream/movie.mp4"),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(lan, pref.pick())
    }

    @Test
    fun `route preference suppresses LAN on cellular when wifiOnlyForLan is true`() = runTest {
        val (consumer, _) = primedConsumer(this)
        val lan = consumer.findLanRoute("The Matrix")!!
        val pref = PlaybackRoutePreference.of(
            lanStream = lan,
            providerStream = PlaybackRoute.ProviderStream("https://upstream/movie.mp4"),
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = true,
        )
        assertEquals(PlaybackRoute.ProviderStream("https://upstream/movie.mp4"), pref.pick())
    }

    @Test
    fun `aggregated entries carry no filesystem paths`() = runTest {
        val (consumer, _) = primedConsumer(this)
        val map = consumer.entries.value
        // Defense in depth: the manifest is path-free by construction;
        // this assertion catches a future drift where a publisher
        // accidentally includes a path-shaped field.
        for ((_, match) in map) {
            val entry = match.entry
            // No field contains a path-like prefix.
            val pathLike = listOfNotNull(
                entry.title, entry.id, entry.containerExtension, entry.mimeType,
                entry.posterUrl,
            ).any { it.startsWith("/") || it.contains(":\\") || it.startsWith("file://") }
            assertFalse(pathLike, "manifest entry must not carry a path: $entry")
        }
    }

    private suspend fun primedConsumer(
        scope: kotlinx.coroutines.test.TestScope,
    ): Pair<LanLibraryConsumer, LanLibraryHttpClient> {
        val registryClient = newRegistryClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/me/lan/hubs") -> respond(
                    content = """{"hubs":[{"publisher_id":"pub-1","device_label":"Mac","lan_host":"192.168.1.10","lan_port":41122,"protocol_version":1,"published_at_epoch_ms":42}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                path.endsWith("/secret") -> respond(
                    content = """{"publisher_id":"pub-1","auth_secret":"shh"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val lanClient = newLanHttpClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/local/manifest") -> respond(
                    content = testJson.encodeToString(LanLibraryManifest.serializer(), manifest()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                path.contains("/local/stream-token/") -> {
                    val entryId = path.substringAfterLast("/local/stream-token/")
                    respond(
                        content = """{"path":"/local/stream/$entryId?token=mint-$entryId","token":"mint-$entryId","expires_at_epoch_ms":99999}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val httpClient = LanLibraryHttpClient(lanClient)
        val consumer = LanLibraryConsumer(
            registry = LanHubRegistryApi(registryClient, tokenProvider = { "tok" }),
            httpClient = httpClient,
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scope.testScheduler)),
        )
        consumer.refreshOnce()
        return consumer to httpClient
    }

    @Suppress("unused")
    private val unusedHub = LanHub("p", "x", "10.0.0.1", 1, MANIFEST_VERSION, 0L)
}
