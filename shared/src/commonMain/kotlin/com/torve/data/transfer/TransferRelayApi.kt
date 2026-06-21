package com.torve.data.transfer

import com.torve.domain.transfer.SealedSecretsEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 3 — backend-relay client contract for credential transfer.
 *
 * The relay only ever sees opaque ciphertext. It pairs a sender with a
 * receiver who already share an ephemeral X25519 key agreement (via the
 * receiver's QR / session string). The relay's job:
 *
 *   1. Hold a session id + receiver public key + expiry.
 *   2. Accept ONE sealed envelope from the sender.
 *   3. Hand that envelope to the receiver ONCE.
 *   4. Mark consumed and forget.
 *
 * Every call requires a Torve JWT. Backend MUST verify sender and
 * receiver share the same `user_id` so a transfer never crosses
 * accounts.
 *
 * If the backend `/transfer/...` family is missing, every call here
 * returns [TransferRelayResult.Unavailable]. Callers degrade to the
 * existing manual paste-and-import flow.
 */
interface TransferRelayApi {

    /**
     * Receiver registers a session up front. Returns a relay-issued
     * session id the receiver embeds in its QR / session string so the
     * sender knows where to post.
     */
    suspend fun createSession(
        accessToken: String,
        request: CreateTransferSessionRequest,
    ): TransferRelayResult<TransferSessionDto>

    /**
     * Receiver polls for envelope arrival. State transitions:
     * `pending` → `delivered` (after sender posts) → `consumed` (after
     * the receiver acks). `expired` once `expires_at_epoch_ms` passes.
     */
    suspend fun getSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<TransferSessionDto>

    /**
     * Sender posts the sealed envelope. Backend MUST reject the second
     * post for the same session id (one-time delivery) and bodies
     * larger than 64 KiB.
     */
    suspend fun postEnvelope(
        accessToken: String,
        sessionId: String,
        envelope: SealedSecretsEnvelope,
    ): TransferRelayResult<Unit>

    /**
     * Receiver marks the session consumed after it has applied the
     * payload locally. Backend deletes the envelope row.
     */
    suspend fun consumeSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<Unit>
}

// ── DTOs ─────────────────────────────────────────────────────────

@Serializable
data class CreateTransferSessionRequest(
    /** Base64url, 32 bytes — receiver's ephemeral X25519 public key. */
    @SerialName("receiver_public_key") val receiverPublicKey: String,
    @SerialName("expires_at_epoch_ms") val expiresAtEpochMs: Long,
    @SerialName("receiver_device_id") val receiverDeviceId: String,
    /** Free-form receiver-provided label, ≤ 64 chars; never logged. */
    @SerialName("receiver_device_name") val receiverDeviceName: String? = null,
)

@Serializable
data class TransferSessionDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("expires_at_epoch_ms") val expiresAtEpochMs: Long,
    /** "pending" | "delivered" | "consumed" | "expired" — backend authoritative. */
    val state: String,
    /** Present iff [state] is "delivered" — sealed bytes the receiver decrypts. */
    val envelope: SealedSecretsEnvelope? = null,
) {
    val isPending: Boolean get() = state == "pending"
    val isDelivered: Boolean get() = state == "delivered"
    val isConsumed: Boolean get() = state == "consumed"
    val isExpired: Boolean get() = state == "expired"
}

// ── Result type ─────────────────────────────────────────────────

/**
 * Typed outcome of a relay call. The UI maps these onto specific
 * banners; nothing collapses to a generic "failed".
 */
sealed interface TransferRelayResult<out T> {
    data class Success<T>(val value: T) : TransferRelayResult<T>

    /** Backend lacks the `/transfer/...` family entirely (404 on the create route). */
    data object Unavailable : TransferRelayResult<Nothing>

    /**
     * The targeted relay resource doesn't exist for this caller — wrong
     * session id, or the session belongs to a different user. The backend
     * uses 404 for both. UI degrades to manual paste.
     */
    data object NotFound : TransferRelayResult<Nothing>

    /** JWT missing/invalid. */
    data object Unauthorized : TransferRelayResult<Nothing>

    /** Sender/receiver belong to different accounts, or other authorization fail. */
    data object Forbidden : TransferRelayResult<Nothing>

    /** TTL passed before the session was used. */
    data object Expired : TransferRelayResult<Nothing>

    /**
     * Session already consumed (receiver imported), or — for the sender —
     * the envelope was already delivered to the relay slot. Both are
     * reported by the backend as 410 with a body containing `"consumed"`
     * or `"delivered"`.
     */
    data object Consumed : TransferRelayResult<Nothing>

    /** Body larger than backend cap (≥ 413). */
    data object PayloadTooLarge : TransferRelayResult<Nothing>

    /**
     * Backend rejected the request because the caller's account email
     * isn't verified yet. Distinct from [Forbidden] so the UI can prompt
     * the user to check their inbox and resend verification, rather than
     * a generic "rejected" message.
     */
    data object EmailNotVerified : TransferRelayResult<Nothing>

    /** Network error before any HTTP status was received. */
    data class NetworkError(val message: String) : TransferRelayResult<Nothing>

    /**
     * Any other non-2xx response. [detail] is the human-readable string
     * pulled from the backend's `{"detail": "..."}` body when present —
     * preferred over [message] (raw body) for UI display.
     */
    data class ServerError(
        val statusCode: Int,
        val message: String,
        val detail: String? = null,
    ) : TransferRelayResult<Nothing>
}

// ── Ktor implementation ─────────────────────────────────────────

/**
 * Real implementation talking to the Torve backend at
 * `${baseUrl}/api/v1/transfer/...`. Uses the same Ktor HttpClient +
 * Bearer-auth pattern as [com.torve.data.pairing.PairingApi].
 *
 * No fake happy path — when the backend lacks these routes (404), the
 * caller sees [TransferRelayResult.Unavailable] and the UI degrades to
 * manual paste.
 */
class KtorTransferRelayApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
) : TransferRelayApi {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    override suspend fun createSession(
        accessToken: String,
        request: CreateTransferSessionRequest,
    ): TransferRelayResult<TransferSessionDto> = call {
        val response = httpClient.post("${baseUrl()}/api/v1/transfer/sessions") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        // 404 on POST /sessions can only mean the route family isn't
        // mounted (no path param to be missing). Keep Unavailable here.
        decode(response, TransferSessionDto.serializer(), notFoundIsUnavailable = true)
    }

    override suspend fun getSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<TransferSessionDto> = call {
        val response = httpClient.get("${baseUrl()}/api/v1/transfer/sessions/$sessionId") {
            bearerAuth(accessToken)
        }
        decode(response, TransferSessionDto.serializer(), notFoundIsUnavailable = false)
    }

    override suspend fun postEnvelope(
        accessToken: String,
        sessionId: String,
        envelope: SealedSecretsEnvelope,
    ): TransferRelayResult<Unit> = call {
        val response = httpClient.post("${baseUrl()}/api/v1/transfer/sessions/$sessionId/envelope") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(envelope)
        }
        if (response.status.isSuccess()) TransferRelayResult.Success(Unit)
        else mapErrorStatus(response.status, response.bodyAsText(), notFoundIsUnavailable = false)
    }

    override suspend fun consumeSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<Unit> = call {
        val response = httpClient.post("${baseUrl()}/api/v1/transfer/sessions/$sessionId/consume") {
            bearerAuth(accessToken)
        }
        if (response.status.isSuccess()) TransferRelayResult.Success(Unit)
        else mapErrorStatus(response.status, response.bodyAsText(), notFoundIsUnavailable = false)
    }

    // ── helpers ─────────────────────────────────────────────────

    private suspend fun <T> call(
        block: suspend () -> TransferRelayResult<T>,
    ): TransferRelayResult<T> = runCatching { block() }
        .getOrElse { t -> TransferRelayResult.NetworkError(t.message ?: t::class.simpleName ?: "network failure") }

    private suspend fun <T> decode(
        response: io.ktor.client.statement.HttpResponse,
        deserializer: kotlinx.serialization.KSerializer<T>,
        notFoundIsUnavailable: Boolean,
    ): TransferRelayResult<T> {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) return mapErrorStatus(response.status, body, notFoundIsUnavailable)
        return runCatching { json.decodeFromString(deserializer, body) }
            .map { TransferRelayResult.Success(it) }
            .getOrElse { t ->
                TransferRelayResult.ServerError(
                    statusCode = response.status.value,
                    message = "decode failed: ${t.message ?: "unknown"}",
                )
            }
    }

    /**
     * Map a non-2xx status to the typed result.
     *
     * The backend uses 404 for both "endpoint missing" and "this session
     * doesn't exist for this user" — only the call site can disambiguate.
     * `createSession` passes `notFoundIsUnavailable = true` (no resource
     * to be missing yet); resource-targeting calls pass `false`.
     *
     * 410 covers expired, consumed (receiver imported), and already-
     * delivered (sender re-posted). The body sniff treats "delivered"
     * the same as "consumed" since both mean the slot is no longer ours
     * to act on.
     */
    private fun <T> mapErrorStatus(
        status: HttpStatusCode,
        bodyText: String,
        notFoundIsUnavailable: Boolean,
    ): TransferRelayResult<T> = when {
        status.value == 401 -> TransferRelayResult.Unauthorized
        status.value == 403 -> {
            // Email-verification gate is sometimes enforced as 403 instead
            // of 400 depending on backend version — sniff the detail so the
            // user gets the right "verify your email" message either way.
            if (looksLikeUnverifiedEmail(bodyText)) TransferRelayResult.EmailNotVerified
            else TransferRelayResult.Forbidden
        }
        status.value == 404 ->
            if (notFoundIsUnavailable) TransferRelayResult.Unavailable
            else TransferRelayResult.NotFound
        status.value == 410 -> {
            val lower = bodyText.lowercase()
            if (lower.contains("\"consumed\"") || lower.contains("\"delivered\"")) {
                TransferRelayResult.Consumed
            } else TransferRelayResult.Expired
        }
        status.value == 413 -> TransferRelayResult.PayloadTooLarge
        status.value == 400 && looksLikeUnverifiedEmail(bodyText) ->
            TransferRelayResult.EmailNotVerified
        else -> TransferRelayResult.ServerError(
            statusCode = status.value,
            message = bodyText.take(500),
            detail = parseDetailField(bodyText),
        )
    }

    /**
     * Pull the `detail` string out of a FastAPI-style error body
     * (`{"detail": "..."}`). Falls back to null when the body isn't JSON
     * or doesn't have that field — caller decides what to render in
     * place of a parsed detail.
     */
    private fun parseDetailField(bodyText: String): String? {
        val trimmed = bodyText.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val element = json.parseToJsonElement(trimmed)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
            val prim = obj["detail"] as? kotlinx.serialization.json.JsonPrimitive
                ?: return@runCatching null
            if (!prim.isString) null else prim.content.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * True if the response body looks like FastAPI's standard "email
     * verification required" error. Production back-ends have rotated
     * between 400 and 403 with various wording; sniff several common
     * forms rather than pin to one.
     */
    private fun looksLikeUnverifiedEmail(bodyText: String): Boolean {
        val lower = bodyText.lowercase()
        return lower.contains("email_not_verified") ||
            lower.contains("email not verified") ||
            lower.contains("unverified") ||
            lower.contains("verify your email") ||
            lower.contains("verify_email")
    }
}
