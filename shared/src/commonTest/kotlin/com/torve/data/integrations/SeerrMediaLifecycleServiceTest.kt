package com.torve.data.integrations

import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SeerrMediaLifecycleServiceTest {
    @Test fun disabledArrMakesNoStatusRequestListOrConnectionProbes() = runTest {
        val prefs = ConfiguredPrefs()
        prefs.setString(com.torve.presentation.settings.OptionalAppFeature.ARR_STACK.preferenceKey, "false")
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("{}", HttpStatusCode.OK) })
        val service = SeerrMediaLifecycleService(client, prefs, ConfiguredSecrets())
        assertFalse(service.isConfigured())
        assertFalse(service.testConnection("http://seerr.test", "valid-key"))
        assertEquals(MediaLifecycleState.UNCONFIGURED, service.getStatus(42, MediaType.MOVIE, emptyList(), false).state)
        assertTrue(service.listRecent(10).isEmpty())
        assertEquals(0, calls)
        client.close()
    }
    @Test
    fun requestListUsesFreshAvailabilityFromTheTitleResponse() = runTest {
        val client = HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath == "/api/v1/request")
                """{"results":[{"id":1,"status":2,"type":"movie","media":{"tmdbId":42,"status":3}}]}"""
            else """{"title":"Resident Evil","posterPath":"/poster.jpg","mediaInfo":{"tmdbId":42,"status":5,"requests":[{"id":1,"status":2}]}}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val item = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets()).listRecent(10).single()
        assertEquals(MediaLifecycleState.AVAILABLE, item.status.state)
        assertEquals("Resident Evil", item.title)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", item.posterUrl)
        client.close()
    }
    private fun readyClient(radarr: String = "[]", sonarr: String = "[]", calls: MutableList<String>,
        authStatus: () -> HttpStatusCode = { HttpStatusCode.OK }): HttpClient =
        HttpClient(MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            val body = when (request.url.encodedPath) {
                "/api/v1/auth/me" -> "{}"
                "/api/v1/settings/radarr" -> radarr
                "/api/v1/settings/sonarr" -> sonarr
                else -> error("Unexpected title lookup or request while checking route readiness")
            }
            respond(body, if (request.url.encodedPath == "/api/v1/auth/me") authStatus() else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun `saved credentials alone do not trigger a title lookup`() = runTest {
        val calls = mutableListOf<String>()
        val client = readyClient(calls = calls)
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())
        assertTrue(service.isConfigured())
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.SERIES))
        assertEquals(emptyList<String>(), calls)
        client.close()
    }

    @Test
    fun `unconnected library requests require no network checks`() = runTest {
        val calls = mutableListOf<String>()
        val client = readyClient(calls = calls)
        val service = SeerrMediaLifecycleService(client, EmptyPrefs(), EmptySecrets())
        assertFalse(service.isConfigured())
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.SERIES))
        assertEquals(emptyList<String>(), calls)
        client.close()
    }

    @Test
    fun `a reachable request manager without ARR defaults is not ready for automatic lookup`() = runTest {
        val calls = mutableListOf<String>()
        val client = readyClient(calls = calls)
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())
        assertTrue(service.testConnection("http://seerr.test", "valid-key"))
        val setupCalls = calls.toList()
        assertEquals(3, setupCalls.size)
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.SERIES))
        assertEquals(setupCalls, calls)
        client.close()
    }

    @Test
    fun `a configured default Radarr route only enables movie lookup`() = runTest {
        val calls = mutableListOf<String>()
        val client = readyClient(radarr = """[{"isDefault":true,"is4k":false,"activeProfileId":1,"activeDirectory":"/movies","apiKey":"ignored"}]""", calls = calls)
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())
        assertTrue(service.testConnection("http://seerr.test", "valid-key"))
        val setupCalls = calls.toList()
        assertTrue(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.SERIES))
        assertEquals(setupCalls, calls)
        client.close()
    }

    @Test
    fun `a configured default Sonarr route only enables series lookup`() = runTest {
        val calls = mutableListOf<String>()
        val client = readyClient(sonarr = """[{"isDefault":true,"activeProfileId":1,"activeDirectory":"/shows"}]""", calls = calls)
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())
        assertTrue(service.testConnection("http://seerr.test", "valid-key"))
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        assertTrue(service.canAutomaticallyCheckStatus(MediaType.SERIES))
        client.close()
    }

    @Test
    fun `4k-only and incomplete servers do not enable standard movie lookup`() = runTest {
        val calls = mutableListOf<String>()
        val client = readyClient(radarr = """[
            {"isDefault":true,"is4k":true,"activeProfileId":1,"activeDirectory":"/4k"},
            {"isDefault":false,"activeProfileId":1,"activeDirectory":"/movies"},
            {"isDefault":true,"activeProfileId":1,"activeDirectory":""}
        ]""", calls = calls)
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())
        assertTrue(service.testConnection("http://seerr.test", "valid-key"))
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        client.close()
    }

    @Test
    fun `a failed connection check clears prior request-route readiness`() = runTest {
        var authorized = true
        val calls = mutableListOf<String>()
        val client = readyClient(radarr = """[{"isDefault":true,"activeProfileId":1,"activeDirectory":"/movies"}]""",
            calls = calls, authStatus = { if (authorized) HttpStatusCode.OK else HttpStatusCode.Unauthorized })
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())
        assertTrue(service.testConnection("http://seerr.test", "valid-key"))
        assertTrue(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        authorized = false
        assertFalse(service.testConnection("http://seerr.test", "valid-key"))
        val failedCheckCalls = calls.toList()
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        assertEquals(failedCheckCalls, calls)
        client.close()
    }

    @Test
    fun `readiness for another credential or server cannot enable this saved connection`() = runTest {
        val calls = mutableListOf<String>()
        val client = readyClient(radarr = """[{"isDefault":true,"activeProfileId":1,"activeDirectory":"/movies"}]""", calls = calls)
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())
        assertTrue(service.testConnection("http://seerr.test", "different-key"))
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        assertTrue(service.testConnection("http://another-seerr.test", "valid-key"))
        assertFalse(service.canAutomaticallyCheckStatus(MediaType.MOVIE))
        client.close()
    }

    @Test
    fun `validated readiness survives restart without a network request and rejects rotated credentials`() = runTest {
        val calls = mutableListOf<String>()
        val client = readyClient(sonarr = """[{"isDefault":true,"activeProfileId":1,"activeDirectory":"/shows"}]""", calls = calls)
        val prefs = ConfiguredPrefs()
        val secrets = ConfiguredSecrets()
        assertTrue(SeerrMediaLifecycleService(client, prefs, secrets).testConnection("http://seerr.test", "valid-key"))
        val setupCalls = calls.toList()
        val restarted = SeerrMediaLifecycleService(client, prefs, secrets)
        assertTrue(restarted.canAutomaticallyCheckStatus(MediaType.SERIES))
        assertEquals(setupCalls, calls)
        secrets.apiKey = "rotated-key"
        assertFalse(restarted.canAutomaticallyCheckStatus(MediaType.SERIES))
        assertFalse(SeerrMediaLifecycleService(client, prefs, secrets).canAutomaticallyCheckStatus(MediaType.SERIES))
        assertEquals(setupCalls, calls)
        client.close()
    }

    @Test
    fun `checking a missing title only reads status and never creates a request`() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = HttpClient(MockEngine { request ->
            methods += request.method
            assertEquals("/api/v1/tv/42", request.url.encodedPath)
            assertEquals("valid-key", request.headers["X-Api-Key"])
            respond(content = "", status = HttpStatusCode.NotFound)
        })
        val status = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())
            .getStatus(42, MediaType.SERIES, seasons = listOf(2))

        assertEquals(MediaLifecycleState.NOT_REQUESTED, status.state)
        assertTrue(status.canRequest)
        assertEquals(listOf(HttpMethod.Get), methods)
        client.close()
    }

    @Test
    fun `a failed availability lookup cannot silently request or retry the title`() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = HttpClient(MockEngine { request ->
            methods += request.method
            assertEquals("/api/v1/movie/42", request.url.encodedPath)
            respond(content = "", status = HttpStatusCode.Unauthorized)
        })
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())

        assertFailsWith<IllegalStateException> { service.getStatus(42, MediaType.MOVIE) }
        assertEquals(listOf(HttpMethod.Get), methods)
        client.close()
    }

    @Test
    fun `connection test validates the key on an authenticated endpoint`() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("valid-key", request.headers["X-Api-Key"])
            respond(
                content = if (request.url.encodedPath == "/api/v1/auth/me") "{}" else "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val service = SeerrMediaLifecycleService(client, EmptyPrefs(), EmptySecrets())

        assertTrue(service.testConnection("http://seerr.test", "valid-key"))
    }

    @Test
    fun `missing media info is requestable`() {
        val status = deriveSeerrLifecycleStatus(42, MediaType.MOVIE, null)
        assertEquals(MediaLifecycleState.NOT_REQUESTED, status.state)
        assertTrue(status.canRequest)
        assertFalse(status.isInProgress)
    }

    @Test
    fun `pending approval remains distinct from processing`() {
        val status = deriveSeerrLifecycleStatus(
            42,
            MediaType.MOVIE,
            SeerrMediaInfoDto(
                tmdbId = 42,
                status = 2,
                requests = listOf(SeerrRequestDto(id = 9, status = 1)),
            ),
        )
        assertEquals(MediaLifecycleState.PENDING_APPROVAL, status.state)
        assertEquals(9, status.requestId)
        assertTrue(status.isInProgress)
        assertTrue(status.canDeleteRequest)
    }

    @Test
    fun `delete request uses authenticated Seerr endpoint`() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(io.ktor.http.HttpMethod.Delete, request.method)
            assertEquals("/api/v1/request/44", request.url.encodedPath)
            assertEquals("valid-key", request.headers["X-Api-Key"])
            respond(content = "", status = HttpStatusCode.NoContent)
        })
        val service = SeerrMediaLifecycleService(client, ConfiguredPrefs(), ConfiguredSecrets())

        assertTrue(service.deleteRequest(44))
    }

    @Test
    fun `available media wins over stale request status`() {
        val status = deriveSeerrLifecycleStatus(
            42,
            MediaType.SERIES,
            SeerrMediaInfoDto(
                tmdbId = 42,
                status = 5,
                requests = listOf(SeerrRequestDto(id = 10, status = 1)),
            ),
        )
        assertEquals(MediaLifecycleState.AVAILABLE, status.state)
        assertFalse(status.canRequest)
    }

    @Test
    fun `latest request controls state`() {
        val status = deriveSeerrLifecycleStatus(
            42,
            MediaType.MOVIE,
            SeerrMediaInfoDto(
                tmdbId = 42,
                requests = listOf(
                    SeerrRequestDto(id = 2, status = 3),
                    SeerrRequestDto(id = 7, status = 2, is4k = true),
                ),
            ),
            is4k = true,
        )
        assertEquals(MediaLifecycleState.APPROVED, status.state)
        assertEquals(7, status.requestId)
        assertTrue(status.is4k)
    }

    @Test
    fun `available old season does not block requesting a different season`() {
        val status = deriveSeerrLifecycleStatus(
            tmdbId = 42,
            mediaType = MediaType.SERIES,
            media = SeerrMediaInfoDto(
                tmdbId = 42,
                status = 5,
                requests = listOf(
                    SeerrRequestDto(
                        id = 10,
                        status = 2,
                        seasons = listOf(SeerrRequestSeasonDto(seasonNumber = 1)),
                    ),
                ),
            ),
            seasons = listOf(2),
        )

        assertEquals(MediaLifecycleState.NOT_REQUESTED, status.state)
        assertTrue(status.canRequest)
    }

    @Test
    fun `4k request uses independent 4k media status`() {
        val status = deriveSeerrLifecycleStatus(
            tmdbId = 42,
            mediaType = MediaType.MOVIE,
            media = SeerrMediaInfoDto(
                tmdbId = 42,
                status = 5,
                status4k = 3,
                requests = listOf(SeerrRequestDto(id = 8, status = 2, is4k = true)),
            ),
            is4k = true,
        )

        assertEquals(MediaLifecycleState.PROCESSING, status.state)
        assertTrue(status.is4k)
    }

    @Test
    fun `server URL requires explicit http transport`() {
        assertEquals(
            "https://seerr.example.test",
            SeerrMediaLifecycleService.normalizeBaseUrl(" https://seerr.example.test/ "),
        )
        assertEquals(null, SeerrMediaLifecycleService.normalizeBaseUrl("seerr.example.test"))
    }

    private class EmptyPrefs : PreferencesRepository {
        override suspend fun getString(key: String): String? = null
        override suspend fun setString(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }

    private class EmptySecrets : IntegrationSecretStore {
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) = Unit
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = null
        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) = Unit
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode = IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() = Unit
        override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> = emptyList()
    }

    private class ConfiguredPrefs : PreferencesRepository {
        private val values = mutableMapOf(SeerrMediaLifecycleService.KEY_SERVER_URL to "http://seerr.test")
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private class ConfiguredSecrets(var apiKey: String = "valid-key") : IntegrationSecretStore {
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) = Unit
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
            if (key == IntegrationSecretKey.SEERR_API_KEY) apiKey else null
        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) = Unit
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode = IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() = Unit
        override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> = emptyList()
    }
}
