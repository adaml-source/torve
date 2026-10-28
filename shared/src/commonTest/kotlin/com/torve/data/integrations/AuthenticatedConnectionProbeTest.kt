package com.torve.data.integrations

import com.torve.domain.integrations.*
import com.torve.domain.providerhealth.*
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.providerhealth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AuthenticatedConnectionProbeTest {
    private val prefs = object : PreferencesRepository {
        override suspend fun getString(key: String): String? = when (key) {
            "plex_server_url" -> "https://plex.test"
            "jellyfin_server_url" -> "https://jellyfin.test"
            else -> null
        }
        override suspend fun setString(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }
    private val secrets = object : IntegrationSecretStore {
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = when (key) {
            IntegrationSecretKey.PLEX_ACCESS_TOKEN -> "plex-private-token"
            IntegrationSecretKey.JELLYFIN_API_KEY -> "jellyfin-private-token"
            else -> null
        }
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) = Unit
        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) = Unit
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
        override suspend fun getStorageMode(key: IntegrationSecretKey) = IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() = Unit
        override suspend fun getSubKeys(key: IntegrationSecretKey) = emptyList<String>()
    }
    private fun client(status: HttpStatusCode) = HttpClient(MockEngine { respond("", status) })

    @Test fun plexUsesAuthenticatedLibraryRouteRatherThanPublicIdentity() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            paths += req.url.encodedPath
            assertEquals("plex-private-token", req.headers["X-Plex-Token"])
            respond("", HttpStatusCode.OK)
        })
        assertTrue(PlexLibraryOverlayService(http, prefs, secrets).testConnection("https://plex.test", "plex-private-token"))
        assertEquals(listOf("/library/sections"), paths); http.close()
    }
    @Test fun rejectedPlexTokenIsNotAServerOutage() = runTest {
        val http = client(HttpStatusCode.Unauthorized)
        assertEquals(ConnectionProbeResult.AUTH_REJECTED, PlexLibraryOverlayService(http, prefs, secrets).probeConnection("https://plex.test", "token"))
        http.close()
    }
    @Test fun rejectedJellyfinTokenHasAnAuthenticationRepair() = runTest {
        val http = client(HttpStatusCode.Forbidden)
        val checker = PlexJellyfinProviderHealthChecker({ "https://jellyfin.test" }, { "private" }, JellyfinLibraryOverlayService(http, prefs, secrets))
        val entry = checker.check().copy(lastCheckedAt = 1)
        assertEquals(ProviderHealthFailure.AUTH_REJECTED, entry.failure)
        assertEquals(ProviderActionKind.REENTER, ProviderStatusMapper.map(entry).primaryActionKind)
        assertFalse(entry.message.orEmpty().contains("private")); http.close()
    }
    @Test fun serverOutageOffersRetryInsteadOfCredentialReplacement() = runTest {
        val http = client(HttpStatusCode.ServiceUnavailable)
        val checker = PlexJellyfinProviderHealthChecker({ "https://jellyfin.test" }, { "token" }, JellyfinLibraryOverlayService(http, prefs, secrets))
        val entry = checker.check().copy(lastCheckedAt = 1)
        assertEquals(ProviderHealthFailure.UNREACHABLE, entry.failure)
        assertEquals(ProviderActionKind.REFRESH, ProviderStatusMapper.map(entry).primaryActionKind); http.close()
    }
    @Test fun wrongServerEndpointRequiresConfiguration() = runTest {
        val http = client(HttpStatusCode.NotFound)
        assertEquals(ConnectionProbeResult.SETUP_INCOMPLETE, PlexLibraryOverlayService(http, prefs, secrets).probeConnection("https://plex.test", "token")); http.close()
    }
    @Test fun transportFailureDoesNotExposeCredentialOrUrl() = runTest {
        val http = HttpClient(MockEngine { error("failed https://private-host/?token=private-token") })
        val entry = PlexJellyfinProviderHealthChecker({ "https://private-host" }, { "private-token" }, PlexLibraryOverlayService(http, prefs, secrets)).check()
        assertEquals(ProviderHealthFailure.UNREACHABLE, entry.failure)
        assertFalse(entry.message.orEmpty().contains("private-host")); assertFalse(entry.message.orEmpty().contains("private-token")); http.close()
    }
    @Test fun cancellationRemainsCancellation() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { JellyfinLibraryOverlayService(http, prefs, secrets).probeConnection("https://jellyfin.test", "token") }
        http.close()
    }
    @Test fun incompleteCredentialsMakeNoNetworkRequest() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; error("No request expected") })
        assertEquals(ConnectionProbeResult.SETUP_INCOMPLETE, PlexLibraryOverlayService(http, prefs, secrets).probeConnection("https://plex.test", ""))
        assertEquals(ConnectionProbeResult.SETUP_INCOMPLETE, JellyfinLibraryOverlayService(http, prefs, secrets).probeConnection("", "token"))
        assertEquals(0, calls); http.close()
    }
    @Test fun compositeProbesOnlyTheMatchingSavedServerAndToken() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            paths += "${req.url.host}${req.url.encodedPath}"
            assertEquals("jellyfin-private-token", req.headers["X-Emby-Token"])
            assertNull(req.headers["X-Plex-Token"])
            respond("", HttpStatusCode.OK)
        })
        val service = CompositeLibraryOverlayService(JellyfinLibraryOverlayService(http, prefs, secrets), PlexLibraryOverlayService(http, prefs, secrets), prefs, secrets)
        assertEquals(ConnectionProbeResult.CONNECTED, service.probeConnection("https://jellyfin.test", "jellyfin-private-token"))
        assertEquals(listOf("jellyfin.test/System/Info"), paths); http.close()
    }
    @Test fun seerrAuthRejectionDoesNotProbeArrOrRequestMedia() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { req -> paths += req.url.encodedPath; respond("", HttpStatusCode.Unauthorized) })
        val service = SeerrMediaLifecycleService(http, prefs, secrets)
        val entry = SeerrProviderHealthChecker({ "https://seerr.test" }, { "secret-key" }, service).check().copy(lastCheckedAt = 1)
        assertEquals(ProviderHealthFailure.AUTH_REJECTED, entry.failure)
        assertEquals(ProviderActionKind.REENTER, ProviderStatusMapper.map(entry).primaryActionKind)
        assertEquals(listOf("/api/v1/auth/me"), paths); http.close()
    }
    @Test fun expectSuccessClientsPreserveHttpEvidence() = runTest {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }) { expectSuccess = true }
        assertEquals(ConnectionProbeResult.AUTH_REJECTED, SeerrMediaLifecycleService(http, prefs, secrets).probeConnection("https://seerr.test", "key")); http.close()
    }
    @Test fun rateLimitAndServerErrorsKeepCredentialsAndOfferRetry() {
        listOf(408, 429, 500, 502, 503).forEach { assertEquals(ConnectionProbeResult.UNREACHABLE, connectionResult(HttpStatusCode.fromValue(it))) }
        assertEquals(ConnectionProbeResult.UNKNOWN_FAILURE, connectionResult(HttpStatusCode.BadRequest))
    }
}
