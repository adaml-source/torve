package com.torve.data.transfer

import com.torve.domain.transfer.SealedSecretsEnvelope
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Round-trip serialization tests for the relay client DTOs.
 *
 * The backend that consumes/produces these is not yet deployed; these
 * tests pin the wire shape so a future server-side stub can match it
 * byte-for-byte. Field names match the snake_case @SerialName values
 * exactly — those are the contract.
 */
class TransferRelayDtoTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun createSessionRequestRoundTripsAndKeepsSnakeCaseKeys() {
        val req = CreateTransferSessionRequest(
            receiverPublicKey = "abc-_123",
            expiresAtEpochMs = 1_700_000_000_000L,
            receiverDeviceId = "desktop-test",
            receiverDeviceName = "Test Box",
        )

        val encoded = json.encodeToString(CreateTransferSessionRequest.serializer(), req)
        // Pin snake_case field names — backend MUST match these.
        assertTrue(encoded.contains("\"receiver_public_key\":\"abc-_123\""), encoded)
        assertTrue(encoded.contains("\"expires_at_epoch_ms\":1700000000000"), encoded)
        assertTrue(encoded.contains("\"receiver_device_id\":\"desktop-test\""), encoded)
        assertTrue(encoded.contains("\"receiver_device_name\":\"Test Box\""), encoded)

        val decoded = json.decodeFromString(CreateTransferSessionRequest.serializer(), encoded)
        assertEquals(req, decoded)
    }

    @Test
    fun createSessionRequestOmitsNullDeviceNameOnDecodeButEncodesIt() {
        val req = CreateTransferSessionRequest(
            receiverPublicKey = "key",
            expiresAtEpochMs = 1L,
            receiverDeviceId = "id",
            receiverDeviceName = null,
        )
        val encoded = json.encodeToString(CreateTransferSessionRequest.serializer(), req)
        // encodeDefaults=true → null field is rendered explicitly. Decode side accepts either.
        assertTrue(encoded.contains("\"receiver_device_name\":null"), encoded)

        val omittedJson = "{\"receiver_public_key\":\"key\",\"expires_at_epoch_ms\":1," +
            "\"receiver_device_id\":\"id\"}"
        val decoded = json.decodeFromString(CreateTransferSessionRequest.serializer(), omittedJson)
        assertNull(decoded.receiverDeviceName)
    }

    @Test
    fun transferSessionDtoPendingRoundTrips() {
        val pending = TransferSessionDto(
            sessionId = "sess-123",
            expiresAtEpochMs = 99L,
            state = "pending",
            envelope = null,
        )
        val encoded = json.encodeToString(TransferSessionDto.serializer(), pending)
        assertTrue(encoded.contains("\"session_id\":\"sess-123\""), encoded)
        assertTrue(encoded.contains("\"state\":\"pending\""), encoded)

        val decoded = json.decodeFromString(TransferSessionDto.serializer(), encoded)
        assertEquals(pending, decoded)
        assertTrue(decoded.isPending)
        assertTrue(!decoded.isDelivered && !decoded.isConsumed && !decoded.isExpired)
    }

    @Test
    fun transferSessionDtoDeliveredCarriesEnvelope() {
        val envelope = SealedSecretsEnvelope(
            version = 1,
            senderEphemeralPublicKey = "spk",
            aeadNonce = "nonce",
            ciphertext = "ct",
            expiresAtEpochMs = 1_700_000_000_000L,
            senderDeviceId = "sender-id",
        )
        val delivered = TransferSessionDto(
            sessionId = "sess-456",
            expiresAtEpochMs = 1_700_000_000_000L,
            state = "delivered",
            envelope = envelope,
        )
        val encoded = json.encodeToString(TransferSessionDto.serializer(), delivered)
        val decoded = json.decodeFromString(TransferSessionDto.serializer(), encoded)
        assertEquals(delivered, decoded)
        assertTrue(decoded.isDelivered)
        assertEquals(envelope, decoded.envelope)
    }

    @Test
    fun transferSessionDtoIgnoresUnknownBackendFields() {
        // Backend may add extra metadata fields without breaking clients.
        val payload = "{\"session_id\":\"x\",\"expires_at_epoch_ms\":1," +
            "\"state\":\"pending\",\"envelope\":null,\"server_added_field\":\"yes\"}"
        val decoded = json.decodeFromString(TransferSessionDto.serializer(), payload)
        assertEquals("x", decoded.sessionId)
    }

    @Test
    fun stateBooleansAreMutuallyExclusive() {
        val states = listOf("pending", "delivered", "consumed", "expired")
        states.forEach { s ->
            val dto = TransferSessionDto(
                sessionId = "id",
                expiresAtEpochMs = 0L,
                state = s,
                envelope = null,
            )
            val flags = listOf(dto.isPending, dto.isDelivered, dto.isConsumed, dto.isExpired)
            assertEquals(1, flags.count { it }, "exactly one flag should be set for state=$s")
        }
    }
}
