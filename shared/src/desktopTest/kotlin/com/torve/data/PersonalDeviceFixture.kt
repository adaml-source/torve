package com.torve.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.auth.*
import com.torve.data.progress.PreferencesRepositoryImpl
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.*
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient

/** Two independent devices can share a mock server while retaining separate SQLite and key stores. */
internal class PersonalDeviceFixture(val http: HttpClient) : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    val database = TorveDatabase(driver).also { TorveDatabase.Schema.create(driver) }
    private lateinit var authClient: AuthClient
    val ids = UserIdProvider({ account -> database.torveQueries.getActiveProfile(account).executeAsOneOrNull()?.id }) { authClient }
    val preferences = PreferencesRepositoryImpl(database, ids)
    val secure = MemorySecureStorage()
    val rawSecrets = MemoryIntegrationSecrets()
    val secrets = ProfileScopedSecretStore(rawSecrets, ids)
    val auth get() = authClient

    suspend fun signIn() {
        preferences.setString("auth_email", "household@example.test")
        preferences.setString("auth_user_id", ACCOUNT)
        secure.putString("auth_access_token", "test-torve-session")
        secure.putString("auth_token_expires_at", Long.MAX_VALUE.toString())
        authClient = AuthClient(preferences, secure, http, { "https://torve.test" },
            deviceRegistrationProvider = { DeviceRegistrationDto(installation_id = "fixture", device_name = "Fixture", device_type = "desktop", platform = "test") })
        authClient.getAuthenticatedUser()
        database.torveQueries.insertProfile(ACCOUNT, "default", "Default", 0, 1, null, null, 1)
    }
    fun select(profile: String) = database.torveQueries.setActiveProfile(profile, ACCOUNT)
    override fun close() { http.close(); driver.close() }
    companion object { const val ACCOUNT = "household-test" }
}

internal class MemorySecureStorage : SecureStorage {
    private val values = mutableMapOf<String, String>()
    var beforeWrite: (suspend (String) -> Unit)? = null
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun putString(key: String, value: String) { beforeWrite?.invoke(key); values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}

internal class MemoryIntegrationSecrets : IntegrationSecretStore {
    private val values = mutableMapOf<Pair<IntegrationSecretKey, String?>, String>()
    override suspend fun get(key: IntegrationSecretKey, subKey: String?) = values[key to subKey]
    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) { values[key to subKey] = value }
    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) { values.remove(key to subKey) }
    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) { put(key, mode.name, "storage_mode") }
    override suspend fun getStorageMode(key: IntegrationSecretKey) = get(key, "storage_mode")?.let(IntegrationStorageMode::valueOf) ?: IntegrationStorageMode.DEVICE_ONLY
    override suspend fun clearAllSecrets() { values.clear() }
    override suspend fun getSubKeys(key: IntegrationSecretKey) = values.keys.filter { it.first == key }.mapNotNull { it.second }
}
