package com.streamvault.data.subscription

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class RebateCodeApi(private val httpClient: HttpClient) {

    companion object {
        // TODO: Replace with actual API URL before enabling rebate codes
        private const val BASE_URL = "https://your-api.example.com"

        /** Rebate codes are disabled until a backend is deployed. */
        const val ENABLED = false
    }

    suspend fun redeemCode(code: String): RebateResult {
        if (!ENABLED) {
            return RebateResult.Error(message = "Rebate codes are not available yet")
        }
        return try {
            val response = httpClient.post("$BASE_URL/redeem") {
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$code"}""")
            }
            val body = response.bodyAsText()
            val parsed = Json.decodeFromString<RedeemResponse>(body)
            if (parsed.valid) {
                RebateResult.Success(type = parsed.type ?: "free_lifetime")
            } else {
                RebateResult.Error(message = parsed.error ?: "Invalid code")
            }
        } catch (e: Exception) {
            RebateResult.Error(message = e.message ?: "Network error")
        }
    }
}

sealed class RebateResult {
    data class Success(val type: String) : RebateResult()
    data class Error(val message: String) : RebateResult()
}

@Serializable
private data class RedeemResponse(
    val valid: Boolean,
    val type: String? = null,
    val error: String? = null,
)
