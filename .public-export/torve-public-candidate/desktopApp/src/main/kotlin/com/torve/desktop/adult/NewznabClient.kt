package com.torve.desktop.adult

import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.platform.TorveRuntimeDebug
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal Newznab API client for the desktop Adult catalog.
 *
 * Newznab indexers (scenenzbs, NZBgeek, NZBfinder, etc.) all expose the
 * same XML-RSS schema at `/api?t=movie&cat=...&apikey=...&extended=1`.
 * We hit that endpoint and regex-extract the entry fields rather than
 * pull in a full XML parser, because the schema is stable and small.
 *
 * Uses the JDK's built-in HttpClient so this module doesn't pull Ktor
 * into the desktop classpath just for one endpoint.
 */
class NewznabClient {

    private val http: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    private inline fun newznabDebugLog(message: () -> String) {
        if (TorveRuntimeDebug.verboseLoggingEnabled) {
            println(DiagnosticsRedactor.redact(message()))
        }
    }

    /**
     * Browse a category. [baseUrl] is e.g. `https://scenenzbs.com`.
     * [category] follows Newznab convention (6010 = XXX/Movies single
     * releases). [offset] is 0-based; one page is ~100 items max
     * server-side, regardless of what we send. Use [browseAllPages] to
     * span multiple pages.
     */
    suspend fun browse(
        baseUrl: String,
        apiKey: String,
        category: String,
        offset: Int = 0,
        limit: Int = 100,
    ): List<NewznabItem> {
        if (baseUrl.isBlank() || apiKey.isBlank()) return emptyList()
        // `o=xml` is the *output format* (vs json) - distinct from sort
        // order, which Newznab keys as `attrs=` / sort. Pubdate-desc is
        // the default for movie/search, but we re-sort client-side too
        // so older indexers without that default still hand back the
        // freshest releases first.
        val url = buildUrl(baseUrl, mapOf(
            "t" to "movie",
            "cat" to category,
            "apikey" to apiKey,
            "extended" to "1",
            "offset" to offset.toString(),
            "limit" to limit.toString(),
            "o" to "xml",
        ))
        val xml = fetchOrNull(url) ?: return emptyList()
        return parseItems(xml).sortedByDateDesc()
    }

    suspend fun search(
        baseUrl: String,
        apiKey: String,
        category: String,
        query: String,
        offset: Int = 0,
        limit: Int = 100,
    ): List<NewznabItem> {
        if (baseUrl.isBlank() || apiKey.isBlank() || query.isBlank()) return emptyList()
        val url = buildUrl(baseUrl, mapOf(
            "t" to "search",
            "cat" to category,
            "q" to query,
            "apikey" to apiKey,
            "extended" to "1",
            "offset" to offset.toString(),
            "limit" to limit.toString(),
            "o" to "xml",
        ))
        val xml = fetchOrNull(url) ?: return emptyList()
        return parseItems(xml).sortedByDateDesc()
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
    ): List<NewznabItem> = paginate(maxItems, pageSize) { offset, limit ->
        browse(baseUrl, apiKey, category, offset, limit)
    }

    suspend fun searchAllPages(
        baseUrl: String,
        apiKey: String,
        category: String,
        query: String,
        maxItems: Int,
        pageSize: Int = 100,
    ): List<NewznabItem> = paginate(maxItems, pageSize) { offset, limit ->
        search(baseUrl, apiKey, category, query, offset, limit)
    }

    private suspend fun paginate(
        maxItems: Int,
        pageSize: Int,
        fetchPage: suspend (offset: Int, limit: Int) -> List<NewznabItem>,
    ): List<NewznabItem> {
        if (maxItems <= 0) return emptyList()
        val seen = LinkedHashMap<String, NewznabItem>()
        var offset = 0
        // Hard guardrail so a malformed indexer can't pull us into an
        // infinite loop if it always returns a non-empty page.
        val maxPages = (maxItems + pageSize - 1) / pageSize + 1
        repeat(maxPages) {
            val page = fetchPage(offset, pageSize)
            if (page.isEmpty()) return@repeat
            page.forEach { item ->
                val key = item.guid ?: item.nzbUrl
                if (key !in seen) seen[key] = item
            }
            offset += pageSize
            if (seen.size >= maxItems) return@repeat
        }
        return seen.values.toList().sortedByDateDesc().take(maxItems)
    }

