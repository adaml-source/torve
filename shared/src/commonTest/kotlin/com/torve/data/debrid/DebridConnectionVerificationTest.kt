package com.torve.data.debrid

import com.torve.domain.model.DebridServiceType
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.providerhealth.DebridProviderHealthChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class DebridConnectionVerificationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun http(status: HttpStatusCode, body: String) = HttpClient(MockEngine {
        respond(body, status, headersOf("Content-Type", "application/json"))
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    @Test fun rejectedHttpResponseCannotDecodeAsAConnectedAccount() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests)) {
            val client = http(status, """{"error":"bad_token","error_code":8}""")
            try {
                val result = DebridClient(client, json).verifyApiKey(DebridServiceType.REAL_DEBRID, "fixture-key")
                assertFalse(result.success)
                assertNull(result.user)
            } finally { client.close() }
        }
    }

    @Test fun emptyOrErrorSuccessBodyIsNotAnAccount() = runTest {
        for (body in listOf("{}", """{"error":"bad_token"}""", """{"username":"test"}""")) {
            val client = http(HttpStatusCode.OK, body)
            try { assertFalse(DebridClient(client, json).verifyApiKey(DebridServiceType.REAL_DEBRID, "fixture-key").success) }
            finally { client.close() }
        }
    }

    @Test fun aRealAccountResponseConnectsOnlyTheCheckedProvider() = runTest {
        val client = http(HttpStatusCode.OK, """{"username":"fixture-user","type":"premium"}""")
        try {
            val checker = DebridProviderHealthChecker(DebridServiceType.REAL_DEBRID, { "fixture-key" }, DebridClient(client, json))
            assertEquals(ProviderHealthStatus.GREEN, checker.check().status)
            assertEquals("debrid:real_debrid", checker.providerKey)
        } finally { client.close() }
    }

    @Test fun rejectedTokenNeverCreatesGreenHealth() = runTest {
        val client = http(HttpStatusCode.Unauthorized, """{"error":"bad_token"}""")
        try {
            val entry = DebridProviderHealthChecker(DebridServiceType.REAL_DEBRID, { "fixture-key" }, DebridClient(client, json)).check()
            assertEquals(ProviderHealthStatus.RED, entry.status)
            assertFalse(entry.message.orEmpty().contains("is connected"))
        } finally { client.close() }
    }

    @Test fun missingOrMaskedCredentialsNeedNoProviderRequest() = runTest {
        val client = HttpClient(MockEngine { error("No network request is allowed without a credential") })
        try {
            for (key in listOf(null, " ", "[redacted]", "********", "••••")) {
                val checker = DebridProviderHealthChecker(DebridServiceType.REAL_DEBRID, { key }, DebridClient(client, json))
                assertEquals(ProviderHealthStatus.UNCONFIGURED, checker.configurationState().status)
                assertEquals(ProviderHealthStatus.UNCONFIGURED, checker.check().status)
            }
        } finally { client.close() }
    }

    @Test fun aSavedCredentialIsUnverifiedUntilItsActualCheckPasses() = runTest {
        val client = HttpClient(MockEngine { error("Configuration reconciliation must stay local") })
        try {
            val entry = DebridProviderHealthChecker(DebridServiceType.REAL_DEBRID, { "fixture-key" }, DebridClient(client, json)).configurationState()
            assertEquals(ProviderHealthStatus.UNKNOWN, entry.status)
            assertNull(entry.lastCheckedAt)
            assertFalse(entry.message.orEmpty().contains("is connected"))
        } finally { client.close() }
    }

    @Test fun tokenRefreshOnlyConnectsAfterVerifyingTheReplacementToken() = runTest {
        val seen = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            val auth = request.headers["Authorization"]
            seen += auth
            if (auth == "Bearer replacement") respond("""{"username":"test","type":"premium"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else respond("""{"error":"bad_token"}""", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
        }) { install(ContentNegotiation) { json(json) } }
        try {
            val debrid = DebridClient(client, json, rdTokenRefresher = RdTokenRefresher { "replacement" })
            assertEquals(ProviderHealthStatus.GREEN, DebridProviderHealthChecker(DebridServiceType.REAL_DEBRID, { "expired" }, debrid).check().status)
            assertEquals(listOf<String?>("Bearer expired", "Bearer replacement"), seen.toList())
        } finally { client.close() }
    }
}
