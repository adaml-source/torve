package com.torve.data.subscription

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.device.DeviceApi
import com.torve.data.entitlement.EntitlementApi
import com.torve.db.TorveDatabase
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertTrue

class SubscriptionRepositoryImplLocalPremiumAccessTest {

    @Test
    fun activeUnexpiredLocalPremiumAccessReturnsTrue() = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        val fixture = fixture()
        fixture.database.insertSubscription(expiresAt = now + 60_000L)
        fixture.settings.cacheVerifiedSnapshot(verifiedAtMs = now)

        assertTrue(fixture.repository.hasLocallyVerifiedPremiumAccess())
    }

    @Test
    fun expiredLocalSubscriptionDoesNotBlockFreeAccess() = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        val fixture = fixture()
        fixture.database.insertSubscription(expiresAt = now - 1L)
        fixture.settings.cacheVerifiedSnapshot(verifiedAtMs = now)

        assertTrue(fixture.repository.hasLocallyVerifiedPremiumAccess())
    }

    @Test
    fun wrongPrincipalDoesNotBlockFreeAccess() = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        val fixture = fixture()
        fixture.database.insertSubscription(expiresAt = now + 60_000L)
        fixture.settings.cacheVerifiedSnapshot(principal = "other-user", verifiedAtMs = now)

        assertTrue(fixture.repository.hasLocallyVerifiedPremiumAccess())
    }

    @Test
    fun clearedLocalEntitlementDoesNotBlockFreeAccess() = runTest {
        val fixture = fixture()
        fixture.database.insertSubscription(expiresAt = null)

        assertTrue(fixture.repository.hasLocallyVerifiedPremiumAccess())
    }

    @Test
    fun locallyStoredDeviceDeactivationDoesNotBlockFreeAccess() = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        val fixture = fixture()
        fixture.database.insertSubscription(expiresAt = now + 60_000L)
        fixture.settings.cacheVerifiedSnapshot(
            verifiedAtMs = now,
            isDeviceActivated = false,
            deviceBlockReason = "device_cap_reached",
        )

        assertTrue(fixture.repository.hasLocallyVerifiedPremiumAccess())
    }

    private suspend fun fixture(): Fixture {
        val database = freshDb()
        val settings = MapDeviceSettings(
            mutableMapOf(
                AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                AuthClient.KEY_AUTH_USER_ID to USER_ID,
                AuthClient.KEY_AUTH_IS_VERIFIED to "true",
            ),
        )
        val secureStorage = MapSecureStorage(
            mutableMapOf(
                AuthClient.KEY_AUTH_ACCESS_TOKEN to "access-token",
                AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh-token",
                "auth_token_expires_at" to "4102444800000",
            ),
        )
        val httpClient = HttpClient(MockEngine { request -> error("Unexpected network request ${request.url}") })
        val authClient = AuthClient(
            localSettingsRepository = settings,
            secureStorage = secureStorage,
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    installation_id = "install-local-premium-test",
                    device_name = "Desktop Test",
                    device_type = "desktop",
                    platform = "desktop",
                )
            },
        )
        authClient.getAuthenticatedUser()
        val repository = SubscriptionRepositoryImpl(
            database = database,
            authClient = authClient,
            entitlementApi = EntitlementApi(httpClient, { "https://api.torve.app" }),
            deviceApi = DeviceApi(httpClient, { "https://api.torve.app" }),
            localSettingsRepository = settings,
        )
        return Fixture(database, settings, repository)
    }

    private fun freshDb(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }

    private fun TorveDatabase.insertSubscription(expiresAt: Long?) {
        torveQueries.insertSubscription(
            user_id = USER_ID,
            id = "sub-1",
            tier = SubscriptionTier.MONTHLY.name,
            purchase_token = "backend_entitlement",
            expires_at = expiresAt,
            is_active = 1L,
            platform = "backend",
            purchased_at = 1L,
        )
    }

    private suspend fun MapDeviceSettings.cacheVerifiedSnapshot(
        principal: String = USER_ID,
        verifiedAtMs: Long,
        isDeviceActivated: Boolean = true,
        deviceBlockReason: String? = null,
    ) {
        setString(SubscriptionEntitlementCacheKeys.VERIFIED_PRINCIPAL, principal)
        setString(SubscriptionEntitlementCacheKeys.VERIFIED_AT_MS, verifiedAtMs.toString())
        setString(SubscriptionEntitlementCacheKeys.VERIFIED_HAS_ENTITLEMENT, "true")
        setString(
            SubscriptionEntitlementCacheKeys.VERIFIED_IS_DEVICE_ACTIVATED,
            isDeviceActivated.toString(),
        )
        deviceBlockReason?.let {
            setString(SubscriptionEntitlementCacheKeys.VERIFIED_DEVICE_BLOCK_REASON, it)
        }
    }

    private data class Fixture(
        val database: TorveDatabase,
        val settings: MapDeviceSettings,
        val repository: SubscriptionRepositoryImpl,
    )

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

    private companion object {
        const val USER_ID = "user-1"
    }
}
