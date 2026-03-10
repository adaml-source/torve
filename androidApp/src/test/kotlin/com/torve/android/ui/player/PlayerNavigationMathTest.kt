package com.torve.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerNavigationMathTest {

    @Test
    fun cyclicIndex_wrapsBothDirections() {
        assertEquals(4, PlayerNavigationMath.cyclicIndex(currentIndex = 0, size = 5, delta = -1))
        assertEquals(0, PlayerNavigationMath.cyclicIndex(currentIndex = 4, size = 5, delta = 1))
        assertEquals(2, PlayerNavigationMath.cyclicIndex(currentIndex = 0, size = 5, delta = 7))
    }

    @Test
    fun seekAccelerationMultiplier_stepsUpAsRepeatsGrow() {
        assertEquals(1L, PlayerNavigationMath.seekAccelerationMultiplier(0))
        assertEquals(1L, PlayerNavigationMath.seekAccelerationMultiplier(2))
        assertEquals(2L, PlayerNavigationMath.seekAccelerationMultiplier(3))
        assertEquals(4L, PlayerNavigationMath.seekAccelerationMultiplier(6))
        assertEquals(8L, PlayerNavigationMath.seekAccelerationMultiplier(10))
    }

    @Test
    fun progressiveSkipStepMs_matchesTvSequence() {
        assertEquals(15_000L, PlayerNavigationMath.progressiveSkipStepMs(0))
        assertEquals(30_000L, PlayerNavigationMath.progressiveSkipStepMs(1))
        assertEquals(60_000L, PlayerNavigationMath.progressiveSkipStepMs(2))
        assertEquals(5 * 60_000L, PlayerNavigationMath.progressiveSkipStepMs(3))
        assertEquals(10 * 60_000L, PlayerNavigationMath.progressiveSkipStepMs(4))
        assertEquals(10 * 60_000L, PlayerNavigationMath.progressiveSkipStepMs(99))
    }

    @Test
    fun nextProgressiveSkipStepIndex_resetsAndAdvancesByBurst() {
        assertEquals(
            0,
            PlayerNavigationMath.nextProgressiveSkipStepIndex(
                previousDirection = 0,
                newDirection = 1,
                previousStepIndex = 0,
                previousPressAtMs = 0L,
                nowMs = 1_000L,
                resetWindowMs = 1_500L,
            ),
        )
        assertEquals(
            1,
            PlayerNavigationMath.nextProgressiveSkipStepIndex(
                previousDirection = 1,
                newDirection = 1,
                previousStepIndex = 0,
                previousPressAtMs = 1_000L,
                nowMs = 2_000L,
                resetWindowMs = 1_500L,
            ),
        )
        assertEquals(
            0,
            PlayerNavigationMath.nextProgressiveSkipStepIndex(
                previousDirection = 1,
                newDirection = -1,
                previousStepIndex = 2,
                previousPressAtMs = 2_000L,
                nowMs = 2_300L,
                resetWindowMs = 1_500L,
            ),
        )
        assertEquals(
            0,
            PlayerNavigationMath.nextProgressiveSkipStepIndex(
                previousDirection = 1,
                newDirection = 1,
                previousStepIndex = 2,
                previousPressAtMs = 2_000L,
                nowMs = 5_000L,
                resetWindowMs = 1_500L,
            ),
        )
    }
}
