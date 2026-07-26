package com.torve.data.integrations

import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.integrations.normalizeAutomationServerUrl
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class PrefsAutomationInstanceRepository(
    private val prefs: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : AutomationInstanceRepository {
    private val writeMutex = Mutex()

    override suspend fun list(): List<AutomationInstance> =
        prefs.getString(KEY_INSTANCES)?.let { encoded ->
            runCatching { json.decodeFromString(ListSerializer(AutomationInstance.serializer()), encoded) }
                .getOrDefault(emptyList())
        }.orEmpty().filter(::isValid)

    override suspend fun save(instance: AutomationInstance, apiKey: String?) {
        val normalized = validated(instance)
        writeMutex.withLock {
            val existingInstances = list()
            val previous = existingInstances.firstOrNull { it.id == normalized.id }
            val suppliedSecret = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            require(previous?.serviceType == null || previous.serviceType == normalized.serviceType || suppliedSecret != null) {
                "A new API key is required when changing automation service type"
            }
            val current = existingInstances.filterNot { it.id == normalized.id }.map { existing ->
                if (normalized.isDefault &&
                    existing.serviceType == normalized.serviceType &&
                    existing.role == normalized.role
                ) existing.copy(isDefault = false) else existing
            }
            val updated = (current + normalized).sortedWith(
                compareBy<AutomationInstance>({ it.serviceType.ordinal }, { it.role.ordinal }, { it.name.lowercase() }),
            )
            val targetKey = secretKeyFor(normalized.serviceType)
            val previousTargetSecret = suppliedSecret?.let { secretStore.get(targetKey, subKey = normalized.id) }
            val oldKey = previous
                ?.takeIf { it.serviceType != normalized.serviceType }
                ?.let { secretKeyFor(it.serviceType) }
            val previousOldSecret = oldKey?.let { secretStore.get(it, subKey = normalized.id) }
            try {
                suppliedSecret?.let { secret ->
                    secretStore.put(targetKey, secret, subKey = normalized.id)
                    secretStore.setStorageMode(targetKey, IntegrationStorageMode.DEVICE_ONLY)
                }
                oldKey?.let { secretStore.remove(it, subKey = normalized.id) }
                prefs.setString(KEY_INSTANCES, json.encodeToString(ListSerializer(AutomationInstance.serializer()), updated))
            } catch (failure: Throwable) {
                suppliedSecret?.let {
                    if (previousTargetSecret == null) secretStore.remove(targetKey, subKey = normalized.id)
                    else secretStore.put(targetKey, previousTargetSecret, subKey = normalized.id)
                }
                oldKey?.let { key ->
                    previousOldSecret?.let { secretStore.put(key, it, subKey = normalized.id) }
                }
                throw failure
            }
        }
    }

    override suspend fun remove(instanceId: String) {
        require(instanceId.matches(ID_PATTERN)) { "Invalid automation instance id" }
        writeMutex.withLock {
            val existing = list()
            val removed = existing.firstOrNull { it.id == instanceId } ?: return@withLock
            val updated = existing.filterNot { it.id == instanceId }
            val secretKey = secretKeyFor(removed.serviceType)
            val previousSecret = secretStore.get(secretKey, subKey = removed.id)
            try {
                secretStore.remove(secretKey, subKey = removed.id)
                prefs.setString(KEY_INSTANCES, json.encodeToString(ListSerializer(AutomationInstance.serializer()), updated))
            } catch (failure: Throwable) {
                previousSecret?.let { secretStore.put(secretKey, it, subKey = removed.id) }
                throw failure
            }
        }
    }

    override suspend fun apiKey(instance: AutomationInstance): String? =
        secretStore.get(secretKeyFor(instance.serviceType), subKey = instance.id)
            ?.trim()?.takeIf { it.isNotEmpty() }

    private fun validated(instance: AutomationInstance): AutomationInstance {
        require(instance.id.matches(ID_PATTERN)) { "Invalid automation instance id" }
        require(instance.name.trim().isNotEmpty()) { "Automation instance name is required" }
        val url = normalizeAutomationServerUrl(instance.serverUrl)
            ?: throw IllegalArgumentException("Automation server URL must use http or https")
        return instance.copy(name = instance.name.trim(), serverUrl = url)
    }

    private fun isValid(instance: AutomationInstance): Boolean =
        instance.id.matches(ID_PATTERN) &&
            instance.name.isNotBlank() &&
            normalizeAutomationServerUrl(instance.serverUrl) != null

    companion object {
        const val KEY_INSTANCES = "automation_instances_v1"
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        fun secretKeyFor(type: AutomationServiceType): IntegrationSecretKey = when (type) {
            AutomationServiceType.SONARR -> IntegrationSecretKey.SONARR_API_KEY
            AutomationServiceType.RADARR -> IntegrationSecretKey.RADARR_API_KEY
            AutomationServiceType.PROWLARR -> IntegrationSecretKey.PROWLARR_API_KEY
            AutomationServiceType.BAZARR -> IntegrationSecretKey.BAZARR_API_KEY
            AutomationServiceType.TDARR -> IntegrationSecretKey.TDARR_API_KEY
        }
    }
}
