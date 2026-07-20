package com.torve.desktop.player

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.net.URI

object VlcDiagnostics {

    fun snapshot(
        mediaPlayer: MediaPlayer?,
        factory: MediaPlayerFactory? = null,
        engineName: String = "LibVLC",
        runtimePath: String? = null,
        bufferingModeLabel: String? = null,
    ): DesktopPlayerDiagnostics {
        if (mediaPlayer == null) {
            return DesktopPlayerDiagnostics(
                engineName = engineName,
                vlcVersion = factory?.application()?.version(),
                runtimePath = runtimePath,
                playbackState = "Idle",
                sourceType = null,
                videoCodec = null,
                audioCodec = null,
                resolution = null,
                fps = null,
                bitrate = null,
                sampleRate = null,
                audioChannels = null,
                cacheLevel = null,
                bufferingMode = bufferingModeLabel,
                playbackSpeed = 1f,
                volume = 100,
                isMuted = false,
                audioDelay = 0,
                subtitleDelay = 0,
                aspectMode = null,
                mediaUrl = null,
                durationMs = 0,
                currentTimeMs = 0,
                audioTrack = null,
                subtitleTrack = null,
            )
        }

        val mediaInfo = mediaPlayer.media()?.info()
        val videoTrack = mediaInfo?.videoTracks()?.firstOrNull()
        val audioTrack = mediaInfo?.audioTracks()?.firstOrNull()
        val statistics = mediaInfo?.statistics()
        val mediaUrl = mediaInfo?.mrl()

        return DesktopPlayerDiagnostics(
            engineName = engineName,
            vlcVersion = factory?.application()?.version(),
            runtimePath = runtimePath,
            playbackState = formatPlaybackState(mediaPlayer),
            sourceType = sourceTypeLabel(mediaUrl),
            videoCodec = videoTrack?.codecDescription(),
            audioCodec = audioTrack?.codecDescription(),
            resolution = videoTrack?.let { "${it.width()}x${it.height()}" },
            fps = videoTrack?.let {
                val rate = it.frameRate()
                val rateBase = it.frameRateBase()
                if (rateBase > 0) String.format("%.2f", rate.toDouble() / rateBase) else null
            },
            bitrate = when {
                videoTrack?.bitRate()?.takeIf { it > 0 } != null -> "${videoTrack.bitRate() / 1000} kbps"
                statistics?.inputBitrate()?.takeIf { it > 0 } != null -> "${(statistics.inputBitrate() * 1000).toInt()} kbps"
                else -> null
            },
            sampleRate = audioTrack?.rate()?.takeIf { it > 0 }?.let { "$it Hz" },
            audioChannels = audioTrack?.channels()?.toString(),
            cacheLevel = statistics?.inputBitrate()?.takeIf { it > 0 }?.let { String.format("%.2f Mbps", it) },
            bufferingMode = bufferingModeLabel,
            playbackSpeed = mediaPlayer.status().rate(),
            volume = mediaPlayer.audio().volume(),
            isMuted = mediaPlayer.audio().isMute,
            audioDelay = mediaPlayer.audio().delay() / 1000,
            subtitleDelay = mediaPlayer.subpictures().delay() / 1000,
            aspectMode = mediaPlayer.video().aspectRatio(),
            mediaUrl = mediaUrl,
            durationMs = mediaPlayer.status().length().coerceAtLeast(0),
            currentTimeMs = mediaPlayer.status().time().coerceAtLeast(0),
            audioTrack = VlcTrackModel.selectedAudioTrackLabel(mediaPlayer),
            subtitleTrack = VlcTrackModel.selectedSubtitleTrackLabel(mediaPlayer),
        )
    }

    private fun formatPlaybackState(mediaPlayer: MediaPlayer): String {
        val state = mediaPlayer.status().state().name.lowercase()
        return state.replaceFirstChar { it.titlecase() }
    }

    private fun sourceTypeLabel(mediaUrl: String?): String? {
        if (mediaUrl.isNullOrBlank()) return null
        return runCatching {
            when (URI(mediaUrl).scheme?.lowercase()) {
                "http", "https" -> "Network Stream"
                "rtsp" -> "RTSP Stream"
                "rtmp" -> "RTMP Stream"
                "file" -> "Local File"
                "udp" -> "UDP Stream"
                "mms" -> "MMS Stream"
                else -> mediaUrl.substringBefore(':').ifBlank { "Unknown Source" }
            }
        }.getOrDefault("Unknown Source")
    }
}
