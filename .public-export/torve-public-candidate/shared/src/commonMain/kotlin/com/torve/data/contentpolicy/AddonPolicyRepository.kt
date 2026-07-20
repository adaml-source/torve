package com.torve.data.contentpolicy

import com.torve.data.account.AddonDto
import com.torve.domain.model.AddonPolicyFlags
import com.torve.domain.repository.PreferencesRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AddonPolicyRepository(
    private val prefsRepo: PreferencesRepository,
    private val json: Json,
) {
    suspend fun updateFromServer(addons: List<AddonDto>) {
        val payload = AddonPolicyCachePayload(
            flagsByManifestUrl = addons.associate { addon ->
                normalizeManifestUrl(addon.manifestUrl) to AddonPolicyFlagsRecord(
                    installable = addon.installable,
                    shelfEligible = addon.shelfEligible,
                    catalogQueryable = addon.catalogQueryable,
                )
            },
        )
        prefsRepo.setString(KEY_ADDON_POLICY_CACHE, json.encodeToString(payload))
    }

    suspend fun getFlags(manifestUrl: String): AddonPolicyFlags? {
        return loadPayload().flagsByManifestUrl[normalizeManifestUrl(manifestUrl)]?.toDomain()
    }

    /** Returns all cached addon policy flags keyed by normalized manifest URL. */
    suspend fun getAllFlags(): Map<String, AddonPolicyFlags> {
        return loadPayload().flagsByManifestUrl.mapValues { (_, record) -> record.toDomain() }
    }

    suspend fun clear() {
        prefsRepo.remove(KEY_ADDON_POLICY_CACHE)
    }

    private suspend fun loadPayload(): AddonPolicyCachePayload {
        val raw = prefsRepo.getString(KEY_ADDON_POLICY_CACHE) ?: return AddonPolicyCachePayload()
        return runCatching {
            json.decodeFromString<AddonPolicyCachePayload>(raw)
        }.getOrElse { AddonPolicyCachePayload() }
    }

    private fun normalizeManifestUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val base = trimmed.removeSuffix("/manifest.json")
        return "$base/manifest.json"
    }

    private companion object {
        const val KEY_ADDON_POLICY_CACHE = "content_policy_addon_flags_v1"
    }
}

@Serializable
private data class AddonPolicyCachePayload(
    val flagsByManifestUrl: Map<String, AddonPolicyFlagsRecord> = emptyMap(),
)

@Serializable
private data class AddonPolicyFlagsRecord(
    val installable: Boolean? = null,
    val shelfEligible: Boolean? = null,
    val catalogQueryable: Boolean? = null,
) {
    fun toDomain(): AddonPolicyFlags = AddonPolicyFlags(
        installable = installable ?: true,
        shelfEligible = shelfEligible ?: true,
        catalogQueryable = catalogQueryable ?: true,
    )
}

