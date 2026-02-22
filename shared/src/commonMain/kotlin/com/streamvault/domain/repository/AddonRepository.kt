package com.streamvault.domain.repository

import com.streamvault.domain.model.AddonManifest
import com.streamvault.domain.model.InstalledAddon

interface AddonRepository {
    suspend fun installAddon(url: String): InstalledAddon
    suspend fun removeAddon(manifestUrl: String)
    suspend fun getInstalledAddons(): List<InstalledAddon>
    suspend fun getEnabledAddons(): List<InstalledAddon>
    suspend fun toggleAddon(manifestUrl: String, enabled: Boolean)
    suspend fun reorderAddons(orderedUrls: List<String>)
    suspend fun getManifest(url: String): AddonManifest
}
