package com.torve.data.beta

import com.torve.data.auth.AuthClient
import com.torve.data.error.parseBackendError
import com.torve.domain.beta.BetaProgramError
import com.torve.domain.beta.BetaProgramException
import com.torve.domain.security.ClientTrustHeaders
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class BetaProgramApi(
    private val httpClient: HttpClient,
    private val authClient: AuthClient,
    private val baseUrlProvider: () -> String,
    private val installationIdProvider: () -> String? = { null },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun getBetaStatus(): DiscordBetaStatusDto {
        val accessToken = authClient.getValidAccessToken()
            ?: throw BetaProgramException(BetaProgramError.AuthRequired)
        return callAndDecode {
            httpClient.get("${baseUrl()}/me/beta/status") {
                bearerAuth(accessToken)
                appendInstallationHeader()
                ClientTrustHeaders.capture()?.appendTo(this)
            }
        }
    }

    suspend fun generateDiscordBetaLinkCode(): DiscordBetaLinkCodeDto {
        val accessToken = authClient.getValidAccessToken()
            ?: throw BetaProgramException(BetaProgramError.AuthRequired)
        return callAndDecode {
            httpClient.post("${baseUrl()}/me/beta/discord-link-code") {
                bearerAuth(accessToken)
                appendInstallationHeader()
                ClientTrustHeaders.capture()?.appendTo(this)
            }
        }
    }

    private suspend inline fun <reified T> callAndDecode(
        crossinline block: suspend () -> io.ktor.client.statement.HttpResponse,
    ): T {
        return try {
            val response = block()
            val raw = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw BetaProgramException(mapError(response.status.value, raw))
            }
            json.decodeFromString(raw)
        } catch (e: BetaProgramException) {
            throw e
        } catch (_: Exception) {
            throw BetaProgramException(BetaProgramError.Network)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendInstallationHeader() {
        installationIdProvider()?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }

    private fun mapError(statusCode: Int, raw: String): BetaProgramError {
        if (statusCode == 401) return BetaProgramError.AuthRequired
        if (statusCode == 429) return BetaProgramError.RateLimited
        val code = parseBackendError(raw).code?.trim()?.lowercase()
            ?: parseBackendError(raw).message?.trim()?.lowercase()
        return when {
            code == "email_not_verified" || code?.contains("email_not_verified") == true -> BetaProgramError.EmailNotVerified
            code == "beta_signup_closed" || code?.contains("signup_closed") == true -> BetaProgramError.SignupClosed
            code == "beta_access_ended" || code?.contains("access_ended") == true -> BetaProgramError.AccessEnded
            code == "already_active" || code?.contains("already_active") == true -> BetaProgramError.AlreadyActive
            code == "beta_unavailable" || code?.contains("beta_unavailable") == true -> BetaProgramError.BetaUnavailable
            code == "rate_limited" || code?.contains("rate_limited") == true -> BetaProgramError.RateLimited
            statusCode == 403 -> BetaProgramError.EmailNotVerified
            else -> BetaProgramError.Unknown
        }
    }
}

@Serializable
data class DiscordBetaLinkCodeDto(
    val code: String? = null,
    @SerialName("link_code") val linkCode: String? = null,
    @SerialName("discord_link_code") val discordLinkCode: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("expiresAt") val expiresAtCamel: String? = null,
    @SerialName("discord_invite_url") val discordInviteUrl: String? = null,
)

@Serializable
data class DiscordBetaStatusDto(
    @SerialName("discord_linked") val discordLinked: Boolean = false,
    @SerialName("discordLinked") val discordLinkedCamel: Boolean? = null,
    @SerialName("beta_application_status") val betaApplicationStatus: String? = null,
    @SerialName("application_status") val applicationStatus: String? = null,
    @SerialName("beta_access_active") val betaAccessActive: Boolean = false,
    @SerialName("beta_access_expires_at") val betaAccessExpiresAt: String? = null,
    @SerialName("days_remaining") val daysRemaining: Int? = null,
    @SerialName("can_apply") val canApply: Boolean? = null,
    @SerialName("blocked_reason") val blockedReason: String? = null,
    @SerialName("beta_signup_close_at") val betaSignupCloseAt: String? = null,
    @SerialName("beta_free_access_end_at") val betaFreeAccessEndAt: String? = null,
    @SerialName("discord_invite_url") val discordInviteUrl: String? = null,
    @SerialName("beta_access") val betaAccess: BetaAccessStateDto? = null,
)

@Serializable
data class BetaAccessStateDto(
    val active: Boolean = false,
    val source: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val status: String? = null,
)
