package com.torve.desktop.playback

import java.io.File

/**
 * Scan the parent directory of [videoFile] for sibling subtitle files.
 *
 * Behaviour pinned by SiblingSubtitleScannerTest:
 *  - Matches a fixed extension allow-list (.srt, .vtt, .ass, .ssa, .sub).
 *  - Stem matching is case-insensitive (Windows / macOS HFS+ ship subtitles
 *    in any case - `Movie.mkv` should match `movie.en.srt`).
 *  - Files in other directories are never picked up.
 *  - Returns an empty list (not null) when nothing matches or the parent
 *    directory is unreadable.
 *  - The language tag is derived case-insensitively from the chars in the
 *    subtitle filename that follow the stem ("Movie.mkv" + "movie.en.srt"
 *    → language = "en"). Falls back to "sub" when there is no suffix.
 */
internal fun scanSiblingSubtitles(videoFile: File): List<DesktopPlaybackSubtitleCandidate> {
    val parent = videoFile.parentFile ?: return emptyList()
    val stem = videoFile.nameWithoutExtension
    val stemLength = stem.length
    val extensions = setOf("srt", "vtt", "ass", "ssa", "sub")
    val siblings = runCatching { parent.listFiles() }.getOrNull() ?: return emptyList()
    return siblings
        .asSequence()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in extensions }
        .filter { it.nameWithoutExtension.startsWith(stem, ignoreCase = true) }
        .sortedBy { it.nameWithoutExtension.lowercase() }
        .map { subFile ->
            // IMPORTANT: slice by length, not removePrefix - the prefix match is
            // case-insensitive but `removePrefix` is case-sensitive, which would
            // leave the original casing intact and pollute the language tag with
            // the start of the original filename.
            val rawName = subFile.nameWithoutExtension
            val tailSuffix = if (rawName.length >= stemLength) {
                rawName.substring(stemLength).trimStart('.', '-', '_', ' ')
            } else {
                ""
            }
            val language = tailSuffix.take(3).ifBlank { "sub" }
            DesktopPlaybackSubtitleCandidate(
                label = subFile.name,
                language = language,
                url = subFile.toURI().toString(),
            )
        }
        .toList()
}
