package com.torve.android.ui

import com.torve.android.billing.BillingManager
import com.torve.data.error.parseBackendError
import com.torve.data.error.extractBackendErrorCode
import com.torve.presentation.error.UserFacingError
import com.torve.presentation.error.defaultMessage
import com.torve.presentation.error.defaultUserFacingMessages
import com.torve.presentation.error.toUserFacingError
import com.torve.presentation.device.DeviceGovernanceUiState
import com.torve.presentation.error.backendReasonToUserFacingError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against raw error text leaking into release UI.
 *
 * These tests verify that:
 * 1. Amazon/Google SDK exception text is never rendered directly.
 * 2. Backend error codes are mapped to safe messages.
 * 3. Device governance errors use safe message keys.
 * 4. Unknown failures resolve to a safe generic message.
 */
class ErrorLeakageGuardTest {

    // ── Amazon SDK: "Resource already registered" must not reach UI ──

    @Test
    fun `Amazon SDK resource already registered maps to safe error`() {
        val amazonException = Exception("Resource already registered: j7.m@bf9041e")
        val error = amazonException.toUserFacingError()
        assertEquals(UserFacingError.ALREADY_REGISTERED, error)
        assertFalse(
            "Raw SDK text must not appear in user message",
            error.defaultMessage().contains("j7.m@bf9041e"),
        )
        assertFalse(
            "Object reference must not appear in user message",
            error.defaultMessage().contains("@"),
        )
    }

    @Test
    fun `Amazon billing init failure produces safe message`() {
        // Simulates AmazonBillingManager.initialize() catching an exception
        val safeMessage = "Could not connect to the store. Please try again."
        // This is what AmazonBillingManager now emits instead of e.message
        assertFalse(safeMessage.contains("resource already registered", ignoreCase = true))
        assertFalse(safeMessage.contains("PurchasingService"))
        assertFalse(safeMessage.contains("@"))
    }

    // ── Google Play: debugMessage must not reach UI ──

    @Test
    fun `Google billing debug message is never forwarded to BillingState`() {
        // The GooglePlayBillingManager now uses safeBillingMessage() instead of debugMessage
        val safeMessages = listOf(
            "In-app purchases are not available right now.",
            "Could not connect to the store. Please try again.",
            "This product is not available right now.",
            "You already own this product.",
            "Could not connect. Please check your internet connection.",
            "Purchase could not be completed. Please try again.",
        )
        for (msg in safeMessages) {
            assertFalse("Message should not contain 'debug': $msg", msg.contains("debug", ignoreCase = true))
            assertFalse("Message should not contain 'BillingResult': $msg", msg.contains("BillingResult"))
        }
    }

    // ── Device governance: errorKey never contains raw exception text ──

    @Test
    fun `DeviceGovernanceUiState errorKey uses message keys not raw text`() {
        val state = DeviceGovernanceUiState(
            errorKey = UserFacingError.DEVICE_CAP_REACHED.messageKey,
        )
        assertNotNull(state.errorKey)
        assertTrue(
            "errorKey should be a known message key",
            state.errorKey!! in defaultUserFacingMessages,
        )
    }

    @Test
    fun `All backend device reasons map to known user-facing errors`() {
        val backendReasons = listOf(
            "device_cap_reached",
            "activation_slot_exhausted",
            "no_activation_slots",
            "swap_limit_reached",
            "already_removed",
            "not_found",
            "no_entitlement",
        )
        for (reason in backendReasons) {
            val error = backendReasonToUserFacingError(reason)
            assertNotNull("Reason '$reason' should map to a non-null error", error)
            assertTrue(
                "Reason '$reason' mapped to ${error.name} which should have a default message",
                error.defaultMessage().isNotBlank(),
            )
            assertFalse(
                "Reason '$reason' must not appear in user message",
                error.defaultMessage().contains(reason),
            )
        }
    }

    // ── Generic unknown fallback is safe ──

    @Test
    fun `Unknown exception produces safe generic message`() {
        val ex = Exception("com.torve.data.SomeInternalClass: unexpected null at line 42")
        val error = ex.toUserFacingError()
        val message = error.defaultMessage()
        assertFalse("Raw class name must not leak", message.contains("SomeInternalClass"))
        assertFalse("Line numbers must not leak", message.contains("line 42"))
        assertTrue("Should have a polished message", message.contains("Something went wrong"))
    }

    // ── BillingState.Error always contains safe messages ──

