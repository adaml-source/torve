package com.torve.desktop.library

/**
 * Heuristic parser for media filenames. Extracts a probable title, year,
 * and (for series) season/episode markers so we can hand a clean query
 * to TMDB.
 *
 * No attempt to be exhaustive - the rule set covers the common P2P /
 * release-group / Plex naming conventions:
 *
 *  - "The.Matrix.1999.1080p.BluRay.x264-FOO.mkv"
 *  - "Interstellar (2014).mp4"
 *  - "Avatar 2009.mkv"
 *  - "Breaking.Bad.S01E01.Pilot.720p.WEB-DL.mkv"  → series
 *  - "[Group] Show Name - 01 [1080p].mkv"
 *
 * Anything obviously wrong falls back to a cleaned filename - TMDB's
 * fuzzy search handles a lot of slop.
 */
object LibraryFilenameParser {

    data class Parsed(
        val title: String,
        val year: Int? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val isSeries: Boolean = false,
    )

    private val seriesPattern = Regex("""[Ss](\d{1,2})[._\s-]?[Ee](\d{1,3})""")
    private val yearPattern = Regex("""\b(19[5-9]\d|20[0-4]\d)\b""")
    private val releaseTagPattern = Regex(
        """\b(2160p|1080p|720p|480p|UHD|4K|HDR|HDR10|10bit|x264|x265|HEVC|AVC|AV1|BluRay|BRRip|Blu-ray|WEB-?DL|WEBRip|DVDRip|HDTV|REMUX|REPACK|PROPER|EXTENDED|UNRATED|DiRECTORS\.?CUT|IMAX|H\.?264|H\.?265|AAC|AC3|DTS|DD5\.?1|TrueHD|Atmos|MULTi|DUAL|SUBBED|DUBBED|YIFY|YTS|RARBG|FLUX|EVO|FGT|SPARKS|GECKOS)\b.*""",
        RegexOption.IGNORE_CASE,
    )
    private val groupTagPattern = Regex("""^\[[^\]]+\]\s*""")
    private val releaseGroupSuffix = Regex("""-[A-Za-z0-9]+$""")

    fun parse(rawName: String): Parsed {
        val withoutExt = rawName.substringBeforeLast('.', rawName)
        // Strip leading [Group Tag] prefixes used by anime releases.
        var working = withoutExt.replaceFirst(groupTagPattern, "")

        // Detect series marker first - splits the title from episode metadata.
        val seriesMatch = seriesPattern.find(working)
        val isSeries = seriesMatch != null
        val seasonNumber = seriesMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episodeNumber = seriesMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        if (seriesMatch != null) {
            working = working.substring(0, seriesMatch.range.first).trimEnd('.', ' ', '_', '-')
        }

        // Strip release tags and everything after the first one - those
        // are quality/codec/group markers, not part of the title.
        working = working.replace(releaseTagPattern, "").trim()
        working = working.replace(releaseGroupSuffix, "").trim()

        // Pull the year (most recent four-digit run that matches the
        // valid-year window). Strip it from the title.
        val yearMatch = yearPattern.find(working)
        val year = yearMatch?.value?.toIntOrNull()
        if (yearMatch != null) {
            working = (working.substring(0, yearMatch.range.first) +
                working.substring(yearMatch.range.last + 1)).trim()
        }

        // Normalise separators: dots, underscores, dashes and parens
        // become spaces, then collapse runs of whitespace.
        val title = working
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace("(", " ")
            .replace(")", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { withoutExt }

        return Parsed(
            title = title,
            year = year,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            isSeries = isSeries,
        )
    }
}
