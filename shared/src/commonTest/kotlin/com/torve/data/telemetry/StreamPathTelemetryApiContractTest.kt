package com.torve.data.telemetry

import com.torve.domain.telemetry.StreamPathTelemetryEvents
import com.torve.domain.telemetry.StreamPathTelemetryKeys
import com.torve.domain.telemetry.StreamPlaybackPath
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamPathTelemetryApiContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `generic handoff path posts safe backend body`() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"ok":true}""")
        }

        val ok = api.reportPathSelected(
            pathType = StreamPlaybackPath.GENERIC_HANDOFF_MEMORY_ID.wireValue,
            contentType = "movie",
            providerCategory = "debrid",
            generatedAtEpochMillis = 1234L,
        )

        assertTrue(ok)
        val request = captured ?: error("request not captured")
        assertEquals("/telemetry/stream-path", request.url.encodedPath)
        assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
        val body = parseBody(request)
        assertEquals("generic_handoff_memory_id", body["path_type"]?.jsonPrimitive?.content)
        assertEquals("android_tv", body["platform"]?.jsonPrimitive?.content)
        assertEquals("1.2.3", body["app_version"]?.jsonPrimitive?.content)
        assertEquals("amazon_sideload", body["distribution_channel"]?.jsonPrimitive?.content)
        assertEquals("movie", body["content_type"]?.jsonPrimitive?.content)
        assertEquals("debrid", body["provider_category"]?.jsonPrimitive?.content)
        assertEquals("1234", body["generated_at_epoch_millis"]?.jsonPrimitive?.content)
        assertNull(body["memory_id"])
        assertNull(body["source_key"])
        assertNull(body["url"])
        assertNull(body["token"])
        assertSafeBody(renderBody(request))
    }

    @Test
    fun `legacy fallback path posts legacy path type`() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"ok":true}""")
        }

        api.reportPathSelected(
            pathType = StreamPlaybackPath.LEGACY_DIRECT_NO_MEMORY_ID.wireValue,
            contentType = "series",
            providerCategory = "addon",
            generatedAtEpochMillis = 1235L,
        )

        val body = parseBody(captured ?: error("request not captured"))
        assertEquals("legacy_direct_no_memory_id", body["path_type"]?.jsonPrimitive?.content)
        assertEquals("series", body["content_type"]?.jsonPrimitive?.content)
        assertEquals("addon", body["provider_category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `usenet path posts usenet handoff path type`() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"ok":true}""")
        }

        api.reportPathSelected(
            pathType = StreamPlaybackPath.USENET_HANDOFF.wireValue,
            contentType = "movie",
            providerCategory = "usenet",
            generatedAtEpochMillis = 1236L,
        )

        val body = parseBody(captured ?: error("request not captured"))
        assertEquals("usenet_handoff", body["path_type"]?.jsonPrimitive?.content)
        assertEquals("usenet", body["provider_category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `iptv path posts iptv direct path type`() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"ok":true}""")
        }

        api.reportPathSelected(
            pathType = StreamPlaybackPath.IPTV_DIRECT.wireValue,
            contentType = "live_tv",
            providerCategory = "iptv",
            generatedAtEpochMillis = 1237L,
        )

        val body = parseBody(captured ?: error("request not captured"))
        assertEquals("iptv_direct", body["path_type"]?.jsonPrimitive?.content)
        assertEquals("live_tv", body["content_type"]?.jsonPrimitive?.content)
        assertEquals("iptv", body["provider_category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `direct free path posts direct free path type`() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"ok":true}""")
        }

        api.reportPathSelected(
            pathType = StreamPlaybackPath.DIRECT_FREE.wireValue,
            contentType = "direct",
            providerCategory = "direct",
            generatedAtEpochMillis = 1238L,
        )

        val body = parseBody(captured ?: error("request not captured"))
        assertEquals("direct_free", body["path_type"]?.jsonPrimitive?.content)
        assertEquals("direct", body["content_type"]?.jsonPrimitive?.content)
        assertEquals("direct", body["provider_category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `telemetry body sanitizes unsafe fields`() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"ok":true}""")
        }

        api.reportPathSelected(
            pathType = "generic_handoff_memory_id",
            contentType = "https://provider.example/path?token=secret",
            providerCategory = "source_key=abc",
            generatedAtEpochMillis = 1239L,
        )

        val bodyText = renderBody(captured ?: error("request not captured"))
        assertSafeBody(bodyText)
        val body = json.parseToJsonElement(bodyText).jsonObject
        assertEquals("unknown", body["content_type"]?.jsonPrimitive?.content)
        assertEquals("unknown", body["provider_category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `backend telemetry failure does not block playback path selection`() = runTest {
        val emitter = StreamPathBackendTelemetryEmitter(
            reportPath = { error("network down") },
            nowMillis = { 999L },
            scope = this,
        )

        emitter.emit(
            event = StreamPathTelemetryEvents.PATH_SELECTED,
            attributes = mapOf(
                StreamPathTelemetryKeys.PATH_TYPE to StreamPlaybackPath.GENERIC_HANDOFF_MEMORY_ID.wireValue,
                StreamPathTelemetryKeys.CONTENT_TYPE to "movie",
                StreamPathTelemetryKeys.PROVIDER_CATEGORY to "debrid",
            ),
        )
        advanceUntilIdle()
    }

    @Test
    fun `backend emitter maps safe attributes into one event`() = runTest {
        val sent = mutableListOf<StreamPathTelemetryRequest>()
        val emitter = StreamPathBackendTelemetryEmitter(
            reportPath = {
                sent += it
                true
            },
            nowMillis = { 777L },
            scope = this,
        )

        emitter.emit(
            event = StreamPathTelemetryEvents.PATH_SELECTED,
            attributes = mapOf(
                StreamPathTelemetryKeys.PATH_TYPE to StreamPlaybackPath.LEGACY_DIRECT_NO_MEMORY_ID.wireValue,
                StreamPathTelemetryKeys.CONTENT_TYPE to "series",
                StreamPathTelemetryKeys.PROVIDER_CATEGORY to "addon",
            ),
        )
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals("legacy_direct_no_memory_id", sent.single().pathType)
        assertEquals("series", sent.single().contentType)
        assertEquals("addon", sent.single().providerCategory)
        assertEquals(777L, sent.single().generatedAtEpochMillis)
    }

    private fun api(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): StreamPathTelemetryApi {
        val client = HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return StreamPathTelemetryApi(
            httpClient = client,
            baseUrlProvider = { "https://api.torve.app" },
            accessTokenProvider = { "access-token" },
            clientMetadataProvider = {
                StreamPathClientMetadata(
                    platform = "android_tv",
                    appVersion = "1.2.3",
                    distributionChannel = "amazon_sideload",
                )
            },
        )
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private suspend fun parseBody(request: HttpRequestData) =
        json.parseToJsonElement(renderBody(request)).jsonObject

    private suspend fun renderBody(request: HttpRequestData): String = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readBytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            body.writeTo(channel)
            channel.close()
            channel.readRemaining().readBytes().decodeToString()
        }
        else -> error("Unsupported body type: ${body::class}")
    }

    private fun assertSafeBody(body: String) {
        val jsonBody = json.parseToJsonElement(body).jsonObject
        listOf(
            "memory_id",
            "source_key",
            "url",
            "token",
            "password",
            "authorization",
            "bearer",
            "api_key",
            "apikey",
        ).forEach { forbidden ->
            assertNull(jsonBody[forbidden], "body included forbidden field '$forbidden': $body")
        }
        val bodyWithoutAllowedPathLabels = body
            .replace(StreamPlaybackPath.GENERIC_HANDOFF_MEMORY_ID.wireValue, "")
            .replace(StreamPlaybackPath.LEGACY_DIRECT_NO_MEMORY_ID.wireValue, "")
        listOf(
            "memory_id",
            "source_key",
            "\"url\"",
            "http://",
            "https://",
            "token",
            "password",
            "authorization",
            "bearer",
            "api_key",
            "apikey",
        ).forEach { forbidden ->
            assertFalse(
                bodyWithoutAllowedPathLabels.contains(forbidden, ignoreCase = true),
                "body leaked '$forbidden': $body",
            )
        }
    }
}
