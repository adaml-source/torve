package com.torve.data.support

import com.torve.data.auth.AuthClient
import com.torve.data.error.parseBackendError
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class SupportApi(
    private val httpClient: HttpClient,
    private val authClient: AuthClient,
    private val baseUrlProvider: () -> String,
    private val installationIdProvider: () -> String? = { null },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun submitBugReport(
        issueType: String,
        report: String,
        platform: String,
        appVersion: String,
    ): SupportBugReportSubmitResult {
        return submitBugReport(
            SupportBugReportPayload.minimal(
                issueType = issueType,
                report = report,
                platform = platform,
                appVersion = appVersion,
            ),
        )
    }

    suspend fun submitBugReport(payload: SupportBugReportPayload): SupportBugReportSubmitResult {
        val accessToken = authClient.getValidAccessToken()
            ?: return SupportBugReportSubmitResult.Error("Sign in with a verified email to send reports.")

        return try {
            val response = httpClient.post("${baseUrl()}/me/support/bug-report") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                installationIdProvider()?.takeIf { it.isNotBlank() }?.let {
                    header("X-Torve-Installation-Id", it)
                }
                setBody(payload)
            }
            val raw = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val parsed = parseBackendError(raw)
                return SupportBugReportSubmitResult.Error(
                    message = parsed.message ?: statusMessage(response.status.value),
                    statusCode = response.status.value,
                    retryable = response.status.value == 503,
                )
            }
            val dto = json.decodeFromString<SupportBugReportSubmitResponse>(raw)
            SupportBugReportSubmitResult.Sent(
                reportId = dto.reportId,
                supportEmail = dto.supportEmail,
            )
        } catch (e: Exception) {
            SupportBugReportSubmitResult.Error(
                message = e.message ?: "Could not send report right now.",
                retryable = true,
            )
        }
    }

    private fun statusMessage(statusCode: Int): String = when (statusCode) {
        401 -> "Sign in with a verified email to send reports."
        403 -> "Verify your email address before sending support reports."
        503 -> "Support report could not be sent right now. It was saved locally so you can retry."
        else -> "Could not send report right now."
    }
}

sealed interface SupportBugReportSubmitResult {
    data class Sent(
        val reportId: String,
        val supportEmail: String,
    ) : SupportBugReportSubmitResult

    data class Error(
        val message: String,
        val statusCode: Int? = null,
        val retryable: Boolean = false,
    ) : SupportBugReportSubmitResult
}

@Serializable
data class SupportBugReportPayload(
    @SerialName("issue_type") val issueType: String,
    val report: String,
    val platform: String,
    val appVersion: String,
    @SerialName("app_version") val legacyAppVersion: String = appVersion,
    val buildNumber: String = "",
    val distributionChannel: String = "unknown",
    val message: String? = null,
    val device: JsonObject = JsonObject(emptyMap()),
    val diagnostics: JsonObject = JsonObject(emptyMap()),
    val logs: List<String> = emptyList(),
) {
    companion object {
        fun minimal(
            issueType: String,
            report: String,
            platform: String,
            appVersion: String,
        ): SupportBugReportPayload {
            val versionCode = appVersion.substringAfter("(", "").substringBefore(")", "").takeIf { it.isNotBlank() }
                ?: "unknown"
            return SupportBugReportPayload(
                issueType = issueType,
                report = report,
                platform = platform,
                appVersion = appVersion,
                buildNumber = versionCode,
                distributionChannel = "unknown",
                device = JsonObject(emptyMap()),
                diagnostics = JsonObject(
                    mapOf(
                        "app" to JsonObject(emptyMap()),
                        "network" to JsonObject(emptyMap()),
                        "integrations" to JsonObject(emptyMap()),
                        "addons" to JsonObject(emptyMap()),
                        "performance" to JsonObject(emptyMap()),
                        "focus" to JsonObject(emptyMap()),
                        "playback" to JsonObject(emptyMap()),
                    ),
                ),
                logs = emptyList(),
            )
        }
    }
}

@Serializable
private data class SupportBugReportSubmitResponse(
    @SerialName("report_id") val reportId: String,
    val status: String,
    @SerialName("support_email") val supportEmail: String,
)
