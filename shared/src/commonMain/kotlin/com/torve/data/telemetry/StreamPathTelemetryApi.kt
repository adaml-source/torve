package com.torve.data.telemetry

import com.torve.domain.security.ClientTrustSignalRegistry
import com.torve.domain.telemetry.StreamPlaybackPath
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class StreamPathClientMetadata(
    val platform: String = "unknown",
    val appVersion: String = "unknown",
    val distributionChannel: String = "unknown",
)

@Serializable
data class StreamPathTelemetryRequest(
    @SerialName("path_type")
    val pathType: String,
    val platform: String,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("distribution_channel")
    val distributionChannel: String,
    @SerialName("content_type")
    val contentType: String,
    @SerialName("provider_category")
    val providerCategory: String,
    @SerialName("generated_at_epoch_millis")
    val generatedAtEpochMillis: Long,
)

class StreamPathTelemetryApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val accessTokenProvider: suspend () -> String?,
    private val clientMetadataProvider: suspend () -> StreamPathClientMetadata = {
        val signal = ClientTrustSignalRegistry.provider.currentSignal(includeIntegrityToken = false)
        StreamPathClientMetadata(
            platform = signal.platform.ifBlank { "unknown" },
            appVersion = signal.appVersion?.takeIf { it.isNotBlank() } ?: "unknown",
            distributionChannel = signal.distributionChannel?.takeIf { it.isNotBlank() } ?: "unknown",
        )
    },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun reportPathSelected(
        pathType: String,
        contentType: String,
        providerCategory: String,
        generatedAtEpochMillis: Long = nowMillis(),
    ): Boolean {
        val accessToken = accessTokenProvider()?.takeIf { it.isNotBlank() } ?: return false
        val metadata = runCatching { clientMetadataProvider() }
            .getOrDefault(StreamPathClientMetadata())
        val body = StreamPathTelemetryRequest(
            pathType = pathType.safePathType(),
            platform = metadata.platform.safeTelemetryField(),
            appVersion = metadata.appVersion.safeTelemetryField(),
            distributionChannel = metadata.distributionChannel.safeTelemetryField(),
            contentType = contentType.safeTelemetryField(),
            providerCategory = providerCategory.safeTelemetryField(),
            generatedAtEpochMillis = generatedAtEpochMillis,
        )
        return runCatching {
            httpClient.post("${baseUrl()}/telemetry/stream-path") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status.isSuccess()
        }.getOrDefault(false)
    }

    private fun String.safePathType(): String {
        val normalized = trim().lowercase()
        return StreamPlaybackPath.values()
            .firstOrNull { it.wireValue == normalized }
            ?.wireValue
            ?: "unknown"
    }

    private fun String.safeTelemetryField(): String {
        val raw = trim()
        if (raw.containsSensitiveTelemetryNeedle()) return "unknown"
        val normalized = trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_.:-]"), "_")
            .trim('_')
            .take(80)
        return normalized.ifBlank { "unknown" }
    }

    private fun String.containsSensitiveTelemetryNeedle(): Boolean {
        val lower = lowercase()
        return listOf(
            "http://",
            "https://",
            "token",
            "password",
            "credential",
            "source_key",
            "memory_id",
            "bearer",
            "authorization",
            "api_key",
            "apikey",
        ).any { it in lower }
    }
}
