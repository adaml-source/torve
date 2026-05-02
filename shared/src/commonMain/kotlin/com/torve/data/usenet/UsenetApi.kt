package com.torve.data.usenet

import com.torve.data.auth.AuthClient
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
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    private suspend fun requireAccessToken(): String =
        authClient.getValidAccessToken() ?: throw UsenetApiException(
            errorCode = "no_access_token",
            message = "Not signed in.",
        )

    // ── Integrations ────────────────────────────────────────────────────

    suspend fun testIntegration(baseUrl: String, apiKey: String): NzbdavTestResponseDto {
        val token = requireAccessToken()
        val response = httpClient.post("${baseUrl()}/integrations/nzbdav/test") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NzbdavTestRequestDto(baseUrl = baseUrl, apiKey = apiKey))
        }
        if (!response.status.isSuccess()) {
            throw UsenetApiException(
                errorCode = "integration_test_failed",
                message = "POST /integrations/nzbdav/test failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
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
        val response = httpClient.put("${baseUrl()}/integrations/nzbdav/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NzbdavConfigRequestDto(baseUrl = baseUrl, apiKey = apiKey, enabled = enabled))
        }
        if (!response.status.isSuccess()) {
            throw UsenetApiException(
                errorCode = "integration_save_failed",
                message = "PUT /integrations/nzbdav/config failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
        }
        return response.body()
    }

    suspend fun getStatus(): NzbdavStatusResponseDto {
        val token = requireAccessToken()
        val response = httpClient.get("${baseUrl()}/integrations/nzbdav/status") {
            bearerAuth(token)
        }
        if (!response.status.isSuccess()) {
            throw UsenetApiException(
                errorCode = "integration_status_failed",
                message = "GET /integrations/nzbdav/status failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
        }
        return response.body()
    }

    suspend fun deleteIntegration() {
        val token = requireAccessToken()
        val response = httpClient.delete("${baseUrl()}/integrations/nzbdav/config") {
            bearerAuth(token)
        }
        // 404 is fine — treat "nothing to delete" as success. Any other
        // non-2xx is a real failure.
        if (!response.status.isSuccess() && response.status.value != 404) {
            throw UsenetApiException(
                errorCode = "integration_delete_failed",
                message = "DELETE /integrations/nzbdav/config failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
        }
    }

    // ── Resolver ────────────────────────────────────────────────────────

    suspend fun warm(
        contentId: String,
        candidates: List<UsenetCandidateDto>,
        topN: Int? = null,
    ): UsenetWarmResponseDto {
        val token = requireAccessToken()
        val response = httpClient.post("${baseUrl()}/resolver/usenet/warm") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UsenetWarmRequestDto(contentId = contentId, candidates = candidates, topN = topN))
        }
        if (!response.status.isSuccess()) {
            throw UsenetApiException(
                errorCode = "warm_failed",
                message = "POST /resolver/usenet/warm failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
        }
        return response.body()
    }

    suspend fun resolve(
        contentId: String,
        candidate: UsenetCandidateDto,
    ): UsenetResolveResponseDto {
        val token = requireAccessToken()
        val response = httpClient.post("${baseUrl()}/resolver/usenet/resolve") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UsenetResolveRequestDto(contentId = contentId, candidate = candidate))
        }
        if (!response.status.isSuccess()) {
            throw UsenetApiException(
                errorCode = "resolve_failed",
                message = "POST /resolver/usenet/resolve failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
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
        val response = httpClient.post("${baseUrl()}/resolver/usenet/resolve-nzb") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UsenetResolveNzbRequestDto(nzbUrl = nzbUrl, title = title))
        }
        if (!response.status.isSuccess()) {
            throw UsenetApiException(
                errorCode = "resolve_nzb_failed",
                message = "POST /resolver/usenet/resolve-nzb failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
        }
        return response.body()
    }

    suspend fun getJobStatus(jobId: String): UsenetJobStatusResponseDto {
        val token = requireAccessToken()
        val response = httpClient.get("${baseUrl()}/resolver/usenet/jobs/$jobId") {
            bearerAuth(token)
        }
        if (!response.status.isSuccess()) {
            throw UsenetApiException(
                errorCode = "job_status_failed",
                message = "GET /resolver/usenet/jobs/$jobId failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
        }
        return response.body()
    }

    suspend fun cancel(
        contentId: String? = null,
        candidateId: String? = null,
        userSession: String? = null,
    ): UsenetCancelResponseDto {
        val token = requireAccessToken()
        val response = httpClient.post("${baseUrl()}/resolver/usenet/cancel") {
            bearerAuth(token)
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
            throw UsenetApiException(
                errorCode = "cancel_failed",
                message = "POST /resolver/usenet/cancel failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
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
        val response = httpClient.get("${baseUrl()}/resolver/usenet/handoff/$token") {
            bearerAuth(accessToken)
        }
        if (!response.status.isSuccess()) {
            throw UsenetApiException(
                errorCode = "handoff_failed",
                message = "GET /resolver/usenet/handoff/$token failed (${response.status.value})",
                httpStatus = response.status.value,
                body = runCatching { response.bodyAsText() }.getOrDefault(""),
            )
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
