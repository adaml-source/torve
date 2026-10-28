package com.torve.data.kodi

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class KodiRemoteContractTest {
    @Test fun transportUsesTheActualPlayerAndNavigationDoesNotRequirePlayback() = runTest {
        val requests = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            requests += body
            respond(if (body["method"]?.jsonPrimitive?.content == "Player.GetActivePlayers")
                """{"result":[{"playerid":0,"type":"audio"}]}""" else """{"result":"OK"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val client = KodiClient(http); val host = KodiHost("Kodi", "127.0.0.1")
        client.control(host, KodiRemoteAction.UP)
        client.control(host, KodiRemoteAction.PLAY_PAUSE)
        client.control(host, KodiRemoteAction.SEEK_BACK)
        assertEquals("Input.Up", requests.first()["method"]?.jsonPrimitive?.content)
        assertEquals("0", requests[2]["params"]?.jsonObject?.get("playerid")?.jsonPrimitive?.content)
        assertEquals("smallbackward", requests.last()["params"]?.jsonObject?.get("value")?.jsonPrimitive?.content)
        http.close()
    }
    @Test fun rpcErrorsAreVisibleInsteadOfPretendingPlaybackSucceeded() = runTest {
        val http = HttpClient(MockEngine { respond("""{"error":{"code":-32602}}""",
            headers = headersOf(HttpHeaders.ContentType, "application/json")) }) { install(ContentNegotiation) { json() } }
        val client = KodiClient(http)
        assertFalse(client.playUrl(KodiHost("Kodi", "127.0.0.1"), "https://example.test/movie"))
        assertFailsWith<IllegalStateException> { client.control(KodiHost("Kodi", "127.0.0.1"), KodiRemoteAction.BACK) }
        http.close()
    }
}
