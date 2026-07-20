package com.torve.data.security

import com.torve.domain.security.ClientTrustHeaders
import com.torve.domain.security.ClientTrustSignal
import com.torve.domain.security.ClientTrustSignalRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class TrustSignalsApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val installationIdProvider: () -> String? = { null },
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun postCurrentTrustSignal(
        accessToken: String,
        eventType: String,
        installationId: String? = null,
    ): Boolean {
        val signal = runCatching {
            ClientTrustSignalRegistry.provider.currentSignal(includeIntegrityToken = false)
        }.getOrNull() ?: return false
        val trustHeaders = ClientTrustHeaders.capture()
        return runCatching {
            httpClient.post("${baseUrl()}/me/trust-signals") {
                bearerAuth(accessToken)
                appendInstallationHeader(installationId)
                trustHeaders?.appendTo(this)
                contentType(ContentType.Application.Json)
                setBody(
                    TrustSignalSubmitRequest(
                        eventType = eventType,
                        trustSignal = signal,
                    ),
                )
            }.status.isSuccess()
        }.getOrDefault(false)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendInstallationHeader(
        installationId: String?,
    ) {
        (installationId ?: installationIdProvider())?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }
}

@Serializable
data class TrustSignalSubmitRequest(
    @SerialName("event_type")
    val eventType: String,
    @SerialName("trust_signal")
    val trustSignal: ClientTrustSignal,
)
