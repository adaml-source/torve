package com.torve.domain.repository

import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon

interface AddonRepository {
    suspend fun installAddon(url: String): InstalledAddon
    suspend fun removeAddon(manifestUrl: String)
    suspend fun getInstalledAddons(): List<InstalledAddon>
    suspend fun getEnabledAddons(): List<InstalledAddon>
    suspend fun toggleAddon(manifestUrl: String, enabled: Boolean)
    suspend fun reorderAddons(orderedUrls: List<String>)
    suspend fun getManifest(url: String): AddonManifest
}
