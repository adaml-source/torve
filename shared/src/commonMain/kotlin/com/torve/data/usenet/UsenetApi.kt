package com.torve.data.usenet

import com.torve.data.auth.AuthClient
import com.torve.data.error.parseBackendError
import com.torve.data.usenet.model.NzbdavConfigRequestDto
import com.torve.data.usenet.model.NzbdavStatusResponseDto
import com.torve.data.usenet.model.NzbdavTestRequestDto
import com.torve.data.usenet.model.NzbdavTestResponseDto
import com.torve.data.usenet.model.ResolvedStreamDto
import com.torve.data.usenet.model.UsenetCancelRequestDto
import com.torve.data.usenet.model.UsenetCancelResponseDto
import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveNzbRequestDto
import com.torve.data.usenet.model.UsenetResolveRequestDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetWarmRequestDto
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.domain.security.ClientTrustHeaderValues
import com.torve.domain.security.ClientTrustHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Thin Ktor wrapper for the Torve backend's NzbDAV + Usenet-resolver APIs.
 *
 * The app talks ONLY to the Torve backend. Raw NzbDAV, WebDAV, and SABnzbd
 * are backend-owned — the client never crosses that boundary.
 */
class UsenetApi(
    private val httpClient: HttpClient,
    private val authClient: AuthClient,
    private val baseUrlProvider: () -> String,
    private val installationIdProvider: () -> String? = { null },
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    private suspend fun requireAccessToken(): String =
        authClient.getValidAccessToken() ?: throw UsenetApiException(
            errorCode = "no_access_token",
            message = "Not signed in.",
        )

    private fun io.ktor.client.request.HttpRequestBuilder.appendTorveContext(
        trustHeaders: ClientTrustHeaderValues?,
    ) {
        installationIdProvider()?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
        trustHeaders?.appendTo(this)
    }

    private suspend fun throwApiException(
        response: HttpResponse,
        fallbackCode: String,
        endpointLabel: String,
    ): Nothing {
        val raw = runCatching { response.bodyAsText() }.getOrDefault("")
        val parsed = parseBackendError(raw)
        val code = parsed.code
            ?: fallbackCodeForStatus(response.status.value)
            ?: fallbackCode
        throw UsenetApiException(
            errorCode = code,
            message = safeMessageFor(code, response.status.value, endpointLabel),
            httpStatus = response.status.value,
            body = "code=$code status=${response.status.value}",
        )
    }

    private fun fallbackCodeForStatus(status: Int): String? = when (status) {
        401 -> "device_required"
        403 -> "premium_required"
        429 -> "rate_limited"
        else -> null
    }

    private fun safeMessageFor(code: String, status: Int, endpointLabel: String): String = when (code) {
        "device_required", "device_not_registered" -> "This device needs setup before playback."
        "device_not_authorized" -> "This device is not authorized for playback."
        "premium_required" -> "Playback is available to everyone. Refresh account access and try again."
        "rate_limited" -> "Too many requests. Please try again later."
        "stream_expired", "invalid_handoff" -> "Playback link expired. Resolve the stream again."
        "nzbdav_not_configured" -> "NzbDAV is not configured for this account."
        else -> "$endpointLabel failed (HTTP $status)."
    }

    // ── Integrations ────────────────────────────────────────────────────

    suspend fun testIntegration(baseUrl: String, apiKey: String): NzbdavTestResponseDto {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.post("${baseUrl()}/integrations/nzbdav/test") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
            contentType(ContentType.Application.Json)
            setBody(NzbdavTestRequestDto(baseUrl = baseUrl, apiKey = apiKey))
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "integration_test_failed", "NzbDAV test")
        }
        return response.body()
    }

    /**
     * Backend returns the same shape as GET /integrations/nzbdav/status, so
     * callers can drop straight into their status-render path without an
     * extra round-trip.
     */
    suspend fun saveIntegration(
        baseUrl: String,
        apiKey: String,
        enabled: Boolean = true,
    ): NzbdavStatusResponseDto {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.put("${baseUrl()}/integrations/nzbdav/config") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
            contentType(ContentType.Application.Json)
            setBody(NzbdavConfigRequestDto(baseUrl = baseUrl, apiKey = apiKey, enabled = enabled))
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "integration_save_failed", "NzbDAV save")
        }
        return response.body()
    }

    suspend fun getStatus(): NzbdavStatusResponseDto {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.get("${baseUrl()}/integrations/nzbdav/status") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "integration_status_failed", "NzbDAV status")
        }
        return response.body()
    }

    suspend fun deleteIntegration() {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.delete("${baseUrl()}/integrations/nzbdav/config") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
        }
        // 404 is fine — treat "nothing to delete" as success. Any other
        // non-2xx is a real failure.
        if (!response.status.isSuccess() && response.status.value != 404) {
            throwApiException(response, "integration_delete_failed", "NzbDAV delete")
        }
    }

    // ── Resolver ────────────────────────────────────────────────────────

    suspend fun warm(
        contentId: String,
        candidates: List<UsenetCandidateDto>,
        topN: Int? = null,
    ): UsenetWarmResponseDto {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.post("${baseUrl()}/resolver/usenet/warm") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
            contentType(ContentType.Application.Json)
            setBody(UsenetWarmRequestDto(contentId = contentId, candidates = candidates, topN = topN))
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "warm_failed", "Usenet warm")
        }
        return response.body()
    }

    suspend fun resolve(
        contentId: String,
        candidate: UsenetCandidateDto,
    ): UsenetResolveResponseDto {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.post("${baseUrl()}/resolver/usenet/resolve") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
            contentType(ContentType.Application.Json)
            setBody(UsenetResolveRequestDto(contentId = contentId, candidate = candidate))
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "resolve_failed", "Usenet resolve")
        }
        return response.body()
    }

    /**
     * Resolve a bare NZB URL to a stream. Backed by
     * `POST /resolver/usenet/resolve-nzb`. The server derives a stable
     * dedup key from sha256(nzb_url) so repeated calls for the same URL
     * reuse any in-progress or cached job rather than creating a duplicate.
     *
     * Returns the same [UsenetResolveResponseDto] shape as [resolve].
     * Throws [UsenetApiException] with errorCode "nzbdav_not_configured"
     * (HTTP 422) when the user has no active NzbDAV integration registered.
     */
    suspend fun resolveBarNzb(nzbUrl: String, title: String): UsenetResolveResponseDto {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.post("${baseUrl()}/resolver/usenet/resolve-nzb") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
            contentType(ContentType.Application.Json)
            setBody(UsenetResolveNzbRequestDto(nzbUrl = nzbUrl, title = title))
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "resolve_nzb_failed", "NZB resolve")
        }
        return response.body()
    }

    suspend fun getJobStatus(jobId: String): UsenetJobStatusResponseDto {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.get("${baseUrl()}/resolver/usenet/jobs/$jobId") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "job_status_failed", "Usenet job status")
        }
        return response.body()
    }

    suspend fun cancel(
        contentId: String? = null,
        candidateId: String? = null,
        userSession: String? = null,
    ): UsenetCancelResponseDto {
        val token = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.post("${baseUrl()}/resolver/usenet/cancel") {
            bearerAuth(token)
            appendTorveContext(trustHeaders)
            contentType(ContentType.Application.Json)
            setBody(
                UsenetCancelRequestDto(
                    contentId = contentId,
                    candidateId = candidateId,
                    userSession = userSession,
                ),
            )
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "cancel_failed", "Usenet cancel")
        }
        return response.body()
    }

    /**
     * Fetch the raw handoff response for a short-lived handoff token. The app
     * normally does not need this — `ResolvedStreamDto.url` is already a
     * Torve handoff URL consumed directly by the player — but it's exposed
     * so an advanced caller (e.g. a refresh path on early playback failure)
     * can re-fetch metadata without going through /resolve.
     */
    suspend fun getHandoff(token: String): ResolvedStreamDto {
        val accessToken = requireAccessToken()
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.get("${baseUrl()}/resolver/usenet/handoff/$token") {
            bearerAuth(accessToken)
            appendTorveContext(trustHeaders)
        }
        if (!response.status.isSuccess()) {
            throwApiException(response, "handoff_failed", "Usenet handoff")
        }
        return response.body()
    }
}

/**
 * Thrown for any non-2xx response from the Usenet backend APIs. The error
 * code is a coarse classification the UI layer can map to neutral copy; raw
 * [body] is retained for diagnostics only and MUST NOT be shown to the user.
 */
class UsenetApiException(
    val errorCode: String,
    override val message: String,
    val httpStatus: Int = 0,
    val body: String = "",
) : Exception(message)
