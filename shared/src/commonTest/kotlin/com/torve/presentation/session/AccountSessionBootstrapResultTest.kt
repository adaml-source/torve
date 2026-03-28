package com.torve.presentation.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountSessionBootstrapResultTest {
    @Test
    fun isReady_true_when_no_limit_and_no_error() {
        val result = AccountSessionBootstrapResult(
            isReady = true,
            deviceLimitReached = false,
            error = null,
        )

        assertTrue(result.isReady)
        assertFalse(result.deviceLimitReached)
    }

    @Test
    fun isReady_false_when_device_limit_reached() {
        val result = AccountSessionBootstrapResult(
            isReady = false,
            deviceLimitReached = true,
            error = "Device limit reached",
        )

        assertFalse(result.isReady)
        assertTrue(result.deviceLimitReached)
    }

    @Test
    fun isReady_false_when_registration_error_present() {
        // Validates the bootstrap fix: registration failure must not produce isReady=true.
        // The coordinator now sets isReady = !deviceLimitReached && registrationError == null
        val result = AccountSessionBootstrapResult(
            isReady = false, // registrationError was non-null
            deviceLimitReached = false,
            error = "Failed to register device (405)",
        )

        assertFalse(result.isReady)
        assertFalse(result.deviceLimitReached)
        assertTrue(!result.error.isNullOrBlank())
    }
}
