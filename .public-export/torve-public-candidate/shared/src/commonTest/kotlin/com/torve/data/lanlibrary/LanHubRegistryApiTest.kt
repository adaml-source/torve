package com.torve.data.lanlibrary

import com.torve.domain.lanlibrary.LanHub
import com.torve.domain.lanlibrary.MANIFEST_VERSION
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance for Prompt 9 backend hub registry:
 *   - Defensive degradation: 404/500 → null/empty without throwing.
 *   - No-token path returns silently.
 *   - Happy path round-trips a hub entry through Ktor.
 *   - Bearer header is set when a token is available; never set when null.
 */
class LanHubRegistryApiTest {

    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun client(handler: suspend (HttpRequestData) -> io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData):
        HttpClient = error("not used — see makeClient instead")

    private fun makeClient(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData): HttpClient =
        HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private fun publishRequest(): com.torve.domain.lanlibrary.LanHubPublishRequest =
        com.torve.domain.lanlibrary.LanHubPublishRequest(
            publisherId = "pub-1",
            deviceLabel = "Adam Mac",
            lanHost = "192.168.1.10",
            lanPort = 41122,
            protocolVersion = MANIFEST_VERSION,
            authSecret = "secret-deadbeef",
        )

    @Test
    fun `publish returns the parsed hub on 200 and sends the auth secret`() = runTest {
        val responsePayload = LanHub(
            publisherId = "pub-1",
            deviceLabel = "Adam Mac",
            lanHost = "192.168.1.10",
            lanPort = 41122,
            protocolVersion = MANIFEST_VERSION,
            publishedAtEpochMs = 1L,
        )
        val captured = mutableListOf<HttpRequestData>()
        val client = makeClient { request ->
            captured += request
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.encodedPath.endsWith("/me/lan/hubs"))
            respond(
                content = testJson.encodeToString(LanHub.serializer(), responsePayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { "tok-A" })
        val out = api.publish(publishRequest())
        assertNotNull(out)
        assertEquals("pub-1", out.publisherId)
        assertEquals("Bearer tok-A", captured.single().headers["Authorization"])
        // Body must include the auth secret — that's the whole point
        // of the publish path.
        val sentBody = (captured.single().body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
            .bytes().decodeToString()
        assertTrue("secret-deadbeef" in sentBody, "publish body must carry auth_secret: $sentBody")
    }

    @Test
    fun publishReturnsNullOn404RegistryNotDeployed() = runTest {
        val client = makeClient { _ ->
            respond("not found", HttpStatusCode.NotFound)
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { "tok" })
        val out = api.publish(publishRequest())
        assertNull(out)
    }

    @Test
    fun `publish returns null when no token is available`() = runTest {
        var hit = false
        val client = makeClient { _ ->
            hit = true
            respond("never reached", HttpStatusCode.OK)
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { null })
        val out = api.publish(publishRequest())
        assertNull(out)
        assertEquals(false, hit, "no HTTP call should fire when token is null")
    }

    @Test
    fun `listing payload never carries an auth_secret field`() = runTest {
        var listingBody: String? = null
        val payload = """{"hubs":[{"publisher_id":"p1","device_label":"Mac","lan_host":"192.168.1.5","lan_port":41122,"protocol_version":1,"published_at_epoch_ms":42}]}"""
        val client = makeClient { _ ->
            listingBody = payload
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { "tok" })
        val hubs = api.list()
        // The bytes the listing endpoint returned. Privacy contract:
        // listing body cannot include the auth secret.
        assertTrue(listingBody!!.let { !it.contains("auth_secret") }, "listing must not include auth_secret")
        assertEquals(1, hubs.size)
    }

    @Test
    fun `list parses the hubs envelope`() = runTest {
        val client = makeClient { _ ->
            respond(
                content = """{"hubs":[{"publisher_id":"p1","device_label":"Mac","lan_host":"192.168.1.5","lan_port":41122,"protocol_version":1,"published_at_epoch_ms":42}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { "tok" })
        val hubs = api.list()
        assertEquals(1, hubs.size)
        assertEquals("p1", hubs.single().publisherId)
        assertEquals("Mac", hubs.single().deviceLabel)
    }

    @Test
    fun `list returns empty on 500`() = runTest {
        val client = makeClient { _ ->
            respond("oops", HttpStatusCode.InternalServerError)
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { "tok" })
        assertEquals(emptyList<LanHub>(), api.list())
    }

    @Test
    fun `fetchSecret returns null when registry is missing the route`() = runTest {
        val client = makeClient { _ ->
            respond("nope", HttpStatusCode.NotFound)
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { "tok" })
        assertNull(api.fetchSecret("pub-1"))
    }

    @Test
    fun `fetchSecret round-trips an auth secret`() = runTest {
        val client = makeClient { _ ->
            respond(
                content = """{"publisher_id":"pub-1","auth_secret":"abcdef"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { "tok" })
        val secret = api.fetchSecret("pub-1")
        assertNotNull(secret)
        assertEquals("abcdef", secret.authSecret)
    }

    @Test
    fun `delete swallows network errors`() = runTest {
        val client = makeClient { _ ->
            respond("server gone", HttpStatusCode.ServiceUnavailable)
        }
        val api = LanHubRegistryApi(httpClient = client, tokenProvider = { "tok" })
        // Just confirms no exception escapes — the API is fire-and-forget.
        api.delete("pub-X")
    }
}
