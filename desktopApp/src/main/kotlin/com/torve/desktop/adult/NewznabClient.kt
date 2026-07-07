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
        return fetchFirstNonEmpty(
            baseUrl = baseUrl,
            apiKey = apiKey,
            category = category,
            query = null,
            offset = offset,
            limit = limit,
            types = listOf("search", "movie"),
        )
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
        return fetchFirstNonEmpty(
            baseUrl = baseUrl,
            apiKey = apiKey,
            category = category,
            query = query,
            offset = offset,
            limit = limit,
            types = listOf("search", "movie"),
        )
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
    ): List<NewznabItem> = paginateWithCategoryFallback(category, maxItems, pageSize) { cat, offset, limit ->
        browse(baseUrl, apiKey, cat, offset, limit)
    }

    suspend fun searchAllPages(
        baseUrl: String,
        apiKey: String,
        category: String,
        query: String,
        maxItems: Int,
        pageSize: Int = 100,
    ): List<NewznabItem> = paginateWithCategoryFallback(category, maxItems, pageSize) { cat, offset, limit ->
        search(baseUrl, apiKey, cat, query, offset, limit)
    }

    fun adultCategories(baseUrl: String, apiKey: String): List<NewznabCategory> {
        if (baseUrl.isBlank() || apiKey.isBlank()) return emptyList()
        val url = buildUrl(baseUrl, mapOf(
            "t" to "caps",
            "apikey" to apiKey,
            "o" to "xml",
        ))
        val xml = fetchOrNull(url) ?: return emptyList()
        return parseAdultCategories(xml)
    }

    private suspend fun paginateWithCategoryFallback(
        category: String,
        maxItems: Int,
        pageSize: Int,
        fetchPage: suspend (category: String, offset: Int, limit: Int) -> List<NewznabItem>,
    ): List<NewznabItem> {
        val combined = paginate(maxItems, pageSize) { offset, limit ->
            fetchPage(category, offset, limit)
        }
        if (combined.isNotEmpty() || ',' !in category) return combined

        val seen = LinkedHashMap<String, NewznabItem>()
        category.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { cat ->
                val page = paginate(maxItems, pageSize) { offset, limit ->
                    fetchPage(cat, offset, limit)
                }
                page.forEach { item ->
                    val key = item.guid ?: item.nzbUrl
                    if (key !in seen) seen[key] = item
                }
            }
        return seen.values.toList().sortedByDateDesc().take(maxItems)
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
            if (page.isEmpty()) return seen.values.toList().sortedByDateDesc().take(maxItems)
            page.forEach { item ->
                val key = item.guid ?: item.nzbUrl
                if (key !in seen) seen[key] = item
            }
            offset += pageSize
            if (seen.size >= maxItems) return seen.values.toList().sortedByDateDesc().take(maxItems)
        }
        return seen.values.toList().sortedByDateDesc().take(maxItems)
    }

    private fun fetchFirstNonEmpty(
        baseUrl: String,
        apiKey: String,
        category: String,
        query: String?,
        offset: Int,
        limit: Int,
        types: List<String>,
    ): List<NewznabItem> {
        var firstParsed: List<NewznabItem> = emptyList()
        for (type in types.distinct()) {
            val params = linkedMapOf(
                "t" to type,
                "cat" to category,
                "apikey" to apiKey,
                "extended" to "1",
                "offset" to offset.toString(),
                "limit" to limit.toString(),
                "o" to "xml",
            )
            if (!query.isNullOrBlank()) {
                params["q"] = query.trim()
            }
            val url = buildUrl(baseUrl, params)
            val xml = fetchOrNull(url) ?: continue
            val parsed = parseItems(xml, baseUrl).sortedByDateDesc()
            if (firstParsed.isEmpty()) firstParsed = parsed
            if (parsed.isNotEmpty()) {
                newznabDebugLog { "TORVE NEWZNAB | type=$type cat=$category query=${!query.isNullOrBlank()} items=${parsed.size}" }
                return parsed
            }
        }
        newznabDebugLog { "TORVE NEWZNAB | empty after fallback cat=$category query=${!query.isNullOrBlank()} types=${types.joinToString(",")}" }
        return firstParsed
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

    private fun parseItems(xml: String, baseUrl: String): List<NewznabItem> {
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
                nzbUrl = normalizeNzbUrl(link, baseUrl),
                guid = guid?.let(::decodeEntities)?.trim(),
                pubDate = pubDate?.trim(),
                sizeBytes = size,
                fileCount = files,
                grabs = grabs,
                categoryId = category,
            )
        }
        return out
    }

    private fun parseAdultCategories(xml: String): List<NewznabCategory> {
        val out = linkedMapOf<String, NewznabCategory>()
        val categoryRegex = Regex("<category\\b([^>]*)>([\\s\\S]*?)</category>", RegexOption.IGNORE_CASE)
        for (match in categoryRegex.findAll(xml)) {
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            val parent = NewznabCategory(
                id = attrValue(attrs, "id") ?: continue,
                name = decodeEntities(attrValue(attrs, "name") ?: ""),
            )
            val subcats = Regex("<subcat\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
                .findAll(body)
                .mapNotNull { sub ->
                    val subAttrs = sub.groupValues[1]
                    NewznabCategory(
                        id = attrValue(subAttrs, "id") ?: return@mapNotNull null,
                        name = decodeEntities(attrValue(subAttrs, "name") ?: ""),
                    )
                }
                .toList()
            if (parent.isAdultCategory()) {
                val leafCategories = subcats.ifEmpty { listOf(parent) }
                leafCategories.forEach { out[it.id] = it }
            }
        }

        // Some indexers return flat caps XML. If no adult parent block was
        // detected, fall back to any explicit adult-named category/subcat.
        if (out.isEmpty()) {
            Regex("<(?:category|subcat)\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
                .findAll(xml)
                .mapNotNull { tag ->
                    val attrs = tag.groupValues[1]
                    NewznabCategory(
                        id = attrValue(attrs, "id") ?: return@mapNotNull null,
                        name = decodeEntities(attrValue(attrs, "name") ?: ""),
                    )
                }
                .filter { it.isAdultCategory() }
                .forEach { out[it.id] = it }
        }

        newznabDebugLog {
            "TORVE NEWZNAB | caps adultCategories=" +
                out.values.joinToString(",") { "${it.id}:${it.name}" }
        }
        return out.values.toList()
    }

    private fun NewznabCategory.isAdultCategory(): Boolean {
        val n = name.lowercase()
        return id.startsWith("60") ||
            "xxx" in n ||
            "adult" in n ||
            "erotic" in n ||
            "porn" in n
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

    private fun attrValue(attrs: String, name: String): String? {
        val m = Regex("\\b$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(attrs)
            ?: return null
        return m.groupValues[1]
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
            decoded.startsWith("/") -> baseUrl.trimEnd('/') + decoded
            else -> baseUrl.trimEnd('/') + "/" + decoded
        }
        return absolute
            .replace("https://scenenzbs.com", "https://treasure-maps.com", ignoreCase = true)
            .replace("http://scenenzbs.com", "https://treasure-maps.com", ignoreCase = true)
    }
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

data class NewznabCategory(
    val id: String,
    val name: String,
)
