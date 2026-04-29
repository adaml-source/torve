package com.torve.data.acceleration

import com.torve.data.auth.AuthClient
import com.torve.data.contentpolicy.ContentChannelProvider
import com.torve.domain.model.SourceAccelerationRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
        val accessToken = authClient.getValidAccessToken() ?: return emptyList()
        val contentId = request.resolvedContentId ?: return emptyList()
        return try {
            val raw = httpClient.get("${baseUrl()}/me/acceleration/startup") {
                bearerAuth(accessToken)
                appendChannelHeader()
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
            httpClient.post("${baseUrl()}/me/acceleration/outcome") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendChannelHeader()
                setBody(outcome)
            }.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ingestInventory(
        providerType: String,
        items: List<kotlinx.serialization.json.JsonObject>,
    ): Boolean {
        if (items.isEmpty()) return false
        val accessToken = authClient.getValidAccessToken() ?: return false
        return try {
            httpClient.post("${baseUrl()}/me/acceleration/inventory/ingest") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendChannelHeader()
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
            httpClient.post("${baseUrl()}/me/acceleration/hashes") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                appendChannelHeader()
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
    }
}
