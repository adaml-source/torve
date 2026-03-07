package com.streamvault.android.ui.player

internal object PlayerNavigationMath {
    fun cyclicIndex(currentIndex: Int, size: Int, delta: Int): Int {
        if (size <= 0) return 0
        return ((currentIndex + delta) % size + size) % size
    }

    fun seekAccelerationMultiplier(repeatCount: Int): Long {
        return when {
            repeatCount >= 10 -> 8L
            repeatCount >= 6 -> 4L
            repeatCount >= 3 -> 2L
            else -> 1L
        }
    }
}
