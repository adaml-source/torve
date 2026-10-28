package com.torve.data.channels

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XtreamAccountValidationTest {
    @Test
    fun unauthenticated_response_is_rejected_before_import() {
        val failure = assertFailsWith<XtreamAccountRejected> {
            requireActiveXtreamAccount(XtreamAuthInfo(false, "Unknown", serverUrl = "https://example.test"))
        }
        assertFalse(failure.inactive)
        assertFalse(failure.message.orEmpty().contains("example.test"))
    }

    @Test
    fun expired_disabled_and_banned_accounts_cannot_be_saved_as_working_playlists() {
        for (status in listOf("Expired", " disabled ", "BANNED", "Inactive")) {
            val failure = assertFailsWith<XtreamAccountRejected> {
                requireActiveXtreamAccount(XtreamAuthInfo(true, status, serverUrl = "https://example.test"))
            }
            assertTrue(failure.inactive)
        }
    }

    @Test
    fun authenticated_accounts_with_active_or_unspecified_status_are_accepted() {
        for (status in listOf("Active", "Unknown", "")) {
            requireActiveXtreamAccount(XtreamAuthInfo(true, status, serverUrl = "https://example.test"))
        }
    }
}
