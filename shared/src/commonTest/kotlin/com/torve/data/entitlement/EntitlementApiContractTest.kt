package com.torve.data.entitlement

import com.torve.domain.security.ClientIntegrityAttestation
import com.torve.domain.security.ClientTrustHeaders
import com.torve.domain.security.ClientTrustSignal
import com.torve.domain.security.ClientTrustSignalProvider
import com.torve.domain.security.ClientTrustSignalRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EntitlementApiContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun googlePurchaseVerificationSendsFullIntegrityTokenOnlyInBody() = runTest {
        ClientTrustSignalRegistry.setProvider(
            object : ClientTrustSignalProvider {
                override suspend fun currentSignal(includeIntegrityToken: Boolean): ClientTrustSignal =
                    ClientTrustSignal(
                        platform = "android",
                        appVersion = "1.0.0",
                        integrityProvider = "google_play_integrity",
                        generatedAtEpochMillis = 123L,
                    )

                override suspend fun currentIntegrityAttestation(): ClientIntegrityAttestation =
                    ClientIntegrityAttestation(
                        integrityProvider = "google_play_integrity",
                        integrityToken = "full-play-integrity-token",
                        nonce = "nonce-verify",
                        generatedAtEpochMillis = 124L,
                    )
            },
        )
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"verified":true,"entitlement_granted":true,"premium_access":true}""")
        }

        try {
            api.verifyGooglePurchase(
                accessToken = "access-token",
                productId = "torve.monthly",
                purchaseToken = "purchase-token",
                installationId = "install-1",
            )

            val request = captured ?: error("request not captured")
            val trustHeader = request.headers[ClientTrustHeaders.TRUST_SIGNAL_HEADER]
                ?: error("missing trust header")
            assertFalse(trustHeader.contains("full-play-integrity-token"))
            assertNull(json.parseToJsonElement(trustHeader).jsonObject["integrity_token"])

            val body = parseBody(request)
            val integrity = body["client_integrity"]?.jsonObject ?: error("missing client_integrity")
            assertEquals("full-play-integrity-token", integrity["integrity_token"]?.jsonPrimitive?.content)
            assertEquals("nonce-verify", integrity["nonce"]?.jsonPrimitive?.content)
            assertEquals("google_play_integrity", integrity["integrity_provider"]?.jsonPrimitive?.content)
        } finally {
            ClientTrustSignalRegistry.clearProvider()
        }
    }

    @Test
    fun amazonPurchaseVerificationDoesNotSendNoopIntegrityPlaceholder() = runTest {
        ClientTrustSignalRegistry.clearProvider()
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson("""{"verified":true,"premium_access":true}""")
        }

        api.verifyAmazonPurchase(
            accessToken = "access-token",
            receiptId = "receipt-1",
            amazonUserId = "amazon-user",
            productId = "torve.lifetime",
            installationId = "install-2",
        )

        val body = parseBody(captured ?: error("request not captured"))
        assertNull(body["client_integrity"])
        assertFalse(body.toString().contains("integrity_token"))
    }

    @Test
    fun canonicalRestoreUsesInstallationIdProviderHeader() = runTest {
        var captured: HttpRequestData? = null
        val api = api(
            installationIdProvider = { "install-entitlement-provider" },
        ) { request ->
            captured = request
            respondJson("""{"restored":true,"has_premium_access":false}""")
        }

        api.restorePurchasesCanonical("access-token")

        val request = captured ?: error("request not captured")
        assertEquals("install-entitlement-provider", request.headers["X-Torve-Installation-Id"])
    }

    private fun api(
        installationIdProvider: () -> String? = { null },
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): EntitlementApi {
        val client = HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return EntitlementApi(
            httpClient = client,
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = installationIdProvider,
        )
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private suspend fun parseBody(request: HttpRequestData) = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readBytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            body.writeTo(channel)
            channel.close()
            channel.readRemaining().readBytes().decodeToString()
        }
        else -> error("Unsupported body type: ${body::class}")
    }.let { json.parseToJsonElement(it).jsonObject }
}
