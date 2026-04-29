package com.torve.data.acceleration

import com.torve.data.debrid.DebridClient
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.apiValue

class AccelerationInventorySyncService(
    private val secretStore: IntegrationSecretStore,
    private val debridClient: DebridClient,
    private val accelerationApi: AccelerationApi,
) {
    suspend fun syncConnectedProviders() {
        DebridServiceType.entries.forEach { provider ->
            val apiKey = secretStore.get(provider.secretKey())?.takeIf { it.isNotBlank() } ?: return@forEach
            val items = debridClient.getInventoryItems(provider, apiKey)
            if (items.isEmpty()) return@forEach
            accelerationApi.ingestInventory(provider.apiValue, items)
        }
    }
}

private fun DebridServiceType.secretKey(): IntegrationSecretKey = when (this) {
    DebridServiceType.REAL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
    DebridServiceType.ALL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
    DebridServiceType.PREMIUMIZE -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
    DebridServiceType.TORBOX -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
}
