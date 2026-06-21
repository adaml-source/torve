package com.torve.android.player

import android.content.Context
import androidx.media3.common.util.UnstableApi

/**
 * Selects the best available player engine.
 * Prefers libmpv if available, falls back to ExoPlayer.
 */
object PlayerFactory {

    enum class EngineType { MPV, EXOPLAYER }

    @UnstableApi
    fun create(context: Context): Pair<EngineType, Any> {
        // Try MPV first
        val mpvEngine = MPVPlayerEngine(context)
        if (mpvEngine.initialize()) {
            return EngineType.MPV to mpvEngine
        }

        // Fallback to ExoPlayer
        val exoEngine = ExoPlayerEngine(context)
        exoEngine.initialize()
        return EngineType.EXOPLAYER to exoEngine
    }

    fun isLibmpvAvailable(): Boolean = MPVLib.tryLoad()
}
