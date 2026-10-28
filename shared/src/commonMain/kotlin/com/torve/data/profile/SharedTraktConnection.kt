package com.torve.data.profile

import com.torve.data.account.AccountSettingsApi
import com.torve.data.auth.*
import com.torve.data.trakt.TraktTokens
import com.torve.domain.integrations.*
import kotlinx.serialization.json.*

class SharedTraktConnection(private val ids: UserIdProvider, private val auth: AuthClient,
    private val api: AccountSettingsApi, private val secrets: IntegrationSecretStore) {
    private fun tokens(values: Map<String, String>): TraktTokens? {
        val access = values["accessToken"] ?: values["access_token"] ?: return null
        return TraktTokens(access, values["refreshToken"] ?: values["refresh_token"].orEmpty(),
            (values["expiresIn"] ?: values["expires_in"])?.toIntOrNull() ?: 0, (values["createdAt"] ?: values["created_at"])?.toLongOrNull() ?: 0)
    }
    suspend fun latest(): TraktTokens? = ids.withPersonalDataScope {
        if (secrets.getStorageMode(IntegrationSecretKey.TRAKT_TOKENS) != IntegrationStorageMode.ACCOUNT) return@withPersonalDataScope null
        val pending = secrets.get(IntegrationSecretKey.TRAKT_TOKENS, "pending_rotation")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (pending != null) {
            val value = tokens(pending.getValue("payload").jsonObject.mapValues { it.value.jsonPrimitive.content })
            if (value != null) return@withPersonalDataScope publish(pending.getValue("previous").jsonPrimitive.content, value)
        }
        val token = auth.getValidAccessTokenForUser(ids.currentUserId()) ?: return@withPersonalDataScope null
        val type = com.torve.data.integrations.profileIntegrationType("TRAKT_TOKENS", ids.currentProfileId())
        api.getIntegrationCredentials(token, type)?.let(::tokens)
    }
    suspend fun publish(previous: String, replacement: TraktTokens): TraktTokens = ids.withPersonalDataScope {
        val payload = mapOf("accessToken" to replacement.accessToken, "refreshToken" to replacement.refreshToken,
            "expiresIn" to replacement.expiresIn.toString(), "createdAt" to replacement.createdAt.toString())
        val result = if (secrets.getStorageMode(IntegrationSecretKey.TRAKT_TOKENS) == IntegrationStorageMode.ACCOUNT) {
            secrets.put(IntegrationSecretKey.TRAKT_TOKENS, buildJsonObject {
                put("previous", previous); put("payload", JsonObject(payload.mapValues { JsonPrimitive(it.value) }))
            }.toString(), "pending_rotation")
            try {
                val token = auth.getValidAccessTokenForUser(ids.currentUserId()) ?: error("Sign in to refresh the shared Trakt connection.")
                val saved = tokens(api.rotateTraktTokens(token, ids.currentProfileId(), previous, payload)) ?: replacement
                secrets.remove(IntegrationSecretKey.TRAKT_TOKENS, "pending_rotation")
                saved
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { replacement }
        } else replacement
        val json = buildJsonObject { put("accessToken", result.accessToken); put("refreshToken", result.refreshToken); put("expiresIn", result.expiresIn); put("createdAt", result.createdAt) }.toString()
        (secrets as? ProfileScopedSecretStore)?.propagateTraktRotation(previous, json)
        result
    }
}
