package com.streamvault.data.addon

/**
 * Parses raw Stremio stream objects into our app's ParsedStream format.
 * Ported from stremio.ts parseStream / extractQuality / extractCodec.
 */
object StreamParser {

    fun parse(stream: StremioStream, fallbackAddonName: String = "Unknown"): ParsedStream {
        val nameParts = (stream.name ?: "").split("\n")
        val addonName = nameParts.firstOrNull()?.takeIf { it.isNotBlank() } ?: fallbackAddonName
        val qualityFromName = nameParts.getOrNull(1) ?: ""

        val title = stream.title ?: stream.behaviorHints?.filename ?: "Unknown"
        val lines = title.split("\n")
        val mainTitle = lines.firstOrNull() ?: title

        val quality = extractQuality(qualityFromName.ifBlank { mainTitle })

        // Extract size (💾 XX.X GB pattern or just numbers)
        val sizeRegex1 = Regex("💾\\s*([\\d.]+\\s*[GMKT]B)", RegexOption.IGNORE_CASE)
        val sizeRegex2 = Regex("([\\d.]+)\\s*(GB|MB)", RegexOption.IGNORE_CASE)
        val sizeMatch = sizeRegex1.find(title) ?: sizeRegex2.find(title)
        val size = sizeMatch?.value?.replace("💾", "")?.trim()

        // Extract seeds (👤 pattern)
        val seedsMatch = Regex("👤\\s*(\\d+)").find(title)
        val seeds = seedsMatch?.groupValues?.get(1)?.toIntOrNull()

        val codec = extractCodec(mainTitle)

        // Extract source (last line often has the indexer)
        val source = if (lines.size > 1) {
            lines.last().replace(Regex("[⚙️]"), "").trim().takeIf { it.isNotBlank() }
        } else null

        return ParsedStream(
            addonName = addonName,
            quality = quality,
            title = mainTitle,
            infoHash = stream.infoHash,
            fileIdx = stream.fileIdx,
            directUrl = stream.url,
            size = size,
            codec = codec,
            seeds = seeds,
            source = source,
        )
    }

    fun extractQuality(text: String): String {
        val t = text.uppercase()
        return when {
            t.contains("2160") || t.contains("4K") || t.contains("UHD") -> "4K"
            t.contains("1080") -> "1080p"
            t.contains("720") -> "720p"
            t.contains("480") -> "480p"
            else -> "1080p"
        }
    }

    fun extractCodec(text: String): String {
        val t = text.uppercase()
        return when {
            t.contains("HEVC") || t.contains("X265") || t.contains("H.265") || t.contains("H265") -> "HEVC"
            t.contains("AV1") -> "AV1"
            t.contains("H.264") || t.contains("H264") || t.contains("X264") || t.contains("AVC") -> "H.264"
            else -> ""
        }
    }
}
