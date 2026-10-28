package com.torve.data.replays

import io.ktor.http.*
import kotlinx.serialization.json.*

data class ReplayHighlight(val id: String, val title: String, val channel: String, val durationSeconds: Int, val publishedLabel: String = "", val isOfficial: Boolean = false,
    val matchupLabel: String = "", val eventDate: String = "", val competitionLabel: String = "") {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
    val durationLabel: String get() = if (durationSeconds >= 3600)
        "${durationSeconds / 3600}:${(durationSeconds / 60 % 60).toString().padStart(2, '0')}:${(durationSeconds % 60).toString().padStart(2, '0')}"
        else "${durationSeconds / 60}:${(durationSeconds % 60).toString().padStart(2, '0')}"
    val lengthLabel: String get() = if (durationSeconds < 300) "Short highlights" else if (durationSeconds >= 600) "Extended highlights" else "Highlights"
    /** Whitelisted coverage words retain useful context without the uploader's score or headline. */
    val contentLabel: String get() = ReplayHighlightLabels.coverage(title, durationSeconds)
    val contextLabel: String get() = listOf(competitionLabel, eventDate).filter { it.isNotBlank() }.joinToString(" · ")
    val spoilerFreeTitle: String get() = listOf(matchupLabel, "$contentLabel · $durationLabel").filter { it.isNotBlank() }.joinToString(" — ")
}

/** Public YouTube metadata only. Playback uses YouTube's player; no stream extraction or replay progress. */
object ReplayHighlights {
    internal data class Channel(val name: String, val id: String)
    internal fun channel(event: ReplayEvent): Channel? {
        val text = "${event.competition} ${event.title}".lowercase()
        return when {
            "euroleague" in text -> Channel("EuroLeague", "UCGr3nR_XH9r6E5b09ZJAT9w")
            Regex("\\bnfl\\b").containsMatchIn(text) -> Channel("NFL", "UCDVYQ4Zhbm3S2dlz7P1GBDg")
            Regex("\\bnba\\b").containsMatchIn(text) -> Channel("NBA", "UCWJ2lWNubArHWmf3FIHbfcQ")
            Regex("\\bf1\\b|formula\\s*1|grand prix").containsMatchIn(text) -> Channel("FORMULA 1", "UCB_qr75-ydFVKSF9Dmo6izg")
            Regex("\\bnhl\\b").containsMatchIn(text) -> Channel("NHL", "UCqFMzb-4AUf6WAIbl132QKA")
            Regex("\\bmlb\\b").containsMatchIn(text) -> Channel("MLB", "UCoLrcjPV5PbUrUyXq5mjc_A")
            else -> null
        }
    }
    fun channelName(event: ReplayEvent): String = channel(event)?.name ?: "YouTube"
    private fun officialChannels(event: ReplayEvent): Map<String, String> = buildMap {
        channel(event)?.let { put(it.id, it.name) }
        if (channel(event)?.name == "NBA") {
            put("UC5qJUyng_ezl0TVjVJFqtfQ", "Philadelphia 76ers")
            put("UCRZDEVva3Z8h_Q0VetTgDUA", "Milwaukee Bucks")
            put("UCVSSpcmZD2PwPBqb8yKQKBA", "NBA on ESPN")
        }
    }
    internal fun query(event: ReplayEvent, game: String?): String {
        val title = ReplayHighlightIdentity.searchNames(event, game)
            .replace(Regex("(?i)\\b(?:" + months.joinToString("|") + ")\\s+\\d{1,2},?\\s+20\\d{2}\\b"), " ")
            .replace(Regex("\\b20\\d{2}-\\d{2}-\\d{2}\\b"), " ")
            .replace(Regex("full[ -]*(game|match|race|event)|replays?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(vs|basketball|basket|nba|nfl|nhl|mlb|euroleague)\\b\\.?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s*[-|]+\\s*"), " ")
            .replace(Regex("\\s+"), " ").trim().take(180)
        val date = runCatching { kotlinx.datetime.LocalDate.parse(event.date) }.getOrNull()
        val searchDate = date?.let { "${months[it.monthNumber - 1]} ${it.dayOfMonth} ${it.year}" }
        return listOf(title, searchDate, "highlights").filterNotNull().joinToString(" ")
    }
    fun searchUrl(event: ReplayEvent, game: String? = null): String {
        val official = channel(event)
        return URLBuilder(if (official != null) "https://www.youtube.com/channel/${official.id}/search" else "https://www.youtube.com/results").apply {
            parameters.append(if (official != null) "query" else "search_query", query(event, game))
            parameters.append("hl", "en")
        }.buildString()
    }

