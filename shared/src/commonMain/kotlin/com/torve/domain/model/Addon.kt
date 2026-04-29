package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val logo: String? = null,
    val resources: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<AddonCatalog> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
)

@Serializable
data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<AddonExtra> = emptyList(),
    val genres: List<String> = emptyList(),
)

@Serializable
data class AddonExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
)

@Serializable
data class InstalledAddon(
    val manifestUrl: String,
    val manifest: AddonManifest,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val installedAt: Long = 0,
    val serverId: String? = null,
    val syncedAt: Long? = null,
    val installedFrom: String = "app",
    val policyFlags: AddonPolicyFlags? = null,
    /**
     * Server-issued identifier for addons backed by a per-user Panda config.
     * Required to authenticate management-token calls (PATCH/DELETE/rotate);
     * null for non-Panda addons and for rows stored before the v5 migration.
     */
    val configId: String? = null,
)
