package com.torve.data.addon

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
        val nameParts = (stream.name ?: "").split("\n")
        val rawAddonName = nameParts.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val isPandaOrigin = isPandaAddon(fallbackAddonName, addonBaseUrl)
        val addonName = if (isPandaOrigin) {
            "Panda"
        } else {
            rawAddonName ?: fallbackAddonName
        }
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
        val displaySource = if (isPandaOrigin) {
            distinctSourceSegments(
                rawAddonName?.takeUnless { it.equals(addonName, ignoreCase = true) },
                source,
            )
        } else {
            source
        }

        val fullText = title
        val hdr = extractHdr(fullText)
        val audioCodec = extractAudioCodec(fullText)
        val languages = extractLanguages(fullText)
        val cachedByAddon = hasDebridCachedMarker(
            text = listOfNotNull(stream.name, stream.title, stream.behaviorHints?.filename).joinToString("\n"),
        )

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
            addonBaseUrl = addonBaseUrl,
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
     * Extract audio-language codes from Panda's `🗣️ DE, EN` badge (or unicode
     * variant `🗣  DE, EN`). Returns upper-cased, trimmed codes in the order
     * they appear. Anything that isn't a 2–3 letter code is rejected so
     * multi-line text or stray punctuation doesn't leak in.
     */
    fun extractLanguages(text: String): List<String> {
        // Match the 🗣 emoji (with or without variation selector) followed by a
        // run of letters/commas/spaces up to the next non-language character.
        val badgeRegex = Regex("\uD83D\uDDE3\uFE0F?\\s*([A-Za-z,\\s]+)")
        val match = badgeRegex.find(text) ?: return emptyList()
        return match.groupValues[1]
            .split(',')
            .map { it.trim().uppercase() }
            .filter { it.matches(Regex("^[A-Z]{2,3}$")) }
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
        return listOf(
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
