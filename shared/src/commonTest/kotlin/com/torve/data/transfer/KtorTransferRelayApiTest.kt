package com.torve.data.transfer

import com.torve.domain.transfer.SealedSecretsEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Direct tests of [KtorTransferRelayApi] against the live backend's
 * exact status-code choices, asserted via Ktor [MockEngine]:
 *
 *   - POST /sessions  → 201 + body  → Success
 *   - POST /sessions/{id}/envelope  → 204 empty → Success(Unit)
 *   - POST /sessions/{id}/consume  → 200 + body → Success(Unit)
 *   - GET  /sessions/{id}  → 404 → NotFound (resource-targeting)
 *   - POST /sessions  → 404 → Unavailable (route family missing)
 *   - POST /sessions/{id}/envelope → 410 with body { "state": "delivered" } → Consumed
 *   - POST /sessions/{id}/envelope → 410 with body { "state": "consumed" }  → Consumed
 *   - POST /sessions/{id}/envelope → 410 with body { "state": "expired" }   → Expired
 *   - POST /sessions/{id}/envelope → 413 → PayloadTooLarge
 */
class KtorTransferRelayApiTest {

    private val baseUrl = "https://api.torve.app"

    @Test
    fun createSession201ReturnsSuccessWithDecodedDto() = runBlocking {
        val api = relayApi { request ->
            assertEquals("/api/v1/transfer/sessions", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                "Bearer test-token",
                request.headers[HttpHeaders.Authorization],
                "bearer auth must be set",
            )
            respond(
                content = """{"session_id":"sess-A","expires_at_epoch_ms":1700000000000,"state":"pending","envelope":null}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = api.createSession(
            accessToken = "test-token",
            request = CreateTransferSessionRequest(
                receiverPublicKey = "pub",
                expiresAtEpochMs = 1700000000000L,
                receiverDeviceId = "dev",
                receiverDeviceName = null,
            ),
        )
        val success = assertIs<TransferRelayResult.Success<TransferSessionDto>>(result)
        assertEquals("sess-A", success.value.sessionId)
        assertTrue(success.value.isPending)
    }

    @Test
    fun postEnvelope204EmptyReturnsSuccess() = runBlocking {
        val api = relayApi { request ->
            assertEquals("/api/v1/transfer/sessions/sess-A/envelope", request.url.encodedPath)
            respond(
                content = "",
                status = HttpStatusCode.NoContent,
            )
        }
        val result = api.postEnvelope(
            accessToken = "test-token",
            sessionId = "sess-A",
            envelope = sampleEnvelope(),
        )
        assertIs<TransferRelayResult.Success<Unit>>(result)
        Unit
    }

    @Test
    fun consumeSession200WithBodyReturnsSuccess() = runBlocking {
        val api = relayApi { request ->
            assertEquals("/api/v1/transfer/sessions/sess-A/consume", request.url.encodedPath)
            respond(
                content = """{"state":"consumed"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = api.consumeSession(accessToken = "test-token", sessionId = "sess-A")
        assertIs<TransferRelayResult.Success<Unit>>(result)
        Unit
    }

    @Test
    fun getSession404MapsToNotFoundNotUnavailable() = runBlocking {
        val api = relayApi { _ ->
            respond(content = """{"detail":"session not found"}""", status = HttpStatusCode.NotFound)
        }
        val result = api.getSession(accessToken = "test-token", sessionId = "stale")
        assertEquals(TransferRelayResult.NotFound, result)
    }

    @Test
    fun postEnvelope404MapsToNotFound() = runBlocking {
        val api = relayApi { _ ->
            respond(content = "", status = HttpStatusCode.NotFound)
        }
        val result = api.postEnvelope(
            accessToken = "test-token",
            sessionId = "stale",
            envelope = sampleEnvelope(),
        )
        assertEquals(TransferRelayResult.NotFound, result)
    }

    @Test
    fun consumeSession404MapsToNotFound() = runBlocking {
        val api = relayApi { _ ->
            respond(content = "", status = HttpStatusCode.NotFound)
        }
        val result = api.consumeSession(accessToken = "test-token", sessionId = "stale")
        assertEquals(TransferRelayResult.NotFound, result)
    }

    @Test
    fun createSession404MapsToUnavailableEvenWithLiveBackend() = runBlocking {
        // POST /sessions has no path-id resource to be missing, so a 404
        // here can only mean the route family isn't mounted. This is the
        // single 404 case where Unavailable is the right answer.
        val api = relayApi { _ ->
            respond(content = "", status = HttpStatusCode.NotFound)
        }
        val result = api.createSession(
            accessToken = "test-token",
            request = CreateTransferSessionRequest(
                receiverPublicKey = "pub",
                expiresAtEpochMs = 1L,
                receiverDeviceId = "dev",
                receiverDeviceName = null,
            ),
        )
        assertEquals(TransferRelayResult.Unavailable, result)
    }

    @Test
    fun postEnvelope410DeliveredBodyMapsToConsumed() = runBlocking {
        val api = relayApi { _ ->
            respond(
                content = """{"state":"delivered","detail":"envelope already delivered"}""",
                status = HttpStatusCode.Gone,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = api.postEnvelope(
            accessToken = "test-token",
            sessionId = "sess-A",
            envelope = sampleEnvelope(),
        )
        assertEquals(TransferRelayResult.Consumed, result)
    }

    @Test
    fun postEnvelope410ConsumedBodyMapsToConsumed() = runBlocking {
        val api = relayApi { _ ->
            respond(
                content = """{"state":"consumed"}""",
                status = HttpStatusCode.Gone,
            )
        }
        val result = api.postEnvelope(
            accessToken = "test-token",
            sessionId = "sess-A",
            envelope = sampleEnvelope(),
        )
        assertEquals(TransferRelayResult.Consumed, result)
    }

    @Test
    fun postEnvelope410ExpiredOrEmptyBodyMapsToExpired() = runBlocking {
        val api = relayApi { _ ->
            respond(content = """{"state":"expired"}""", status = HttpStatusCode.Gone)
        }
        val result = api.postEnvelope(
            accessToken = "test-token",
            sessionId = "sess-A",
            envelope = sampleEnvelope(),
        )
        assertEquals(TransferRelayResult.Expired, result)
    }

    @Test
    fun postEnvelope413MapsToPayloadTooLarge() = runBlocking {
        val api = relayApi { _ ->
            respond(content = "", status = HttpStatusCode.PayloadTooLarge)
        }
        val result = api.postEnvelope(
            accessToken = "test-token",
            sessionId = "sess-A",
            envelope = sampleEnvelope(),
        )
        assertEquals(TransferRelayResult.PayloadTooLarge, result)
    }

    @Test
    fun networkExceptionMapsToNetworkErrorNotCrash() = runBlocking {
        val api = relayApi { _ ->
            throw RuntimeException("connection reset")
        }
        val result = api.consumeSession(accessToken = "test-token", sessionId = "sess-A")
        val err = assertIs<TransferRelayResult.NetworkError>(result)
        assertTrue(err.message.contains("connection reset"), err.message)
    }

    @Test
    fun unauthorizedAndForbiddenMapCorrectly() = runBlocking {
        val api401 = relayApi { _ -> respond(content = "", status = HttpStatusCode.Unauthorized) }
        assertEquals(
            TransferRelayResult.Unauthorized,
            api401.getSession(accessToken = "tok", sessionId = "x"),
        )

        val api403 = relayApi { _ -> respond(content = "", status = HttpStatusCode.Forbidden) }
        assertEquals(
            TransferRelayResult.Forbidden,
            api403.getSession(accessToken = "tok", sessionId = "x"),
        )
    }

    private fun relayApi(
        handler: io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): KtorTransferRelayApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
        return KtorTransferRelayApi(httpClient = client, baseUrlProvider = { baseUrl })
    }

    private fun sampleEnvelope(): SealedSecretsEnvelope = SealedSecretsEnvelope(
        version = 1,
        senderEphemeralPublicKey = "spk",
        aeadNonce = "nonce",
        ciphertext = "ct",
        expiresAtEpochMs = 1L,
        senderDeviceId = "sender",
    )
}
