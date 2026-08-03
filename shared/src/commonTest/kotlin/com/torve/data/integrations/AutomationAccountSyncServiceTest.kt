package com.torve.data.integrations

import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.IntegrationMetadataDto
import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomationAccountSyncServiceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun completeAccountBundleReplacesAccountEntriesAndPreservesDeviceOnlyEntries() = runTest {
        val old = instance("old", IntegrationStorageMode.ACCOUNT)
        val local = instance("living-room", IntegrationStorageMode.DEVICE_ONLY)
        val remote = instance("radarr-main", IntegrationStorageMode.ACCOUNT)
        val repository = MutableAutomationRepository(
            initial = listOf(old to "old-key", local to "device-key"),
        )

        val result = service(repository).restoreSafely(
            metadata = accountMetadata(),
            credentials = bundle(remote to "remote-key"),
        )

        assertTrue(result.succeeded)
        assertEquals(1, result.restored)
        assertEquals(setOf("living-room", "radarr-main"), repository.list().map { it.id }.toSet())
        assertEquals("device-key", repository.apiKey(local))
        assertEquals("remote-key", repository.apiKey(remote))
    }

    @Test
    fun malformedBundleLeavesTheWorkingLocalStackUntouched() = runTest {
        val old = instance("radarr-old", IntegrationStorageMode.ACCOUNT)
        val local = instance("device-sonarr", IntegrationStorageMode.DEVICE_ONLY)
        val repository = MutableAutomationRepository(
            initial = listOf(old to "old-key", local to "local-key"),
        )

        val result = service(repository).restoreSafely(
            metadata = accountMetadata(),
            credentials = mapOf("instances_json" to "{not-json", "api_key__radarr-old" to "replacement"),
        )

        assertEquals(AutomationRestoreFailure.MALFORMED_DESCRIPTORS, result.failure)
        assertEquals(listOf(old, local), repository.list())
        assertEquals("old-key", repository.apiKey(old))
        assertEquals("local-key", repository.apiKey(local))
    }

    @Test
    fun partialApplyFailureRollsBackEveryAccountConnectionAndSecret() = runTest {
        val old = instance("radarr-old", IntegrationStorageMode.ACCOUNT)
        val local = instance("device-sonarr", IntegrationStorageMode.DEVICE_ONLY)
        val firstRemote = instance("radarr-new", IntegrationStorageMode.ACCOUNT)
        val failingRemote = instance("sonarr-new", IntegrationStorageMode.ACCOUNT, AutomationServiceType.SONARR)
        val repository = MutableAutomationRepository(
            initial = listOf(old to "old-key", local to "local-key"),
            failOnceOnSaveId = failingRemote.id,
        )

        val result = service(repository).restoreSafely(
            metadata = accountMetadata(),
            credentials = bundle(firstRemote to "radarr-key", failingRemote to "sonarr-key"),
        )

        assertEquals(AutomationRestoreFailure.APPLY_FAILED, result.failure)
        assertEquals(setOf("radarr-old", "device-sonarr"), repository.list().map { it.id }.toSet())
        assertEquals("old-key", repository.apiKey(old))
        assertEquals("local-key", repository.apiKey(local))
    }

    @Test
    fun accountBundleCannotOverwriteADeviceOnlyConnectionWithTheSameId() = runTest {
        val local = instance("radarr-main", IntegrationStorageMode.DEVICE_ONLY)
        val remote = instance("radarr-main", IntegrationStorageMode.ACCOUNT)
        val repository = MutableAutomationRepository(initial = listOf(local to "device-key"))

        val result = service(repository).restoreSafely(
            metadata = accountMetadata(),
            credentials = bundle(remote to "remote-key"),
        )

        assertEquals(AutomationRestoreFailure.DEVICE_CONFLICT, result.failure)
        assertEquals(listOf(local), repository.list())
        assertEquals("device-key", repository.apiKey(local))
    }

    @Test
    fun duplicateRemoteConnectionIdsAreRejectedBeforeAnythingIsWritten() = runTest {
        val old = instance("radarr-old", IntegrationStorageMode.ACCOUNT)
        val duplicateA = instance("duplicate", IntegrationStorageMode.ACCOUNT)
        val duplicateB = duplicateA.copy(name = "Duplicate two")
        val repository = MutableAutomationRepository(initial = listOf(old to "old-key"))

        val result = service(repository).restoreSafely(
            metadata = accountMetadata(),
            credentials = bundle(duplicateA to "key-a", duplicateB to "key-b"),
        )

        assertEquals(AutomationRestoreFailure.DUPLICATE_CONNECTIONS, result.failure)
        assertEquals(listOf(old), repository.list())
        assertEquals(0, repository.writeCount)
    }

    private fun instance(
        id: String,
        storageMode: IntegrationStorageMode,
        serviceType: AutomationServiceType = AutomationServiceType.RADARR,
    ) = AutomationInstance(
        id = id,
        serviceType = serviceType,
        name = id,
        serverUrl = "http://$id.local:7878",
        storageMode = storageMode,
    )

    private fun accountMetadata() = IntegrationMetadataDto(
        integrationType = AutomationAccountSyncService.INTEGRATION_TYPE,
        storageMode = IntegrationStorageMode.ACCOUNT.name.lowercase(),
        isConnected = true,
        hasCredentials = true,
    )

    private fun bundle(vararg entries: Pair<AutomationInstance, String>): Map<String, String> = buildMap {
        put(
            "instances_json",
            json.encodeToString(ListSerializer(AutomationInstance.serializer()), entries.map { it.first }),
        )
        entries.forEach { (instance, apiKey) -> put("api_key__${instance.id}", apiKey) }
    }

    private fun service(repository: AutomationInstanceRepository): AutomationAccountSyncService {
        val httpClient = HttpClient(MockEngine { error("No network call expected") })
        val authClient = AuthClient(
            localSettingsRepository = EmptyLocalSettings,
            secureStorage = EmptySecureStorage,
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    installation_id = "test-installation",
                    device_name = "Test",
                    device_type = "desktop",
                    platform = "test",
                )
            },
        )
        return AutomationAccountSyncService(
            repository = repository,
            authClient = authClient,
            accountSettingsApi = AccountSettingsApi(
                httpClient = httpClient,
                baseUrlProvider = { "https://api.torve.app" },
            ),
            json = json,
        )
    }
}

