package com.torve.android.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/**
 * Media3 PlayerView can throw "Release should only be called once" when Compose
 * tears down or resets the AndroidView while the backing ExoPlayer is also being
 * released. Treat that as a stale view lifecycle event; the engine remains the
 * playback authority.
 */
class TorvePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr) {
    override fun onDetachedFromWindow() {
        try {
            super.onDetachedFromWindow()
        } catch (error: IllegalStateException) {
            if (error.isMedia3DuplicateRelease()) {
                Log.w(TAG, "Ignoring duplicate Media3 PlayerView detach release", error)
            } else {
                throw error
            }
        }
    }

    companion object {
        private const val TAG = "TorvePlayerView"
    }
}

fun PlayerView.setPlayerSafely(player: Player?, reason: String) {
    try {
        this.player = player
    } catch (error: IllegalStateException) {
        if (error.isMedia3DuplicateRelease()) {
            Log.w("TorvePlayerView", "Ignoring duplicate Media3 PlayerView setPlayer during $reason", error)
        } else {
            throw error
        }
    }
}

fun PlayerView.clearPlayerSafely(reason: String) {
    setPlayerSafely(null, reason)
}

private fun Throwable.isMedia3DuplicateRelease(): Boolean =
    this is IllegalStateException && message?.contains("Release should only be called once") == true
