package com.torve.domain.sports

data class SportsReleaseDisplay(
    val sportLabel: String,
    val leagueLabel: String?,
    val title: String,
    val dateLabel: String?,
    val roundLabel: String?,
    val qualityLabel: String?,
    val sourceLabel: String?,
    val codecLabel: String?,
    val releaseGroup: String?,
) {
    val qualitySourceLabel: String?
        get() = listOfNotNull(qualityLabel, sourceLabel).joinToString(" ").takeIf { it.isNotBlank() }
}

object SportsReleaseDisplayParser {
    fun parse(rawTitle: String, bucket: SportBucket = SportBucket.classify(rawTitle)): SportsReleaseDisplay {
        val releaseGroup = extractReleaseGroup(rawTitle)
        val titleWithoutGroup = releaseGroup?.let { rawTitle.removeSuffix("-$it") } ?: rawTitle
        val normalized = titleWithoutGroup
            .replace(Regex("[._]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val tokens = normalized.split(" ").filter { it.isNotBlank() }
        val league = detectLeague(tokens, bucket)
        val date = extractDate(rawTitle)
        val round = extractRound(tokens)
        val quality = QUALITY_REGEX.find(normalized)?.value?.normalizeQuality()
        val source = SOURCE_REGEX.find(normalized)?.value?.uppercase()
        val codec = CODEC_REGEX.find(normalized)?.value?.uppercase()
        val eventTitle = extractEventTitle(
            tokens = tokens,
            league = league,
            date = date,
            round = round,
            quality = quality,
            source = source,
            codec = codec,
        ).ifBlank { cleanedFallbackTitle(normalized) }

        return SportsReleaseDisplay(
            sportLabel = bucket.label,
            leagueLabel = league ?: bucket.label.takeUnless { bucket == SportBucket.OTHER },
            title = eventTitle,
            dateLabel = date?.label,
            roundLabel = round?.let { "Round $it" },
            qualityLabel = quality,
            sourceLabel = source,
            codecLabel = codec,
            releaseGroup = releaseGroup,
        )
    }

    private fun extractReleaseGroup(rawTitle: String): String? {
        val suffix = rawTitle.substringAfterLast('-', missingDelimiterValue = "")
            .trim()
        return suffix
            .takeIf { it.length in 2..32 && it.any { ch -> ch.isLetterOrDigit() } && !it.contains(' ') }
    }

    private fun detectLeague(tokens: List<String>, bucket: SportBucket): String? {
        val joined = tokens.joinToString(" ").lowercase()
        return when {
            "formula1" in joined || "formula 1" in joined || tokens.any { it.equals("f1", true) } -> "Formula 1"
            tokens.any { it.equals("mlb", true) } -> "MLB"
            tokens.any { it.equals("nba", true) } -> "NBA"
            tokens.any { it.equals("wnba", true) } -> "WNBA"
            tokens.any { it.equals("nfl", true) } -> "NFL"
            tokens.any { it.equals("nhl", true) } -> "NHL"
            tokens.any { it.equals("ufc", true) } -> "UFC"
            tokens.any { it.equals("mma", true) } -> "MMA"
            tokens.any { it.equals("wwe", true) } -> "WWE"
            tokens.any { it.equals("aew", true) } -> "AEW"
            bucket != SportBucket.OTHER -> bucket.label
            else -> null
        }
    }

    private fun extractDate(rawTitle: String): ParsedDate? {
        val match = DATE_REGEX.find(rawTitle) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val monthName = MONTHS.getOrNull(month - 1) ?: return null
        return ParsedDate(
            year = year,
            month = month,
            day = day,
            label = "$day $monthName $year",
        )
    }

    private fun extractRound(tokens: List<String>): Int? {
        tokens.forEachIndexed { index, token ->
            if (token.equals("round", ignoreCase = true)) {
                return tokens.getOrNull(index + 1)?.toIntOrNull()
            }
        }
        return null
    }

    private fun extractEventTitle(
        tokens: List<String>,
        league: String?,
        date: ParsedDate?,
        round: Int?,
        quality: String?,
        source: String?,
        codec: String?,
    ): String {
        if (tokens.isEmpty()) return ""
        val start = buildList {
            league?.split(" ")?.forEach { add(it) }
            date?.let {
                add(it.year.toString())
                add(it.month.toString().padStart(2, '0'))
                add(it.day.toString().padStart(2, '0'))
            }
        }
        var startIndex = 0
        while (startIndex < tokens.size && shouldSkipLeadingToken(tokens[startIndex], start, league)) {
            startIndex++
        }
        if (round != null) {
            val roundIndex = tokens.indexOfFirst { it.equals("round", ignoreCase = true) }
            if (roundIndex >= 0) {
                startIndex = maxOf(startIndex, roundIndex + 2)
            }
        }

        val stopIndex = tokens.indexOfFirstFrom(startIndex) { token ->
            token.equals(quality, ignoreCase = true) ||
                token.equals(source, ignoreCase = true) ||
                token.equals(codec, ignoreCase = true) ||
                TECH_TOKENS.any { token.equals(it, ignoreCase = true) }
        }.let { if (it == -1) tokens.size else it }

        return titleCase(
            tokens.subList(startIndex.coerceAtMost(tokens.size), stopIndex.coerceIn(startIndex, tokens.size))
                .filterNot { token -> TECH_TOKENS.any { token.equals(it, ignoreCase = true) } }
                .joinToString(" ")
                .replace(Regex("\\b(v|vs|versus)\\b", RegexOption.IGNORE_CASE), " vs ")
                .replace(Regex("\\s+"), " ")
                .trim(),
        )
    }

    private fun shouldSkipLeadingToken(token: String, start: List<String>, league: String?): Boolean {
        if (start.any { token.equals(it, ignoreCase = true) }) return true
        if (league == "Formula 1" && token.equals("Formula1", ignoreCase = true)) return true
        return false
    }

    private fun cleanedFallbackTitle(normalized: String): String =
        titleCase(
            normalized
                .replace(QUALITY_REGEX, " ")
                .replace(SOURCE_REGEX, " ")
                .replace(CODEC_REGEX, " ")
                .replace(Regex("\\s+"), " ")
                .trim(),
        )

    private fun titleCase(value: String): String =
        value.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                when {
                    word.equals("vs", ignoreCase = true) -> "vs"
                    word.length <= 4 && word == word.uppercase() && word.any { it.isLetter() } -> word.uppercase()
                    else -> word.lowercase().replaceFirstChar { it.uppercase() }
                }
            }

    private fun String.normalizeQuality(): String =
        when {
            equals("4k", ignoreCase = true) -> "4K"
            else -> lowercase().replaceFirstChar { it.uppercase() }
        }

    private fun List<String>.indexOfFirstFrom(startIndex: Int, predicate: (String) -> Boolean): Int {
        for (index in startIndex until size) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private data class ParsedDate(
        val year: Int,
        val month: Int,
        val day: Int,
        val label: String,
    )

    private val DATE_REGEX = Regex("\\b(20\\d{2})[._ -](\\d{2})[._ -](\\d{2})\\b")
    private val QUALITY_REGEX = Regex("\\b(2160p|1080p|720p|480p|4k|uhd|hdr)\\b", RegexOption.IGNORE_CASE)
    private val SOURCE_REGEX = Regex("\\b(WEB-DL|WEBRip|WEB|HDTV|BluRay|HDTVRip|IPTV)\\b", RegexOption.IGNORE_CASE)
    private val CODEC_REGEX = Regex("\\b(x264|x265|h264|h265|hevc|avc)\\b", RegexOption.IGNORE_CASE)
    private val TECH_TOKENS = setOf("web", "webrip", "web-dl", "hdtv", "bluray", "x264", "x265", "h264", "h265", "hevc", "avc")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
