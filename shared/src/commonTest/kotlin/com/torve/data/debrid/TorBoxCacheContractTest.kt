package com.torve.data.debrid

import com.torve.domain.model.DebridServiceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class TorBoxCacheContractTest {
    private val cached = "a".repeat(40)
    private val absent = "b".repeat(40)

    @Test fun objectCacheEntriesAreCachedWithoutRequestingFilesOrCreatingDownloads() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertTrue(request.url.encodedPath.endsWith("/torrents/checkcached"))
            assertEquals("object", request.url.parameters["format"])
            assertEquals("false", request.url.parameters["list_files"])
            respond("""{"success":true,"data":{"$cached":{"hash":"$cached","size":1000000000}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertEquals(mapOf(cached to true, absent to false),
                DebridClient(http, Json).checkCache(DebridServiceType.TORBOX, "fixture", listOf(cached, absent)))
        } finally { http.close() }
    }

    @Test fun legacyFileArraysRemainSupportedAndEmptyEntriesAreNotReady() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"success":true,"data":{"$cached":[{"name":"movie.mkv"}],"$absent":[]}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertEquals(mapOf(cached to true, absent to false),
                DebridClient(http, Json).checkCache(DebridServiceType.TORBOX, "fixture", listOf(cached, absent)))
        } finally { http.close() }
    }
}
