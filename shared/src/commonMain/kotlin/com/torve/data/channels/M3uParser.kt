package com.torve.data.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.M3uPlaylist

class M3uParser {

    companion object {
        private const val MAX_CHANNELS = 50_000
        private const val MAX_LINES = 50_000
        private const val UTF8_BOM = "﻿"
    }

    fun parse(content: String, playlistId: String = ""): M3uPlaylist {
        // Strip UTF-8 BOM which is common on IPTV provider feeds and would
        // otherwise make the #EXTM3U header check fail silently.
        val normalized = content.removePrefix(UTF8_BOM).trimStart()
        if (!normalized.startsWith("#EXTM3U")) {
            return M3uPlaylist(
                channels = emptyList(),
                error = "Invalid M3U: missing #EXTM3U header",
            )
        }

        val lines = normalized.lines().map { it.trim() }
        if (lines.size > MAX_LINES) {
            return M3uPlaylist(
                channels = emptyList(),
                error = "Playlist too large (${lines.size} lines, max $MAX_LINES)",
            )
        }

        val channels = mutableListOf<Channel>()
        var playlistEpgUrl: String? = null
        var playlistRefresh: Int? = null

        var i = 0

        // Parse header
        val header = lines[0]
        playlistEpgUrl = extractAttr(header, "url-tvg")
            ?: extractAttr(header, "x-tvg-url")
        playlistRefresh = extractAttr(header, "refresh")?.toIntOrNull()
        i = 1

        // Parse channels
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF:")) {
                try {
                    val builder = parseExtInf(line, playlistId)

                    // Collect extra directives before URL
                    i++
                    while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith("#"))) {
                        when {
                            lines[i].startsWith("#EXTVLCOPT:") ->
                                builder.vlcOptions.add(lines[i].removePrefix("#EXTVLCOPT:"))
                            lines[i].startsWith("#KODIPROP:") -> {
                                val parts = lines[i].removePrefix("#KODIPROP:").split("=", limit = 2)
                                if (parts.size == 2) builder.kodiProps[parts[0]] = parts[1]
                            }
                        }
                        i++
                    }

                    // Next non-empty non-comment line = stream URL
                    if (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#")) {
                        builder.url = lines[i]
                        // Skip malformed entries (empty title or URL)
                        if (builder.name.isNotBlank() && builder.url.isNotBlank()) {
                            channels.add(builder.build())
                            if (channels.size >= MAX_CHANNELS) {
                                println("M3uParser: Channel limit ($MAX_CHANNELS) reached, truncating playlist")
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed entry, continue parsing
                }
            }
            i++
        }

        return M3uPlaylist(
            epgUrl = playlistEpgUrl,
            refreshSeconds = playlistRefresh,
            channels = channels,
        )
    }

    private fun parseExtInf(line: String, playlistId: String): ChannelBuilder {
        val builder = ChannelBuilder(playlistId)
        val afterPrefix = line.removePrefix("#EXTINF:")

        builder.duration = afterPrefix.takeWhile { it != ' ' && it != ',' }.toIntOrNull() ?: -1

        val commaIdx = afterPrefix.lastIndexOf(',')
        if (commaIdx > 0) {
            builder.name = afterPrefix.substring(commaIdx + 1).trim()
            val attrSection = afterPrefix.substring(0, commaIdx)

            builder.tvgId = extractAttr(attrSection, "tvg-id")
            builder.tvgName = extractAttr(attrSection, "tvg-name")
            builder.tvgLogo = extractAttr(attrSection, "tvg-logo")
            builder.groupTitle = extractAttr(attrSection, "group-title")
            builder.tvgLanguage = extractAttr(attrSection, "tvg-language")
            builder.tvgCountry = extractAttr(attrSection, "tvg-country")
            builder.tvgShift = extractAttr(attrSection, "tvg-shift")?.toIntOrNull()
            builder.channelNumber = extractAttr(attrSection, "channel-number")?.toIntOrNull()
            builder.catchupType = extractAttr(attrSection, "catchup")
            builder.catchupDays = extractAttr(attrSection, "catchup-days")?.toIntOrNull()
            builder.catchupSource = extractAttr(attrSection, "catchup-source")
            builder.userAgent = extractAttr(attrSection, "user-agent")
        }
        return builder
    }

    private fun extractAttr(text: String, key: String): String? {
        val pattern = """$key="([^"]*?)"""".toRegex()
        return pattern.find(text)?.groupValues?.get(1)
    }
}

private class ChannelBuilder(val playlistId: String) {
    var name = ""
    var url = ""
    var tvgId: String? = null
    var tvgName: String? = null
    var tvgLogo: String? = null
    var groupTitle: String? = null
    var tvgLanguage: String? = null
    var tvgCountry: String? = null
    var tvgShift: Int? = null
    var channelNumber: Int? = null
    var duration = -1
    var catchupType: String? = null
    var catchupDays: Int? = null
    var catchupSource: String? = null
    var userAgent: String? = null
    val vlcOptions = mutableListOf<String>()
    val kodiProps = mutableMapOf<String, String>()

    fun build() = Channel(
        name = name,
        url = url,
        tvgId = tvgId,
        tvgName = tvgName,
        tvgLogo = tvgLogo,
        groupTitle = groupTitle,
        tvgLanguage = tvgLanguage,
        tvgCountry = tvgCountry,
        tvgShift = tvgShift,
        channelNumber = channelNumber,
        duration = duration,
        catchupType = catchupType,
        catchupDays = catchupDays,
        catchupSource = catchupSource,
        userAgent = userAgent,
        vlcOptions = vlcOptions.toList(),
        kodiProps = kodiProps.toMap(),
        playlistId = playlistId,
        contentType = inferContentType(url, groupTitle),
    )

    private fun inferContentType(url: String, groupTitle: String?): ChannelContentType {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.contains("/movie/") || path.contains("/vod/movies") -> ChannelContentType.VOD_MOVIE
            path.contains("/series/") || path.contains("/vod/series") -> ChannelContentType.VOD_SERIES
            path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi") ||
                path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".wmv") ||
                path.endsWith(".webm") -> ChannelContentType.VOD_MOVIE
            groupTitle?.startsWith("VOD:", ignoreCase = true) == true -> ChannelContentType.VOD_MOVIE
            else -> ChannelContentType.UNKNOWN
        }
    }
}
