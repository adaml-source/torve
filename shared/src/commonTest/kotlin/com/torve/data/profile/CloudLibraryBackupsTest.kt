package com.torve.data.profile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.test.StandardTestDispatcher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CloudLibraryBackupsTest {
    private val version = "11111111-1111-4111-8111-111111111111"
    private val safety = "22222222-2222-4222-8222-222222222222"
    private fun metadata(id: String) = """{"id":"$id","created_at":"2026-10-20T12:00:00Z","platform":"android","reason":"manual","byte_count":200,"counts":{"profile":1}}"""
    private class Identity : CloudBackupIdentity {
        var owner: String? = "account-one"
        var profile = "account-one:default"
        val tokenOwners = mutableListOf<String>()
        override fun account() = owner
        override fun dataOwner() = profile
        override suspend fun token(expectedAccount: String): String { tokenOwners += expectedAccount; return "fixture-token" }
    }
    private class Payloads : CloudBackupPayloads {
        val restored = mutableListOf<Pair<String, String>>()
        override suspend fun export(expectedAccount: String) = """{"schemaVersion":1,"exportedAt":1,"records":[],"preferences":[]}"""
        override suspend fun restore(payload: String, expectedAccount: String) { restored += expectedAccount to payload }
    }

    @Test fun restoreSavesASafetySnapshotBeforeApplyingTheSelectedVersion() = runTest {
        val identity = Identity(); val payloads = Payloads(); val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            assertEquals("Bearer fixture-token", request.headers[HttpHeaders.Authorization])
            if (request.method == HttpMethod.Get) respond("""{"backup":${metadata(version)},"payload":{"schemaVersion":1,"selected":true}}""")
            else respond(metadata(safety))
        } }))
        try {
            val result = CloudLibraryBackups(http, identity, payloads).restore(version, "android")
            assertEquals(safety, result.id)
            assertEquals(listOf("GET /me/backups/$version", "POST /me/backups"), calls)
            assertTrue(Json.parseToJsonElement(payloads.restored.single().second).jsonObject["selected"]!!.jsonPrimitive.boolean)
            assertTrue(identity.tokenOwners.all { it == "account-one" })
        } finally { http.close() }
    }

    @Test fun aFailedSafetySnapshotNeverMutatesTheLibrary() = runTest {
        val payloads = Payloads()
        val http = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            if (request.method == HttpMethod.Get) respond("""{"payload":{"schemaVersion":1}}""")
            else respond("{}", HttpStatusCode.ServiceUnavailable)
        } }))
        try {
            assertFailsWith<CloudBackupFailure> { CloudLibraryBackups(http, Identity(), payloads).restore(version, "firetv") }
            assertTrue(payloads.restored.isEmpty())
        } finally { http.close() }
    }

    @Test fun changingAccountOrProfileDuringARequestPreventsRestore() = runTest {
        for (switchAccount in listOf(true, false)) {
            val identity = Identity(); val payloads = Payloads(); var posts = 0
            val http = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
                if (request.method == HttpMethod.Post) posts++
                if (switchAccount) identity.owner = "account-two" else identity.profile = "account-one:other-profile"
                respond("""{"payload":{"schemaVersion":1}}""")
            } }))
            try {
                assertFailsWith<CloudBackupFailure> { CloudLibraryBackups(http, identity, payloads).restore(version, "android") }
                assertTrue(payloads.restored.isEmpty()); assertEquals(0, posts)
            } finally { http.close() }
        }
    }

    @Test fun redirectsAreRejectedWithoutForwardingAccountCredentials() = runTest {
        val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            calls += request.url.host
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example/stolen"))
        } }))
        try {
            assertFailsWith<CloudBackupFailure> { CloudLibraryBackups(http, Identity(), Payloads()).list() }
            assertEquals(listOf("api.torve.app"), calls)
        } finally { http.close() }
    }

    @Test fun signedOutUsersNeverStartAnAccountRequest() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler { calls++; respond("{}") } }))
        try {
            assertFailsWith<CloudBackupFailure> { CloudLibraryBackups(http, Identity().apply { owner = null }, Payloads()).list() }
            assertEquals(0, calls)
        } finally { http.close() }
    }
}