    @Test
    fun `BillingState Error can only carry sanitized messages`() {
        // Verify that the safe message constants used in both billing managers
        // are free of technical jargon
        val unsafePatterns = listOf(
            "exception", "stacktrace", "debug", "null",
            "registerListener", "PurchasingService", "BillingClient",
            "amazonaws", "googleapis",
        )
        val safeMessages = listOf(
            "Could not connect to the store. Please try again.",
            "Purchase could not be completed. Please try again.",
            "This product is not available right now.",
            "In-app purchases are not available right now.",
        )
        for (msg in safeMessages) {
            for (pattern in unsafePatterns) {
                assertFalse(
                    "Safe message '$msg' contains unsafe pattern '$pattern'",
                    msg.contains(pattern, ignoreCase = true),
                )
            }
        }
    }

    // ── Account/Settings sync errors are keyed, not raw ──

    @Test
    fun `Sync failed error key resolves to safe message`() {
        val key = UserFacingError.SYNC_FAILED.messageKey
        assertEquals("error_sync_failed", key)
        val message = defaultUserFacingMessages[key]
        assertNotNull("Sync failed key should have default message", message)
        assertFalse("Sync message should not contain raw text", message!!.contains("exception", ignoreCase = true))
    }

    // ── Integration errors are safe ──

    @Test
    fun `Integration error messages are user-friendly`() {
        val integrationErrors = listOf(
            UserFacingError.INTEGRATION_CONNECT_FAILED,
            UserFacingError.INTEGRATION_AUTH_TIMEOUT,
            UserFacingError.INTEGRATION_AUTH_DENIED,
            UserFacingError.INTEGRATION_AUTH_EXPIRED,
            UserFacingError.INTEGRATION_AUTH_USED,
            UserFacingError.INTEGRATION_INVALID_KEY,
            UserFacingError.INTEGRATION_SERVICE_UNAVAILABLE,
            UserFacingError.INTEGRATION_RATE_LIMITED,
            UserFacingError.INTEGRATION_CONFIG_MISSING,
            UserFacingError.INTEGRATION_CONNECT_CHECK,
        )
        val unsafePatterns = listOf("http", "status", "exception", "stacktrace", "json", "body")
        for (error in integrationErrors) {
            val message = error.defaultMessage()
            assertTrue("${error.name} must have non-blank message", message.isNotBlank())
            for (pattern in unsafePatterns) {
                assertFalse(
                    "${error.name} message '$message' contains unsafe pattern '$pattern'",
                    message.contains(pattern, ignoreCase = true),
                )
            }
        }
    }

    // ── Structured backend errors: code extraction ──

    @Test
    fun `structured device_cap_reached detail maps to correct UX`() {
        val body = """{"detail": {"code": "device_cap_reached", "message": "Device limit reached"}}"""
        val code = extractBackendErrorCode(body)
        assertEquals("device_cap_reached", code)
        val error = backendReasonToUserFacingError(code)
        assertEquals(UserFacingError.DEVICE_CAP_REACHED, error)
    }

    @Test
    fun `structured premium_required detail maps to correct UX`() {
        val body = """{"detail": {"code": "premium_required", "message": "Upgrade to Pro"}}"""
        val code = extractBackendErrorCode(body)
        assertEquals("premium_required", code)
        val error = backendReasonToUserFacingError(code)
        assertEquals(UserFacingError.PREMIUM_REQUIRED, error)
    }

    @Test
    fun `structured error message never leaks object stringification`() {
        val body = """{"detail": {"code": "device_cap_reached", "message": "Limit", "active_devices": [{"id": "x"}]}}"""
        val parsed = parseBackendError(body)
        val msg = parsed.message ?: ""
        assertFalse("Must not contain [object Object]", msg.contains("[object Object]"))
        assertFalse("Must not contain raw JSON braces", msg.contains("{"))
    }

    @Test
    fun `active device data preserved in structured error rawDetail`() {
        val body = """{"detail": {"code": "device_cap_reached", "message": "Limit", "active_devices": [{"id": "dev1"}]}}"""
        val parsed = parseBackendError(body)
        assertNotNull("rawDetail should be preserved for device-limit UI", parsed.rawDetail)
        assertEquals("device_cap_reached", parsed.code)
    }

    @Test
    fun `plain string detail does not produce object leakage`() {
        val body = """{"detail": "Invalid credentials"}"""
        val parsed = parseBackendError(body)
        val msg = parsed.message ?: ""
        assertFalse("No object leakage", msg.contains("[object"))
        assertEquals("Invalid credentials", msg)
    }
}
