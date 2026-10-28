package com.torve.domain.sports

import com.torve.util.DisplayTextDecoder
import kotlinx.datetime.LocalDate

data class SportsReleaseDate(val year: Int, val monthNumber: Int, val dayOfMonth: Int)

object SportsReleaseDateParser {
    private val yearFirst = Regex("(?<!\\d)(20\\d{2})[._ /-](\\d{1,2})[._ /-](\\d{1,2})(?!\\d)")
    private val dayFirst = Regex("(?<!\\d)(\\d{1,2})[._ /-](\\d{1,2})[._ /-](20\\d{2})(?!\\d)")
    private val longSeason = Regex("(?<!\\d)(20\\d{2})[-/](20\\d{2}|\\d{2})(?!\\d)")
    private val shortSeason = Regex("(?<!\\d)(\\d{2})[-/](\\d{2})(?!\\d)")

    fun eventDate(title: String): SportsReleaseDate? {
        val decodedTitle = DisplayTextDecoder.decodeReleaseTitle(title)
        yearFirst.findAll(decodedTitle).forEach { match ->
            date(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.let { return it }
        }
        dayFirst.findAll(decodedTitle).forEach { match ->
            date(match.groupValues[3], match.groupValues[2], match.groupValues[1])?.let { return it }
        }
        return null
    }

    // A season is not an exact match date. Its end year can still rule out an
    // obviously archived season from a recent feed, even when newly uploaded.
    fun seasonEndYear(title: String): Int? {
        val decodedTitle = DisplayTextDecoder.decodeReleaseTitle(title)
        if (eventDate(decodedTitle) != null) return null
        for (pattern in listOf(longSeason, shortSeason)) {
            pattern.findAll(decodedTitle).forEach { match ->
                val start = match.groupValues[1].toInt().let { if (it < 100) 2000 + it else it }
                val end = match.groupValues[2].toInt().let { if (it < 100) 2000 + it else it }
                if (end in start..start + 1) return end
            }
        }
        return null
    }

    private fun date(year: String, month: String, day: String): SportsReleaseDate? = runCatching {
        val valid = LocalDate(year.toInt(), month.toInt(), day.toInt())
        SportsReleaseDate(valid.year, valid.monthNumber, valid.dayOfMonth)
    }.getOrNull()
}
