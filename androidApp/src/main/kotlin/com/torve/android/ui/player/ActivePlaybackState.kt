package com.torve.android.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Singleton tracking whether video playback is active.
 * Set by [PlayerScreen] composable, read by [com.torve.android.MainActivity]
 * to decide whether to enter Picture-in-Picture on user leave.
 *
 * [isPlaying] is @Volatile for synchronous reads from Activity lifecycle callbacks.
 * [isInPipMode] is a Compose [mutableStateOf] so PlayerScreen recomposes
 * automatically when PiP state changes (controls hide/show, immersive restore).
 */
object ActivePlaybackState {
    /** True while a video player is actively playing (not paused, not ended). */
    @Volatile
    var isPlaying: Boolean = false

    /** True while the activity is in Picture-in-Picture mode. Observable by Compose. */
    var isInPipMode by mutableStateOf(false)
}
