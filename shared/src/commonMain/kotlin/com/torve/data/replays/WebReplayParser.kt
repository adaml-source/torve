package com.torve.data.replays

import kotlinx.serialization.json.*

object WebReplayParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun value(node: JsonElement?): String? = (node as? JsonPrimitive)?.contentOrNull
    private fun obj(node: JsonElement?): JsonObject? = node as? JsonObject
    private fun json(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
    private fun isProtected(data: JsonElement?): Boolean = obj(data)?.let { root ->
        root["drm"] is JsonObject && (root["drm"] as JsonObject).isNotEmpty() || value(root["drm"]) == "true"
    } ?: false
    private fun metadata(nodes: List<ReplayHtml.Node>, key: String) = nodes.firstOrNull {
        it.tag == "meta" && (it["property"].equals(key, true) || it["name"].equals(key, true))
    }?.get("content")?.takeIf { it.isNotBlank() }

    fun catalogue(html: String, pageUrl: String, website: ReplayWebsite): ReplayPage = catalogue(html, pageUrl, website) {}
    internal fun catalogue(html: String, pageUrl: String, website: ReplayWebsite, checkpoint: () -> Unit): ReplayPage {
        val nodes = ReplayHtml.parse(html, checkpoint).all().toList()
        val cards = nodes.filter { it.hasClass("short_item") || it.tag == "article" || it.hasClass("replay-card") }
        val events = mutableListOf<ReplayEvent>()
        fun add(title: String, href: String, image: String?, category: String = "", dateText: String = "") {
            val url = WebReplayUrls.normalize(href, pageUrl) ?: return
            if (!WebReplayUrls.sameOrigin(website.url, url) || title.length !in 3..600) return
            events += ReplayEvent("web-replay:" + WebReplayUrls.identity(url), website.id,
                title, url, image?.let { WebReplayUrls.normalize(it, pageUrl) }, sport(title + " " + category),
                category.take(100), date(dateText + " " + title))
        }
        cards.take(150).forEach { card ->
            val children = card.all().toList()
            val heading = children.firstOrNull { it.tag in setOf("h1", "h2", "h3", "h4") }
            val anchor = heading?.all()?.firstOrNull { it.tag == "a" && it["href"].isNotBlank() }
                ?: children.firstOrNull { it.tag == "a" && it["href"].isNotBlank() }
            val image = children.firstOrNull { it.tag == "img" }
            val title = heading?.text()?.takeIf { it.isNotBlank() } ?: image?.get("alt")?.takeIf { it.isNotBlank() }
                ?: anchor?.get("title") ?: return@forEach
            if (anchor != null) add(title, anchor["href"], image?.get("data-src")?.ifBlank { null } ?: image?.get("src"),
                children.firstOrNull { it.hasClass("short_cat") || it.hasClass("category") }?.text().orEmpty(),
                children.firstOrNull { it.tag == "time" }?.get("datetime").orEmpty())
        }
        // Schema.org ItemList/VideoObject also supports sites without this theme.
        fun structured(element: JsonElement?, depth: Int = 0) {
            if (depth > 10 || events.size > 150) return
            when (element) {
                is JsonArray -> element.take(150).forEach { structured(it, depth + 1) }
                is JsonObject -> {
                    if (value(element["@type"]) in setOf("VideoObject", "SportsEvent", "Movie", "Article", "ListItem", "Event")) {
                        val title = value(element["name"]) ?: value(element["headline"])
                        val url = value(element["url"]) ?: value(element["@id"]) ?: pageUrl
                        val image = value(element["thumbnailUrl"]) ?: value(element["image"])
                            ?: (element["thumbnailUrl"] as? JsonArray)?.firstOrNull()?.let(::value)
                        if (title != null) add(title, url, image, dateText = value(element["datePublished"]).orEmpty())
                    }
                    for (key in listOf("@graph", "itemListElement", "item")) structured(element[key], depth + 1)
                }
                else -> Unit
            }
        }
        nodes.filter { it.tag == "script" && it["type"].contains("ld+json") }.forEach { structured(json(it.raw)) }
        if (events.isEmpty()) {
            nodes.filter { it.tag in setOf("h2", "h3") }.take(150).forEach { heading ->
                val a = heading.all().firstOrNull { it.tag == "a" } ?: return@forEach
                val title = heading.text()
                if (Regex("replay|full game|full race|\\bvs\\.?\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) {
                    val url = WebReplayUrls.normalize(a["href"], pageUrl)
                    val img = nodes.firstOrNull { it.tag == "a" && WebReplayUrls.normalize(it["href"], pageUrl) == url }
                        ?.all()?.firstOrNull { it.tag == "img" }?.get("src")
                    add(title, a["href"], img)
                }
            }
        }
        if (events.isEmpty()) {
            val title = metadata(nodes, "og:title") ?: nodes.firstOrNull { it.tag == "h1" }?.text()
            val image = metadata(nodes, "og:image") ?: nodes.firstOrNull { it.hasClass("poster") }
                ?.all()?.firstOrNull { it.tag == "img" }?.get("src")
            if (title != null && (Regex("replay|full game|full race", RegexOption.IGNORE_CASE).containsMatchIn(title) ||
                    nodes.any { it.tag == "video" })) add(title, pageUrl, image)
        }
        val next = nodes.firstOrNull { it.tag == "a" && (it["rel"].split(' ').contains("next") ||
            it.text() in listOf("Next", "Next page", "»", "›") || it["aria-label"].equals("Next", true)) }
            ?.get("href")?.let { WebReplayUrls.normalize(it, pageUrl) }
            ?.takeIf { WebReplayUrls.sameOrigin(it, website.url) && it != pageUrl }
        return ReplayPage(events.distinctBy { it.id }.take(100), next)
    }

    fun sources(html: String, pageUrl: String): List<ReplaySourceOption> = sources(html, pageUrl) {}
    internal fun sources(html: String, pageUrl: String, checkpoint: () -> Unit): List<ReplaySourceOption> {
        val nodes = ReplayHtml.parse(html, checkpoint).all().toList()
        val options = mutableListOf<ReplaySourceOption>()
        var label = ""
        var game: String? = null
        nodes.forEach { node ->
            if (node.tag in setOf("p", "h2", "h3", "b", "strong")) {
                val text = node.text()
                if (text.length in 7..180 && !node.all().any { it.tag == "a" } &&
                    Regex("\\bvs\\.?\\s|\\bversus\\b|\\bv[.]\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    game = text; label = ""
                }
                if (text.length in 2..100 && !node.all().any { it.tag == "a" } &&
                    Regex("server\\s*#?\\s*[0-9]+|\\b(?:F1\\s?TV|Sky\\s?Sports|ESPN|DAZN|English|German|French|Spanish|Italian|OK|FM|Filemoon|Dailymotion|Vidara|[0-9]{3,4}p|part\\s*[0-9]+)\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(text)) label = text
            }
            val raw = when {
                node.tag in setOf("video", "source") -> node["src"]
                node.tag == "iframe" && node["width"] != "1" && node["height"] != "1" &&
                    !node["class"].contains("advert", true) -> node["src"].ifBlank { node["data-src"] }
                node.tag == "a" && (node.hasClass("su-button") || node.hasClass("replay-source") ||
                    WebReplayUrls.media(node["href"])) -> node["href"]
                else -> ""
            }
            val url = raw.takeIf { it.isNotBlank() }?.let { WebReplayUrls.normalize(it, pageUrl) } ?: return@forEach
            val host = io.ktor.http.Url(url).host
            if (Regex("doubleclick|googlesyndication|adsterra|adsystem|popads|czernik\\.org", RegexOption.IGNORE_CASE).containsMatchIn(host)) return@forEach
            val name = if (label.isNotEmpty()) label else when {
                host == "ok.ru" || host.endsWith(".ok.ru") -> "OK"
                host.contains("dailymotion") || host == "dai.ly" -> "Dailymotion"
                host.contains("filemoon") || host.startsWith("byse") -> "FM"
                WebReplayUrls.media(url) -> "Video"
                else -> host
            }
            options += ReplaySourceOption(WebReplayUrls.identity(if (game == null) url else "$url|$game"), name, url, pageUrl, game)
            label = ""
        }
        return options.distinctBy { it.url to it.gameTitle }.take(60)
    }

    fun playable(html: String, pageUrl: String): String? = playable(html, pageUrl) {}
    internal fun playable(html: String, pageUrl: String, checkpoint: () -> Unit): String? {
        val rootJson = json(html)
        if (isProtected(rootJson)) return null
        val nodes = ReplayHtml.parse(html, checkpoint).all().toList()
        nodes.filter { it.tag == "script" && it["type"].contains("ld+json") }.forEach { script ->
            val data = obj(json(script.raw))
            value(data?.get("contentUrl"))?.let { WebReplayUrls.normalize(it, pageUrl) }?.takeIf(WebReplayUrls::media)?.let { return it }
        }
        nodes.filter { it["data-options"].isNotBlank() }.forEach { node ->
            val data = obj(json(node["data-options"]))
            val vars = obj(data?.get("flashvars"))
            val metadata = obj(vars?.get("metadata")) ?: obj(json(value(vars?.get("metadata")).orEmpty())) ?: obj(data?.get("metadata"))
            if (isProtected(metadata)) return null
            if (metadata != null) fromMetadata(metadata, pageUrl)?.let { return it }
        }
        fromMetadata(rootJson, pageUrl)?.let { return it }
        sources(html, pageUrl, checkpoint).firstOrNull { WebReplayUrls.media(it.url) }?.let { return it.url }
        // Only media-valued player properties; image, advertising and arbitrary
        // outbound links do not become playback candidates.
        val candidates = Regex("(?:[\"']?(?:file|hlsManifestUrl|hls|contentUrl)[\"']?\\s*[:=]\\s*)([\"'])(.*?)\\1")
            .findAll(html).take(20).mapNotNull { match ->
                val text = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
                WebReplayUrls.normalize(ReplayHtml.unescape(text), pageUrl)?.takeIf(WebReplayUrls::media)
            }.toList()
        return candidates.firstOrNull { it.substringBefore('?').endsWith(".m3u8") } ?: candidates.firstOrNull()
    }
    private fun fromMetadata(data: JsonElement?, base: String): String? {
        val root = obj(data) ?: return null
        if (root["drm"] is JsonObject && (root["drm"] as JsonObject).isNotEmpty() ||
            value(root["drm"]) == "true") return null
        val host = runCatching { io.ktor.http.Url(base).host.lowercase() }.getOrDefault("")
        if (host == "ok.ru" || host.endsWith(".ok.ru")) {
            // OK's HLS endpoint may be an open-ended broadcast playlist even
            // for a recorded event. Prefer its finite progressive replay when
            // supplied, so duration/range seeking can be discovered normally.
            val qualityOrder = mapOf("mobile" to 144, "lowest" to 240, "low" to 360, "sd" to 480,
                "hd" to 720, "full" to 1080, "quad" to 1440, "ultra" to 2160)
            (root["videos"] as? JsonArray)?.mapNotNull { video ->
                val entry = obj(video) ?: return@mapNotNull null
                val url = value(entry["url"])?.let { WebReplayUrls.normalize(it, base) } ?: return@mapNotNull null
                if (io.ktor.http.Url(url).encodedPath.substringAfterLast('.').lowercase() in setOf("m3u8", "mpd")) return@mapNotNull null
                val name = value(entry["name"]).orEmpty().lowercase()
                val rank = name.filter(Char::isDigit).toIntOrNull() ?: qualityOrder[name] ?: 0
                rank to url
            }?.maxByOrNull { it.first }?.second?.let { return it }
        }
        for (key in listOf("hlsManifestUrl", "hlsMasterPlaylistUrl", "contentUrl")) {
            value(root[key])?.let { WebReplayUrls.normalize(it, base) }?.takeIf(WebReplayUrls::media)?.let { return it }
        }
        val videos = root["videos"] as? JsonArray
        videos?.asReversed()?.forEach { item -> value(obj(item)?.get("url"))
            ?.let { WebReplayUrls.normalize(it, base) }?.let { return it } }
        val qualities = obj(root["qualities"])
        for (quality in listOf("auto", "1080", "720", "480", "380", "240")) {
            (qualities?.get(quality) as? JsonArray)?.forEach { item ->
                val entry = obj(item) ?: return@forEach
                if (!value(entry["type"]).orEmpty().contains("dash", true)) value(entry["url"])
                    ?.let { WebReplayUrls.normalize(it, base) }?.let { return it }
            }
        }
        return null
    }
    fun sport(text: String): String = when {
        Regex("basketball|\\bnba\\b|\\bwnba\\b|euroleague", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Basketball"
        Regex("formula|\\bf1\\b|motogp|nascar|indycar|grand prix|motorsport", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Motorsport"
        Regex("\\bnfl\\b|american football", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "American football"
        Regex("\\bmlb\\b|baseball", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Baseball"
        Regex("soccer|football|premier league|champions league", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Football"
        Regex("hockey|\\bnhl\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Hockey"
        else -> "Other"
    }
    fun date(text: String): String {
        Regex("\\b(20[0-9]{2}-[01][0-9]-[0-3][0-9])\\b").find(text)?.let { return it.groupValues[1] }
        val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        val match = Regex("(${months.joinToString("|")})\\s+([0-3]?[0-9]),?\\s+(20[0-9]{2})", RegexOption.IGNORE_CASE).find(text) ?: return ""
        val day = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return ""
        return "${match.groupValues[3]}-${(months.indexOf(match.groupValues[1].lowercase()) + 1).toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }
}
