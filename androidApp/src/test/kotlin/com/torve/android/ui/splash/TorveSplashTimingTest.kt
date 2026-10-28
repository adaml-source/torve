package com.torve.android.ui.splash

import org.junit.Assert.assertTrue
import org.junit.Test

class TorveSplashTimingTest {

    @Test
    fun splashAnimationBudgetStaysBelowTwoSeconds() {
        assertTrue(TorveSplashTiming.EXPECTED_TOTAL_MS in 1_000L..1_800L)
    }

    @Test
    fun taglineCanFinishBeforeHoldEnds() {
        assertTrue(
            TorveSplashTiming.TAGLINE_DELAY_MS + TorveSplashTiming.TAGLINE_FADE_MS <=
                TorveSplashTiming.HOLD_MS,
        )
    }
}
