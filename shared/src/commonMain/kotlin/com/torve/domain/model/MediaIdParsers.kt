package com.torve.domain.model

private val trailingDelimitedDigitsRegex = Regex("""(?:^|[_:/-])(\d+)$""")
private val imdbIdRegex = Regex("""tt\d{6,}""")

fun String.extractTmdbIdOrNull(): Int? {
    val raw = trim()
    if (raw.isBlank()) return null

    raw.toIntOrNull()?.let { return it }

    val tmdbMarkerIndex = raw.lastIndexOf("tmdb:")
    if (tmdbMarkerIndex >= 0) {
        val candidate = raw.substring(tmdbMarkerIndex + "tmdb:".length)
            .takeWhile { it.isDigit() }
        if (candidate.isNotEmpty()) {
            return candidate.toIntOrNull()
        }
    }

    // Don't extract trailing digits from strings that contain an IMDB-style id —
    // "tt12345_1" would otherwise yield 1 (the season/episode suffix) and point
    // to the wrong TMDB entry.
    if (imdbIdRegex.containsMatchIn(raw)) return null

    return trailingDelimitedDigitsRegex.find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

/**
 * Extract an IMDB id ("tt" + 6+ digits) from any string form — raw id, "imdb:ttXXXX",
 * Stremio keys like "ttXXXX:1:3", or compound keys. Returns null if absent.
 */
fun String.extractImdbIdOrNull(): String? {
    val raw = trim()
    if (raw.isBlank()) return null
    return imdbIdRegex.find(raw)?.value
}
