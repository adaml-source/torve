package com.torve.android.ui.transfer

import com.torve.domain.transfer.Base64Url
import com.torve.domain.transfer.TransferReceiverHandshake
import com.torve.presentation.transfer.TransferSessionCodec
import com.torve.presentation.transfer.TransferSessionParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates that the cross-platform [TransferSessionCodec] decodes
 * payloads the Android scanner is realistically going to feed it,
 * including the relay-session-id additive field, malformed prefixes,
 * and rejected receiver-public-key sizes. This is the same codec the
 * desktop receiver writes, so a clean parse here proves wire-format
 * compatibility across the QR boundary.
 */
class ScannedQrPayloadParseTest {

    @Test
    fun emptyOrUntrimmedInputProducesEmpty() {
        assertEquals(TransferSessionParseResult.Empty, TransferSessionCodec.decode(""))
        assertEquals(TransferSessionParseResult.Empty, TransferSessionCodec.decode("   \n  "))
    }

    @Test
    fun nonTorvePrefixIsRejected() {
        assertEquals(
            TransferSessionParseResult.BadPrefix,
            TransferSessionCodec.decode("https://example.com/whatever"),
        )
        assertEquals(
            TransferSessionParseResult.BadPrefix,
            TransferSessionCodec.decode("torve://transfer/send/abc"),
        )
    }

    @Test
    fun nonBase64UrlBodyIsRejected() {
        assertEquals(
            TransferSessionParseResult.BadBase64,
            TransferSessionCodec.decode("${TransferSessionCodec.QR_PREFIX}***not-base64!!"),
        )
    }

    @Test
    fun validHandshakeRoundTripsThroughCodec() {
        val pubKey = ByteArray(32) { it.toByte() }
        val handshake = TransferReceiverHandshake(
            sessionId = "sess-id-1",
            receiverEphemeralPublicKey = Base64Url.encode(pubKey),
            expiresAtEpochMs = 1_700_000_000_000L,
            relaySessionId = null,
        )
        val encoded = TransferSessionCodec.encode(handshake)
        assertTrue(encoded.startsWith(TransferSessionCodec.QR_PREFIX))
        val decoded = TransferSessionCodec.decode(encoded)
        assertTrue("expected Success, got $decoded", decoded is TransferSessionParseResult.Success)
        assertEquals(handshake, (decoded as TransferSessionParseResult.Success).handshake)
    }

    @Test
    fun handshakeCarriesRelaySessionIdWhenPresent() {
        val pubKey = ByteArray(32) { (0x10 + it).toByte() }
        val handshake = TransferReceiverHandshake(
            sessionId = "sess-id-2",
            receiverEphemeralPublicKey = Base64Url.encode(pubKey),
            expiresAtEpochMs = 1_700_000_001_000L,
            relaySessionId = "relay-sess-2",
        )
        val encoded = TransferSessionCodec.encode(handshake)
        val decoded = TransferSessionCodec.decode(encoded)
        assertTrue("expected Success, got $decoded", decoded is TransferSessionParseResult.Success)
        assertEquals("relay-sess-2", (decoded as TransferSessionParseResult.Success).handshake.relaySessionId)
    }

    @Test
    fun receiverKeyOfWrongSizeIsRejected() {
        // Construct a handshake JSON with a 16-byte receiver pubkey
        // (instead of 32). This proves the decoder won't pass through a
        // payload that would explode the crypto layer.
        val short = Base64Url.encode(ByteArray(16))
        val payload = """{"sessionId":"x","receiverEphemeralPublicKey":"$short","expiresAtEpochMs":1}"""
        val raw = TransferSessionCodec.QR_PREFIX + Base64Url.encode(payload.encodeToByteArray())
        assertEquals(TransferSessionParseResult.BadReceiverPublicKey, TransferSessionCodec.decode(raw))
    }

    @Test
    fun unknownFieldsInHandshakeAreIgnored() {
        // A future receiver may emit extra fields; older parsers must
        // not break.
        val pubKey = ByteArray(32) { (0x20 + it).toByte() }
        val payload = """{
            "sessionId": "sess-x",
            "receiverEphemeralPublicKey": "${Base64Url.encode(pubKey)}",
            "expiresAtEpochMs": 1700000000000,
            "futureField": "ignored",
            "nestedObject": { "a": 1 }
        }"""
        val raw = TransferSessionCodec.QR_PREFIX + Base64Url.encode(payload.encodeToByteArray())
        val decoded = TransferSessionCodec.decode(raw)
        assertTrue("expected Success, got $decoded", decoded is TransferSessionParseResult.Success)
        assertEquals("sess-x", (decoded as TransferSessionParseResult.Success).handshake.sessionId)
    }
}
