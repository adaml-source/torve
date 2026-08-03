package com.torve.data.integrations

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinLibraryOverlayServiceTest {
    @Test
    fun `primary poster tag is read from standard jellyfin image tags`() {
        val item = Json { ignoreUnknownKeys = true }.decodeFromString<JellyfinBrowseItem>(
            """{"Id":"movie-1","Name":"Movie","Type":"Movie","ImageTags":{"Primary":"poster-tag"}}""",
        )

        assertEquals("poster-tag", item.resolvedPrimaryImageTag)
    }

    @Test
    fun `legacy primary image tag remains a fallback`() {
        val item = Json { ignoreUnknownKeys = true }.decodeFromString<JellyfinBrowseItem>(
            """{"Id":"movie-1","Name":"Movie","Type":"Movie","PrimaryImageTag":"legacy-tag"}""",
        )

        assertEquals("legacy-tag", item.resolvedPrimaryImageTag)
    }

    @Test
    fun `non-empty response with another tmdb id is not in library`() = runTest {
        val service = serviceWithResponse(
            """{"Items":[{"Name":"Another movie","Type":"Movie","ProviderIds":{"Tmdb":"999"}}]}""",
        )

        assertFalse(service.isInLibrary(42, MediaType.MOVIE))
    }

    @Test
    fun `matching tmdb id and media type is in library`() = runTest {
        val service = serviceWithResponse(
            """{"Items":[{"Name":"The movie","Type":"Movie","ProviderIds":{"Tmdb":"42"}}]}""",
        )

        assertTrue(service.isInLibrary(42, MediaType.MOVIE))
    }

    @Test
    fun episodeDisplayIncludesParentSeriesAndNumber() {
        val item = Json { ignoreUnknownKeys = true }.decodeFromString<JellyfinBrowseItem>(
            """{"Id":"episode-1","Name":"Faceless Men","Type":"Episode","SeriesName":"House of the Dragon","SeriesId":"series-1","ParentIndexNumber":3,"IndexNumber":6}""",
        )

        assertEquals("House of the Dragon", item.displayTitle)
        assertEquals("S03E06 · Faceless Men", item.displaySubtitle)
        assertEquals("series-1", item.seriesId)
    }

    private fun serviceWithResponse(body: String): JellyfinLibraryOverlayService {
        val client = HttpClient(MockEngine { request ->
            assertEquals("Tmdb.42", request.url.parameters["AnyProviderIdEquals"])
            assertEquals("ProviderIds", request.url.parameters["Fields"])
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return JellyfinLibraryOverlayService(client, ConfiguredPrefs(), ConfiguredSecrets())
    }

    private class ConfiguredPrefs : PreferencesRepository {
        override suspend fun getString(key: String): String? = when (key) {
            "jellyfin_server_url" -> "http://jellyfin.test"
            "jellyfin_selected_user_id" -> "user-1"
            else -> null
        }

        override suspend fun setString(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }

    private class ConfiguredSecrets : IntegrationSecretStore {
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) = Unit
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
            if (key == IntegrationSecretKey.JELLYFIN_API_KEY) "test-key" else null

        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) = Unit
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
            IntegrationStorageMode.DEVICE_ONLY

        override suspend fun clearAllSecrets() = Unit
        override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> = emptyList()
    }
}
