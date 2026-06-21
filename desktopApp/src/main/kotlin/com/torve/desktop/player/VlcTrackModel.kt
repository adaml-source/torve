package com.torve.desktop.player

import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.TrackDescription

/**
 * Maps vlcj track descriptions to Torve [DesktopTrackInfo].
 */
object VlcTrackModel {

    fun audioTracks(mediaPlayer: MediaPlayer): List<DesktopTrackInfo> {
        return mediaPlayer.audio().trackDescriptions()
            .filter { it.id() != -1 } // -1 is "Disable"
            .map { desc ->
                DesktopTrackInfo(
                    id = desc.id(),
                    label = desc.description() ?: "Track ${desc.id()}",
                    isSelected = mediaPlayer.audio().track() == desc.id(),
                )
            }
    }

    fun subtitleTracks(mediaPlayer: MediaPlayer): List<DesktopTrackInfo> {
        return mediaPlayer.subpictures().trackDescriptions()
            .filter { it.id() != -1 } // -1 is "Disable"
            .map { desc ->
                DesktopTrackInfo(
                    id = desc.id(),
                    label = desc.description() ?: "Track ${desc.id()}",
                    isSelected = mediaPlayer.subpictures().track() == desc.id(),
                )
            }
    }

    fun videoTracks(mediaPlayer: MediaPlayer): List<DesktopTrackInfo> {
        return mediaPlayer.video().trackDescriptions()
            .filter { it.id() != -1 }
            .map { desc ->
                DesktopTrackInfo(
                    id = desc.id(),
                    label = desc.description() ?: "Track ${desc.id()}",
                    isSelected = mediaPlayer.video().track() == desc.id(),
                )
            }
    }

    fun selectedAudioTrackLabel(mediaPlayer: MediaPlayer): String? {
        return audioTracks(mediaPlayer)
            .firstOrNull { it.isSelected }
            ?.label
    }

    fun selectedSubtitleTrackLabel(mediaPlayer: MediaPlayer): String? {
        return subtitleTracks(mediaPlayer)
            .firstOrNull { it.isSelected }
            ?.label
    }
}
