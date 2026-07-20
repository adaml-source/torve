package com.torve.presentation.transfer

import com.torve.domain.transfer.Base64Url
import com.torve.domain.transfer.TransferReceiverHandshake
import kotlinx.serialization.json.Json

/**
 * Wire codec for the receive-session string the receiver shows as a QR
 * (and accepts via paste). Pure Kotlin, cross-platform: shared by desktop
 * (Compose Desktop) and mobile/TV (Android, ML Kit).
 *
 * Format: `torve://transfer/receive/<base64url(JSON(TransferReceiverHandshake))>`.
 *
 * `relaySessionId` may be present in the payload — when set, the sender
 * may post the sealed envelope to the backend relay so the receiver can
 * auto-import. Older parsers drop the field harmlessly via
 * `ignoreUnknownKeys = true`.
 */
object TransferSessionCodec {
    /**
     * QR / session-string prefix. Owned here because the codec is the
     * authoritative wire-format anchor; receiver VMs reference this
     * constant rather than redefining it.
     */
    const val QR_PREFIX: String = "torve://transfer/receive/"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(handshake: TransferReceiverHandshake): String =
        QR_PREFIX + Base64Url.encode(
            json.encodeToString(TransferReceiverHandshake.serializer(), handshake)
                .encodeToByteArray(),
        )

    fun decode(raw: String): TransferSessionParseResult {
        val input = raw.trim()
        if (input.isBlank()) return TransferSessionParseResult.Empty
        if (!input.startsWith(QR_PREFIX)) return TransferSessionParseResult.BadPrefix
        val encoded = input.removePrefix(QR_PREFIX)
        val bytes = Base64Url.decodeOrNull(encoded)
            ?: return TransferSessionParseResult.BadBase64
        val handshake = runCatching {
            json.decodeFromString(TransferReceiverHandshake.serializer(), bytes.decodeToString())
        }.getOrElse { t ->
            return TransferSessionParseResult.BadJson(t.message ?: "invalid handshake JSON")
        }
        val publicKey = Base64Url.decodeOrNull(handshake.receiverEphemeralPublicKey)
        if (publicKey?.size != 32) return TransferSessionParseResult.BadReceiverPublicKey
        return TransferSessionParseResult.Success(handshake)
    }
}

sealed interface TransferSessionParseResult {
    data class Success(val handshake: TransferReceiverHandshake) : TransferSessionParseResult
    data object Empty : TransferSessionParseResult
    data object BadPrefix : TransferSessionParseResult
    data object BadBase64 : TransferSessionParseResult
    data class BadJson(val reason: String) : TransferSessionParseResult
    data object BadReceiverPublicKey : TransferSessionParseResult
}
