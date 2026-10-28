package com.torve.data.addon

import com.torve.util.DisplayTextDecoder

/**
 * Parses raw Stremio stream objects into our app's ParsedStream format.
 * Ported from stremio.ts parseStream / extractQuality / extractCodec.
 */
object StreamParser {

    fun parse(
        stream: StremioStream,
        fallbackAddonName: String = "Unknown",
        addonBaseUrl: String? = null,
    ): ParsedStream {
        val nameParts = DisplayTextDecoder.decode(stream.name ?: "").split("\n")
        val rawAddonName = nameParts.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val isPandaOrigin = isPandaAddon(fallbackAddonName, addonBaseUrl)
        val addonName = if (isPandaOrigin) {
            "Panda"
        } else {
            rawAddonName ?: DisplayTextDecoder.decode(fallbackAddonName)
        }
        val qualityFromName = nameParts.drop(1).joinToString(" ").ifBlank { nameParts.firstOrNull().orEmpty() }

        val title = DisplayTextDecoder.decodeReleaseTitle(stream.title?.takeIf(String::isNotBlank)
            ?: stream.behaviorHints?.filename?.takeIf(String::isNotBlank)
            ?: stream.description?.takeIf(String::isNotBlank) ?: "Unknown")
        val lines = title.split("\n")
        val mainTitle = lines.firstOrNull() ?: title
        val fullText = listOfNotNull(title, stream.description?.let(DisplayTextDecoder::decodeReleaseTitle)).joinToString("\n")

        val quality = extractQuality("$qualityFromName $mainTitle")

        // Extract size (💾 XX.X GB pattern or just numbers)
        val sizeRegex1 = Regex("💾\\s*([\\d.]+\\s*[GMKT]B)", RegexOption.IGNORE_CASE)
        val sizeRegex2 = Regex("([\\d.]+)\\s*(GB|MB)", RegexOption.IGNORE_CASE)
        val sizeMatch = sizeRegex1.find(fullText) ?: sizeRegex2.find(fullText)
        val size = sizeMatch?.value?.replace("💾", "")?.trim()
            ?: stream.behaviorHints?.videoSize?.takeIf { it > 0 }?.let { bytes -> "${bytes / 1_000_000} MB" }

        // Extract seeds (👤 pattern)
        val seedsMatch = Regex("👤\\s*(\\d+)").find(fullText)
        val seeds = stream.behaviorHints?.seeders ?: seedsMatch?.groupValues?.get(1)?.toIntOrNull()

        val codec = extractCodec(fullText)

        // Extract source (last line often has the indexer)
        val source = stream.behaviorHints?.indexer?.takeIf(String::isNotBlank)
            ?: Regex("🔍\\s*([^\\n]+)").find(fullText)?.groupValues?.get(1)?.trim()
            ?: if (lines.size > 1) {
            lines.last().replace(Regex("[⚙️]"), "").trim().takeIf { it.isNotBlank() }
        } else null
        val displaySource = if (isPandaOrigin) {
            distinctSourceSegments(
                rawAddonName?.takeUnless { it.equals(addonName, ignoreCase = true) },
                source,
            )
        } else {
            source
        }

        val hdr = extractHdr(fullText)
        val audioCodec = extractAudioCodec(fullText)
        val languageText = listOfNotNull(fullText,
            stream.behaviorHints?.filename?.let(DisplayTextDecoder::decodeReleaseTitle)).joinToString("\n")
        val languages = extractLanguages(languageText)
        // An explicit service-scoped false must override any decorative cached badge.
        val cachedByAddon = stream.behaviorHints?.let { hints -> hints.cached?.takeIf { !hints.service.isNullOrBlank() } }
            ?: hasDebridCachedMarker(listOfNotNull(stream.name, stream.title, stream.description).joinToString("\n"))

        val primaryUrl = stream.url?.trim()?.takeIf { it.isNotBlank() }
        val externalUrl = stream.externalUrl?.trim()?.takeIf { it.isNotBlank() }
        val explicitMagnet = stream.magnet?.trim()?.takeIf { it.isNotBlank() && it.isMagnetUri() }
        val magnetUrl = listOfNotNull(primaryUrl, externalUrl, explicitMagnet)
            .firstOrNull { it.isMagnetUri() }
        val directUrl = primaryUrl?.takeUnless { it.isMagnetUri() }
        val resolvedInfoHash = listOfNotNull(
            stream.infoHash,
            stream.infoHashSnake,
            stream.hash,
            magnetUrl?.extractBtihInfoHash(),
        ).firstNotNullOfOrNull { candidate ->
            candidate.normalizeBtihInfoHash()
        }

        return ParsedStream(
            addonName = addonName,
            quality = quality,
            title = mainTitle,
            fileName = stream.behaviorHints?.filename?.let(DisplayTextDecoder::decodeReleaseTitle),
            infoHash = resolvedInfoHash,
            fileIdx = stream.fileIdx,
            magnetUrl = magnetUrl,
            directUrl = directUrl,
            size = size,
            codec = codec,
            seeds = seeds,
            source = displaySource,
            isCached = cachedByAddon,
            hdr = hdr,
            audioCodec = audioCodec,
            languages = languages,
            subtitleLanguages = SourceLanguages.extractSubtitles(languageText),
            addonBaseUrl = addonBaseUrl,
            bingeGroup = stream.behaviorHints?.bingeGroup?.trim()?.takeIf { it.isNotEmpty() },
            downloadService = stream.behaviorHints?.service?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun isPandaAddon(fallbackAddonName: String, addonBaseUrl: String?): Boolean {
        return fallbackAddonName.contains("Panda", ignoreCase = true) ||
            addonBaseUrl?.contains("panda.torve.app", ignoreCase = true) == true
    }

    private fun distinctSourceSegments(vararg segments: String?): String? {
        val values = segments
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase() }
            .filterNot { it.equals("Panda", ignoreCase = true) }
        return values.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /**
     * Prefer explicit audio badges, otherwise infer conservative release-file
     * tags. Subtitle-only tags and an unspecified MULTI never establish audio.
     */
    fun extractLanguages(text: String): List<String> = SourceLanguages.extract(text)

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

    fun extractHdr(text: String): String? {
        val t = text.uppercase()
        return when {
            (t.contains("DOVI") || t.contains("DOLBY VISION") || t.contains("DOLBYVISION")) &&
                (t.contains("HDR10") || t.contains("HDR")) -> "DV HDR"
            t.contains("DOVI") || t.contains("DOLBY VISION") || t.contains("DOLBYVISION") ||
                Regex("\\bDV\\b").containsMatchIn(t) -> "DV"
            t.contains("HDR10+") -> "HDR10+"
            t.contains("HDR10") -> "HDR10"
            Regex("\\bHDR\\b").containsMatchIn(t) -> "HDR"
            else -> null
        }
    }

    fun extractAudioCodec(text: String): String? {
        val t = text.uppercase()
        return when {
            t.contains("ATMOS") -> "Atmos"
            t.contains("TRUEHD") || t.contains("TRUE HD") || t.contains("TRUE.HD") -> "TrueHD"
            Regex("DTS[.\\- ]?HD[.\\- ]?MA", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "DTS-HD MA"
            Regex("\\bDTS\\b").containsMatchIn(t) -> "DTS"
            t.contains("EAC3") || t.contains("EAC-3") || t.contains("DD+") || t.contains("DDP") ||
                Regex("\\bE-?AC-?3\\b").containsMatchIn(t) -> "EAC3"
            Regex("\\bAAC\\b").containsMatchIn(t) -> "AAC"
            else -> null
        }
    }

    private fun hasDebridCachedMarker(text: String): Boolean {
        val t = text.uppercase()
        return Regex("\\[(?:RD|AD|PM|TB|DL|ED|OC|PP|TORBOX)\\s*⚡\\uFE0F?]", RegexOption.IGNORE_CASE).containsMatchIn(text) || listOf(
            "RD+",
            "AD+",
            "PM+",
            "TB+",
            "REALDEBRID+",
            "REAL-DEBRID+",
            "ALLDEBRID+",
            "ALL-DEBRID+",
            "PREMIUMIZE+",
            "TORBOX+",
        ).any { marker -> t.contains(marker) }
    }
}