    internal fun searchUrls(event: ReplayEvent, game: String?): List<String> {
        val official = channel(event) ?: return emptyList()
        val names = ReplayHighlightIdentity.searchNames(event, game)
        if (names.isBlank()) return emptyList()
        fun url(query: String, channelOnly: Boolean) = URLBuilder(if (channelOnly)
            "https://www.youtube.com/channel/${official.id}/search" else "https://www.youtube.com/results").apply {
            parameters.append(if (channelOnly) "query" else "search_query", query)
            parameters.append("hl", "en")
        }.buildString()
        // Short team names catch abbreviations and sponsor-name changes. A year-only
        // variant finds uploads without a literal date in the title; the result
        // still has to pass participant, date and official-channel verification.
        return listOf(searchUrl(event, game), url("$names highlights ${event.date.take(4)}", true),
            url("${official.name} ${query(event, game)}", false)).distinct()
    }

    // Channel IDs are checked, rather than trusting a display name or an 'official' word in a title.
    internal fun parse(html: String, event: ReplayEvent, game: String?, nowMs: Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()): List<ReplayHighlight> {
        val official = channel(event) ?: return emptyList()
        val officialIds = officialChannels(event)
        val data = initialData(html) ?: return emptyList()
        val result = mutableListOf<ReplayHighlight>()
        var visited = 0
        fun text(value: JsonElement?): String = (value as? JsonObject)?.let { obj ->
            obj["simpleText"]?.jsonPrimitive?.contentOrNull ?: (obj["runs"] as? JsonArray)?.joinToString("") {
                (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }.orEmpty()
        fun visit(value: JsonElement, depth: Int) {
            if (depth > 35 || ++visited > 25_000 || result.size >= 48) return
            when (value) {
                is JsonArray -> value.forEach { visit(it, depth + 1) }
                is JsonObject -> {
                    val video = value["videoRenderer"] as? JsonObject
                    if (video != null) {
                        val id = video["videoId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val title = text(video["title"])
                        val byline = video["ownerText"] ?: video["shortBylineText"] ?: video["longBylineText"]
                        val runs = (byline as? JsonObject)?.get("runs") as? JsonArray
                        val channelId = runs?.firstNotNullOfOrNull { run ->
                            (((run as? JsonObject)?.get("navigationEndpoint") as? JsonObject)?.get("browseEndpoint") as? JsonObject)
                                ?.get("browseId")?.jsonPrimitive?.contentOrNull
                        }
                        val seconds = duration(text(video["lengthText"]))
                        val published = text(video["publishedTimeText"])
                        val uploader = text(byline).trim()
                        val description = text(video["descriptionSnippet"]) + " " +
                            ((video["detailedMetadataSnippets"] as? JsonArray)?.joinToString(" ") { text((it as? JsonObject)?.get("snippetText")) }.orEmpty())
                        val confirmedOfficial = channelId in officialIds
                        val validCreator = channelId?.matches(Regex("UC[A-Za-z0-9_-]{22}")) == true &&
                            uploader.isNotBlank() && officialIds.values.none { uploader.equals(it, true) }
                        if (id.matches(Regex("[A-Za-z0-9_-]{11}")) && (confirmedOfficial || validCreator) && seconds != null &&
                            seconds in 30..10800 && !ReplayHighlightLabels.isSimulation(title, uploader, description, confirmedOfficial) &&
                            matchesEvent(title, event, game) && publicationMatches(title, published, event, nowMs))
                            result += ReplayHighlight(id, title, officialIds[channelId] ?: uploader.take(120), seconds, published, confirmedOfficial,
                                ReplayHighlightIdentity.displayName(event, game), event.date, official.name)
                    } else value.values.forEach { visit(it, depth + 1) }
                }
                else -> Unit
            }
        }
        visit(data, 0)
        return result.distinctBy { it.id }.sortedWith(compareByDescending<ReplayHighlight> { it.isOfficial }.thenBy { it.durationSeconds }).take(12)
    }
    internal fun duration(value: String): Int? {
        if (!value.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?"))) return null
        val parts = value.split(':').map { it.toInt() }
        if (parts.drop(1).any { it > 59 }) return null
        return parts.fold(0) { sum, part -> sum * 60 + part }
    }
    internal fun matchesEvent(title: String, event: ReplayEvent, game: String?): Boolean {
        val normalized = words(title)
        if (normalized.none { it in setOf("highlights", "highlight", "recap", "condensed") } ||
            Regex("full (game|race|match) replay|reaction|compilation|(?:player|career) highlights|\\bnba\\s*2k|\\bmadden\\b|\\bgameplay\\b|\\bsimulation\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) return false
        val expected = game?.takeIf { it.isNotBlank() } ?: event.title
        // Do not choose an arbitrary game from a multi-game daily listing.
        if (game == null && Regex("full game replays", RegexOption.IGNORE_CASE).containsMatchIn(expected) &&
            !Regex("\\bvs\\.?\\b", RegexOption.IGNORE_CASE).containsMatchIn(expected)) return false
        val sides = ReplayHighlightIdentity.sides(event, game)
        if (sides.size == 2) {
            if (!ReplayHighlightIdentity.participantsMatch(title, event, game)) return false
        } else if (channel(event)?.name == "FORMULA 1") {
            if (!ReplayHighlightIdentity.raceMatches(title, expected)) return false
        } else {
            val required = words(ReplayHighlightIdentity.clean(expected)).filter { it.length >= 3 && it !in stop && !it.all(Char::isDigit) }
            if (required.isEmpty() || !required.all { it in normalized }) return false
        }
        val year = event.date.take(4).takeIf { it.matches(Regex("20\\d{2}")) }
        if (year != null && Regex("\\b20\\d{2}\\b(?!-\\d{2}\\b)").findAll(title).any { it.value != year }) return false
        if (titleDateOffsets(title, event).any { kotlin.math.abs(it) > 1 }) return false
        // An explicit conflicting date means a different meeting of the same teams.
        val dates = Regex("(?i)(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\.?\\s+(\\d{1,2})").findAll(title)
        for (match in dates) {
            val month = months.indexOfFirst { it.startsWith(match.groupValues[1].lowercase().take(3)) } + 1
            val expectedDate = runCatching { kotlinx.datetime.LocalDate.parse(event.date) }.getOrNull()
            val actualDate = expectedDate?.let { runCatching { kotlinx.datetime.LocalDate(it.year, month, match.groupValues[2].toInt()) }.getOrNull() }
            if (expectedDate != null && (actualDate == null || kotlin.math.abs(actualDate.toEpochDays() - expectedDate.toEpochDays()) > 1)) return false
        }
        return true
    }
    internal fun publicationMatches(title: String, published: String, event: ReplayEvent, nowMs: Long): Boolean {
        val eventDate = runCatching { kotlinx.datetime.LocalDate.parse(event.date) }.getOrNull() ?: return true
        val offsets = titleDateOffsets(title, event)
        if (offsets.isNotEmpty() && offsets.all { it == 0 }) return true
        if (offsets.any { kotlin.math.abs(it) > 1 }) return false
        // Public search provides coarse upload ages, not exact timestamps. Accept only a nearby
        // upload window; old/undated meetings require the user's YouTube search instead.
        val relative = Regex("(?i)^(?:(?:Streamed|Premiered)\\s+)?(\\d+)\\s*(minute|hour|day|m|h|d)s? ago$").matchEntire(published) ?: return false
        val count = relative.groupValues[1].toLongOrNull() ?: return false
        val unit = when (relative.groupValues[2].lowercase()) { "minute", "m" -> 60_000L; "hour", "h" -> 3_600_000L; else -> 86_400_000L }
        if (count > 365) return false
        val earliest = nowMs - (count + 1) * unit
        val latest = nowMs - count * unit
        val eventStart = eventDate.toEpochDays().toLong() * 86_400_000L
        return latest >= eventStart - 86_400_000L && earliest < eventStart + 3 * 86_400_000L
    }
    internal fun titleDateOffsets(title: String, event: ReplayEvent): List<Int> {
        val date = runCatching { kotlinx.datetime.LocalDate.parse(event.date) }.getOrNull() ?: return emptyList()
        val iso = Regex("\\b20\\d{2}-\\d{2}-\\d{2}\\b").findAll(title).mapNotNull {
            runCatching { kotlinx.datetime.LocalDate.parse(it.value) }.getOrNull()
        }.toList()
        val named = Regex("(?i)(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\.?\\s+(\\d{1,2})").findAll(title).mapNotNull { match ->
            val month = months.indexOfFirst { it.startsWith(match.groupValues[1].lowercase().take(3)) } + 1
            runCatching { kotlinx.datetime.LocalDate(date.year, month, match.groupValues[2].toInt()) }.getOrNull()
        }.toList()
        return (iso + named).map { it.toEpochDays() - date.toEpochDays() }
    }
    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val stop = (months + months.map { it.take(3) } + listOf("nba", "nfl", "nhl", "mlb", "euroleague", "basketball", "basket", "football", "full", "game", "games", "replay", "replays", "highlights", "the", "asv")).toSet()
    private fun words(value: String) = ReplayHighlightIdentity.normalize(value).split(' ').filter { it.isNotBlank() }.toSet()
    private fun initialData(html: String): JsonElement? {
        if (html.length > 2_000_000) return null
        val marker = Regex("(?:var\\s+ytInitialData|window\\[\"ytInitialData\"\\]|ytInitialData)\\s*=\\s*\\{").find(html) ?: return null
        val start = html.indexOf('{', marker.range.first)
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return runCatching { Json.parseToJsonElement(html.substring(start, i + 1)) }.getOrNull() } }
        }
        return null
    }
}
