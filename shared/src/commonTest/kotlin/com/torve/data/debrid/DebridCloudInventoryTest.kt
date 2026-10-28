package com.torve.data.debrid

import com.torve.domain.model.DebridServiceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class DebridCloudInventoryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun client(handler: (String, Parameters) -> Pair<Int, String>): HttpClient = HttpClient(MockEngine { request ->
        assertEquals(HttpMethod.Get, request.method, "Browsing must not create downloads")
        val (status, body) = handler(request.url.encodedPath, request.url.parameters)
        respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
    }) { install(ContentNegotiation) { json(json) } }
    private val folders = """{"success":true,"data":[{"id":7,"name":"Existing download","download_state":"completed","size":900}]}"""
    private val info = """{"id":7,"download_finished":true,"files":[{"id":4,"name":"video.mp4","size":900}]}"""

    @Test fun torrentAndUsenetIdsCannotCollide() = runTest {
        val http = client { _, _ -> 200 to folders }
        try {
            val result = DebridClient(http, json).cloudInventory(DebridServiceType.TORBOX, "fixture-key")
            assertEquals(setOf("torrent:7", "usenet:7"), result.folders.map { it.id }.toSet())
            assertNull(result.notice)
        } finally { http.close() }
    }
    @Test fun partialUsenetFailureRetainsTorrentsAndExplainsMissingInventory() = runTest {
        val http = client { path, _ -> if ("usenet" in path) 403 to "{}" else 200 to folders }
        try {
            val result = DebridClient(http, json).cloudInventory(DebridServiceType.TORBOX, "fixture-key")
            assertEquals("torrent:7", result.folders.single().id)
            assertContains(result.notice.orEmpty(), "Usenet downloads could not be loaded")
        } finally { http.close() }
    }
    @Test fun partialTorrentFailureRetainsUsenetAndBothFailuresAreAnError() = runTest {
        val http = client { path, _ -> if ("torrents" in path) 429 to "{}" else 200 to folders }
        try {
            val result = DebridClient(http, json).cloudInventory(DebridServiceType.TORBOX, "fixture-key")
            assertEquals("usenet:7", result.folders.single().id)
            assertNotNull(result.notice)
        } finally { http.close() }
        val rejected = client { _, _ -> 200 to """{"success":false,"data":[]}""" }
        try { assertFailsWith<IllegalStateException> { DebridClient(rejected, json).cloudInventory(DebridServiceType.TORBOX, "fixture-key") } }
        finally { rejected.close() }
    }
    @Test fun usenetPlaybackUsesExistingUsenetFileAndAcceptsObjectOrArrayInfo() = runTest {
        for (data in listOf(info, "[$info]")) {
            val http = client { path, parameters ->
                assertContains(path, "/usenet/")
                if (path.endsWith("mylist")) {
                    assertEquals("7", parameters["id"])
                    200 to """{"success":true,"data":$data}"""
                } else {
                    assertEquals("7", parameters["usenet_id"])
                    assertNull(parameters["torrent_id"])
                    assertEquals("4", parameters["file_id"])
                    200 to """{"success":true,"data":"https://cdn.example/video.mp4"}"""
                }
            }
            try { assertEquals("https://cdn.example/video.mp4", DebridClient(http, json).playCloudFile(DebridServiceType.TORBOX, "fixture-key", "usenet:7", "4")) }
            finally { http.close() }
        }
    }
    @Test fun legacyTorrentFolderStillUsesTorrentEndpoint() = runTest {
        val http = client { path, parameters ->
            assertContains(path, "/torrents/")
            if (path.endsWith("mylist")) 200 to """{"success":true,"data":$info}"""
            else {
                assertEquals("7", parameters["torrent_id"]); assertNull(parameters["usenet_id"])
                200 to """{"success":true,"data":"https://cdn.example/video.mp4"}"""
            }
        }
        try { DebridClient(http, json).playCloudFile(DebridServiceType.TORBOX, "fixture-key", "7", "4") }
        finally { http.close() }
    }
    @Test fun missingFileUnreadyJobAndWrongJobNeverRequestPlayback() = runTest {
        for (body in listOf(info.replace("true", "false"), info.replace("\"id\":7", "\"id\":8"), info.replace("\"id\":4", "\"id\":5"))) {
            val http = client { path, _ ->
                assertTrue(path.endsWith("mylist"), "An unavailable file must not request a link")
                200 to """{"success":true,"data":$body}"""
            }
            try { assertFailsWith<IllegalStateException> { DebridClient(http, json).playCloudFile(DebridServiceType.TORBOX, "fixture-key", "usenet:7", "4") } }
            finally { http.close() }
        }
    }
    @Test fun realDebridInventoryKeepsItsExistingIdentifiers() = runTest {
        val http = client { path, _ -> assertTrue(path.endsWith("/torrents")); 200 to """[{"id":"RD-id","filename":"Movie","status":"downloaded","bytes":1200}]""" }
        try { assertEquals("RD-id", DebridClient(http, json).cloudFolders(DebridServiceType.REAL_DEBRID, "fixture-key").single().id) }
        finally { http.close() }
    }
}
