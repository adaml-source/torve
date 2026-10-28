package com.torve.android.player

import android.os.Build
import android.view.Surface

/** Keep the exact source rate (including 23.976/29.97); release the hint on pause or teardown. */
internal class VideoFrameRateMatcher {
    private var previousSurface: Surface? = null
    private var previousRate = -1f
    fun apply(surface: Surface?, enabled: Boolean, frameRate: Float?, playing: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val rate = frameRate?.takeIf { enabled && playing && it.isFinite() && it in 10f..240f } ?: 0f
        if (previousSurface === surface && previousRate == rate) return
        if (previousSurface !== surface) clear()
        if (surface?.isValid != true) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            else surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
        previousSurface = surface; previousRate = rate
    }
    fun clear() {
        if (Build.VERSION.SDK_INT >= 30) previousSurface?.takeIf { it.isValid }?.let {
            runCatching { it.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
        previousSurface = null; previousRate = -1f
    }
}
