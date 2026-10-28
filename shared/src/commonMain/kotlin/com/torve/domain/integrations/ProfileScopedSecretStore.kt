package com.torve.domain.integrations

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.checkPersonalScope

/** Keep the existing primary-profile slots, isolate connections for every other person. */
class ProfileScopedSecretStore(private val delegate: IntegrationSecretStore, private val profileProvider: () -> String,
    private val validate: suspend () -> Unit = {}) :
    IntegrationSecretStore {
    constructor(delegate: IntegrationSecretStore, userIds: UserIdProvider) : this(delegate,
        userIds::currentProfileId, { userIds.checkPersonalScope() })
    private suspend fun scope(subKey: String?): String? {
        validate()
        val profile = profileProvider()
        return if (profile == "default") subKey else "profile:$profile:${subKey ?: "primary"}"
    }
    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) = delegate.put(key, value, scope(subKey))
    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = delegate.get(key, scope(subKey))
    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) = delegate.remove(key, scope(subKey))
    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) =
        delegate.put(key, mode.name, scope("storage_mode"))
    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
        delegate.get(key, scope("storage_mode"))?.let { runCatching { IntegrationStorageMode.valueOf(it) }.getOrNull() }
            ?: if (profileProvider() == "default") delegate.getStorageMode(key) else IntegrationStorageMode.DEVICE_ONLY
    /** Called only by the parent-authorized source provisioner; secrets stay in secure storage. */
    internal suspend fun copyJellyfinToProfile(target: String) {
        validate()
        val source = profileProvider()
        if (source == target) return
        val sourceKey = if (source == "default") null else "profile:$source:primary"
        val targetKey = if (target == "default") null else "profile:$target:primary"
        val secret = delegate.get(IntegrationSecretKey.JELLYFIN_API_KEY, sourceKey) ?: return
        delegate.put(IntegrationSecretKey.JELLYFIN_API_KEY, secret, targetKey)
        delegate.put(IntegrationSecretKey.JELLYFIN_API_KEY, IntegrationStorageMode.DEVICE_ONLY.name,
            if (target == "default") "storage_mode" else "profile:$target:storage_mode")
        validate()
    }
    internal suspend fun propagateTraktRotation(previous: String, replacement: String) {
        validate()
        val slots = listOf<String?>(null) + delegate.getSubKeys(IntegrationSecretKey.TRAKT_TOKENS).filter { it.endsWith(":primary") }
        for (slot in slots) {
            val value = delegate.get(IntegrationSecretKey.TRAKT_TOKENS, slot) ?: continue
            val refresh = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(value).let {
                (it as? kotlinx.serialization.json.JsonObject)?.get("refreshToken")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
            } }.getOrNull()
            if (refresh == previous) delegate.put(IntegrationSecretKey.TRAKT_TOKENS, replacement, slot)
        }
        validate()
    }
    internal suspend fun readForProfile(key: IntegrationSecretKey, profile: String): String? =
        delegate.get(key, if (profile == "default") null else "profile:$profile:primary")
    internal suspend fun removeForProfile(key: IntegrationSecretKey, profile: String) {
        validate()
        delegate.remove(key, if (profile == "default") null else "profile:$profile:primary")
    }
    internal suspend fun writeForProfile(key: IntegrationSecretKey, profile: String, value: String, mode: IntegrationStorageMode) {
        delegate.put(key, value, if (profile == "default") null else "profile:$profile:primary")
        delegate.put(key, mode.name, if (profile == "default") "storage_mode" else "profile:$profile:storage_mode")
    }
    override suspend fun clearAllSecrets() = delegate.clearAllSecrets()
    override suspend fun getSubKeys(key: IntegrationSecretKey): List<String> {
        validate()
        val profile = profileProvider()
        return if (profile == "default") delegate.getSubKeys(key).filterNot { it.startsWith("profile:") || it == "storage_mode" }
        else delegate.getSubKeys(key).filter { it.startsWith("profile:$profile:") }
            .map { it.removePrefix("profile:$profile:") }.filterNot { it == "primary" || it == "storage_mode" }
    }
}
