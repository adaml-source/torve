package com.torve.data.security

import com.torve.domain.security.ClientIntegrityAttestation
import com.torve.domain.security.ClientTrustHeaders
import com.torve.domain.security.ClientTrustSignal
import com.torve.domain.security.ClientTrustSignalProvider
import com.torve.domain.security.ClientTrustSignalRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrustSignalsApiContractTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun trustSignalPostUsesCompactMetadataWithoutFullIntegrityToken() = runTest {
        ClientTrustSignalRegistry.setProvider(
            object : ClientTrustSignalProvider {
                override suspend fun currentSignal(includeIntegrityToken: Boolean): ClientTrustSignal =
                    ClientTrustSignal(
                        platform = "android_tv",
                        appVersion = "1.0",
                        integrityProvider = "google_play_integrity",
                        generatedAtEpochMillis = 42L,
                    )

                override suspend fun currentIntegrityAttestation(): ClientIntegrityAttestation =
                    ClientIntegrityAttestation(
                        integrityProvider = "google_play_integrity",
                        integrityToken = "full-play-integrity-token",
                        generatedAtEpochMillis = 43L,
                    )
            },
        )
        var captured: HttpRequestData? = null
        val api = TrustSignalsApi(
            httpClient = HttpClient(MockEngine { request ->
                captured = request
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }) {
                install(ContentNegotiation) { json(json) }
            },
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = { "install-trust-1" },
        )

        try {
            val posted = api.postCurrentTrustSignal("access-token", "login")

            assertEquals(true, posted)
            val request = captured ?: error("request not captured")
            assertEquals("install-trust-1", request.headers["X-Torve-Installation-Id"])
            val header = request.headers[ClientTrustHeaders.TRUST_SIGNAL_HEADER].orEmpty()
            val body = parseBody(request)
            assertFalse(header.contains("full-play-integrity-token"))
            assertFalse(body.contains("full-play-integrity-token"))
            assertFalse(body.contains("integrity_token"))
        } finally {
            ClientTrustSignalRegistry.clearProvider()
        }
    }

    @Test
    fun noOpProviderDoesNotEmitTokenPlaceholder() = runTest {
        ClientTrustSignalRegistry.clearProvider()
        var captured: HttpRequestData? = null
        val api = TrustSignalsApi(
            httpClient = HttpClient(MockEngine { request ->
                captured = request
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }) {
                install(ContentNegotiation) { json(json) }
            },
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = { "install-trust-noop" },
        )

        val posted = api.postCurrentTrustSignal("access-token", "foreground")

        assertEquals(true, posted)
        val request = captured ?: error("request not captured")
        val header = request.headers[ClientTrustHeaders.TRUST_SIGNAL_HEADER].orEmpty()
        val body = parseBody(request)
        assertFalse(header.contains("integrity_token"))
        assertFalse(body.contains("integrity_token"))
        assertFalse(body.contains("full-play-integrity-token"))
        assertFalse(body.contains("placeholder", ignoreCase = true))
    }

    private suspend fun parseBody(request: HttpRequestData): String = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readBytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            body.writeTo(channel)
            channel.close()
            channel.readRemaining().readBytes().decodeToString()
        }
        else -> error("Unsupported body type: ${body::class}")
    }
}
