package com.torve.data.addon

/** Optional native metadata reader. Resolving or downloading a source is never part of inspection. */
interface SourceTrackInspector {
    fun cached(stream: ParsedStream): ParsedStream
    fun canInspect(stream: ParsedStream): Boolean
    suspend fun inspect(stream: ParsedStream): ParsedStream
    fun associate(stream: ParsedStream, playbackUrl: String)
}

object SourceTrackLanguagePolicy {
    /** Browsing includes explicit release hints; inspecting a file can replace, never confirm, those hints. */
    fun matchesForBrowsing(stream: ParsedStream, language: String, allowSubtitles: Boolean): Boolean =
        SourceLanguages.matchesPreference(stream, language, allowSubtitles)

    fun matches(stream: ParsedStream, language: String, allowSubtitles: Boolean, readable: Boolean): Boolean =
        language.isEmpty() || ((stream.trackLanguagesVerified || !readable) &&
            SourceLanguages.matchesPreference(stream, language, allowSubtitles))
}
