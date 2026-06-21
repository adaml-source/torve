package com.torve.data.contentpolicy

import com.torve.data.auth.AuthClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

class ContentPolicyApi(
    private val httpClient: HttpClient,
    private val authClient: AuthClient,
    private val baseUrlProvider: () -> String,
    private val channelProvider: ContentChannelProvider,
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun getPolicy(): ContentPolicyResponseDto {
        val accessToken = authClient.getValidAccessToken() ?: error("Missing access token")
        val response = httpClient.get("${baseUrl()}/me/content-policy") {
            bearerAuth(accessToken)
            appendChannelHeader()
        }
        if (!response.status.isSuccess()) {
            error("GET /me/content-policy failed (${response.status.value})")
        }
        return response.body()
    }

    suspend fun submitDob(dateOfBirth: String) {
        val accessToken = authClient.getValidAccessToken() ?: error("Missing access token")
        val response = httpClient.patch("${baseUrl()}/me/content-policy/dob") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            appendChannelHeader()
            setBody(ContentPolicyDobRequestDto(dateOfBirth = dateOfBirth))
        }
        if (!response.status.isSuccess()) {
            error(response.bodyAsText().ifBlank { "PATCH /me/content-policy/dob failed (${response.status.value})" })
        }
    }

    /**
     * Accept the sensitive-material policy. Backend requires the exact
     * `policy_version` the user was shown — must come from the most recent
     * GET /me/content-policy response (field `current_policy_version`) so
     * consents are traceable to the exact text the user saw. Calling this
     * without a body yields 422 from the server.
     *
     * Throws [ContentPolicyNotAdultEligibleException] on 403 so the caller
     * can route the user to the DOB flow instead of surfacing a generic
     * error.
     */
    suspend fun enableSensitive(policyVersion: String) {
        require(policyVersion.isNotBlank()) { "policy_version is required" }
        val accessToken = authClient.getValidAccessToken() ?: error("Missing access token")
        val response = httpClient.post("${baseUrl()}/me/content-policy/enable-sensitive") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            appendChannelHeader()
            setBody(EnableSensitiveRequestDto(policy_version = policyVersion))
        }
        if (response.status.isSuccess()) return
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        if (response.status.value == 403 && body.contains("not_adult_eligible")) {
            throw ContentPolicyNotAdultEligibleException(body)
        }
        error(body.ifBlank { "POST /me/content-policy/enable-sensitive failed (${response.status.value})" })
    }

    suspend fun disableSensitive() {
        val accessToken = authClient.getValidAccessToken() ?: error("Missing access token")
        val response = httpClient.post("${baseUrl()}/me/content-policy/disable-sensitive") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            appendChannelHeader()
            setBody("{}")
        }
        if (!response.status.isSuccess()) {
            error(response.bodyAsText().ifBlank { "POST /me/content-policy/disable-sensitive failed (${response.status.value})" })
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendChannelHeader() {
        channelProvider.channel?.let { header("X-Torve-Channel", it) }
    }
}

@Serializable
data class ContentPolicyResponseDto(
    val age_band: String? = null,
    val adult_eligible: Boolean = false,
    val sensitive_material_enabled: Boolean = false,
    @Serializable(with = FlexibleStringSerializer::class)
    val sensitive_material_policy_version: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val current_policy_version: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val policy_state_version: String? = null,
)

@Serializable
data class ContentPolicyDobRequestDto(
    @kotlinx.serialization.SerialName("date_of_birth")
    val dateOfBirth: String,
)

/**
 * Body for POST /me/content-policy/enable-sensitive. Field name is
 * snake_case to match the backend contract verbatim — serialization does
 * not alias this.
 */
@Serializable
data class EnableSensitiveRequestDto(
    val policy_version: String,
)

/**
 * Raised when the server rejects the accept call because the account
 * isn't adult-eligible yet. The UI should redirect to the DOB flow.
 */
class ContentPolicyNotAdultEligibleException(message: String) : Exception(message)

object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }.ifBlank { null }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeString(value.orEmpty())
    }
}

