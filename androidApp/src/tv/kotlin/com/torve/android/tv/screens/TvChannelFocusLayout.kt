package com.torve.android.tv.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/** Centre remote focus while scrolling; unpadded lists stop naturally at their ends. */
@OptIn(ExperimentalFoundationApi::class)
internal class TvChannelCenterBringIntoViewSpec(
    private val centerInViewport: Float? = null,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (containerSize <= 0f) return 0f
        if (size >= containerSize) return offset
        val center = (centerInViewport ?: (containerSize / 2f))
            .coerceIn(size / 2f, containerSize - size / 2f)
        return offset + size / 2f - center
    }
}
