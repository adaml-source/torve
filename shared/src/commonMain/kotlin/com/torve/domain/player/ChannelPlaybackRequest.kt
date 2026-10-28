package com.torve.domain.player

import com.torve.domain.model.Channel
import io.ktor.http.decodeURLPart

data class ChannelPlaybackRequest(val url: String, val headers: Map<String, String>)

/** Same header precedence as the retained Android live player. */
object ChannelPlaybackRequestPolicy {
    fun request(channel: Channel): ChannelPlaybackRequest {
        val raw = channel.url.trim()
        val headers = linkedMapOf<String, String>()
        channel.userAgent?.takeIf(String::isNotBlank)?.let { headers["User-Agent"] = it.trim() }
        channel.vlcOptions.forEach { option ->
            val parts = option.split('=', limit = 2)
            if (parts.size == 2) when (parts[0].trim().lowercase()) {
                "http-user-agent" -> headers["User-Agent"] = parts[1].trim()
                "http-referrer", "http-referer" -> headers["Referer"] = parts[1].trim()
                "http-origin" -> headers["Origin"] = parts[1].trim()
            }
        }
        channel.kodiProps.forEach { (key, value) ->
            when (key.trim().lowercase()) {
                "inputstream.adaptive.stream_headers", "inputstream.adaptive.manifest_headers" -> headers.putAll(parse(value))
                "inputstream.adaptive.user_agent" -> headers["User-Agent"] = value.trim()
            }
        }
        headers.putAll(parse(raw.substringAfter('|', "")))
        return ChannelPlaybackRequest(raw.substringBefore('|').trim(), headers.filterValues(String::isNotBlank))
    }
    private fun parse(value: String): Map<String, String> = buildMap {
        value.split('&').forEach { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) return@forEach
            val key = runCatching { parts[0].decodeURLPart() }.getOrDefault(parts[0]).trim()
            val content = runCatching { parts[1].decodeURLPart() }.getOrDefault(parts[1]).trim()
            val name = when (key.lowercase()) {
                "user-agent", "user_agent", "http-user-agent" -> "User-Agent"
                "referer", "referrer", "http-referrer", "http-referer" -> "Referer"
                "origin", "http-origin" -> "Origin"
                "cookie" -> "Cookie"
                else -> key
            }
            if (name.isNotBlank() && content.isNotBlank()) put(name, content)
        }
    }
}
