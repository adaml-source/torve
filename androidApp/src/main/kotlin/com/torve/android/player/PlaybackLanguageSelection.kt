package com.torve.android.player

import com.torve.domain.player.PlaybackLanguagePolicy
import com.torve.domain.player.PlaybackTrackSelection
import com.torve.domain.player.TrackDescription

internal data class PlaybackLanguageSelection(val audioId: Int?, val subtitleId: Int?, val disableSubtitles: Boolean)

internal fun playbackLanguageSelection(
    audio: List<TrackDescription>, subtitles: List<TrackDescription>, language: String, allowSubtitleFallback: Boolean,
    rememberedAudio: String?, rememberedSubtitle: String?, preferredSubtitle: String?, subtitlesEnabled: Boolean,
    audioChosenManually: Boolean = false, subtitleChosenManually: Boolean = false,
): PlaybackLanguageSelection {
    val audioId = if (audioChosenManually) null else PlaybackLanguagePolicy.audioId(audio, language, rememberedAudio)
    val missingAudio = language.isNotEmpty() && audio.none { PlaybackTrackSelection.languageMatches(it.language, language) }
    if (subtitleChosenManually) return PlaybackLanguageSelection(audioId, null, false)
    // The selected fallback language outranks an old subtitle choice for a new playback session.
    val fallbackId = if (missingAudio && allowSubtitleFallback) subtitles.firstOrNull {
        PlaybackTrackSelection.languageMatches(it.language, language)
    }?.id else null
    val subtitleId = fallbackId ?: if (subtitlesEnabled) PlaybackTrackSelection.subtitleId(
        subtitles, rememberedSubtitle, preferredSubtitle, null) else null
    return PlaybackLanguageSelection(audioId, subtitleId, !subtitlesEnabled && fallbackId == null)
}
