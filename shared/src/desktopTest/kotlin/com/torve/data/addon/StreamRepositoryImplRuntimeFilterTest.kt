package com.torve.data.addon

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.acceleration.AccelerationApi
import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.debrid.DebridClient
import com.torve.db.TorveDatabase
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamGroup
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.security.SecureStorage
import com.torve.domain.streams.StreamFilterPreferenceKeys
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamRepositoryImplRuntimeFilterTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun fetchStreamsAppliesPersistedPremiumRegexAndStreamGroups() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        preferences.setString(
            StreamFilterPreferenceKeys.STREAM_GROUPS,
            json.encodeToString(
                listOf(
                    StreamGroup(name = "4K DV", matchPattern = "(?i)2160p", priority = 0),
                    StreamGroup(name = "1080p", matchPattern = "(?i)1080p", priority = 1),
                ),
            ),
        )

        val repository = repository(
            streams = testStreams(),
            preferences = preferences,
            premium = true,
        )

        val result = repository.fetchTestStreamsWithFeedback()

        assertEquals(
            listOf(
                "Movie.2026.2160p.WEB-DL.DV.Atmos",
                "Movie.2026.1080p.WEB-DL",
            ),
            result.streams.map { it.title },
        )
        assertEquals(1, result.filterFeedback.hiddenCount)
    }

    @Test
    fun fetchStreamsAppliesUserRulesWithoutPremium() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        preferences.setString(
            StreamFilterPreferenceKeys.STREAM_GROUPS,
            json.encodeToString(listOf(StreamGroup(name = "4K DV", matchPattern = "(?i)2160p", priority = 0))),
        )
        val streams = testStreams()
        val repository = repository(
            streams = streams,
            preferences = preferences,
            premium = false,
        )

        val result = repository.fetchTestStreamsWithFeedback()

        assertEquals(
            listOf(
                "Movie.2026.2160p.WEB-DL.DV.Atmos",
                "Movie.2026.1080p.WEB-DL",
            ),
            result.streams.map { it.title },
        )
        assertEquals(1, result.filterFeedback.hiddenCount)
    }

    @Test
    fun fetchStreamsIgnoresInvalidPersistedRegexWithoutCrashing() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "Broken", pattern = "["))),
        )
        preferences.setString(
            StreamFilterPreferenceKeys.STREAM_GROUPS,
            json.encodeToString(listOf(StreamGroup(name = "Broken group", matchPattern = "[", priority = 0))),
        )
        val streams = testStreams()
        val repository = repository(
            streams = streams,
            preferences = preferences,
            premium = true,
        )

        val result = repository.fetchTestStreamsWithFeedback()

        assertEquals(streams.map { it.title }, result.streams.map { it.title })
        assertEquals(0, result.filterFeedback.hiddenCount)
    }

    @Test
    fun fetchStreamsReportsWhenAllStreamsAreHiddenByFilters() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "Hide movie", pattern = "(?i)Movie\\.2026"))),
        )
        val streams = testStreams()
        val repository = repository(
            streams = streams,
            preferences = preferences,
            premium = true,
        )

        val result = repository.fetchTestStreamsWithFeedback()

        assertEquals(emptyList(), result.streams)
        assertEquals(streams.size, result.filterFeedback.hiddenCount)
    }

    @Test
    fun fetchStreamsWithFeedbackScopesHiddenCountToTheSameFetchResult() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        val cleanStream = stream("Other.2026.1080p.WEB-DL")
        val repository = repository(
            preferences = preferences,
            premium = true,
            streamsForImdb = { imdbId ->
                if (imdbId == "tt-hidden") {
                    listOf(stream("Movie.2026.HDCAM.x264"), stream("Movie.2026.1080p.WEB-DL"))
                } else {
                    listOf(cleanStream)
                }
            },
        )

        val hiddenResult = repository.fetchTestStreamsWithFeedback(imdbId = "tt-hidden")
        val cleanResult = repository.fetchTestStreamsWithFeedback(imdbId = "tt-clean")

        assertEquals(1, hiddenResult.filterFeedback.hiddenCount)
        assertEquals(0, cleanResult.filterFeedback.hiddenCount)
        assertEquals(listOf(cleanStream.title), cleanResult.streams.map { it.title })
    }

    private fun repository(
        streams: List<ParsedStream>,
        preferences: PreferencesRepository,
        premium: Boolean,
    ): StreamRepositoryImpl = repository(
        preferences = preferences,
        premium = premium,
        streamsForImdb = { streams },
    )

    private fun repository(
        preferences: PreferencesRepository,
        premium: Boolean,
        streamsForImdb: (String) -> List<ParsedStream>,
    ): StreamRepositoryImpl {
        val httpClient = HttpClient(MockEngine { request -> error("Unexpected network request ${request.url}") })
        val authClient = testAuthClient(httpClient)
        val accelerationApi = AccelerationApi(
            httpClient = httpClient,
            authClient = authClient,
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
            baseUrlProvider = { "https://api.torve.app" },
        )
        return StreamRepositoryImpl(
            debridClient = DebridClient(httpClient, json, accelerationApi),
            streamAggregationSource = StreamAggregationSource { _: List<InstalledAddon>,
                _: MediaType,
                imdbId: String,
                _: Int?,
                _: Int?,
                _: Map<DebridServiceType, String>,
                _: StreamPreferences,
                _: StreamFetchPolicy ->
                streamsForImdb(imdbId)
            },
            database = freshDb(),
            accelerationApi = accelerationApi,
            httpClient = httpClient,
            telemetry = NoOpTelemetryEmitter(),
            preferencesRepository = preferences,
            subscriptionRepository = FakeSubscriptionRepository(premium),
        )
    }

    private suspend fun StreamRepositoryImpl.fetchTestStreamsWithFeedback(
        imdbId: String = "tt1234567",
    ) = fetchStreamsWithFeedback(
        type = MediaType.MOVIE,
        imdbId = imdbId,
        contentId = "tmdb:movie:123",
        title = "Movie",
        season = null,
        episode = null,
        addons = emptyList(),
        debridAccounts = emptyMap(),
        preferences = StreamPreferences(),
        fetchPolicy = StreamFetchPolicy.FULL,
    )

    private fun freshDb(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }

    private fun testStreams(): List<ParsedStream> = listOf(
        stream("Movie.2026.1080p.WEB-DL"),
        stream("Movie.2026.HDCAM.x264"),
        stream("Movie.2026.2160p.WEB-DL.DV.Atmos", quality = "2160p"),
    )

    private fun stream(
        title: String,
        quality: String = "1080p",
    ): ParsedStream = ParsedStream(
        addonName = "Panda",
        quality = quality,
        title = title,
        source = "Real-Debrid",
        codec = "HEVC",
        hdr = "DV",
        audioCodec = "Atmos",
        isCached = true,
        score = 80,
    )

    private fun testAuthClient(httpClient: HttpClient): AuthClient {
        return AuthClient(
            localSettingsRepository = MapDeviceSettings(
                mutableMapOf(
                    AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                    AuthClient.KEY_AUTH_USER_ID to "user-1",
                    AuthClient.KEY_AUTH_IS_VERIFIED to "true",
                ),
            ),
            secureStorage = MapSecureStorage(
                mutableMapOf(
                    AuthClient.KEY_AUTH_ACCESS_TOKEN to "access-token",
                    AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh-token",
                    "auth_token_expires_at" to "4102444800000",
                ),
            ),
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    installation_id = "install-stream-filter-test",
                    device_name = "Desktop Test",
                    device_type = "desktop",
                    platform = "desktop",
                )
            },
        )
    }

    private class InMemoryPreferencesRepository : PreferencesRepository {
        private val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private class FakeSubscriptionRepository(
        private val premium: Boolean,
    ) : SubscriptionRepository {
        override suspend fun getActiveSubscription(): Subscription? = if (premium) {
            Subscription(
                id = "sub-1",
                tier = SubscriptionTier.MONTHLY,
                purchaseToken = "backend_entitlement",
                expiresAt = null,
                isActive = true,
                platform = "backend",
                purchasedAt = 1L,
            )
        } else {
            null
        }

        override suspend fun isPro(): Boolean = premium
        override suspend fun hasAccess(feature: PremiumFeature): Boolean = premium
        override suspend fun hasLocallyVerifiedPremiumAccess(): Boolean = premium
        override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) = Unit
        override suspend fun ensureFreeTier() = Unit
        override suspend fun restorePurchase(purchaseToken: String): Subscription? = getActiveSubscription()
        override suspend fun refreshFromBackend(): Boolean = premium
        override suspend fun refreshFromBackendDetailed(): BackendPremiumResult =
            if (premium) BackendPremiumResult.Active else BackendPremiumResult.NoEntitlement
        override suspend fun onBackendEntitlementGranted(isPremium: Boolean) = Unit
    }

    private class MapDeviceSettings(
        private val values: MutableMap<String, String>,
    ) : DeviceLocalSettingsRepository {
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private class MapSecureStorage(
        private val values: MutableMap<String, String>,
    ) : SecureStorage {
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun putString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }
}
