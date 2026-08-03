package com.torve.android.ui.player

import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.torve.android.player.ExoPlayerEngine
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.domain.model.Channel
import java.util.Locale

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

    /** Monotonic event used by TV remotes to put focus back on the playback card. */
    var focusPlaybackBarRequestId by mutableStateOf(0L)
        private set

    var isLiveSession by mutableStateOf(false)
        private set

    var retainedLiveGroupName by mutableStateOf("")
        private set

    private var retainedEngine: ExoPlayerEngine? = null
    private val retainedListener = object : PlayerListener {
        override fun onStateChanged(state: PlayerState) {
            // Moving one ExoPlayer between the full-screen and preview surfaces
            // briefly reports isPlaying=false while playWhenReady stays true.
            // Preserve the user's play intent so the transfer is not presented
            // as a pause and the playback controls remain truthful while tuning.
            val playing = retainedEngine?.hasActivePlayIntent(state.isPlaying) == true
            isPlaying = playing
            session = session?.copy(
                positionMs = state.positionMs.coerceAtLeast(0L),
                durationMs = state.durationMs.coerceAtLeast(0L),
                isPlaying = playing,
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
            isLiveSession = false
            retainedLiveGroupName = ""
            isPlaying = engine.state.isPlaying
            return engine
        }
        stopAndClear()
        return null
    }

    /** Reuses the one retained decoder when returning to, or retuning, live TV. */
    internal fun takeRetainedLiveEngine(): ExoPlayerEngine? {
        if (!isLiveSession) return null
        val engine = retainedEngine ?: return null
        engine.removeListener(retainedListener)
        retainedEngine = null
        session = null
        isLiveSession = false
        retainedLiveGroupName = ""
        isPlaying = engine.state.isPlaying
        return engine
    }

    /** Player used by the Channels preview. It is never a second playback instance. */
    fun retainedLivePlayer(): androidx.media3.common.Player? =
        retainedEngine?.takeIf { isLiveSession }?.getExoPlayer()

    /**
     * Retunes background live TV in-place. Returns false when there is no
     * retained live session, allowing the caller to open full-screen normally.
     */
    fun retuneLive(channel: Channel): Boolean {
        val engine = retainedEngine ?: return false
        val current = session?.takeIf { isLiveSession } ?: return false
        val request = channel.toRetainedLiveRequest()
        if (request.url.isBlank()) return false
        if (request.headers.isNotEmpty()) engine.setNextRequestHeaders(request.headers)
        engine.play(request.url)
        session = current.copy(
            url = channel.url,
            title = channel.name,
            positionMs = 0L,
            durationMs = 0L,
            isPlaying = true,
        )
        retainedLiveGroupName = channel.groupTitle.orEmpty()
        isPlaying = true
        return true
    }

    internal fun retain(
        engine: ExoPlayerEngine,
        descriptor: ActivePlaybackSession,
        live: Boolean = false,
        liveGroupName: String = "",
    ) {
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
        val playing = engine.hasActivePlayIntent(state.isPlaying)
        session = descriptor.copy(
            positionMs = state.positionMs.coerceAtLeast(0L),
            durationMs = state.durationMs.coerceAtLeast(0L),
            isPlaying = playing,
        )
        isLiveSession = live
        retainedLiveGroupName = if (live) liveGroupName else ""
        isPlaying = playing
    }

    fun togglePlayback() {
        val engine = retainedEngine ?: return
        if (engine.hasActivePlayIntent(engine.state.isPlaying)) engine.pause() else engine.resume()
    }

    fun requestReturnToPlayer() {
        if (session == null || isFullScreenPlayerVisible) return
        returnToPlayerRequestId += 1L
    }

    fun requestPlaybackBarFocus() {
        if (session == null || isFullScreenPlayerVisible) return
        focusPlaybackBarRequestId += 1L
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
        isLiveSession = false
        retainedLiveGroupName = ""
        isPlaying = false
    }
}

private fun ExoPlayerEngine.hasActivePlayIntent(stateIsPlaying: Boolean): Boolean {
    val player = getExoPlayer()
    return stateIsPlaying || (
        player?.playWhenReady == true &&
            player.playbackState != androidx.media3.common.Player.STATE_ENDED
        )
}

private data class RetainedLiveRequest(
    val url: String,
    val headers: Map<String, String>,
)

private fun Channel.toRetainedLiveRequest(): RetainedLiveRequest {
    val raw = url.trim()
    val streamUrl = raw.substringBefore('|').trim()
    val headers = linkedMapOf<String, String>()
    userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it.trim() }
    vlcOptions.forEach { option ->
        val parts = option.split("=", limit = 2)
        if (parts.size != 2) return@forEach
        when (parts[0].trim().lowercase(Locale.ROOT)) {
            "http-user-agent" -> headers["User-Agent"] = parts[1].trim()
            "http-referrer", "http-referer" -> headers["Referer"] = parts[1].trim()
            "http-origin" -> headers["Origin"] = parts[1].trim()
        }
    }
    kodiProps.forEach { (key, value) ->
        when (key.trim().lowercase(Locale.ROOT)) {
            "inputstream.adaptive.stream_headers", "inputstream.adaptive.manifest_headers" ->
                headers.putAll(parseRetainedLiveHeaders(value))
            "inputstream.adaptive.user_agent" -> headers["User-Agent"] = value.trim()
        }
    }
    raw.substringAfter('|', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.let { headers.putAll(parseRetainedLiveHeaders(it)) }
    return RetainedLiveRequest(streamUrl, headers.filterValues { it.isNotBlank() })
}

private fun parseRetainedLiveHeaders(raw: String): Map<String, String> = buildMap {
    raw.split('&').forEach { pair ->
        val parts = pair.split("=", limit = 2)
        if (parts.size != 2) return@forEach
        val key = Uri.decode(parts[0]).trim()
        val value = Uri.decode(parts[1]).trim()
        val header = when (key.lowercase(Locale.ROOT)) {
            "user-agent", "user_agent", "http-user-agent" -> "User-Agent"
            "referer", "referrer", "http-referrer", "http-referer" -> "Referer"
            "origin", "http-origin" -> "Origin"
            "cookie" -> "Cookie"
            else -> key
        }
        if (header.isNotBlank() && value.isNotBlank()) put(header, value)
    }
}
