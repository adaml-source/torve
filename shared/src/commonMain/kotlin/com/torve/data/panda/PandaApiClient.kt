package com.torve.data.panda

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class PandaApiClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        const val BASE_URL = "https://panda.torve.app"
    }

    private var cachedSchema: PandaSchema? = null

    suspend fun getPandaSchema(): PandaSchema {
        cachedSchema?.let { return it }
        val response: HttpResponse = httpClient.get("$BASE_URL/api/v1/schema")
        checkResponse(response)
        val dto: PandaSchemaResponse = response.body()
        val schema = PandaSchema(
            debridServices = dto.debridServices,
            usenetProviders = dto.usenetProviders,
            nzbIndexers = dto.nzbIndexers,
            downloadClients = dto.downloadClients,
            qualityOptions = dto.qualityOptions,
            qualityProfiles = dto.qualityProfiles,
            releaseLanguages = dto.releaseLanguages,
            sortOptions = dto.sortOptions,
            resultLimits = dto.resultLimits,
            downloadClientFields = dto.downloadClientFields.mapValues { (_, v) ->
                DownloadClientFieldSpec(fields = v.fields, cloud = v.cloud)
            },
        )
        cachedSchema = schema
        return schema
    }

    /** Force a refetch — call only when the cache is null (prior fetch failed). */
    suspend fun refreshSchema(): PandaSchema {
        cachedSchema = null
        return getPandaSchema()
    }

    fun cachedSchemaOrNull(): PandaSchema? = cachedSchema

    suspend fun getProviders(): List<PandaProvider> {
        val response: HttpResponse = httpClient.get("$BASE_URL/api/v1/providers")
        checkResponse(response)
        val resp: PandaProvidersResponse = response.body()
        return resp.providers.map { dto ->
            PandaProvider(
                id = dto.id,
                name = dto.name,
                authMethods = dto.authMethods,
                logoUrl = dto.logoUrl,
                helpUrl = dto.helpUrl,
            )
        }
    }

    suspend fun startAuth(providerId: String): PandaDeviceCode {
        val response: HttpResponse = httpClient.post("$BASE_URL/api/v1/debrid/$providerId/auth/start") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        checkResponse(response)
        val resp: PandaDeviceCodeResponse = response.body()
        return PandaDeviceCode(
            deviceCode = resp.deviceCode,
            userCode = resp.userCode,
            verificationUrl = resp.verificationUrl,
            expiresIn = resp.expiresIn,
            interval = resp.interval,
        )
    }

    suspend fun pollAuth(providerId: String, deviceCode: String): PandaAuthPollResult {
        return try {
            val response: HttpResponse = httpClient.post("$BASE_URL/api/v1/debrid/$providerId/auth/poll") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PandaDeviceCodeRequest.serializer(), PandaDeviceCodeRequest(deviceCode)))
            }
            when {
                response.status.isSuccess() -> {
                    val resp: PandaAuthPollResponse = response.body()
                    when (resp.status) {
                        "approved" -> PandaAuthPollResult.Approved(resp.apiKey ?: "")
                        "expired" -> PandaAuthPollResult.Expired
                        else -> PandaAuthPollResult.Pending
                    }
                }
                response.status.value == 410 -> PandaAuthPollResult.Expired
                else -> {
                    val err = parseError(response)
                    PandaAuthPollResult.Error(err?.message ?: "HTTP ${response.status.value}")
                }
            }
        } catch (e: Exception) {
            PandaAuthPollResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun validateApiKey(providerId: String, apiKey: String) {
        val response: HttpResponse = httpClient.post("$BASE_URL/api/v1/debrid/$providerId/auth/apikey") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PandaApiKeyRequest.serializer(), PandaApiKeyRequest(apiKey)))
        }
        checkResponse(response)
    }

    suspend fun createConfig(config: PandaConfigPayload): PandaConfigResponse {
        val response: HttpResponse = httpClient.post("$BASE_URL/api/v1/configs") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PandaConfigPayload.serializer(), config))
        }
        checkResponse(response)
        return response.body()
    }

    /**
     * Read-only config fetch. Accepts the manifest (panda) token — the server
     * still honors it for GET so the UI can pre-populate without requiring the
     * management token. Management-token reads go through [getConfigAsManager].
     */
    suspend fun getConfig(pandaToken: String): PandaConfigRecord {
        val response: HttpResponse = httpClient.get("$BASE_URL/api/v1/configs/me") {
            header("Authorization", "Bearer $pandaToken")
        }
        checkResponse(response)
        return response.body()
    }

    /**
     * Fetch the config with the management token — used to validate a token
     * after the recovery-paste flow. Any success (2xx) means the token is
     * valid for this [configId]; a 401/403 means the user typed the wrong one.
     */
    /**
     * Per Panda commit 972fa4a (2026-04-27), the `/api/v1/configs/me`
     * family of endpoints accepts three auth methods, in priority order:
     *   1. **Torve JWT** owner token (Bearer) + `X-Panda-Config-Id` —
     *      preferred path; works for any config that's been bound to
     *      the user's Torve account (all production configs already are).
     *   2. **Bearer management_token** + `X-Panda-Config-Id` — legacy.
     *   3. **Bearer manifest_token** — legacy fallback for very old rows.
     *
     * The Authorization header shape is identical for all three; the
     * backend differentiates by token type. Callers pass whichever
     * token they have via [bearerToken] — clients should prefer the
     * Torve JWT when available.
     */
    suspend fun getConfigAsManager(configId: String, bearerToken: String): PandaConfigRecord {
        val response: HttpResponse = httpClient.get("$BASE_URL/api/v1/configs/me") {
            header("Authorization", "Bearer $bearerToken")
            header("X-Panda-Config-Id", configId)
        }
        checkResponse(response)
        return response.body()
    }

    /**
     * Fetch unredacted secrets for the bound owner. Panda's standard read
     * endpoints always return secrets as `[redacted]` by design. This route
     * is the single path back to the plaintext values. Auth must be the
     * Torve JWT of the config owner — management or manifest tokens get
     * rejected (403 / 401). Treat the response as device-private and never
     * cache it where it can be inspected.
     */
    suspend fun getConfigSecrets(configId: String, torveJwt: String): PandaConfigSecrets {
        val response: HttpResponse = httpClient.get("$BASE_URL/api/v1/configs/me/secrets") {
            header("Authorization", "Bearer $torveJwt")
            header("X-Panda-Config-Id", configId)
        }
        checkResponse(response)
        return response.body()
    }

    suspend fun updateConfig(
        configId: String,
        bearerToken: String,
        patch: PandaConfigPatch,
    ): PandaConfigRecord {
        val response: HttpResponse = httpClient.patch("$BASE_URL/api/v1/configs/me") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $bearerToken")
            header("X-Panda-Config-Id", configId)
            setBody(json.encodeToString(PandaConfigPatch.serializer(), patch))
        }
        checkResponse(response)
        return response.body()
    }

    suspend fun deleteConfig(configId: String, bearerToken: String) {
        val response: HttpResponse = httpClient.delete("$BASE_URL/api/v1/configs/me") {
            header("Authorization", "Bearer $bearerToken")
            header("X-Panda-Config-Id", configId)
        }
        checkResponse(response)
    }

    /** Rotate the manifest (panda) token. Old manifest URL returns 404 after success. */
    suspend fun rotateManifest(
        configId: String,
        bearerToken: String,
    ): PandaRotateManifestResponse {
        val response: HttpResponse = httpClient.post("$BASE_URL/api/v1/configs/me/rotate-manifest") {
            header("Authorization", "Bearer $bearerToken")
            header("X-Panda-Config-Id", configId)
        }
        checkResponse(response)
        return response.body()
    }

    /** Mint a fresh management token. The previous token is revoked server-side. */
    suspend fun rotateManagement(
        configId: String,
        bearerToken: String,
    ): PandaRotateManagementResponse {
        val response: HttpResponse = httpClient.post("$BASE_URL/api/v1/configs/me/rotate-management") {
            header("Authorization", "Bearer $bearerToken")
            header("X-Panda-Config-Id", configId)
        }
        checkResponse(response)
        return response.body()
    }

    private suspend fun checkResponse(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val err = parseError(response)
            throw PandaApiException(
                errorCode = err?.code ?: "http_${response.status.value}",
                message = err?.message ?: "HTTP ${response.status.value}",
                httpStatus = response.status.value,
            )
        }
    }

    private suspend fun parseError(response: HttpResponse): PandaErrorResponse? {
        return try {
            val text = response.bodyAsText()
            json.decodeFromString(PandaErrorResponse.serializer(), text)
        } catch (_: Exception) {
            null
        }
    }
}
