package com.torve.android.ui.player

internal object PlayerNavigationMath {
    private val tvProgressiveSkipStepsMs = longArrayOf(
        15_000L,
        30_000L,
        60_000L,
        5 * 60_000L,
        10 * 60_000L,
    )

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

    fun progressiveSkipStepMs(stepIndex: Int): Long {
        val clamped = stepIndex.coerceIn(0, tvProgressiveSkipStepsMs.lastIndex)
        return tvProgressiveSkipStepsMs[clamped]
    }

    fun nextProgressiveSkipStepIndex(
        previousDirection: Int,
        newDirection: Int,
        previousStepIndex: Int,
        previousPressAtMs: Long,
        nowMs: Long,
        resetWindowMs: Long,
    ): Int {
        val elapsed = (nowMs - previousPressAtMs).coerceAtLeast(0L)
        val inBurst = previousDirection == newDirection &&
            previousDirection != 0 &&
            elapsed <= resetWindowMs.coerceAtLeast(250L)
        return if (inBurst) {
            (previousStepIndex + 1).coerceAtMost(tvProgressiveSkipStepsMs.lastIndex)
        } else {
            0
        }
    }
}
