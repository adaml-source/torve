package com.torve.desktop.adult

/**
 * Parse a scene-style TV release name into the show title we can hand
 * to TMDB. Scene patterns vary widely, but they always follow a
 * predictable shape:
 *
 *   `Show.Name.S01E02.1080p.WEB.x264-GROUP`
 *   `Show.Name.S01.COMPLETE.MULTi.2160p.UHD.HDR.HEVC-GROUP`
 *   `Show.Name.2024.S01E02.1080p.x264-GROUP`
 *   `Show Name (2024) S01E02 [WEB-DL]`
 *
 * Strategy: anchor on the first `S\d{2}(E\d{2})?` token, take the
 * substring before it as the show title, normalise separators and
 * brackets.  An optional release year that appears before the season
 * tag is captured separately so TMDB matches can disambiguate
 * remakes (e.g. "Doctor Who 1963" vs "Doctor Who 2005").
 */
object NzbTvReleaseTitleParser {

    private val SEASON_REGEX = Regex("""\bS(\d{1,2})(?:E(\d{1,3}))?\b""", RegexOption.IGNORE_CASE)
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val SEPARATORS = Regex("""[._]""")

    data class Parsed(
        val showTitle: String,
        val year: Int? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    )

    fun parse(release: String): Parsed? {
        val cleaned = release.substringBeforeLast(".nzb").trim()
        val seasonMatch = SEASON_REGEX.find(cleaned) ?: return null
        val rawTitle = cleaned.substring(0, seasonMatch.range.first)
        val title = rawTitle
            .replace(SEPARATORS, " ")
            .replace(Regex("""[\[\(\{].*?[\]\)\}]"""), " ")
            .trim()
            .trimEnd('-', ':', ',', '·', ' ')
            .trim()
        if (title.isBlank()) return null

        // Year may sit either inside the title prefix (e.g. "Show 2024 S01E01")
        // or after the season tag. Inside-title disambiguates better for
        // TMDB so check there first.
        val titleYear = YEAR_REGEX.find(title)?.value?.toIntOrNull()
        // Strip the trailing year from the title so the TMDB query is
        // clean. ("Show 2024" → "Show".)
        val titleWithoutYear = if (titleYear != null) {
            title.replace(YEAR_REGEX, "").trim().trimEnd('-', ':', ',', '·', ' ').trim()
        } else title

        val season = seasonMatch.groupValues[1].toIntOrNull()
        val episode = seasonMatch.groupValues.getOrNull(2)?.toIntOrNull()
        return Parsed(
            showTitle = titleWithoutYear,
            year = titleYear,
            seasonNumber = season,
            episodeNumber = episode,
        )
    }
}
