package com.torve.desktop.adult

/**
 * Parse a scene-style NZB release name into a (title, year) pair we can
 * feed to TMDB search. Releases follow loose conventions like:
 *
 *   `The.Movie.Title.2024.1080p.WEBRip.x264-GROUP`
 *   `Movie Title (2023) [BluRay] [1080p]`
 *   `Movie_Title.2022.MULTi.UHD.HDR.HEVC-RELEASE`
 *
 * Strategy: find the four-digit year (1900-2099), take everything to its
 * left as the title, normalise separators (`.`/`_`) into spaces, drop
 * trailing junk (square-bracketed scene tags, group suffix after a `-`).
 *
 * Year is a strong matching signal - TMDB queries with a `year=` filter
 * disambiguate same-title remakes - so when we can extract one we always
 * pass it along. Returns `null` if no year can be found, in which case
 * the release row falls back to text-only rendering.
 */
object NzbReleaseTitleParser {

    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val SEPARATORS = Regex("""[._]""")

    data class Parsed(val title: String, val year: Int)

    fun parse(release: String): Parsed? {
        val cleaned = release
            .substringBeforeLast(".nzb")
            .trim()
        val yearMatch = YEAR_REGEX.find(cleaned) ?: return null
        val year = yearMatch.value.toIntOrNull() ?: return null
        val rawTitle = cleaned.substring(0, yearMatch.range.first)
        val title = rawTitle
            .replace(SEPARATORS, " ")
            // Remove anything in brackets/parens - usually scene tags or
            // language hints we don't want in the search query.
            .replace(Regex("""[\[\(\{].*?[\]\)\}]"""), " ")
            // Trim any trailing dash-group, dash-language, etc.
            .trim()
            .trimEnd('-', ':', ',', '·', ' ')
            .trim()
        if (title.isBlank()) return null
        return Parsed(title = title, year = year)
    }
}
