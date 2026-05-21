package com.torve.presentation.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UserFacingErrorTest {

    // ── toUserFacingError: exception classification ──

    @Test
    fun timeoutExceptionMapsToTimeout() {
        val ex = Exception("Connection timed out")
        assertEquals(UserFacingError.TIMEOUT, ex.toUserFacingError())
    }

    @Test
    fun networkExceptionMapsToNetworkFailure() {
        val ex = Exception("Unable to resolve host api.torve.app")
        assertEquals(UserFacingError.NETWORK_FAILURE, ex.toUserFacingError())
    }

    @Test
    fun unauthorizedExceptionMapsToUnauthorized() {
        val ex = Exception("401 Unauthorized")
        assertEquals(UserFacingError.UNAUTHORIZED, ex.toUserFacingError())
    }

    @Test
    fun resourceAlreadyRegisteredMapsToAlreadyRegistered() {
        val ex = Exception("Resource already registered: j7.m@bf9041e")
        assertEquals(UserFacingError.ALREADY_REGISTERED, ex.toUserFacingError())
    }

    @Test
    fun amazonSdkAlreadyRegisteredMapsToAlreadyRegistered() {
        val ex = Exception("resource already registered")
        assertEquals(UserFacingError.ALREADY_REGISTERED, ex.toUserFacingError())
    }

    @Test
    fun unknownExceptionMapsToUnknown() {
        val ex = Exception("some random internal error XYZ")
        assertEquals(UserFacingError.UNKNOWN, ex.toUserFacingError())
    }

    @Test
    fun nullMessageMapsToUnknown() {
        val ex = Exception(null as String?)
        assertEquals(UserFacingError.UNKNOWN, ex.toUserFacingError())
    }

    // ── backendReasonToUserFacingError ──

    @Test
    fun deviceCapReachedMapsCorrectly() {
        assertEquals(UserFacingError.DEVICE_CAP_REACHED, backendReasonToUserFacingError("device_cap_reached"))
    }

    @Test
    fun activationSlotExhaustedMapsToDeviceCapReached() {
        assertEquals(UserFacingError.DEVICE_CAP_REACHED, backendReasonToUserFacingError("activation_slot_exhausted"))
    }

    @Test
    fun noActivationSlotsMapsToDeviceCapReached() {
        assertEquals(UserFacingError.DEVICE_CAP_REACHED, backendReasonToUserFacingError("no_activation_slots"))
    }

    @Test
    fun swapLimitReachedMapsCorrectly() {
        assertEquals(UserFacingError.DEVICE_SWAP_LIMIT, backendReasonToUserFacingError("swap_limit_reached"))
    }

    @Test
    fun alreadyRemovedMapsCorrectly() {
        assertEquals(UserFacingError.DEVICE_ALREADY_REMOVED, backendReasonToUserFacingError("already_removed"))
    }

    @Test
    fun unknownReasonMapsToUnknown() {
        assertEquals(UserFacingError.UNKNOWN, backendReasonToUserFacingError("some_new_reason"))
    }

    @Test
    fun nullReasonMapsToUnknown() {
        assertEquals(UserFacingError.UNKNOWN, backendReasonToUserFacingError(null))
    }

    // ── defaultMessage: every enum has a non-empty string ──

    @Test
    fun allEnumsHaveDefaultMessages() {
        for (error in UserFacingError.entries) {
            val msg = error.defaultMessage()
            assert(msg.isNotBlank()) { "Missing default message for ${error.name}" }
        }
    }

    // ── defaultMessage: raw text is NEVER leaked ──

    @Test
    fun rawExceptionTextNeverAppears() {
        val rawTexts = listOf(
            "Resource already registered: j7.m@bf9041e",
            "DEVICE_LIMIT_REACHED",
            "io.ktor.client.plugins.ClientRequestException",
            "java.net.SocketTimeoutException",
            "com.amazon.device.iap",
            "debugMessage",
        )
        for (error in UserFacingError.entries) {
            val msg = error.defaultMessage()
            for (raw in rawTexts) {
                assert(raw !in msg) {
                    "Raw text '$raw' found in default message for ${error.name}: $msg"
                }
            }
        }
    }

    // ── messageKey: all keys are unique and present in default map ──

    @Test
    fun allMessageKeysAreInDefaultMap() {
        for (error in UserFacingError.entries) {
            assert(error.messageKey in defaultUserFacingMessages) {
                "Missing key '${error.messageKey}' in defaultUserFacingMessages"
            }
        }
    }

    @Test
    fun messageKeysAreUnique() {
        val keys = UserFacingError.entries.map { it.messageKey }
        assertEquals(keys.size, keys.toSet().size, "Duplicate messageKey found")
    }

    // ── Safe billing messages: no debug text ──

    @Test
    fun billingInitFailedMessageIsSafe() {
        val msg = UserFacingError.BILLING_INIT_FAILED.defaultMessage()
        assert("debug" !in msg.lowercase())
        assert("exception" !in msg.lowercase())
    }

    @Test
    fun purchaseFailedMessageIsSafe() {
        val msg = UserFacingError.PURCHASE_FAILED.defaultMessage()
        assert("debug" !in msg.lowercase())
        assert("BillingResult" !in msg)
    }

    // ── Device governance: existing friendly cap message ──

    @Test
    fun deviceCapReachedHasFriendlyMessage() {
        val msg = UserFacingError.DEVICE_CAP_REACHED.defaultMessage()
        assert("device" in msg.lowercase() || "limit" in msg.lowercase()) {
            "Device cap message should mention device or limit: $msg"
        }
        assert("device_cap_reached" !in msg) { "Raw reason code leaked into message: $msg" }
    }

    // ── New backend codes ──

    @Test
    fun premiumRequiredCodeMapsCorrectly() {
        assertEquals(UserFacingError.PREMIUM_REQUIRED, backendReasonToUserFacingError("premium_required"))
    }

    @Test
    fun alreadyRegisteredCodeMapsCorrectly() {
        assertEquals(UserFacingError.ALREADY_REGISTERED, backendReasonToUserFacingError("already_registered"))
    }

    @Test
    fun premiumRequiredExceptionMapsCorrectly() {
        val ex = Exception("premium_required")
        assertEquals(UserFacingError.PREMIUM_REQUIRED, ex.toUserFacingError())
    }

    @Test
    fun deviceCapReachedExceptionMapsCorrectly() {
        val ex = Exception("device_cap_reached")
        assertEquals(UserFacingError.DEVICE_CAP_REACHED, ex.toUserFacingError())
    }

    @Test
    fun premiumRequiredHasFriendlyMessage() {
        val msg = UserFacingError.PREMIUM_REQUIRED.defaultMessage()
        assert("premium_required" !in msg) { "Raw code leaked: $msg" }
        assert(msg.isNotBlank())
    }

    @Test
    fun resolverDeviceRequiredCodeMapsToSanitizedMessage() {
        assertEquals(UserFacingError.DEVICE_REQUIRED, backendReasonToUserFacingError("device_required"))
        val msg = UserFacingError.DEVICE_REQUIRED.defaultMessage()
        assert("device_required" !in msg) { "Raw code leaked: $msg" }
        assert(msg.isNotBlank())
    }

    @Test
    fun resolverDeviceNotAuthorizedCodeMapsToSanitizedMessage() {
        assertEquals(UserFacingError.DEVICE_NOT_AUTHORIZED, backendReasonToUserFacingError("device_not_authorized"))
        val msg = UserFacingError.DEVICE_NOT_AUTHORIZED.defaultMessage()
        assert("device_not_authorized" !in msg) { "Raw code leaked: $msg" }
        assert(msg.isNotBlank())
    }

    @Test
    fun resolverRateLimitedCodeMapsToSanitizedMessage() {
        assertEquals(UserFacingError.RATE_LIMITED, backendReasonToUserFacingError("rate_limited"))
        val msg = UserFacingError.RATE_LIMITED.defaultMessage()
        assert("rate_limited" !in msg) { "Raw code leaked: $msg" }
        assert(msg.isNotBlank())
    }

    @Test
    fun expiredHandoffCodeMapsToSanitizedMessage() {
        assertEquals(UserFacingError.PLAYBACK_LINK_EXPIRED, backendReasonToUserFacingError("stream_expired"))
        assertEquals(UserFacingError.PLAYBACK_LINK_EXPIRED, backendReasonToUserFacingError("invalid_handoff"))
        val msg = UserFacingError.PLAYBACK_LINK_EXPIRED.defaultMessage()
        assert("stream_expired" !in msg) { "Raw code leaked: $msg" }
        assert("invalid_handoff" !in msg) { "Raw code leaked: $msg" }
        assert(msg.isNotBlank())
    }

    @Test
    fun streamHandoffReferenceCodesMapToSanitizedUnavailableMessage() {
        assertEquals(UserFacingError.STREAM_REFERENCE_UNAVAILABLE, backendReasonToUserFacingError("stream_reference_required"))
        assertEquals(UserFacingError.STREAM_REFERENCE_UNAVAILABLE, backendReasonToUserFacingError("stream_reference_not_found"))
        assertEquals(UserFacingError.STREAM_RESOLVE_FAILED, backendReasonToUserFacingError("stream_handoff_unavailable"))
        val msg = UserFacingError.STREAM_REFERENCE_UNAVAILABLE.defaultMessage()
        assert("stream_reference" !in msg) { "Raw code leaked: $msg" }
        assert("stream_handoff" !in msg) { "Raw code leaked: $msg" }
    }
}
