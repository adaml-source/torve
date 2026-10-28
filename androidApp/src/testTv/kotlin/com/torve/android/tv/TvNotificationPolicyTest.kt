package com.torve.android.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvNotificationPolicyTest {
    @Test
    fun unrelatedTaggedClearDoesNotDismissVisibleNotification() {
        val active = TvNotification(
            message = "No playable source found",
            type = NotificationType.ERROR,
            tag = "tv_source_error",
        )

        assertFalse(
            shouldClearTvNotification(
                active = active,
                clearRequest = TvNotification(message = "", tag = "tv_stream_resolving", clear = true),
            ),
        )
    }

    @Test
    fun matchingOrGlobalClearDismissesVisibleNotification() {
        val active = TvNotification(message = "Error", tag = "tv_source_error")

        assertTrue(
            shouldClearTvNotification(
                active = active,
                clearRequest = TvNotification(message = "", tag = "tv_source_error", clear = true),
            ),
        )
        assertTrue(
            shouldClearTvNotification(
                active = active,
                clearRequest = TvNotification(message = "", clear = true),
            ),
        )
    }
}
