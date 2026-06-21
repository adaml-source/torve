package com.torve.domain.telemetry

/**
 * Stable event-name + attribute-key contract for the credential-transfer
 * surface. Mirrors [UsenetTelemetry] — every emission goes through the
 * builders below so raw secrets, envelope JSON, session strings, public
 * keys, and access tokens are *structurally* unable to reach the
 * [TelemetryEmitter] from feature code.
 *
 * Tests in `TransferTelemetryRedactionTest` scan emitted attribute
 * values for known leak shapes (`spk=`, `torve://transfer/receive/`,
 * `Bearer `, `{"version":1`, etc.) and assert nothing leaks.
 */
object TransferTelemetryEvents {
    const val RECEIVE_SESSION_CREATED: String = "transfer_receive_session_created"
    const val SEND_DELIVERED: String = "transfer_send_delivered"
    const val SEND_FAILED: String = "transfer_send_failed"
    const val IMPORT_SUCCESS: String = "transfer_import_success"
    const val IMPORT_FAILED: String = "transfer_import_failed"
    const val RELAY_UNAVAILABLE: String = "transfer_relay_unavailable"
    const val MANUAL_PASTE_USED: String = "transfer_manual_paste_used"
}

object TransferTelemetryKeys {
    const val PLATFORM: String = "platform"            // android / desktop / ios
    const val ROLE: String = "role"                    // sender / receiver
    const val STATE: String = "state"                  // coarse outcome enum
    const val ERROR_CATEGORY: String = "error_category" // see TransferTelemetryErrorCategory
    const val SECRET_COUNT: String = "secret_count"     // bucketed integer
    const val CONFIG_COUNT: String = "config_count"     // bucketed integer
    const val CATEGORY_COUNT: String = "category_count" // bucketed integer
}

/**
 * Coarse outcome vocabulary. Every emission's `state` attribute is one
 * of these — no backend reason tokens, no raw exception strings.
 */
enum class TransferTelemetryState(val value: String) {
    REGISTERED("registered"),
    UNAVAILABLE("unavailable"),
    DELIVERED("delivered"),
    APPLIED("applied"),
    FAILED("failed"),
}

/**
 * Closed vocabulary for the `error_category` attribute. Maps every
 * [com.torve.data.transfer.TransferRelayResult] failure branch and every
 * [com.torve.domain.transfer.TransferDecryptResult] / [TransferApplyResult]
 * failure into a small enum. Backend bodies, exception messages, URLs,
 * and stack traces never reach this layer.
 */
enum class TransferTelemetryErrorCategory(val value: String) {
    RELAY_NOT_DEPLOYED("relay_not_deployed"),
    RELAY_NOT_FOUND("relay_not_found"),
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    EXPIRED("expired"),
    CONSUMED("consumed"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    NETWORK("network"),
    SERVER("server"),
    SIGNED_OUT("signed_out"),
    DECRYPT_AUTH("decrypt_auth"),
    DECRYPT_EXPIRED("decrypt_expired"),
    DECRYPT_REPLAY("decrypt_replay"),
    DECRYPT_VERSION("decrypt_version"),
    DECRYPT_MALFORMED("decrypt_malformed"),
    APPLY_NOTHING("apply_nothing"),
    APPLY_STORE("apply_store"),
    OTHER("other"),
}

/**
 * Counts above 50 collapse to a single bucket. Keeps emission cardinality
 * bounded and avoids leaking the exact number of secrets a user holds
 * (which itself is mildly sensitive metadata).
 */
fun transferCountBucket(count: Int): String = when {
    count < 0 -> "unknown"
    count == 0 -> "0"
    count <= 3 -> "1_3"
    count <= 10 -> "4_10"
    count <= 25 -> "11_25"
    count <= 50 -> "26_50"
    else -> "gt_50"
}

/**
 * Safe attribute builder. Accepts only the typed values declared above —
 * arbitrary strings can't sneak in.
 */
fun transferAttrs(
    platform: String,
    role: TransferRole,
    state: TransferTelemetryState? = null,
    errorCategory: TransferTelemetryErrorCategory? = null,
    secretCount: Int? = null,
    configCount: Int? = null,
    categoryCount: Int? = null,
): Map<String, String> = buildMap {
    put(TransferTelemetryKeys.PLATFORM, platform)
    put(TransferTelemetryKeys.ROLE, role.value)
    state?.let { put(TransferTelemetryKeys.STATE, it.value) }
    errorCategory?.let { put(TransferTelemetryKeys.ERROR_CATEGORY, it.value) }
    secretCount?.let { put(TransferTelemetryKeys.SECRET_COUNT, transferCountBucket(it)) }
    configCount?.let { put(TransferTelemetryKeys.CONFIG_COUNT, transferCountBucket(it)) }
    categoryCount?.let { put(TransferTelemetryKeys.CATEGORY_COUNT, transferCountBucket(it)) }
}

enum class TransferRole(val value: String) { SENDER("sender"), RECEIVER("receiver") }
