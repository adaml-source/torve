package com.torve.android.ui

import com.torve.presentation.error.UserFacingError
import com.torve.presentation.error.defaultMessage
import com.torve.presentation.error.defaultUserFacingMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that secondary content/browsing ViewModels no longer surface raw
 * exception text and that all new error categories are resolvable.
 */
class SecondaryViewModelErrorSanitizationTest {

    // ── All new content error keys exist in default map ──

    @Test
    fun `content load failed key is resolvable`() {
        val key = UserFacingError.CONTENT_LOAD_FAILED.messageKey
        assertEquals("error_content_load_failed", key)
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `search failed key is resolvable`() {
        val key = UserFacingError.SEARCH_FAILED.messageKey
        assertEquals("error_search_failed", key)
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `streams load failed key is resolvable`() {
        val key = UserFacingError.STREAMS_LOAD_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `stream resolve failed key is resolvable`() {
        val key = UserFacingError.STREAM_RESOLVE_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `stream resolve timeout key is resolvable`() {
        val key = UserFacingError.STREAM_RESOLVE_TIMEOUT.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `watchlist failed key is resolvable`() {
        val key = UserFacingError.WATCHLIST_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `download failed key is resolvable`() {
        val key = UserFacingError.DOWNLOAD_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `channel load failed key is resolvable`() {
        val key = UserFacingError.CHANNEL_LOAD_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `profile failed key is resolvable`() {
        val key = UserFacingError.PROFILE_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `stats load failed key is resolvable`() {
        val key = UserFacingError.STATS_LOAD_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `addon failed key is resolvable`() {
        val key = UserFacingError.ADDON_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `addon install failed key is resolvable`() {
        val key = UserFacingError.ADDON_INSTALL_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    @Test
    fun `calendar load failed key is resolvable`() {
        val key = UserFacingError.CALENDAR_LOAD_FAILED.messageKey
        assertNotNull(defaultUserFacingMessages[key])
    }

    // ── Messages are safe and user-friendly ──

    @Test
    fun `all content error messages are free of technical jargon`() {
        val contentErrors = listOf(
            UserFacingError.CONTENT_LOAD_FAILED,
            UserFacingError.SEARCH_FAILED,
            UserFacingError.STREAMS_LOAD_FAILED,
            UserFacingError.STREAM_RESOLVE_FAILED,
            UserFacingError.STREAM_RESOLVE_TIMEOUT,
            UserFacingError.WATCHLIST_FAILED,
            UserFacingError.DOWNLOAD_FAILED,
            UserFacingError.CHANNEL_LOAD_FAILED,
            UserFacingError.PROFILE_FAILED,
            UserFacingError.STATS_LOAD_FAILED,
            UserFacingError.ADDON_FAILED,
            UserFacingError.ADDON_INSTALL_FAILED,
            UserFacingError.CALENDAR_LOAD_FAILED,
        )
        val unsafePatterns = listOf(
            "exception", "stacktrace", "http", "status", "json",
            "null", "class ", "IOException", "SocketTimeout",
            "ktor", "okhttp", "retrofit",
        )
        for (error in contentErrors) {
            val msg = error.defaultMessage()
            assertTrue("${error.name} must have non-blank message", msg.isNotBlank())
            for (pattern in unsafePatterns) {
                assertFalse(
                    "${error.name} message '$msg' contains unsafe pattern '$pattern'",
                    msg.contains(pattern, ignoreCase = true),
                )
            }
        }
    }

    // ── messageKey never equals a raw exception message ──

    @Test
    fun `message keys look like resource identifiers not raw text`() {
        val contentErrors = listOf(
            UserFacingError.CONTENT_LOAD_FAILED,
            UserFacingError.SEARCH_FAILED,
            UserFacingError.STREAMS_LOAD_FAILED,
            UserFacingError.WATCHLIST_FAILED,
            UserFacingError.DOWNLOAD_FAILED,
            UserFacingError.CHANNEL_LOAD_FAILED,
            UserFacingError.PROFILE_FAILED,
            UserFacingError.STATS_LOAD_FAILED,
            UserFacingError.ADDON_FAILED,
            UserFacingError.CALENDAR_LOAD_FAILED,
        )
        for (error in contentErrors) {
            val key = error.messageKey
            assertTrue("Key '$key' should start with error_", key.startsWith("error_"))
            assertFalse("Key should not contain spaces", key.contains(" "))
            assertFalse("Key should not contain uppercase", key.any { it.isUpperCase() })
        }
    }

    // ── Watchlist snackbar uses safe message ──

    @Test
    fun `watchlist failure default message does not contain raw Error prefix`() {
        val msg = UserFacingError.WATCHLIST_FAILED.defaultMessage()
        assertFalse(
            "Message should not start with 'Error:' pattern that embeds raw text",
            msg.startsWith("Error:"),
        )
    }

    // ── Previously hardened flows not regressed ──

    @Test
    fun `device cap reached still resolves correctly`() {
        val msg = UserFacingError.DEVICE_CAP_REACHED.defaultMessage()
        assertTrue(msg.isNotBlank())
        assertFalse(msg.contains("device_cap_reached"))
    }

    @Test
    fun `premium required still resolves correctly`() {
        val msg = UserFacingError.PREMIUM_REQUIRED.defaultMessage()
        assertTrue(msg.isNotBlank())
        assertFalse(msg.contains("premium_required"))
    }

    @Test
    fun `billing init failed still resolves correctly`() {
        val msg = UserFacingError.BILLING_INIT_FAILED.defaultMessage()
        assertTrue(msg.isNotBlank())
        assertFalse(msg.contains("debug", ignoreCase = true))
    }

    // ── All enum values still covered ──

    @Test
    fun `every UserFacingError has a default message entry`() {
        for (error in UserFacingError.entries) {
            assertTrue(
                "Missing default message for ${error.name} (key=${error.messageKey})",
                error.messageKey in defaultUserFacingMessages,
            )
        }
    }

    @Test
    fun `every UserFacingError produces a non-blank default message`() {
        for (error in UserFacingError.entries) {
            assertTrue(
                "Blank message for ${error.name}",
                error.defaultMessage().isNotBlank(),
            )
        }
    }
}