private class MutableAutomationRepository(
    initial: List<Pair<AutomationInstance, String>>,
    private val failOnceOnSaveId: String? = null,
) : AutomationInstanceRepository {
    private val instances = linkedMapOf<String, AutomationInstance>()
    private val keys = mutableMapOf<String, String>()
    private var hasFailed = false
    var writeCount: Int = 0
        private set

    init {
        initial.forEach { (instance, key) ->
            instances[instance.id] = instance
            keys[instance.id] = key
        }
    }

    override suspend fun list(): List<AutomationInstance> = instances.values.toList()

    override suspend fun save(instance: AutomationInstance, apiKey: String?) {
        if (instance.id == failOnceOnSaveId && hasFailed.not()) {
            hasFailed = true
            error("controlled save failure")
        }
        writeCount += 1
        instances[instance.id] = instance
        if (apiKey != null) keys[instance.id] = apiKey
    }

    override suspend fun remove(instanceId: String) {
        writeCount += 1
        instances.remove(instanceId)
        keys.remove(instanceId)
    }

    override suspend fun apiKey(instance: AutomationInstance): String? = keys[instance.id]
}

private object EmptyLocalSettings : DeviceLocalSettingsRepository {
    override suspend fun getString(key: String): String? = null
    override suspend fun setString(key: String, value: String) = Unit
    override suspend fun remove(key: String) = Unit
}

private object EmptySecureStorage : SecureStorage {
    override suspend fun getString(key: String): String? = null
    override suspend fun putString(key: String, value: String) = Unit
    override suspend fun remove(key: String) = Unit
}
