package com.torve.domain.player

/** The phone players use the same preference identity and language priority. */
object PlaybackTrackSelection {
    private fun remembered(tracks: List<TrackDescription>, tag: String): TrackDescription? =
        tracks.firstOrNull { preferenceTag(it.language, it.label) == tag } ?: tag.takeIf {
            Regex("[a-zA-Z]{2,3}(?:[-_][a-zA-Z0-9]{2,8})?").matches(it)
        }?.let { language -> tracks.firstOrNull { languageMatches(it.language, language) } }
    fun preferenceTag(language: String?, label: String): String =
        language?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: label.trim().lowercase().take(48)

    fun languageMatches(language: String?, preference: String): Boolean {
        val track = language?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return false
        val preferred = preference.trim().lowercase().takeIf(String::isNotEmpty) ?: return false
        if (track == preferred) return true
        val trackRoot = track.substringBefore('-').substringBefore('_')
        val preferredRoot = preferred.substringBefore('-').substringBefore('_')
        val canonicalTrack = com.torve.data.addon.SourceLanguages.normalize(track)
        val canonicalPreferred = com.torve.data.addon.SourceLanguages.normalize(preferred)
        if (canonicalTrack != null && canonicalPreferred != null) {
            return canonicalTrack.substringBefore('-') == canonicalPreferred.substringBefore('-')
        }
        return trackRoot == preferredRoot ||
            (trackRoot.length == 2 && preferredRoot.startsWith(trackRoot)) ||
            (preferredRoot.length == 2 && trackRoot.startsWith(preferredRoot))
    }

    fun subtitleId(tracks: List<TrackDescription>, rememberedTag: String?, preferredLanguage: String?,
                   missingAudioLanguage: String?): Int? {
        rememberedTag?.let { tag -> remembered(tracks, tag)?.let { return it.id } }
        listOfNotNull(preferredLanguage, missingAudioLanguage).forEach { language ->
            tracks.firstOrNull { languageMatches(it.language, language) }?.let { return it.id }
        }
        return null
    }

    fun audioId(tracks: List<TrackDescription>, rememberedTag: String?, preferredLanguage: String?): Int? {
        if (rememberedTag != null) return remembered(tracks, rememberedTag)?.id
        return preferredLanguage?.let { language -> tracks.firstOrNull { languageMatches(it.language, language) }?.id }
    }
}
