package com.torve.domain.player

import com.torve.data.addon.SourceLanguages
import com.torve.domain.model.SourceFallbackPolicy
import com.torve.domain.model.SourceLanguageMatchMode
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.UnknownSourceMetadataPolicy

object PlaybackLanguagePolicy {
    fun preferredLanguage(preference: String, appLanguage: String): String {
        val value = preference.trim()
        if (value.equals("any", true)) return ""
        return SourceLanguages.normalize(if (value.isEmpty() || value == "system") appLanguage else value.split(',', ';').first()) ?: ""
    }

    fun sourcePreferences(current: StreamPreferences, preference: String, appLanguage: String, allowSubtitles: Boolean): StreamPreferences {
        val language = preferredLanguage(preference, appLanguage)
        return current.copy(
            preferredAudioLanguages = listOf(language).filter(String::isNotEmpty),
            allowSubtitleLanguageFallback = allowSubtitles,
            sourceLanguageMatchMode = if (language.isEmpty()) current.sourceLanguageMatchMode else SourceLanguageMatchMode.REQUIRE,
            unknownSourceLanguagePolicy = if (language.isEmpty()) current.unknownSourceLanguagePolicy else UnknownSourceMetadataPolicy.REJECT,
            sourceFallbackPolicy = if (language.isNotEmpty() && current.sourceFallbackPolicy == SourceFallbackPolicy.BEST_AVAILABLE) SourceFallbackPolicy.ASK else current.sourceFallbackPolicy,
        )
    }

    /** Explicit per-session choices win in the player; new sessions start in the configured language. */
    fun audioId(tracks: List<TrackDescription>, preferredLanguage: String, rememberedTag: String?): Int? =
        tracks.firstOrNull { PlaybackTrackSelection.languageMatches(it.language, preferredLanguage) }?.id
            ?: PlaybackTrackSelection.audioId(tracks, rememberedTag, null)
}
