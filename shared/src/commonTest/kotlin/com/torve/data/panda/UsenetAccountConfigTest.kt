package com.torve.data.panda

import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.AccountApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class UsenetAccountConfigTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val saved = """{"config_id":"saved-1","config":{"enableUsenet":true,"usenetProvider":"generic","usenetHost":"news.fixture.invalid","usenetPassword":"[redacted]","nzbIndexers":[{"type":"scenenzbs","url":"","apiKey":"[redacted]"}]}}"""
    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
    }
    private fun account(client: HttpClient) = AccountSettingsApi(client, { "https://account.fixture.invalid" })
    private fun metadata(type: String = "PANDA_TOKEN", mode: String = "account") =
        """{"integration_type":"$type","storage_mode":"$mode","has_credentials":true}"""

    @Test fun freshDeviceLoadsAccountSetupAndOwnerSecretsWithoutLocalTokenOrAddon() = runTest {
        val paths = mutableListOf<String>()
        val client = client(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("Bearer account-fixture", request.headers[HttpHeaders.Authorization])
            paths += request.url.encodedPath
            val response = when (request.url.encodedPath) {
                "/me/integrations" -> "[${metadata()}]"
                "/me/integrations/PANDA_TOKEN/credentials" -> """{"credentials":{"config_id":"saved-1"}}"""
                "/api/v1/configs/me" -> {
                    assertEquals("saved-1", request.headers["X-Panda-Config-Id"])
                    saved
                }
                "/api/v1/configs/me/secrets" -> {
                    assertEquals("saved-1", request.headers["X-Panda-Config-Id"])
                    """{"usenet_password":"fixture-password","nzb_indexers":[{"type":"scenenzbs","url":"","api_key":"fixture-indexer-key"}]}"""
                }
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val reference = assertNotNull(account(client).getUsenetAccountConfig("account-fixture", "default"))
        assertNull(reference.manifestToken)
        val api = PandaApiClient(client, json)
        val record = api.readUsenetConfig(reference, "account-fixture")
        assertEquals("saved-1", record.configId)
        assertTrue(assertNotNull(record.config).enableUsenet)
        assertEquals("news.fixture.invalid", record.config?.usenetHost)
        assertEquals("scenenzbs", record.config?.nzbIndexers?.single()?.type)
        val secrets = api.getConfigSecrets("saved-1", "account-fixture")
        assertEquals("fixture-password", secrets.usenetPassword)
        assertEquals("fixture-indexer-key", secrets.nzbIndexers.single().apiKey)
        assertEquals(4, paths.size)
    }

    @Test fun lookupOnlyFetchesTheActiveProfilesAccountCredentialsAndSupportsLegacyAliases() = runTest {
        val paths = mutableListOf<String>()
        val client = client(MockEngine { request ->
            paths += request.url.encodedPath
            val response = if (paths.size == 1)
                "[${metadata()},${metadata("PANDA_TOKEN::p:other")},${metadata("panda::profile:child")}]"
            else """{"credentials":{"token":"child-token","config_id":"child-config"}}"""
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val reference = assertNotNull(account(client).getUsenetAccountConfig("account-fixture", "child"))
        assertEquals("child-config", reference.configId)
        assertEquals("child-token", reference.manifestToken)
        assertEquals(listOf("/me/integrations", "/me/integrations/panda::profile:child/credentials"), paths)
    }

    @Test fun anotherProfileOrDeviceOnlySetupIsNotRestored() = runTest {
        var calls = 0
        val client = client(MockEngine {
            calls++
            respond("[${metadata("PANDA_TOKEN::p:other")},${metadata(mode = "device_only")}]",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertNull(account(client).getUsenetAccountConfig("account-fixture", "default"))
        assertEquals(1, calls)
    }

    @Test fun failedMetadataReadIsNotAnEmptyConfiguration() = runTest {
        val client = client(MockEngine { respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) })
        assertFailsWith<AccountApiException> { account(client).getUsenetAccountConfig("expired", "default") }
    }

    @Test fun missingSavedCredentialsFailInsteadOfOfferingAnotherNewSetup() = runTest {
        val client = client(MockEngine { request ->
            if (request.url.encodedPath == "/me/integrations") respond("[${metadata()}]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalStateException> { account(client).getUsenetAccountConfig("account-fixture", "default") }
    }

    @Test fun ownerReadWinsOverAnOldManifestToken() = runTest {
        var calls = 0
        val client = client(MockEngine { request ->
            calls++
            assertEquals("Bearer account-fixture", request.headers[HttpHeaders.Authorization])
            respond(saved, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        PandaApiClient(client, json).readUsenetConfig(UsenetAccountConfig("saved-1", "old-manifest"), "account-fixture")
        assertEquals(1, calls)
    }

    @Test fun legacyManifestOnlySetupStillLoads() = runTest {
        val client = client(MockEngine { request ->
            assertEquals("Bearer legacy-fixture", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Panda-Config-Id"])
            respond(saved, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("saved-1", PandaApiClient(client, json).readUsenetConfig(UsenetAccountConfig(null, "legacy-fixture"), null).configId)
    }

    @Test fun cancelledOwnerReadDoesNotTryAnotherCredential() = runTest {
        var calls = 0
        val client = client(MockEngine { calls++; throw CancellationException("Account changed") })
        assertFailsWith<CancellationException> {
            PandaApiClient(client, json).readUsenetConfig(UsenetAccountConfig("saved-1", "old-manifest"), "account-fixture")
        }
        assertEquals(1, calls)
    }

    @Test fun newConfigurationsAreBoundToTheSignedInOwner() = runTest {
        val client = client(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer account-fixture", request.headers[HttpHeaders.Authorization])
            respond("""{"config_id":"new-config","panda_token":"fixture"}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("new-config", PandaApiClient(client, json).createConfig(PandaConfigPayload(), "account-fixture").configId)
    }
}
