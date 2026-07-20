package com.torve.data.acceleration

import com.torve.data.auth.AuthClient
import com.torve.data.contentpolicy.ContentChannelProvider
import com.torve.data.error.parseBackendError
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.security.ClientTrustHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class AccelerationApi(
    private val httpClient: HttpClient,
    private val authClient: AuthClient,
    private val json: Json,
    private val baseUrlProvider: () -> String,
    private val channelProvider: ContentChannelProvider? = null,
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun getStartupCandidates(
        request: SourceAccelerationRequest,
    ): List<StartupAccelerationCandidateDto> {
        return getAccelerationCandidates(path = "startup", request = request)
    }

    suspend fun getSourceCandidates(
        request: SourceAccelerationRequest,
    ): List<StartupAccelerationCandidateDto> {
        return getAccelerationCandidates(path = "sources", request = request)
    }

    private suspend fun getAccelerationCandidates(
        path: String,
        request: SourceAccelerationRequest,
    ): List<StartupAccelerationCandidateDto> {
        val accessToken = authClient.getValidAccessToken() ?: return emptyList()
        val contentId = request.resolvedContentId ?: return emptyList()
        return try {
            val trustHeaders = ClientTrustHeaders.capture()
            val raw = httpClient.get("${baseUrl()}/me/acceleration/$path") {
                bearerAuth(accessToken)
                appendChannelHeader()
                trustHeaders?.appendTo(this)
                parameter("content_id", contentId)
                request.title?.takeIf { it.isNotBlank() }?.let { parameter("title", it) }
                request.seasonNumber?.let { parameter("season", it) }
                request.episodeNumber?.let { parameter("episode", it) }
            }.bodyAsText()
            parseStartupAccelerationResponse(json, raw).candidates
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun reportOutcome(outcome: AccelerationOutcomeDto): Boolean {
        val accessToken = authClient.getValidAccessToken() ?: return false
        return try {
            val trustHeaders = ClientTrustHeaders.capture()
            httpClient.post("${baseUrl()}/me/acceleration/outcome") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendChannelHeader()
                trustHeaders?.appendTo(this)
                setBody(outcome)
            }.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun requestStreamHandoff(
        contentId: String,
        memoryId: String,
    ): StreamHandoffResponseDto {
        val accessToken = authClient.getValidAccessToken() ?: throw StreamHandoffApiException(
            errorCode = "device_required",
            message = "Sign in and set up this device before playback.",
        )
        val normalizedContentId = contentId.trim()
        val normalizedMemoryId = memoryId.trim()
        if (normalizedContentId.isBlank() || normalizedMemoryId.isBlank()) {
            throw StreamHandoffApiException(
                errorCode = "stream_reference_required",
                message = "This stream reference is no longer available.",
            )
        }
        val trustHeaders = ClientTrustHeaders.capture()
        val response = httpClient.post("${baseUrl()}/resolver/stream/handoff") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            appendChannelHeader()
            trustHeaders?.appendTo(this)
            setBody(
                StreamHandoffRequestDto(
                    contentId = normalizedContentId,
                    memoryId = normalizedMemoryId,
                ),
            )
        }
        if (!response.status.isSuccess()) {
            throwStreamHandoffException(response)
        }
        return response.body()
    }

    suspend fun ingestInventory(
        providerType: String,
        items: List<kotlinx.serialization.json.JsonObject>,
    ): Boolean {
        if (items.isEmpty()) return false
        val accessToken = authClient.getValidAccessToken() ?: return false
        return try {
            val trustHeaders = ClientTrustHeaders.capture()
            httpClient.post("${baseUrl()}/me/acceleration/inventory/ingest") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendChannelHeader()
                trustHeaders?.appendTo(this)
                setBody(
                    AccelerationInventoryIngestDto(
                        providerType = providerType,
                        items = items,
                    ),
                )
            }.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun reportHashes(
        providerType: String,
        observations: List<HashAvailabilityObservationDto>,
    ): Boolean {
        if (observations.isEmpty()) return false
        val accessToken = authClient.getValidAccessToken() ?: return false
        return try {
            val trustHeaders = ClientTrustHeaders.capture()
            httpClient.post("${baseUrl()}/me/acceleration/hashes") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendChannelHeader()
                trustHeaders?.appendTo(this)
                setBody(
                    HashAvailabilityReportDto(
                        providerType = providerType,
                        observations = observations,
                    ),
                )
            }.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendChannelHeader() {
        channelProvider?.channel?.let { header("X-Torve-Channel", it) }
        authClient.currentDeviceRegistration().installation_id.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }

    private suspend fun throwStreamHandoffException(response: HttpResponse): Nothing {
        val raw = runCatching { response.bodyAsText() }.getOrDefault("")
        val parsed = parseBackendError(raw)
        val code = parsed.code
            ?: fallbackStreamHandoffCode(response.status.value)
            ?: "stream_handoff_unavailable"
        throw StreamHandoffApiException(
            errorCode = code,
            message = safeStreamHandoffMessage(code),
            httpStatus = response.status.value,
            body = "code=$code status=${response.status.value}",
        )
    }

    private fun fallbackStreamHandoffCode(status: Int): String? = when (status) {
        401 -> "device_required"
        403 -> "premium_required"
        404 -> "stream_reference_not_found"
        429 -> "rate_limited"
        else -> null
    }

    private fun safeStreamHandoffMessage(code: String): String = when (code) {
        "device_required", "device_not_registered" -> "This device needs setup before playback."
        "device_not_authorized" -> "This device is not authorized for playback."
        "premium_required" -> "Playback is available to everyone. Refresh account access and try again."
        "rate_limited" -> "Too many requests. Please try again later."
        "stream_reference_required", "stream_reference_not_found" -> "This stream is no longer available."
        "stream_handoff_unavailable" -> "This stream is temporarily unavailable."
        "stream_expired", "invalid_handoff" -> "Playback link expired. Resolve the stream again."
        else -> "Could not prepare this stream."
    }
}

class StreamHandoffApiException(
    val errorCode: String,
    override val message: String,
    val httpStatus: Int? = null,
    val body: String = "",
) : Exception(message)
