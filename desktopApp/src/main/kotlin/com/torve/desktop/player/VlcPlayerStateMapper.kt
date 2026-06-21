package com.torve.desktop.player

import com.torve.desktop.playback.DesktopPlaybackEngineEvent
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * Installs vlcj event listeners and maps native VLC media player events
 * to [DesktopPlaybackEngineEvent] emissions.
 */
class VlcPlayerStateMapper(
    private val emit: (DesktopPlaybackEngineEvent) -> Unit,
) {
    var lastMediaPath: String? = null
        private set
    var lastHeaderCount: Int = 0
        private set
    var lastSubtitleLabel: String? = null
        private set
    var lastBufferingModeLabel: String? = null
        private set

    fun updateContext(
        mediaPath: String,
        headerCount: Int,
        subtitleLabel: String?,
        bufferingModeLabel: String?,
    ) {
        lastMediaPath = mediaPath
        lastHeaderCount = headerCount
        lastSubtitleLabel = subtitleLabel
        lastBufferingModeLabel = bufferingModeLabel
    }

    fun createEventAdapter(): MediaPlayerEventAdapter = object : MediaPlayerEventAdapter() {

        override fun opening(mediaPlayer: MediaPlayer) {
            emit(
                DesktopPlaybackEngineEvent.Opening(
                    mediaPath = lastMediaPath.orEmpty(),
                    autoPlay = true,
                    appliedHeaderCount = lastHeaderCount,
                    selectedSubtitleLabel = lastSubtitleLabel,
                    bufferingModeLabel = lastBufferingModeLabel,
                ),
            )
        }

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            if (newCache in 0f..<100f) {
                emit(
                    DesktopPlaybackEngineEvent.Buffering(
                        mediaPath = lastMediaPath,
                        cachePercent = newCache.coerceIn(0f, 100f),
                    ),
                )
            }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            emit(DesktopPlaybackEngineEvent.Playing(lastMediaPath))
        }

        override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
            if (newCount > 0) {
                emit(DesktopPlaybackEngineEvent.FirstFrame(lastMediaPath))
            }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            emit(DesktopPlaybackEngineEvent.Paused(lastMediaPath))
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            emit(DesktopPlaybackEngineEvent.Stopped("Playback stopped."))
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            emit(DesktopPlaybackEngineEvent.Stopped("Playback finished."))
        }

        override fun error(mediaPlayer: MediaPlayer) {
            emit(
                DesktopPlaybackEngineEvent.Error(
                    code = "VLC_PLAYBACK_ERROR",
                    message = "VLC encountered a playback error.",
                    recoverable = true,
                ),
            )
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            val duration = mediaPlayer.status().length()
            emit(
                DesktopPlaybackEngineEvent.Position(
                    positionSeconds = newTime / 1000.0,
                    durationSeconds = if (duration > 0) duration / 1000.0 else null,
                ),
            )
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            emit(
                DesktopPlaybackEngineEvent.Position(
                    positionSeconds = null,
                    durationSeconds = newLength / 1000.0,
                ),
            )
        }
    }
}
