package com.torve.data.lanlibrary

import com.torve.data.auth.AuthClient
import com.torve.domain.lanlibrary.LanHub
import com.torve.domain.lanlibrary.LanHubPublishRequest
import com.torve.domain.lanlibrary.LanHubSecret
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Backend-assisted LAN hub registry.
 *
 * Both desktop (publisher) and TV/mobile (consumer) authenticate with
 * the user's Torve access token, so the registry only ever talks to
 * one user's own devices — no cross-tenant leakage.
 *
 * Defensive: every method returns a sentinel result on non-2xx so the
 * caller can degrade gracefully when the backend hasn't deployed the
 * `/me/lan/hubs/...` routes yet. **No exceptions cross this boundary** —
 * the LAN feature is "best-effort" and must not break onboarding when
 * the registry is missing.
 */
class LanHubRegistryApi(
    private val httpClient: HttpClient,
    private val tokenProvider: suspend () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val baseUrlProvider: () -> String = { AuthClient.DEFAULT_BASE_URL },
) {
    /**
     * Convenience constructor for production wiring. Defers to the
     * shared [AuthClient] for the access token; callers in tests pass
     * the suspend-lambda form instead so they don't need to spin up
     * an AuthClient.
     */
    constructor(
        httpClient: HttpClient,
        authClient: AuthClient,
        json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        baseUrlProvider: () -> String = { AuthClient.DEFAULT_BASE_URL },
    ) : this(
        httpClient = httpClient,
        tokenProvider = { authClient.getValidAccessToken() },
        json = json,
        baseUrlProvider = baseUrlProvider,
    )
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    /**
     * Publish or refresh a hub entry. The request carries the LAN auth
     * secret because consumers can only fetch it back via the
     * authenticated `/secret` endpoint; without it the LAN server
     * would still reject every consumer request.
     *
     * Returns null when:
     *   - no access token available (caller is signed out)
     *   - the registry endpoint is missing (404 / 405)
     *   - any network or serialization error
     * In every "null" case the publisher should still keep serving on
     * the LAN — the registry is opportunistic.
     */
    suspend fun publish(request: LanHubPublishRequest): LanHub? {
        val token = tokenProvider() ?: return null
        return runCatching {
            val response = httpClient.post("${baseUrl()}/me/lan/hubs") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (!response.status.isSuccess()) return@runCatching null
            json.decodeFromString(LanHub.serializer(), response.bodyAsText())
        }.getOrNull()
    }

    /**
     * List the user's currently-online hubs. Returns an empty list on
     * any failure — UI just doesn't show LAN-stream options.
     */
    suspend fun list(): List<LanHub> {
        val token = tokenProvider() ?: return emptyList()
        return runCatching {
            val response = httpClient.get("${baseUrl()}/me/lan/hubs") {
                bearerAuth(token)
            }
            if (!response.status.isSuccess()) return@runCatching emptyList()
            val raw = response.bodyAsText()
            json.decodeFromString(LanHubListResponse.serializer(), raw).hubs
        }.getOrDefault(emptyList())
    }

    /**
     * Fetch the per-hub auth secret. Distinct endpoint so the secret
     * doesn't leak into the bulk listing — the bulk listing is
     * cacheable, the secret is one-shot.
     */
    suspend fun fetchSecret(publisherId: String): LanHubSecret? {
        val token = tokenProvider() ?: return null
        return runCatching {
            val response = httpClient.get("${baseUrl()}/me/lan/hubs/$publisherId/secret") {
                bearerAuth(token)
            }
            if (!response.status.isSuccess()) return@runCatching null
            json.decodeFromString(LanHubSecret.serializer(), response.bodyAsText())
        }.getOrNull()
    }

    /**
     * Drop a hub entry on sign-out / shutdown. Best effort — no return
     * value because the backend retains its own TTL pruning either way.
     */
    suspend fun delete(publisherId: String) {
        val token = tokenProvider() ?: return
        runCatching {
            httpClient.delete("${baseUrl()}/me/lan/hubs/$publisherId") {
                bearerAuth(token)
            }
        }
    }
}

@Serializable
private data class LanHubListResponse(
    @SerialName("hubs") val hubs: List<LanHub> = emptyList(),
)
