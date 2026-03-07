package com.torve.data.subscription

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class RebateCodeApi(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://torve-rebate.torve-api.workers.dev"

        /** Rebate code redemption via Cloudflare Worker + KV. */
        const val ENABLED = false

        private const val MAX_ATTEMPTS = 5
        private const val WINDOW_MS = 60 * 60 * 1000L // 1 hour
    }

    private val attempts = mutableListOf<Long>()

    suspend fun redeemCode(code: String, deviceId: String): RebateResult {
        if (!ENABLED) {
            return RebateResult.Error(message = "Rebate codes are not available yet")
        }

        // Local rate limiting
        val now = Clock.System.now().toEpochMilliseconds()
        attempts.removeAll { it < now - WINDOW_MS }
        if (attempts.size >= MAX_ATTEMPTS) {
            return RebateResult.Error(message = "Too many attempts. Try again later.")
        }
        attempts.add(now)

        return try {
            val response = httpClient.post(BASE_URL) {
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$code","deviceId":"$deviceId"}""")
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
