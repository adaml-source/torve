package com.torve.data.integrations

import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.IntegrationMetadataDto
import com.torve.data.account.SaveIntegrationRequest
import com.torve.data.auth.AuthClient
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.integrations.normalizeAutomationServerUrl
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Synchronizes every account-enabled *Arr descriptor and its scoped API key as one
 * encrypted backend record. Device-only descriptors and keys never enter the payload.
 */
class AutomationAccountSyncService(
    private val repository: AutomationInstanceRepository,
    private val authClient: AuthClient,
    private val accountSettingsApi: AccountSettingsApi,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    suspend fun preferredStorageMode(): IntegrationStorageMode =
        if (authClient.getCurrentUser() != null) IntegrationStorageMode.ACCOUNT
        else IntegrationStorageMode.DEVICE_ONLY

    /** Remove only account-owned descriptors during sign-out; device-only entries remain. */
    suspend fun clearLocalAccountInstances() {
        repository.list()
            .filter { it.storageMode == IntegrationStorageMode.ACCOUNT }
            .forEach { repository.remove(it.id) }
    }

    /**
     * One-time safe migration for connections created before account sync existed.
     * A device-local stack is uploaded only when the backend has no *Arr record,
     * so a stale device can never overwrite an existing account copy.
     *
     * @return true only when a local stack was successfully promoted and uploaded.
     */
    suspend fun pushLocalIfRemoteMissing(
        remoteIntegrations: List<IntegrationMetadataDto>,
    ): Boolean {
        if (remoteIntegrations.any { it.integrationType == INTEGRATION_TYPE }) return false
        val localInstances = repository.list()
        if (localInstances.isEmpty()) return false
        val snapshot = localInstances.map { it to repository.apiKey(it).orEmpty() }
        return try {
            localInstances.forEach { instance ->
                if (instance.storageMode != IntegrationStorageMode.ACCOUNT) {
                    // Omitting apiKey deliberately preserves the existing encrypted local secret.
                    repository.save(instance.copy(storageMode = IntegrationStorageMode.ACCOUNT))
                }
            }
            val synced = push() == AutomationSyncResult.Synced
            if (synced.not()) restoreSnapshot(snapshot)
            synced
        } catch (failure: Throwable) {
            restoreSnapshot(snapshot)
            throw failure
        }
    }

    suspend fun push(): AutomationSyncResult {
        val token = authClient.getValidAccessToken() ?: return AutomationSyncResult.NotSignedIn
        val accountInstances = repository.list().filter { it.storageMode == IntegrationStorageMode.ACCOUNT }
        if (accountInstances.isEmpty()) {
            return if (accountSettingsApi.deleteIntegration(token, INTEGRATION_TYPE)) {
                AutomationSyncResult.Synced
            } else {
                AutomationSyncResult.Failed
            }
        }

        val credentials = linkedMapOf(SCHEMA_CREDENTIAL to SCHEMA_VERSION.toString())
        for (instance in accountInstances) {
            val apiKey = repository.apiKey(instance)
            if (apiKey.isNullOrBlank() && instance.serviceType != AutomationServiceType.TDARR) {
                return AutomationSyncResult.Failed
            }
            if (!apiKey.isNullOrBlank()) credentials[credentialName(instance.id)] = apiKey
        }
        val descriptors = json.encodeToString(
            ListSerializer(AutomationInstance.serializer()),
            accountInstances,
        )
        credentials[DESCRIPTORS_CREDENTIAL] = descriptors
        val saved = accountSettingsApi.saveIntegration(
            accessToken = token,
            integrationType = INTEGRATION_TYPE,
            request = SaveIntegrationRequest(
                integrationType = INTEGRATION_TYPE,
                storageMode = "account",
                credentials = credentials,
                displayIdentifier = "*Arr media automation",
                config = mapOf(
                    "schema_version" to SCHEMA_VERSION.toString(),
                    "connection_count" to accountInstances.size.toString(),
                    // Descriptors contain names, roles and server URLs, never API keys.
                    // Publishing them as account-private metadata lets the web portal
                    // show what is configured without returning decrypted credentials.
                    "instances_json" to descriptors,
                ),
            ),
        )
        return if (saved) AutomationSyncResult.Synced else AutomationSyncResult.Failed
    }

    suspend fun restoreLegacyConnection(
        serviceType: AutomationServiceType,
        metadata: IntegrationMetadataDto,
        apiKey: String,
        credentialValues: Collection<String> = emptyList(),
    ): AutomationRestoreResult {
        val normalizedUrl = (metadata.config.values + credentialValues)
            .firstNotNullOfOrNull(::normalizeAutomationServerUrl)
            ?: return AutomationRestoreResult(failure = AutomationRestoreFailure.INVALID_CONNECTION)
        val instanceId = serviceType.name.lowercase()
        val existing = repository.list().firstOrNull { it.id == instanceId }
        if (existing?.storageMode == IntegrationStorageMode.DEVICE_ONLY) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.DEVICE_CONFLICT)
        }
        val instance = AutomationInstance(
            id = instanceId,
            serviceType = serviceType,
            name = metadata.displayIdentifier.orEmpty().ifBlank { serviceType.name.lowercase() },
            serverUrl = normalizedUrl,
            storageMode = IntegrationStorageMode.ACCOUNT,
        )
        return runCatching {
            repository.save(instance, apiKey)
            AutomationRestoreResult(restored = 1)
        }.getOrElse {
            AutomationRestoreResult(failure = AutomationRestoreFailure.APPLY_FAILED)
        }
    }

    /**
     * Applies a complete account bundle as one transaction. Any malformed or
     * incomplete payload leaves the previous local stack untouched.
     */
    suspend fun restoreSafely(
        metadata: IntegrationMetadataDto,
        credentials: Map<String, String>,
    ): AutomationRestoreResult {
        if (metadata.storageMode != IntegrationStorageMode.ACCOUNT.name.lowercase()) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.MISSING_CREDENTIALS)
        }
        if (metadata.hasCredentials.not()) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.MISSING_CREDENTIALS)
        }
        val encoded = credentials[DESCRIPTORS_CREDENTIAL]
        if (encoded.isNullOrBlank()) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.MISSING_DESCRIPTORS)
        }
        val decoded = try {
            json.decodeFromString(ListSerializer(AutomationInstance.serializer()), encoded)
        } catch (failure: Throwable) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.MALFORMED_DESCRIPTORS)
        }
        if (decoded.isEmpty()) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.MISSING_DESCRIPTORS)
        }
        if (decoded.map { it.id }.distinct().size != decoded.size) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.DUPLICATE_CONNECTIONS)
        }
        val normalizedRemote = try {
            decoded.map { instance ->
                require(instance.storageMode == IntegrationStorageMode.ACCOUNT)
                require(instance.id.isNotBlank())
                require(instance.name.isNotBlank())
                val normalizedUrl = requireNotNull(normalizeAutomationServerUrl(instance.serverUrl))
                val apiKey = credentials[credentialName(instance.id)]
                val keyOptional = instance.serviceType == AutomationServiceType.TDARR
                require(keyOptional.or(apiKey.isNullOrBlank().not()))
                instance.copy(
                    name = instance.name.trim(),
                    serverUrl = normalizedUrl,
                    storageMode = IntegrationStorageMode.ACCOUNT,
                )
            }
        } catch (failure: Throwable) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.INVALID_CONNECTION)
        }

        val localBefore = repository.list()
        val deviceOnlyIds = localBefore
            .filter { it.storageMode == IntegrationStorageMode.DEVICE_ONLY }
            .mapTo(mutableSetOf()) { it.id }
        if (normalizedRemote.any { it.id in deviceOnlyIds }) {
            return AutomationRestoreResult(failure = AutomationRestoreFailure.DEVICE_CONFLICT)
        }
        val localAccountBefore = localBefore.filter { it.storageMode == IntegrationStorageMode.ACCOUNT }
        val snapshot = localAccountBefore.map { it to repository.apiKey(it).orEmpty() }
        val remoteIds = normalizedRemote.mapTo(mutableSetOf()) { it.id }
        return try {
            normalizedRemote.forEach { instance ->
                repository.save(instance, credentials[credentialName(instance.id)])
            }
            localAccountBefore
                .filter { it.id !in remoteIds }
                .forEach { repository.remove(it.id) }
            AutomationRestoreResult(restored = normalizedRemote.size)
        } catch (failure: Throwable) {
            restoreSnapshot(snapshot)
            AutomationRestoreResult(failure = AutomationRestoreFailure.APPLY_FAILED)
        }
    }

    private suspend fun restoreSnapshot(
        snapshot: List<Pair<AutomationInstance, String>>,
    ) {
        val snapshotIds = snapshot.mapTo(mutableSetOf()) { it.first.id }
        repository.list()
            .filter {
                (it.storageMode == IntegrationStorageMode.ACCOUNT).and(it.id !in snapshotIds)
            }
            .forEach { current -> runCatching { repository.remove(current.id) } }
        snapshot.forEach { (instance, apiKey) ->
            runCatching { repository.save(instance, apiKey.ifBlank { null }) }
        }
    }

    /** Returns the number of valid account connections applied locally. */
    suspend fun restore(accessToken: String, metadata: IntegrationMetadataDto): Int {
        val safeCredentials = accountSettingsApi.getIntegrationCredentials(
            accessToken = accessToken,
            integrationType = INTEGRATION_TYPE,
        )
        if (safeCredentials.isNullOrEmpty()) return 0
        return restoreSafely(metadata, safeCredentials).restored
    }

    companion object {
        const val INTEGRATION_TYPE = "ARR_STACK_V1"
        private const val SCHEMA_VERSION = 1
        private const val SCHEMA_CREDENTIAL = "schema_version"
        private const val DESCRIPTORS_CREDENTIAL = "instances_json"

        private fun credentialName(instanceId: String): String = "api_key__$instanceId"
    }
}

enum class AutomationSyncResult {
    Synced,
    NotSignedIn,
    Failed,
}

data class AutomationRestoreResult(
    val restored: Int = 0,
    val failure: AutomationRestoreFailure = AutomationRestoreFailure.NONE,
) {
    val succeeded: Boolean
        get() = failure == AutomationRestoreFailure.NONE
}

enum class AutomationRestoreFailure {
    NONE,
    MISSING_CREDENTIALS,
    MISSING_DESCRIPTORS,
    MALFORMED_DESCRIPTORS,
    DUPLICATE_CONNECTIONS,
    INVALID_CONNECTION,
    DEVICE_CONFLICT,
    APPLY_FAILED,
}
