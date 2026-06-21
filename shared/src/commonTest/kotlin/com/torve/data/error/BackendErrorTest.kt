package com.torve.data.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BackendErrorTest {

    // ── Plain string detail ──

    @Test
    fun parsesPlainStringDetail() {
        val body = """{"detail": "Invalid credentials"}"""
        val error = parseBackendError(body)
        assertNull(error.code)
        assertEquals("Invalid credentials", error.message)
    }

    // ── Structured object detail ──

    @Test
    fun parsesStructuredDetailWithCodeAndMessage() {
        val body = """{"detail": {"code": "device_cap_reached", "message": "Device limit reached"}}"""
        val error = parseBackendError(body)
        assertEquals("device_cap_reached", error.code)
        assertEquals("Device limit reached", error.message)
    }

    @Test
    fun parsesStructuredDetailWithCodeOnly() {
        val body = """{"detail": {"code": "premium_required"}}"""
        val error = parseBackendError(body)
        assertEquals("premium_required", error.code)
        assertNull(error.message)
    }

    @Test
    fun parsesStructuredDetailWithErrorCodeFallback() {
        val body = """{"detail": {"error_code": "device_required", "message": "raw backend text"}}"""
        val error = parseBackendError(body)
        assertEquals("device_required", error.code)
        assertEquals("raw backend text", error.message)
    }

    @Test
    fun parsesStructuredDetailWithMessageOnly() {
        val body = """{"detail": {"message": "Something happened"}}"""
        val error = parseBackendError(body)
        assertNull(error.code)
        assertEquals("Something happened", error.message)
    }

    @Test
    fun structuredDetailPreservesRawElement() {
        val body = """{"detail": {"code": "device_cap_reached", "message": "Limit", "active_devices": []}}"""
        val error = parseBackendError(body)
        assertNotNull(error.rawDetail)
        assertEquals("device_cap_reached", error.code)
    }

    @Test
    fun structuredDeviceCapDetailExtractsBackendLimit() {
        val body = """{"detail": {"code": "device_cap_reached", "message": "Limit", "max_devices": 20, "active_devices": 20}}"""
        val error = parseBackendError(body)

        assertEquals(20, error.maxDevices())
        assertEquals(20, error.activeDeviceCount())
        assertEquals(
            "You have reached your 20-device limit. Remove an existing device to continue.",
            error.deviceLimitReachedMessage(),
        )
    }

    // ── Validation array detail ──

    @Test
    fun parsesValidationArrayDetail() {
        val body = """{"detail": [{"msg": "Field required", "type": "missing"}, {"msg": "Invalid email"}]}"""
        val error = parseBackendError(body)
        assertNull(error.code)
        assertEquals("Field required; Invalid email", error.message)
    }

    @Test
    fun emptyValidationArrayGivesGenericMessage() {
        val body = """{"detail": []}"""
        val error = parseBackendError(body)
        assertEquals("Validation error", error.message)
    }

    // ── Malformed / edge cases ──

    @Test
    fun malformedJsonReturnsEmptyError() {
        val error = parseBackendError("not json at all")
        assertNull(error.code)
        assertNull(error.message)
    }

    @Test
    fun emptyObjectReturnsEmptyError() {
        val error = parseBackendError("{}")
        assertNull(error.code)
        assertNull(error.message)
    }

    @Test
    fun nullDetailReturnsEmptyError() {
        val error = parseBackendError("""{"detail": null}""")
        assertNull(error.code)
        assertNull(error.message)
    }

    // ── extractBackendErrorCode convenience ──

    @Test
    fun extractCodeFromStructuredDetail() {
        val body = """{"detail": {"code": "premium_required", "message": "Upgrade needed"}}"""
        assertEquals("premium_required", extractBackendErrorCode(body))
    }

    @Test
    fun extractCodeReturnsNullForPlainString() {
        val body = """{"detail": "Invalid credentials"}"""
        assertNull(extractBackendErrorCode(body))
    }

    // ── No [object Object] leakage ──

    @Test
    fun structuredDetailNeverProducesObjectObjectString() {
        val body = """{"detail": {"code": "device_cap_reached", "message": "Limit reached", "extra": {"nested": true}}}"""
        val error = parseBackendError(body)
        val message = error.message ?: ""
        assertFalse("[object Object]" in message, "Raw object stringification leaked: $message")
        assertFalse("{" in message, "Raw JSON leaked into message: $message")
    }

    @Test
    fun plainStringDetailNeverContainsJsonBraces() {
        val body = """{"detail": "Normal error message"}"""
        val error = parseBackendError(body)
        val message = error.message ?: ""
        assertFalse("{" in message, "Unexpected JSON in message: $message")
    }
}
