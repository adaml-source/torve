package com.torve.data.metadata

import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class TmdbUserCredentialsTest {
    private class Store : SecureStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override suspend fun putString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    @Test fun personalKeyIsIsolatedByAccountProfileAndGuestAndCanBeRemoved() = runTest {
        val storage = Store()
        var owner = "guest"
        val keys = TmdbUserCredentials(storage) { owner }
        val guestKey = "a".repeat(32)
        val profileKey = "b".repeat(32)
        assertNull(keys.read())
        keys.save(" $guestKey ")
        owner = "account:profile1"
        assertNull(keys.read())
        keys.save(profileKey)
        owner = "account:profile2"
        assertNull(keys.read())
        owner = "guest"
        assertEquals(guestKey, keys.read())
        keys.clear()
        assertNull(keys.read())
        owner = "account:profile1"
        assertEquals(profileKey, keys.read())
    }

    @Test fun malformedCredentialsDoNotOverwriteAnExistingKey() = runTest {
        val keys = TmdbUserCredentials(Store()) { "fixture" }
        val valid = "a".repeat(32)
        keys.save(valid)
        for (invalid in listOf("", "short", "g".repeat(32), "https://example.test/key")) {
            assertFailsWith<IllegalArgumentException> { keys.save(invalid) }
            assertEquals(valid, keys.read())
        }
    }

    @Test fun requestsUsePersonalKeyAndReturnToBuiltInAfterRemovalWithoutLeakingInDiagnostics() = runTest {
        val keys = TmdbUserCredentials(Store()) { "fixture" }
        val custom = "a".repeat(32)
        val seen = mutableListOf<String?>()
        val diagnostics = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            seen += request.url.parameters["api_key"]
            respond("""{"page":1,"results":[],"total_pages":1,"total_results":0}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val client = TmdbApiClient(http, { keys.read() ?: "built-in-fixture" }, { null },
                diagnostics = { diagnostics += it.toLogLine() })
            client.searchMulti("fixture")
            keys.save(custom)
            client.searchMulti("fixture")
            keys.clear()
            client.searchMulti("fixture")
            assertEquals(listOf<String?>("built-in-fixture", custom, "built-in-fixture"), seen)
            assertTrue(diagnostics.none { custom in it || "built-in-fixture" in it })
        } finally { http.close() }
    }
}
