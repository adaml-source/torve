package com.streamvault.data.iptv

import com.streamvault.domain.model.IptvChannel
import com.streamvault.domain.model.M3uPlaylist

class M3uParser {

    fun parse(content: String, playlistId: String = ""): M3uPlaylist {
        val lines = content.lines().map { it.trim() }
        val channels = mutableListOf<IptvChannel>()
        var playlistEpgUrl: String? = null
        var playlistRefresh: Int? = null

        var i = 0

        // Parse header
        if (lines.firstOrNull()?.startsWith("#EXTM3U") == true) {
            val header = lines[0]
            playlistEpgUrl = extractAttr(header, "url-tvg")
                ?: extractAttr(header, "x-tvg-url")
            playlistRefresh = extractAttr(header, "refresh")?.toIntOrNull()
            i = 1
        }

        // Parse channels
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF:")) {
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
                    channels.add(builder.build())
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

    private fun parseExtInf(line: String, playlistId: String): IptvChannelBuilder {
        val builder = IptvChannelBuilder(playlistId)
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

private class IptvChannelBuilder(val playlistId: String) {
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

    fun build() = IptvChannel(
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
    )
}
