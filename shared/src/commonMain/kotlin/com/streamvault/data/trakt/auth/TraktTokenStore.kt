package com.streamvault.data.trakt.auth

import com.streamvault.data.trakt.TraktTokens
import com.streamvault.domain.integrations.IntegrationSecretKey
import com.streamvault.domain.integrations.IntegrationSecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class TraktTokenPayload(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val createdAt: Long,
)

class TraktTokenStore(
    private val secretStore: IntegrationSecretStore,
    private val json: Json,
) {
    suspend fun read(): TraktTokens? {
        val raw = secretStore.get(IntegrationSecretKey.TRAKT_TOKENS) ?: return null
        return runCatching {
            val parsed = json.decodeFromString<TraktTokenPayload>(raw)
            TraktTokens(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                expiresIn = parsed.expiresIn,
                createdAt = parsed.createdAt,
            )
        }.getOrNull()
    }

    suspend fun write(tokens: TraktTokens) {
        val payload = TraktTokenPayload(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresIn = tokens.expiresIn,
            createdAt = tokens.createdAt,
        )
        secretStore.put(IntegrationSecretKey.TRAKT_TOKENS, json.encodeToString(payload))
    }

    suspend fun clear() {
        secretStore.remove(IntegrationSecretKey.TRAKT_TOKENS)
    }

    suspend fun accessToken(): String? = read()?.accessToken?.takeIf { it.isNotBlank() }
}

