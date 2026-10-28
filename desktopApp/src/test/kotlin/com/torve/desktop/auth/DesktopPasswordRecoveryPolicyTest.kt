package com.torve.desktop.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPasswordRecoveryPolicyTest {
    @Test
    fun successfulRequestUsesNonEnumeratingConfirmation() {
        val state = DesktopAuthUiState(email = "viewer@example.com")
            .withPasswordResetResult(success = true, error = null)

        assertEquals(DesktopAuthPhase.LOGGED_OUT, state.phase)
        assertNull(state.authError)
        assertTrue(state.recoveryMessage.orEmpty().contains("If an account exists"))
    }

    @Test
    fun failedRequestKeepsAVisibleRecoverableError() {
        val state = DesktopAuthUiState(recoveryMessage = "old")
            .withPasswordResetResult(success = false, error = "Please enter a valid email address")

        assertEquals(DesktopAuthPhase.AUTH_ERROR, state.phase)
        assertEquals("Please enter a valid email address", state.authError)
        assertNull(state.recoveryMessage)
    }
}
