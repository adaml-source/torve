package com.torve.data.replays

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Cancelled refreshes and multiple websites share a single CPU/memory budget.
// A permit is retained until a cancelled parser has actually stopped.
private val replayParseBudget = Semaphore(1)
internal suspend fun <T> withReplayParsingBudget(dispatcher: CoroutineDispatcher, block: (() -> Unit) -> T): T = replayParseBudget.withPermit {
    withContext(dispatcher) {
        val context = currentCoroutineContext()
        context.ensureActive()
        block { context.ensureActive() }.also { context.ensureActive() }
    }
}

/** Public page/host metadata only. Media bytes go directly to the existing player. */
class WebReplayRepository(client: HttpClient, private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private suspend fun <T> parse(block: (() -> Unit) -> T): T = withReplayParsingBudget(parseDispatcher, block)
    private val userAgent = "Mozilla/5.0"
    private val client = client.config {
        followRedirects = false; expectSuccess = false
        defaultRequest { headers.remove(HttpHeaders.ContentType) }
        install(Logging) { level = LogLevel.NONE }
        install(HttpTimeout) { requestTimeoutMillis = 12_000; connectTimeoutMillis = 8_000; socketTimeoutMillis = 12_000 }
    }
    private data class Document(val url: String, val text: String)
    private suspend fun document(input: String, referrer: String? = null): Document {
        var url = WebReplayUrls.normalize(input) ?: error("Enter a public HTTP or HTTPS website address.")
        repeat(4) {
            var redirect: String? = null
            val result = client.prepareGet(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "text/html,application/json;q=0.9,*/*;q=0.5")
                referrer?.let { header(HttpHeaders.Referrer, WebReplayUrls.origin(it)) }
            }.execute { response ->
                if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                    redirect = response.headers[HttpHeaders.Location]?.let { WebReplayUrls.normalize(it, url) }
                        ?: error("The website returned an invalid redirect.")
                    return@execute null
                }
                when (response.status.value) {
                    401, 403 -> error("This website requires sign-in or browser verification. Choose another source or open the website in your browser.")
                    429 -> error("This website is limiting requests. Try again later.")
                }
                check(response.status.value in 200..299) { "The website could not be reached (${response.status.value})." }
                check((response.contentLength() ?: 0) <= 2_000_000) { "The website page is too large." }
                val bytes = ByteArray(2_000_001)
                var count = 0
                val channel = response.bodyAsChannel()
                while (true) {
                    val read = channel.readAvailable(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                    check(count < bytes.size) { "The website page is too large." }
                }
                Document(url, bytes.copyOf(count).decodeToString())
            }
            if (result != null) return result
            url = redirect!!
        }
        error("The website redirected too many times.")
    }
    suspend fun catalogue(website: ReplayWebsite, page: String = website.url): ReplayPage = withTimeout(15_000) {
        check(WebReplayUrls.sameOrigin(website.url, page)) { "The next page belongs to another website." }
        val document = document(page)
        check(WebReplayUrls.sameOrigin(website.url, document.url)) { "The website moved to another address. Update its address in Sports Replays settings." }
        parse { checkpoint -> WebReplayParser.catalogue(document.text, document.url, website, checkpoint) }
    }
    suspend fun sources(event: ReplayEvent): List<ReplaySourceOption> = withTimeout(15_000) {
        if (WebReplayUrls.media(event.pageUrl)) return@withTimeout listOf(ReplaySourceOption(event.id, "Video", event.pageUrl, event.pageUrl))
        val page = document(event.pageUrl)
        val options = parse { checkpoint -> WebReplayParser.sources(page.text, page.url, checkpoint) }
        if (options.isNotEmpty()) options else parse { checkpoint -> WebReplayParser.playable(page.text, page.url, checkpoint) }?.let {
            listOf(ReplaySourceOption(WebReplayUrls.identity(it), "Video", it, page.url))
        }.orEmpty()
    }
    private val highlightCache = linkedMapOf<String, Pair<Long, List<ReplayHighlight>>>()
    suspend fun highlights(event: ReplayEvent, game: String?): List<ReplayHighlight> = withTimeout(18_000) {
        if (ReplayHighlights.channel(event) == null) return@withTimeout emptyList()
        val url = ReplayHighlights.searchUrl(event, game)
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        highlightCache[url]?.takeIf { now - it.first < if (it.second.isEmpty()) 15_000 else 30 * 60_000 }?.let { return@withTimeout it.second }
        val matches = mutableListOf<ReplayHighlight>()
        for (search in ReplayHighlights.searchUrls(event, game)) {
            val page = withTimeoutOrNull(5_000) { document(search) } ?: continue
            // A verification or consent gate is not a search miss. Do not
            // keep requesting alternate queries against a blocked page.
            if (page.url.contains("consent.") || page.text.contains("g-recaptcha", true) ||
                page.text.contains("unusual traffic", true)) break
            matches += withContext(parseDispatcher) { ReplayHighlights.parse(page.text, event, game) }
        }
        val exactDate = matches.filter { ReplayHighlights.titleDateOffsets(it.title, event).any { offset -> offset == 0 } }
        val candidates = if (exactDate.isNotEmpty()) matches.filter { ReplayHighlights.titleDateOffsets(it.title, event).all { offset -> offset == 0 } } else matches
        val result = candidates.distinctBy { it.id }.sortedWith(compareByDescending<ReplayHighlight> { it.isOfficial }.thenBy { it.durationSeconds })
        if (highlightCache.size >= 24) highlightCache.remove(highlightCache.keys.first())
        // Don't pin an empty result for half an hour just as a league uploads
        // its recap. Successful metadata is reused across repeat visits.
        highlightCache[url] = now to result
        result
    }
    suspend fun resolve(option: ReplaySourceOption): ResolvedReplay = withTimeout(25_000) {
        var url = WebReplayUrls.normalize(option.url) ?: error("This source has an invalid address.")
        var referrer = option.referrer
        val visited = mutableSetOf<String>()
        repeat(6) {
            check(visited.add(url)) { "This video host returned a link loop. Choose another source." }
            if (WebReplayUrls.media(url)) return@withTimeout ResolvedReplay(url, mapOf(HttpHeaders.Referrer to WebReplayUrls.origin(referrer), HttpHeaders.UserAgent to userAgent))
            val parsed = Url(url)
            if (parsed.host == "dai.ly" || parsed.host == "dailymotion.com" || parsed.host.endsWith(".dailymotion.com")) {
                val id = parsed.parameters["video"] ?: parsed.encodedPath.substringAfterLast('/').substringBefore('?')
                if (id.matches(Regex("[A-Za-z0-9]{2,32}")) && !parsed.encodedPath.contains("/metadata/"))
                    url = "https://www.dailymotion.com/player/metadata/video/$id"
            }
            val page = document(url, referrer)
            parse { checkpoint -> WebReplayParser.playable(page.text, page.url, checkpoint) }?.let {
                return@withTimeout ResolvedReplay(it, mapOf(HttpHeaders.Referrer to WebReplayUrls.origin(page.url), HttpHeaders.UserAgent to userAgent))
            }
            val next = parse { checkpoint -> WebReplayParser.sources(page.text, page.url, checkpoint) }.firstOrNull { it.url !in visited }
                ?: error("This host does not expose a supported playable video. Try another source.")
            referrer = page.url; url = next.url
        }
        error("This source uses too many intermediate pages. Try another source.")
    }
    fun close() { client.close() }
}
