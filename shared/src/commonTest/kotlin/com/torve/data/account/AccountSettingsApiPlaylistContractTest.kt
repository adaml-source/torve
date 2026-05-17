package com.torve.data.account

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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountSettingsApiPlaylistContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun getPlaylistsMapsM3uEpgUrl() = runTest {
        val api = api { _ ->
            respondJson(
                """
                [
                  {
                    "id": "row-1",
                    "playlist_id": "playlist-1",
                    "name": "M3U",
                    "playlist_type": "m3u",
                    "url": "https://example.com/playlist.m3u",
                    "epg_url": "https://example.com/guide.xml"
                  }
                ]
                """.trimIndent(),
            )
        }

        val playlists = api.getPlaylists("token")

        assertEquals("playlist-1", playlists.single().playlistId)
        assertEquals("https://example.com/guide.xml", playlists.single().epgUrl)
        assertFalse(playlists.single().isXtreamPlaylist())
    }

    @Test
    fun savePlaylistSendsM3uEpgUrl() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"ok":true}""")
        }

        val ok = api.savePlaylist(
            accessToken = "token",
            playlistId = "playlist-1",
            request = SavePlaylistRequest(
                playlistId = "playlist-1",
                name = "M3U",
                playlistType = "m3u",
                url = "https://example.com/playlist.m3u",
                epgUrl = "https://example.com/guide.xml",
            ),
        )

        assertTrue(ok)
        assertEquals("/me/playlists/playlist-1", captured?.url?.encodedPath)
        val body = parseBody(captured ?: error("request not captured"))
        assertEquals("playlist-1", body["playlist_id"]?.jsonPrimitive?.content)
        assertEquals("m3u", body["playlist_type"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/playlist.m3u", body["url"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/guide.xml", body["epg_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun validateEpgPostsUrlAndReturnsBackendMessage() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson(
                """
                {
                  "success": true,
                  "status": "ok",
                  "message": "EPG data found: 10 channels",
                  "http_status": 200,
                  "content_type": "application/xml",
                  "channel_count": 10,
                  "programme_count": 200,
                  "bytes_checked": 12345
                }
                """.trimIndent(),
            )
        }

        val result = api.validateEpg("token", "https://example.com/guide.xml")

        assertEquals("/me/playlists/validate-epg", captured?.url?.encodedPath)
        assertEquals(
            "https://example.com/guide.xml",
            parseBody(captured ?: error("request not captured"))["epg_url"]?.jsonPrimitive?.content,
        )
        assertTrue(result.success)
        assertEquals("EPG data found: 10 channels", result.message)
        assertEquals(10, result.channelCount)
        assertEquals(200, result.programmeCount)
    }

    @Test
    fun authenticatedPlaylistCallsIncludeInstallationHeader() = runTest {
        var captured: HttpRequestData? = null
        val api = api(installationId = "install-123") { request ->
            captured = request
            respondJson("[]")
        }

        api.getPlaylists("token")

        assertEquals("install-123", captured?.headers?.get("X-Torve-Installation-Id"))
    }

    @Test
    fun getPlaylistsFailureThrowsInsteadOfReturningEmptyList() = runTest {
        val api = api { _ ->
            respondJson("""{"detail":"temporary failure"}""", status = HttpStatusCode.InternalServerError)
        }

        val error = assertFailsWith<AccountApiException> {
            api.getPlaylists("token")
        }

        assertEquals(500, error.statusCode)
    }

    private fun api(
        installationId: String? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AccountSettingsApi {
        val client = HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return AccountSettingsApi(
            client,
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = { installationId },
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

    private suspend fun parseBody(request: HttpRequestData) = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readBytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            body.writeTo(channel)
            channel.close()
            channel.readRemaining().readBytes().decodeToString()
        }
        else -> error("Unsupported body type: ${body::class}")
    }.let { json.parseToJsonElement(it).jsonObject }
}
