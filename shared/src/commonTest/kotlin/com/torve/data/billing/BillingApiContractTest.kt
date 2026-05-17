package com.torve.data.billing

import com.torve.domain.security.ClientTrustHeaders
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
import kotlin.test.assertNull

class BillingApiContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun checkoutSessionIncludesInstallationHeaderAndPurchaseType() = runTest {
        var captured: HttpRequestData? = null
        val api = api(installationId = "install-billing-1") { request ->
            captured = request
            respondJson("""{"checkout_url":"https://checkout.stripe.test/session"}""")
        }

        val result = api.createStripeCheckoutSession("token", StripePurchaseType.MONTHLY)

        assertEquals("https://checkout.stripe.test/session", result.resolvedUrl())
        assertEquals("/billing/stripe/checkout-session", captured?.url?.encodedPath)
        assertEquals("install-billing-1", captured?.headers?.get("X-Torve-Installation-Id"))
        val trustHeader = captured?.headers?.get(ClientTrustHeaders.TRUST_SIGNAL_HEADER)
            ?: error("missing trust header")
        val trust = json.parseToJsonElement(trustHeader).jsonObject
        assertEquals("unknown", trust["platform"]?.jsonPrimitive?.content)
        assertEquals("none", trust["integrity_provider"]?.jsonPrimitive?.content)
        assertNull(trust["integrity_token"])
        assertEquals("monthly", parseBody(captured ?: error("request not captured"))["purchase_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun portalSessionIncludesInstallationHeader() = runTest {
        var captured: HttpRequestData? = null
        val api = api(installationId = "install-billing-2") { request ->
            captured = request
            respondJson("""{"portal_url":"https://billing.stripe.test/session"}""")
        }

        val result = api.createStripePortalSession("token")

        assertEquals("https://billing.stripe.test/session", result.resolvedUrl())
        assertEquals("/billing/stripe/portal-session", captured?.url?.encodedPath)
        assertEquals("install-billing-2", captured?.headers?.get("X-Torve-Installation-Id"))
    }

    @Test
    fun missingInstallationIdOmitsHeaderSafely() = runTest {
        var captured: HttpRequestData? = null
        val api = api(installationId = "") { request ->
            captured = request
            respondJson("""{"checkout_url":"https://checkout.stripe.test/session"}""")
        }

        api.createStripeCheckoutSession("token", StripePurchaseType.LIFETIME)

        assertNull(captured?.headers?.get("X-Torve-Installation-Id"))
        assertEquals("lifetime", parseBody(captured ?: error("request not captured"))["purchase_type"]?.jsonPrimitive?.content)
    }

    private fun api(
        installationId: String?,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorBillingApi {
        val client = HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return KtorBillingApi(
            httpClient = client,
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = { installationId },
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
