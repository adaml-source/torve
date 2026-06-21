package com.torve.domain.repository

import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon

interface AddonRepository {
    suspend fun installAddon(
        url: String,
        enabled: Boolean = true,
        priority: Int? = null,
        serverId: String? = null,
        syncedAt: Long? = null,
        installedFrom: String = "app",
    ): InstalledAddon
    suspend fun removeAddon(manifestUrl: String)
    suspend fun getInstalledAddons(): List<InstalledAddon>
    suspend fun getEnabledAddons(): List<InstalledAddon>
    suspend fun toggleAddon(manifestUrl: String, enabled: Boolean)
    suspend fun reorderAddons(orderedUrls: List<String>)
    suspend fun getManifest(url: String): AddonManifest
    suspend fun getAddon(manifestUrl: String): InstalledAddon?
    suspend fun markAddonSynced(
        manifestUrl: String,
        serverId: String,
        syncedAt: Long?,
        installedFrom: String,
    )
    suspend fun syncRemoteState(
        manifestUrl: String,
        serverId: String,
        enabled: Boolean,
        priority: Int,
        syncedAt: Long,
        installedFrom: String,
    )
    suspend fun clearSyncMetadata()

    /**
     * Persist the server-issued Panda config_id for an already-installed addon.
     * Called immediately after Panda onboarding so subsequent management-token
     * calls know which config they address. Non-Panda addons never set this.
     */
    suspend fun setAddonConfigId(manifestUrl: String, configId: String?)
}
