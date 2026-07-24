package com.torve.android.ui.player

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.torve.android.player.ExoPlayerEngine
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState

data class ActivePlaybackSession(
    val url: String,
    val fallbackUrl: String = "",
    val autoSourceSelection: Boolean = false,
    val title: String,
    val mediaId: String = "",
    val mediaType: String = "movie",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val showTmdbId: Int? = null,
    val showImdbId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = true,
)

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
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while a video player is actively playing (not paused, not ended). */
    @Volatile
    var isPlaying: Boolean = false

    /** True while the activity is in Picture-in-Picture mode. Observable by Compose. */
    var isInPipMode by mutableStateOf(false)

    /** In-app playback that survives leaving the full-screen player route. */
    var session by mutableStateOf<ActivePlaybackSession?>(null)
        private set

    /** Lets TV key handling distinguish the player from retained background playback. */
    @Volatile
    var isFullScreenPlayerVisible: Boolean = false
        internal set

    /** Monotonic event observed by TV navigation to reopen retained playback. */
    var returnToPlayerRequestId by mutableStateOf(0L)
        private set

    private var retainedEngine: ExoPlayerEngine? = null
    private val retainedListener = object : PlayerListener {
        override fun onStateChanged(state: PlayerState) {
            isPlaying = state.isPlaying
            session = session?.copy(
                positionMs = state.positionMs.coerceAtLeast(0L),
                durationMs = state.durationMs.coerceAtLeast(0L),
                isPlaying = state.isPlaying,
            )
        }

        override fun onError(message: String) {
            // Defer removal and release until PlayerEngine finishes dispatching
            // the current listener callback.
            mainHandler.post {
                if (retainedEngine != null) stopAndClear()
            }
        }
    }

    internal fun takeRetainedEngine(url: String): ExoPlayerEngine? {
        val engine = retainedEngine ?: return null
        if (session?.url == url) {
            engine.removeListener(retainedListener)
            retainedEngine = null
            session = null
            isPlaying = engine.state.isPlaying
            return engine
        }
        stopAndClear()
        return null
    }

    internal fun retain(engine: ExoPlayerEngine, descriptor: ActivePlaybackSession) {
        // Full-screen callbacks close over a disposed navigation composition.
        // Background playback owns only the retained listener below.
        engine.onCodecError = null
        engine.onRecoverableSourceError = null
        if (retainedEngine !== engine) {
            retainedEngine?.let { old ->
                old.removeListener(retainedListener)
                runCatching { old.stop() }
                runCatching { old.release() }
            }
            retainedEngine = engine
            engine.addListener(retainedListener)
        }
        val state = engine.state
        session = descriptor.copy(
            positionMs = state.positionMs.coerceAtLeast(0L),
            durationMs = state.durationMs.coerceAtLeast(0L),
            isPlaying = state.isPlaying,
        )
        isPlaying = state.isPlaying
    }

    fun togglePlayback() {
        val engine = retainedEngine ?: return
        if (engine.state.isPlaying) engine.pause() else engine.resume()
    }

    fun requestReturnToPlayer() {
        if (session == null || isFullScreenPlayerVisible) return
        returnToPlayerRequestId += 1L
    }

    fun stopAndClear() {
        retainedEngine?.let { engine ->
            engine.removeListener(retainedListener)
            engine.onCodecError = null
            engine.onRecoverableSourceError = null
            runCatching { engine.stop() }
            runCatching { engine.release() }
        }
        retainedEngine = null
        session = null
        isPlaying = false
    }
}
