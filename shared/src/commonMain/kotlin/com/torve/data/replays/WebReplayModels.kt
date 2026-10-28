package com.torve.data.replays

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class ReplayWebsite(val id: String, val name: String, val url: String, val enabled: Boolean = true)
@Serializable data class ReplayEvent(val id: String, val websiteId: String, val title: String, val pageUrl: String,
    val artwork: String? = null, val sport: String = "Other", val competition: String = "", val date: String = "") {
    fun mediaItem() = MediaItem(id = id, title = title, type = MediaType.MOVIE, posterUrl = artwork,
        backdropUrl = artwork, familySourceKey = "web-replays")
}
data class ReplayPage(val events: List<ReplayEvent>, val next: String? = null)
data class ReplaySourceOption(val id: String, val title: String, val url: String, val referrer: String, val gameTitle: String? = null)
data class ResolvedReplay(val url: String, val headers: Map<String, String> = emptyMap())
@Serializable data class ReplayProgress(val positionMs: Long, val durationMs: Long, val updatedAt: Long)
@Serializable data class ReplayConfiguration(val enabled: Boolean = false, val websites: List<ReplayWebsite> = emptyList())
@Serializable internal data class ReplayCache(val events: List<ReplayEvent> = emptyList(), val savedAt: Long = 0,
    val nextPages: Map<String, String> = emptyMap())
data class WebReplayState(val owner: String = "", val loaded: Boolean = false, val enabled: Boolean = false,
    val websites: List<ReplayWebsite> = emptyList(), val events: List<ReplayEvent> = emptyList(),
    val loading: Boolean = false, val hasMore: Boolean = false, val error: String? = null)

object WebReplayUrls {
    fun normalize(value: String, base: String? = null): String? = runCatching {
        val input = value.trim().replace("\\/", "/")
        if (input.isEmpty() || input.length > 8192 || input.any { it <= ' ' } || '\\' in input) return null
        val absolute = when {
            input.startsWith("//") -> (base?.let { Url(it).protocol.name } ?: "https") + ":" + input
            input.contains("://") -> input
            base != null -> URLBuilder(base).apply {
                // A new path/query replaces the base query. Keeping ?page=2
                // would create duplicate event identities and bad poster URLs.
                if (!input.startsWith('#')) parameters.clear()
                takeFrom(input)
            }.buildString()
            else -> "https://$input"
        }
        val url = Url(absolute)
        if (url.protocol.name !in setOf("https", "http") || url.user != null || url.password != null) return null
        val host = url.host.lowercase().trimEnd('.')
        if (host.isEmpty() || host == "localhost" || !host.contains('.') || host.endsWith(".local") ||
            host.endsWith(".localhost") || ':' in host || '%' in host) return null
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (host.matches(Regex("[0-9a-fA-FxX.]+")) && host.first().isDigit() &&
            (octets.size != 4 || host.split('.').zip(octets).any { (raw, value) -> raw != value.toString() })) return null
        if (octets.size == 4 && (octets.any { it !in 0..255 } || octets[0] in listOf(0, 10, 127) || octets[0] >= 224 ||
            octets[0] == 169 && octets[1] == 254 || octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] in listOf(0, 168) || octets[0] == 198 && octets[1] in 18..19)) return null
        URLBuilder(url).apply { fragment = "" }.buildString()
    }.getOrNull()
    fun origin(url: String): String = URLBuilder(Url(url)).apply { encodedPath = "/"; parameters.clear(); fragment = "" }.buildString()
    fun sameOrigin(a: String, b: String) = runCatching { origin(a) == origin(b) }.getOrDefault(false)
    fun media(url: String): Boolean = runCatching { Url(url).encodedPath.substringAfterLast('.').lowercase() in
        setOf("mp4", "m3u8", "m4v", "mov", "webm", "mpd", "mkv", "ts") }.getOrDefault(false)
    fun identity(value: String): String {
        var hash = -3750763034362895579L
        value.encodeToByteArray().forEach { hash = (hash xor (it.toLong() and 255)) * 1099511628211L }
        return hash.toULong().toString(16)
    }
}
