package com.torve.android.player

import com.torve.domain.player.TrackDescription

/**
 * Pure helpers for subtitle track selection. Extracted from PlayerScreen so
 * that the language-matching and priority-selection logic can be unit
 * tested without pulling in the composable (and Media3) dependencies.
 *
 * These do not perform any engine I/O themselves — callers pass a
 * `selectTrack` lambda that performs the actual engine.selectSubtitleTrack
 * call (or records the intention, in tests).
 */

/**
 * Fuzzy match between a track's language metadata and the user's
 * preferred language string from Settings. Accepts differences in
 * casing, whitespace, and ISO 639-1 vs 639-2 prefixes (e.g. "en" vs
 * "eng"). Returns false for null/blank track language — tracks without
 * language metadata are never treated as matching a preference.
 */
internal fun subtitleLanguageMatches(trackLanguage: String?, preferredLowercased: String): Boolean {
    val track = trackLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    if (track == preferredLowercased) return true
    val trackRoot = track.substringBefore('-').substringBefore('_')
    val preferredRoot = preferredLowercased.substringBefore('-').substringBefore('_')
    if (trackRoot == preferredRoot) return true
    if (trackRoot.length == 2 && preferredRoot.startsWith(trackRoot)) return true
    if (preferredRoot.length == 2 && trackRoot.startsWith(preferredRoot)) return true
    return false
}

/**
 * Priority-based subtitle selection. Returns true if any track was
 * selected (so the caller knows the engine state is in sync with user
 * intent); false when no preference applied and selection was left to
 * the engine's default.
 *
 * Priority:
 * 1. Per-content remembered tag (user's last explicit pick on this title).
 * 2. Global preferredSubtitleLanguage from Settings.
 * 3. Audio-unavailable fallback: when `audioFallbackLanguage` is non-null,
 *    the caller has already determined the preferred audio language is
 *    missing from the stream, and wants a same-language sub enabled.
 */
internal fun selectPreferredSubtitle(
    subtitleTracks: List<TrackDescription>,
    perContentTag: String?,
    preferredSubtitleLanguage: String?,
    audioFallbackLanguage: String?,
    selectTrack: (Int) -> Unit,
): Boolean {
    if (subtitleTracks.isEmpty()) return false
    if (perContentTag != null) {
        subtitleTracks.firstOrNull { subtitleTrackPreferenceTag(it) == perContentTag }?.let { track ->
            if (!track.isSelected) selectTrack(track.id)
            return true
        }
    }
    if (preferredSubtitleLanguage != null) {
        subtitleTracks.firstOrNull { subtitleLanguageMatches(it.language, preferredSubtitleLanguage) }?.let { track ->
            if (!track.isSelected) selectTrack(track.id)
            return true
        }
    }
    if (audioFallbackLanguage != null) {
        subtitleTracks.firstOrNull { subtitleLanguageMatches(it.language, audioFallbackLanguage) }?.let { track ->
            if (!track.isSelected) selectTrack(track.id)
            return true
        }
    }
    return false
}

/**
 * Stable key used to remember a user's per-content track choice. Same
 * identity rule as `trackPreferenceTag` in PlayerScreen — keep them in
 * sync so that stored tags from a prior session still match.
 */
internal fun subtitleTrackPreferenceTag(track: TrackDescription): String {
    return track.language
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: track.label.trim().lowercase().take(48)
}
