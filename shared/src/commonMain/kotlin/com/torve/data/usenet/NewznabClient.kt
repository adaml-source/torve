package com.torve.data.usenet

import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.platform.torveVerboseLog
import com.torve.util.DisplayTextDecoder
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LogLevel

/**
 * Minimal Newznab API client used by the cross-platform Sports / Adult
 * catalogs. KMP-friendly Ktor rewrite of the previous JVM-only
 * `desktop.adult.NewznabClient` so TV and mobile can hit the same
 * endpoints without duplicating the regex parser and pagination loop.
 *
 * Newznab indexers (scenenzbs, NZBgeek, NZBfinder, etc.) all expose
 * the same XML-RSS schema at `/api?t=search&cat=...&apikey=...`. We
 * regex-extract the entry fields rather than pull in a full XML
 * parser — the schema is stable, small, and one less dependency to
 * carry across platforms.
 */
class NewznabClient(
    private val httpClient: HttpClient,
) {
    // NZB URLs carry indexer credentials in their query, so header-level logging is unsafe.
    private val transport = httpClient.config { install(Logging) { level = LogLevel.NONE } }

    /** Honest application identity used for both searches and NZB retrieval. */
    companion object {
        const val USER_AGENT = "Torve/1.0 (+https://torve.app)"
        private val ERROR_REGEX = Regex("<error[^>]*description=[\"']([^\"']*)[\"'][^>]*/?>", RegexOption.IGNORE_CASE)
        private val ITEM_REGEX = Regex("<item(?:\\s[^>]*)?>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
    }

    suspend fun searchTitle(baseUrl: String, apiKey: String, imdbId: String,
        season: Int? = null, episode: Int? = null): List<NewznabItem> {
        require(Regex("tt\\d+").matches(imdbId)) { "This title needs an IMDb identifier for NZB lookup." }
        val url = buildUrl(baseUrl, buildMap {
            put("apikey", apiKey)
            put("t", if (season == null) "movie" else "tvsearch")
            put("imdbid", imdbId.removePrefix("tt"))
            put("extended", "1"); put("o", "xml"); put("limit", "100")
            season?.let { put("season", it.toString()) }
            episode?.let { put("ep", it.toString()) }
        })
        return parseItems(fetchOrNull(url) ?: return emptyList(), baseUrl, apiKey).sortedByPubDateDesc()
    }

    /**
     * Browse a category. [baseUrl] is e.g. `https://scenenzbs.com`.
     * [category] follows Newznab convention (5060 = Sports). [offset]
     * is 0-based; one page is ~100 items max server-side regardless
     * of what we send. Use [browseAllPages] to span multiple pages.
     */
    suspend fun browse(
        baseUrl: String,
        apiKey: String,
        category: String,
        offset: Int = 0,
        limit: Int = 100,
        maxAgeDays: Int? = null,
    ): List<NewznabItem> {
        if (baseUrl.isBlank() || apiKey.isBlank()) return emptyList()
        val url = buildUrl(
            baseUrl,
            buildMap {
                putAll(mapOf(
                "t" to "search",
                "cat" to category,
                "apikey" to apiKey,
                "extended" to "1",
                "offset" to offset.toString(),
                "limit" to limit.toString(),
                "o" to "xml",
                ))
                maxAgeDays?.takeIf { it > 0 }?.let { put("maxage", it.toString()) }
            },
        )
        val xml = fetchOrNull(url) ?: return emptyList()
        return parseItems(xml, baseUrl).sortedByPubDateDesc()
    }

    suspend fun search(
        baseUrl: String,
        apiKey: String,
        category: String,
        query: String,
        offset: Int = 0,
        limit: Int = 100,
        maxAgeDays: Int? = null,
    ): List<NewznabItem> {
        if (baseUrl.isBlank() || apiKey.isBlank() || query.isBlank()) return emptyList()
        val url = buildUrl(
            baseUrl,
            buildMap {
                putAll(mapOf(
                "t" to "search",
                "cat" to category,
                "q" to query,
                "apikey" to apiKey,
                "extended" to "1",
                "offset" to offset.toString(),
                "limit" to limit.toString(),
                "o" to "xml",
                ))
                maxAgeDays?.takeIf { it > 0 }?.let { put("maxage", it.toString()) }
            },
        )
        val xml = fetchOrNull(url) ?: return emptyList()
        return parseItems(xml, baseUrl).sortedByPubDateDesc()
    }

    /**
     * Walk pages until either [maxItems] is reached or the indexer
     * returns an empty page. Newznab caps each page at ~100 items
     * server-side, so users who want hundreds of results need explicit
     * pagination. De-duplicates on `guid` (or, missing that, `nzbUrl`)
     * because some indexers paginate inconsistently across requests.
     */
    suspend fun browseAllPages(
        baseUrl: String,
        apiKey: String,
        category: String,
        maxItems: Int,
        pageSize: Int = 100,
        maxAgeDays: Int? = null,
        onProgress: ((fetched: Int, max: Int) -> Unit)? = null,
    ): List<NewznabItem> = paginate(maxItems, pageSize, onProgress) { offset, limit ->
        browse(baseUrl, apiKey, category, offset, limit, maxAgeDays)
    }

    suspend fun searchAllPages(
        baseUrl: String,
        apiKey: String,
        category: String,
        query: String,
        maxItems: Int,
        pageSize: Int = 100,
        maxAgeDays: Int? = null,
        onProgress: ((fetched: Int, max: Int) -> Unit)? = null,
    ): List<NewznabItem> = paginate(maxItems, pageSize, onProgress) { offset, limit ->
        search(baseUrl, apiKey, category, query, offset, limit, maxAgeDays)
    }

    private suspend fun paginate(
        maxItems: Int,
        pageSize: Int,
        onProgress: ((fetched: Int, max: Int) -> Unit)? = null,
        fetchPage: suspend (offset: Int, limit: Int) -> List<NewznabItem>,
    ): List<NewznabItem> {
        if (maxItems <= 0) return emptyList()
        val seen = LinkedHashMap<String, NewznabItem>()
        var offset = 0
        require(pageSize > 0)
        while (seen.size < maxItems) {
            val page = fetchPage(offset, minOf(pageSize, maxItems - seen.size))
            if (page.isEmpty()) break
            val previousSize = seen.size
            page.forEach { item ->
                val key = item.guid ?: item.nzbUrl
                if (key !in seen) seen[key] = item
            }
            // Providers may cap a requested 100-item page at 50. Advancing by
            // the requested size skips releases. Stop if offsets are ignored.
            offset += page.size
            onProgress?.invoke(seen.size.coerceAtMost(maxItems), maxItems)
            if (seen.size == previousSize) break
        }
        return seen.values.toList().sortedByPubDateDesc().take(maxItems)
    }

    /**
     * Sort by RSS `pubDate` parsed as RFC-1123 (Newznab's wire
     * format). KMP doesn't have a direct equivalent of
     * `DateTimeFormatter.RFC_1123_DATE_TIME`, so we hand-roll a
     * minimal parser for the format we actually see:
     *   "Tue, 30 Apr 2026 18:55:00 +0000"
     * Items without a parseable date sink to the bottom — they almost
     * always represent stale or malformed releases.
     */
    private fun List<NewznabItem>.sortedByPubDateDesc(): List<NewznabItem> =
        sortedByDescending { item -> parseRfc1123Millis(item.pubDate) ?: Long.MIN_VALUE }

    private fun buildUrl(baseUrl: String, params: Map<String, String>): String {
        return URLBuilder(baseUrl.trim()).apply {
            // Accept both an indexer's site URL and the API URL copied from its docs.
            // Appending /api to an existing /api endpoint silently produced /api/api.
            val path = encodedPath.trimEnd('/')
            encodedPath = if (path.substringAfterLast('/').lowercase() in setOf("api", "api.php")) path else "$path/api"
            params.forEach { (name, value) -> parameters[name] = value }
            fragment = ""
        }.buildString()
    }

    private suspend fun fetchOrNull(url: String): String? = try {
        val response = transport.get(url) {
            header("Accept", "application/xml, text/xml")
            header("User-Agent", USER_AGENT)
        }
        val body = response.bodyAsText()
        // Debug-only transport summary. Do not log URL, API key, or XML body.
        torveVerboseLog { "TORVE NEWZNAB | GET status=${response.status.value} bodyBytes=${body.length}" }
        if (!response.status.isSuccess()) throw NewznabRequestFailure(when (response.status.value) {
            401 -> "The indexer rejected its API key (HTTP 401). Check the saved key in Usenet settings."
            403 -> "The indexer denied access (HTTP 403). Check your subscription and allowed tools; Torve needs search and NZB download permission."
            404 -> "The indexer API address was not found (HTTP 404). Check its URL in Usenet settings."
            429 -> "The indexer usage limit has been reached (HTTP 429). Try again after it resets."
            else -> "The indexer returned HTTP ${response.status.value}. Try again later."
        })
        body
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: NewznabRequestFailure) {
        throw failure
    } catch (t: Throwable) {
        torveVerboseLog { "TORVE NEWZNAB | GET failed: ${t::class.simpleName} ${DiagnosticsRedactor.redact(t.message)}" }
        throw IllegalStateException("Could not reach the indexer. Check its connection and try again.", t)
    }

    private fun parseItems(xml: String, baseUrl: String, apiKey: String = ""): List<NewznabItem> {
        // Newznab error envelope:
        //   <error code="100" description="Incorrect user credentials"/>.
        // Surface as an exception so the page banner shows what the
        // indexer said instead of an empty grid.
        val errMatch = ERROR_REGEX.find(xml)
        if (errMatch != null) error("Indexer rejected the request: ${errMatch.groupValues[1]}")
        val out = mutableListOf<NewznabItem>()
        for (match in ITEM_REGEX.findAll(xml)) {
            val body = match.groupValues[1]
            val title = textBetween(body, "title") ?: continue
            val link = attrAfter(body, "<enclosure", "url")
                ?: textBetween(body, "link")
                ?: continue
            val guid = textBetween(body, "guid")
            val pubDate = textBetween(body, "pubDate")
            val size = newznabAttr(body, "size")?.toLongOrNull()
                ?: attrAfter(body, "<enclosure", "length")?.toLongOrNull()
            val files = newznabAttr(body, "files")?.toIntOrNull()
            val grabs = newznabAttr(body, "grabs")?.toIntOrNull()
            val category = newznabAttr(body, "category")
            out += NewznabItem(
                title = DisplayTextDecoder.decodeReleaseTitle(title),
                nzbUrl = authenticatedDownloadUrl(link, baseUrl, apiKey, guid),
                guid = guid?.let(::decodeEntities)?.trim(),
                pubDate = pubDate?.trim(),
                sizeBytes = size,
                fileCount = files,
                grabs = grabs,
                categoryId = category,
                imdbId = newznabAttr(body, "imdb") ?: newznabAttr(body, "imdbid"),
            )
        }
        return out
    }

    private fun textBetween(body: String, tag: String): String? {
        val regex = Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
        val m = regex.find(body) ?: return null
        return m.groupValues[1].trim()
            .removePrefix("<![CDATA[").removeSuffix("]]>")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun newznabAttr(body: String, name: String): String? {
        val regex = Regex(
            "<newznab:attr[^>]*name=\"$name\"[^>]*value=\"([^\"]*)\"",
            RegexOption.IGNORE_CASE,
        )
        return regex.find(body)?.groupValues?.get(1)
    }

    private fun attrAfter(body: String, tag: String, name: String): String? {
        val regex = Regex("$tag[^>]*$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return regex.find(body)?.groupValues?.get(1)
    }

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun normalizeNzbUrl(raw: String, baseUrl: String): String {
        val decoded = decodeEntities(raw).trim()
        val absolute = when {
            decoded.startsWith("http://", ignoreCase = true) ||
                decoded.startsWith("https://", ignoreCase = true) -> decoded
            decoded.startsWith("/") -> URLBuilder(baseUrl).apply { encodedPath = "/"; parameters.clear() }
                .buildString().trimEnd('/') + decoded
            else -> baseUrl.trimEnd('/') + "/" + decoded
        }
        return absolute
            .replace("https://scenenzbs.com", "https://treasure-maps.com", ignoreCase = true)
            .replace("http://scenenzbs.com", "https://treasure-maps.com", ignoreCase = true)
    }

    private fun authenticatedDownloadUrl(raw: String, baseUrl: String, apiKey: String, guid: String?): String {
        val absolute = normalizeNzbUrl(raw, baseUrl)
        val parsed = io.ktor.http.Url(absolute)
        require(parsed.protocol.name in setOf("http", "https")) { "The indexer returned an invalid NZB address." }
        val base = io.ktor.http.Url(baseUrl)
        if (apiKey.isBlank() || parsed.host != base.host || parsed.protocol != base.protocol || parsed.port != base.port) return absolute
        if (parsed.encodedPath.contains(Regex("/(details|release)/")) && !guid.isNullOrBlank()) {
            val id = if (guid.contains("://")) parsed.encodedPath.substringAfterLast('/') else decodeEntities(guid)
            return buildUrl(baseUrl, mapOf("t" to "get", "id" to id, "apikey" to apiKey))
        }
        return URLBuilder(absolute).apply { parameters["apikey"] = apiKey }.buildString()
    }
}

private class NewznabRequestFailure(message: String) : IllegalStateException(message)

@Serializable
data class NewznabItem(
    val title: String,
    val nzbUrl: String,
    val guid: String? = null,
    val pubDate: String? = null,
    val sizeBytes: Long? = null,
    val fileCount: Int? = null,
    val grabs: Int? = null,
    val categoryId: String? = null,
    val imdbId: String? = null,
)

/**
 * Hand-rolled RFC-1123 parser. KMP's `kotlinx.datetime` doesn't ship
 * a built-in for "Tue, 30 Apr 2026 18:55:00 +0000" — and the items
 * we sort here are best-effort anyway (a release that doesn't parse
 * just sinks to the bottom). Returns epoch millis or null.
 */
internal fun parseRfc1123Millis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    // Format: "Tue, 30 Apr 2026 18:55:00 +0000"
    //          Day, DD MMM YYYY HH:MM:SS ±HHMM
    val parts = raw.trim().split(' ')
    if (parts.size < 5) return null
    val day = parts[1].toIntOrNull() ?: return null
    val month = MONTHS[parts[2].lowercase()] ?: return null
    val year = parts[3].toIntOrNull() ?: return null
    val timeParts = parts[4].split(':')
    if (timeParts.size != 3) return null
    val hour = timeParts[0].toIntOrNull() ?: return null
    val minute = timeParts[1].toIntOrNull() ?: return null
    val second = timeParts[2].toIntOrNull() ?: return null
    val tzOffsetMin = if (parts.size >= 6) parseTzOffsetMinutes(parts[5]) else 0
    return try {
        val instant = LocalDateTime(year, month, day, hour, minute, second)
            .toInstant(TimeZone.UTC)
        instant.toEpochMilliseconds() - tzOffsetMin * 60_000L
    } catch (_: Throwable) {
        null
    }
}

private val MONTHS = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
)

private fun parseTzOffsetMinutes(raw: String): Int {
    if (raw.length < 5) return 0
    val sign = if (raw[0] == '-') -1 else 1
    val hh = raw.substring(1, 3).toIntOrNull() ?: return 0
    val mm = raw.substring(3, 5).toIntOrNull() ?: return 0
    return sign * (hh * 60 + mm)
}