    /**
     * Sort by RSS `pubDate` parsed as RFC-1123 / 822 (Newznab's wire
     * format). Items without a parseable date sink to the bottom - they
     * almost always represent stale or malformed releases.
     */
    private fun List<NewznabItem>.sortedByDateDesc(): List<NewznabItem> =
        sortedByDescending { item -> parsePubDateMillis(item.pubDate) ?: Long.MIN_VALUE }

    private fun parsePubDateMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        // Try RFC-1123 first (the Newznab default), then a couple of
        // common variants seen in the wild.
        val patterns = listOf(
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z"),
        )
        for (fmt in patterns) {
            runCatching { return java.time.OffsetDateTime.parse(raw.trim(), fmt).toInstant().toEpochMilli() }
        }
        return null
    }

    private fun buildUrl(baseUrl: String, params: Map<String, String>): String {
        val base = baseUrl.trimEnd('/')
        val qs = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$base/api?$qs"
    }

    private fun fetchOrNull(url: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/xml, text/xml")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        val body = resp.body().orEmpty()
        newznabDebugLog { "TORVE NEWZNAB | GET status=${resp.statusCode()} bodyBytes=${body.length}" }
        if (resp.statusCode() in 200..299) body else null
    }.getOrNull()

    private fun parseItems(xml: String): List<NewznabItem> {
        // Newznab error envelope: <error code="100" description="Incorrect user credentials"/>.
        // Surface as an exception so the page banner shows what the
        // indexer said instead of an empty grid.
        val errMatch = Regex(
            "<error[^>]*description=\"([^\"]*)\"[^>]*/>",
            RegexOption.IGNORE_CASE,
        ).find(xml)
        if (errMatch != null) error("Indexer rejected the request: ${errMatch.groupValues[1]}")
        val out = mutableListOf<NewznabItem>()
        val itemRegex = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
        for (match in itemRegex.findAll(xml)) {
            val body = match.groupValues[1]
            val title = textBetween(body, "title") ?: continue
            val link = textBetween(body, "link")
                ?: attrAfter(body, "<enclosure", "url")
                ?: continue
            val guid = textBetween(body, "guid")
            val pubDate = textBetween(body, "pubDate")
            val size = newznabAttr(body, "size")?.toLongOrNull()
                ?: attrAfter(body, "<enclosure", "length")?.toLongOrNull()
            val files = newznabAttr(body, "files")?.toIntOrNull()
            val grabs = newznabAttr(body, "grabs")?.toIntOrNull()
            // Newznab attaches the category id (e.g. "6010") via
            // <newznab:attr name="category" value="6010" /> - sometimes
            // multiple times for items that span subcategories.
            val category = newznabAttr(body, "category")
            out += NewznabItem(
                title = decodeEntities(title),
                nzbUrl = link.trim(),
                guid = guid?.trim(),
                pubDate = pubDate?.trim(),
                sizeBytes = size,
                fileCount = files,
                grabs = grabs,
                categoryId = category,
            )
        }
        return out
    }

    private fun textBetween(body: String, tag: String): String? {
        val m = Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE).find(body)
            ?: return null
        return m.groupValues[1].trim()
            .removePrefix("<![CDATA[").removeSuffix("]]>")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun newznabAttr(body: String, name: String): String? {
        val m = Regex(
            "<newznab:attr[^>]*name=\"$name\"[^>]*value=\"([^\"]*)\"",
            RegexOption.IGNORE_CASE,
        ).find(body) ?: return null
        return m.groupValues[1]
    }

    private fun attrAfter(body: String, tag: String, name: String): String? {
        val m = Regex("$tag[^>]*$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(body)
            ?: return null
        return m.groupValues[1]
    }

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
}

@kotlinx.serialization.Serializable
data class NewznabItem(
    val title: String,
    val nzbUrl: String,
    val guid: String?,
    val pubDate: String?,
    val sizeBytes: Long?,
    val fileCount: Int?,
    val grabs: Int?,
    val categoryId: String? = null,
)
