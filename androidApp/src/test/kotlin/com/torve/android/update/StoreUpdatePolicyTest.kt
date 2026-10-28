package com.torve.android.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreUpdatePolicyTest {
    @Test fun aNewReleaseOverridesDismissalButTheSameReleaseWaitsADay() {
        val now = 500_000_000L
        assertFalse(StoreUpdatePolicy.shouldRemind(10161, 10161, now, now + 1))
        assertFalse(StoreUpdatePolicy.shouldRemind(10161, 10161, now, now + StoreUpdatePolicy.REMINDER_INTERVAL_MS - 1))
        assertTrue(StoreUpdatePolicy.shouldRemind(10161, 10161, now, now + StoreUpdatePolicy.REMINDER_INTERVAL_MS))
        assertTrue(StoreUpdatePolicy.shouldRemind(10162, 10161, now, now + 1))
        assertFalse(StoreUpdatePolicy.shouldRemind(0, 0, 0, now))
    }
    @Test fun changingTheClockCannotSuppressUpdatePromptsForever() {
        assertTrue(StoreUpdatePolicy.shouldRemind(10161, 10161, 100_000, 50_000))
    }
    @Test fun restartIsBlockedDuringEveryPlaybackModeIncludingAPausedFullScreenPlayer() {
        assertTrue(StoreUpdatePolicy.mayInstall(false, false, false, false))
        assertFalse(StoreUpdatePolicy.mayInstall(true, false, false, false))
        assertFalse(StoreUpdatePolicy.mayInstall(false, true, false, false))
        assertFalse(StoreUpdatePolicy.mayInstall(false, false, true, false))
        assertFalse(StoreUpdatePolicy.mayInstall(false, false, false, true))
    }
}
