package com.torve.data.account

import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.model.MediaFavorite
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaFavoritesRepositoryImplTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun addFavoriteSuccessUpdatesRepositoryStateFromAcknowledgementResponse() = runTest {
        val repositoryScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val settings = FakeDeviceLocalSettingsRepository(
            mutableMapOf(
                AuthClient.KEY_AUTH_EMAIL to "user@example.com",
                AuthClient.KEY_AUTH_USER_ID to "user-1",
                AuthClient.KEY_AUTH_IS_VERIFIED to "true",
                AuthClient.KEY_AUTH_DEVICE_ID to "22222222-2222-2222-2222-222222222222",
            ),
        )
        val storage = FakeSecureStorage(
            mutableMapOf(
                AuthClient.KEY_AUTH_ACCESS_TOKEN to "access-token",
                AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh-token",
                "auth_token_expires_at" to "4102444800000",
            ),
        )
        val httpClient = HttpClient(MockEngine { request -> error("Unexpected network request ${request.url}") })
        val authClient = AuthClient(
            localSettingsRepository = settings,
            secureStorage = storage,
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    installation_id = "install-1",
                    device_name = "Test",
                    device_type = "desktop",
                    platform = "desktop_windows",
                )
            },
        )
        authClient.getAuthenticatedUser()
        val api = FakeMediaFavoritesRemoteDataSource()
        val repository = MediaFavoritesRepositoryImpl(
            authClient = authClient,
            api = api,
            localSettingsRepository = settings,
            json = json,
            scope = repositoryScope,
            eventsEnabled = false,
        )

        try {
            advanceUntilIdle()
            assertEquals(1, api.listCalls)

            repository.addFavorite(
                MediaItem(
                    id = "tmdb:movie:123",
                    tmdbId = 123,
                    type = MediaType.MOVIE,
                    title = "Test Movie",
                    posterUrl = "https://image.example/poster.jpg",
                ),
            )
            advanceUntilIdle()

            assertTrue(repository.state.value.favoriteKeys.contains("movie:123"))
            assertEquals("Test Movie", repository.state.value.items.singleOrNull()?.title)
            assertEquals("2", repository.state.value.version)
            assertEquals("2026-05-16T00:02:00Z", repository.state.value.updatedAt)
            assertEquals("access-token", api.upsertAccessToken)
            assertEquals("22222222-2222-2222-2222-222222222222", api.upsertSourceDeviceId)
            assertEquals("movie:123", api.upsertFavorite?.mediaKey)
            assertEquals(MediaType.MOVIE, api.upsertFavorite?.mediaType)
            assertEquals("Test Movie", api.upsertFavorite?.title)
            assertTrue(repository.state.value.lastError == null)

            repository.clearSessionState()
        } finally {
            repositoryScope.cancel()
        }
    }
}

private class FakeMediaFavoritesRemoteDataSource : MediaFavoritesRemoteDataSource {
    var listCalls = 0
        private set
    var upsertAccessToken: String? = null
        private set
    var upsertFavorite: MediaFavorite? = null
        private set
    var upsertSourceDeviceId: String? = null
        private set

    override suspend fun listFavorites(accessToken: String): MediaFavoritesListDto {
        listCalls += 1
        return MediaFavoritesListDto(favorites = emptyList(), version = "1")
    }

    override suspend fun upsertFavorite(
        accessToken: String,
        favorite: MediaFavorite,
        sourceDeviceId: String?,
    ): MediaFavoriteMutationResultDto {
        upsertAccessToken = accessToken
        upsertFavorite = favorite
        upsertSourceDeviceId = sourceDeviceId
        return MediaFavoriteMutationResultDto(
            favorite = MediaFavoriteDto(
                mediaKey = favorite.mediaKey,
                mediaType = "movie",
                tmdbId = favorite.tmdbId,
                imdbId = favorite.imdbId,
                title = favorite.title,
                posterUrl = favorite.posterUrl,
                backdropUrl = favorite.backdropUrl,
                rating = favorite.rating,
                year = favorite.year,
                updatedAt = "2026-05-16T00:02:00Z",
            ),
            version = "2",
            updatedAt = "2026-05-16T00:02:00Z",
        )
    }

    override suspend fun deleteFavorite(accessToken: String, mediaKey: String): MediaFavoriteDeleteDto =
        MediaFavoriteDeleteDto(removed = true)

    override suspend fun collectFavoriteInvalidations(
        accessToken: String,
        onInvalidated: suspend () -> Unit,
    ) {
        error("Event stream should be disabled in this test")
    }
}

private class FakeSecureStorage(
    private val values: MutableMap<String, String?> = mutableMapOf(),
) : SecureStorage {
    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class FakeDeviceLocalSettingsRepository(
    private val values: MutableMap<String, String?> = mutableMapOf(),
) : DeviceLocalSettingsRepository {
    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
