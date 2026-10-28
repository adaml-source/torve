package com.torve.data.profile

import com.torve.data.auth.AuthClient
import com.torve.domain.model.FamilyConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class FamilyPolicySnapshot(val configuration: FamilyConfiguration, val pinRevision: Long = 0)
@Serializable
data class FamilyParentProof(val configuration: FamilyConfiguration, val pinRevision: Long = 0, val grant: String = "", val expiresAt: Long = 0)
@Serializable
private data class FamilyPolicyUpdate(val configuration: FamilyConfiguration, val expectedVersion: Long, val pin: String? = null)
@Serializable
private data class FamilyPinProof(val pin: String)
@Serializable
private data class FamilyRecoveryProof(val code: String, val pin: String)
@Serializable
data class FamilyRecoveryDestination(val destination: String, val expiresInSeconds: Int = 600)
@Serializable
data class ProfileConnectionChoices(val services: List<String> = emptyList())
@Serializable
private data class ProfileConnectionCopy(val sourceProfile: String, val targetProfile: String, val services: List<String>, val replaceExisting: Boolean)

interface FamilyPolicyRemote {
    suspend fun fetch(): FamilyPolicySnapshot
    suspend fun update(configuration: FamilyConfiguration, expectedVersion: Long, pin: String?, grant: String?): FamilyParentProof
    suspend fun authorize(pin: String): FamilyParentProof
    suspend fun requestRecovery(): FamilyRecoveryDestination
    suspend fun confirmRecovery(code: String, pin: String): FamilyParentProof
    suspend fun connections(profile: String, grant: String): List<String>
    suspend fun copyConnections(source: String, target: String, services: List<String>, replace: Boolean, grant: String)
}

class FamilyPolicyApi(private val http: HttpClient, private val auth: AuthClient, private val baseUrl: () -> String, private val installationId: () -> String = { "" }) : FamilyPolicyRemote {
    private suspend inline fun <reified T> call(path: String = "", method: HttpMethod = HttpMethod.Get, body: JsonElement? = null, grant: String? = null): T {
        val token = auth.getValidAccessToken() ?: error("Sign in to sync Family settings.")
        val response = http.request(baseUrl().trimEnd('/') + "/me/family" + path) {
            this.method = method
            bearerAuth(token)
            header(HttpHeaders.CacheControl, "no-store")
            installationId().takeIf { it.isNotBlank() }?.let { header("X-Torve-Installation-Id", it) }
            grant?.let { header("X-Torve-Parent-Grant", it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        if (!response.status.isSuccess()) error(when (response.status.value) {
            409 -> "Settings changed on another device. Refresh and try again."
            429 -> "Too many attempts. Wait a minute before trying again."
            403 -> "Parent verification failed. Check the PIN or recovery code."
            else -> "Could not sync Family settings. Check your connection and try again."
        })
        return response.body()
    }
    override suspend fun fetch(): FamilyPolicySnapshot = call()
    override suspend fun update(configuration: FamilyConfiguration, expectedVersion: Long, pin: String?, grant: String?): FamilyParentProof =
        call(method = HttpMethod.Put, body = Json.encodeToJsonElement(FamilyPolicyUpdate(configuration, expectedVersion, pin)), grant = grant)
    override suspend fun authorize(pin: String): FamilyParentProof = call("/authorize", HttpMethod.Post, Json.encodeToJsonElement(FamilyPinProof(pin)))
    override suspend fun requestRecovery(): FamilyRecoveryDestination = call("/recovery/request", HttpMethod.Post)
    override suspend fun confirmRecovery(code: String, pin: String): FamilyParentProof = call("/recovery/confirm", HttpMethod.Post, Json.encodeToJsonElement(FamilyRecoveryProof(code, pin)))
    override suspend fun connections(profile: String, grant: String): List<String> = call<ProfileConnectionChoices>("/connections/$profile", grant = grant).services
    override suspend fun copyConnections(source: String, target: String, services: List<String>, replace: Boolean, grant: String) {
        call<JsonObject>("/connections/copy", HttpMethod.Post, Json.encodeToJsonElement(ProfileConnectionCopy(source, target, services, replace)), grant)
    }
}
