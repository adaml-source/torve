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
}
