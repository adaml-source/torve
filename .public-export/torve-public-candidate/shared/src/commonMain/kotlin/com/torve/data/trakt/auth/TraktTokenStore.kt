package com.torve.data.trakt.auth

import com.torve.data.trakt.TraktTokens
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import kotlinx.datetime.Clock
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
        val raw = secretStore.get(IntegrationSecretKey.TRAKT_TOKENS)
            ?: return readLegacyTokens()?.also { write(it) }
        return runCatching {
            val parsed = json.decodeFromString<TraktTokenPayload>(raw)
            TraktTokens(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                expiresIn = parsed.expiresIn,
                createdAt = parsed.createdAt,
            )
        }.getOrNull() ?: readLegacyTokens()?.also { write(it) }
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
        secretStore.remove(IntegrationSecretKey.TRAKT_ACCESS_TOKEN)
        secretStore.remove(IntegrationSecretKey.TRAKT_REFRESH_TOKEN)
        secretStore.remove(IntegrationSecretKey.TRAKT_CONNECTION_SCOPE)
    }

    suspend fun accessToken(): String? = read()?.accessToken?.takeIf { it.isNotBlank() }

    private suspend fun readLegacyTokens(): TraktTokens? {
        val accessToken = secretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val refreshToken = secretStore.get(IntegrationSecretKey.TRAKT_REFRESH_TOKEN)
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        return TraktTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = 0,
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
    }
}
