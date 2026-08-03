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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeerrMediaLifecycleServiceTest {
    @Test
    fun `connection test validates the key on an authenticated endpoint`() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v1/auth/me", request.url.encodedPath)
            assertEquals("valid-key", request.headers["X-Api-Key"])
            respond(
                content = "{}",
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
        override suspend fun getString(key: String): String? =
            if (key == SeerrMediaLifecycleService.KEY_SERVER_URL) "http://seerr.test" else null
        override suspend fun setString(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }

    private class ConfiguredSecrets : IntegrationSecretStore {
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) = Unit
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
            if (key == IntegrationSecretKey.SEERR_API_KEY) "valid-key" else null
        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) = Unit
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode = IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() = Unit
        override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> = emptyList()
    }
}
