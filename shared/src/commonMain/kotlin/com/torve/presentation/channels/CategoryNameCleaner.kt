package com.torve.presentation.channels

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.ChannelCategory

/**
 * M3U/Xtream category name cleaner.
 *
 * Raw group titles from channel providers are messy:
 *   "AL| ALBANIA SPORT GOLD RAW"
 *   "AL| ALBANIA HD RAW"
 *   "AL| FILMA HD/SERIALE"
 *   "4K| UHD 33:0P"
 *   "4K| RELAX UHD 33:0P 5"
 *
 * This utility:
 * 1. Strips quality markers (4K, UHD, FHD, HD, SD) from the name
 * 2. Strips provider/format markers (RAW, GOLD, PREMIUM, etc.)
 * 3. Strips prefix codes like "AL|", "4K|", "US|", "UK|"
 * 4. Normalizes whitespace
 * 5. Extracts quality tags from both the group name and channel names
 * 6. Groups categories that become identical after cleaning (e.g.
 *    "AL| SPORT HD" and "AL| SPORT FHD" both become "ALBANIA SPORT")
 */
object CategoryNameCleaner {

    // Markers to strip from category names (case-insensitive)
    private val STRIP_MARKERS = listOf(
        "GOLD RAW", "HD RAW", "SD RAW", "FHD RAW", "UHD RAW", "PREMIUM RAW",
        "RAW", "GOLD", "PREMIUM", "BACKUP", "MULTI",
    )

    // Quality patterns to extract AND strip
    private val QUALITY_PATTERNS = listOf(
        Regex("""(?i)\b4K\b""") to "4K",
        Regex("""(?i)\bUHD\b""") to "4K",
        Regex("""(?i)\b2160[Pp]\b""") to "4K",
        Regex("""(?i)\bFHD\b""") to "FHD",
        Regex("""(?i)\b1080[Pp]\b""") to "FHD",
        Regex("""(?i)\bHD\b""") to "HD",
        Regex("""(?i)\b720[Pp]\b""") to "HD",
        Regex("""(?i)\bSD\b""") to "SD",
        Regex("""(?i)\b480[Pp]\b""") to "SD",
    )

    // Country/provider prefix pattern: "XX|" or "XX |"
    private val PREFIX_PATTERN = Regex("""^[A-Z0-9]{1,4}\s*\|\s*""")

    // Junk patterns often seen in group titles
    private val JUNK_PATTERNS = listOf(
        Regex("""\d{2}:\d[Pp]\s*\d*"""),  // "33:0P 5", "24:0P"
        Regex("""\(\d+\)"""),               // "(123)"
        Regex("""\[\d+\]"""),               // "[123]"
        Regex("""\s*[-–—]\s*$"""),          // trailing dashes
    )

    data class CleanResult(
        val name: String,
        val qualityTags: Set<String>,
        val countryCode: String? = null,
    )

    /**
     * Clean a single category name and extract quality tags and country code from it.
     */
    fun clean(rawName: String): CleanResult {
        var name = rawName.trim()
        val extractedQualities = mutableSetOf<String>()

        // 1. Extract quality tags from the name
        for ((pattern, tag) in QUALITY_PATTERNS) {
            if (pattern.containsMatchIn(name)) {
                extractedQualities.add(tag)
                name = pattern.replace(name, " ")
            }
        }

        // 2. Strip known markers
        for (marker in STRIP_MARKERS) {
            name = name.replace(marker, " ", ignoreCase = true)
        }

        // 3. Extract country code prefix before stripping it
        val prefixMatch = PREFIX_PATTERN.find(name)
        val countryCode = prefixMatch?.value?.replace(Regex("""[\s|]+"""), "")?.uppercase()?.takeIf {
            // Only treat as country code if it's 2-3 letters (ISO country codes)
            it.length in 2..3 && it.all { c -> c.isLetter() }
        }
        name = PREFIX_PATTERN.replace(name, "")

        // 4. Strip junk patterns
        for (pattern in JUNK_PATTERNS) {
            name = pattern.replace(name, " ")
        }

        // 5. Normalize whitespace and trim
        name = name.replace(Regex("""\s+"""), " ").trim()

        // 6. Title case if the name is ALL CAPS (most providers use ALL CAPS)
        if (name.length > 3 && name == name.uppercase() && name.any { it.isLetter() }) {
            name = name.split(" ").joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
        }

        // 7. If cleaning resulted in empty string, fall back to original
        if (name.isBlank()) {
            name = rawName.trim()
        }

        return CleanResult(name, extractedQualities, countryCode)
    }

    /**
     * Extract quality tags from channel names within a category.
     */
    fun extractChannelQualityTags(channels: List<EnrichedChannel>): Set<String> {
        val tags = mutableSetOf<String>()
        for (ch in channels) {
            val name = ch.channel.name.uppercase()
            if ("4K" in name || "UHD" in name || "2160" in name) tags.add("4K")
            if ("FHD" in name || "1080" in name) tags.add("FHD")
            if ("HD" in name && "UHD" !in name && "FHD" !in name) tags.add("HD")
            if ("SD" in name || "480" in name) tags.add("SD")
        }
        return tags
    }

    /**
     * Lightweight version of processCategories that works with category name -> count pairs
     * without needing actual channel objects. Used for initial fast category display.
     */
    fun processCategoryCountsOnly(rawCounts: List<Pair<String, Long>>): List<ChannelCategory> {
        data class MergedCategory(
            var totalCount: Long = 0,
            val qualityTags: MutableSet<String> = mutableSetOf(),
            var countryCode: String? = null,
        )

        val merged = LinkedHashMap<String, MergedCategory>()
        for ((rawName, count) in rawCounts) {
            val cleaned = clean(rawName)
            val key = cleaned.name.lowercase()
            val entry = merged.getOrPut(key) { MergedCategory(countryCode = cleaned.countryCode) }
            entry.totalCount += count
            entry.qualityTags.addAll(cleaned.qualityTags)
        }

        return merged.entries.map { (key, data) ->
            // Recover display-cased name from the first raw entry that maps to this key
            val displayName = rawCounts.firstNotNullOfOrNull { (rawName, _) ->
                val cleaned = clean(rawName)
                if (cleaned.name.lowercase() == key) cleaned.name else null
            } ?: key

            ChannelCategory(
                name = displayName,
                channelCount = data.totalCount.toInt(),
                channels = emptyList(),
                qualityTags = data.qualityTags,
                countryCode = data.countryCode,
            )
        }.sortedWith(
            compareBy<ChannelCategory> { it.countryCode?.lowercase() ?: "zzz" }
                .thenBy { it.name.lowercase() },
        )
    }

    /**
     * Process a list of raw categories: clean names, merge duplicates,
     * and combine quality tags from both group name and channel names.
     *
     * Categories with identical cleaned names get merged — their channels
     * and quality tags are combined. This is common when providers have
     * separate groups for different qualities:
     *   "AL| SPORT HD" + "AL| SPORT FHD" + "AL| SPORT 4K" → "Albania Sport" [HD, FHD, 4K]
     */
    fun processCategories(
        rawGrouped: Map<String, List<EnrichedChannel>>,
    ): List<ChannelCategory> {
        // First pass: clean each group name
        data class CleanedGroup(
            val cleanedName: String,
            val nameQualities: Set<String>,
            val countryCode: String?,
            val channels: List<EnrichedChannel>,
        )

        val cleaned = rawGrouped.map { (rawName, channels) ->
            val result = clean(rawName)
            CleanedGroup(result.name, result.qualityTags, result.countryCode, channels)
        }

        // Second pass: merge groups with identical cleaned names
        val merged = cleaned.groupBy { it.cleanedName.lowercase() }
            .map { (_, groups) ->
                val displayName = groups.first().cleanedName
                val allChannels = groups.flatMap { it.channels }
                val allQualities = mutableSetOf<String>()
                groups.forEach { allQualities.addAll(it.nameQualities) }
                allQualities.addAll(extractChannelQualityTags(allChannels))
                // Use first non-null country code, or derive from channel tvgCountry
                val country = groups.firstNotNullOfOrNull { it.countryCode }
                    ?: allChannels.firstNotNullOfOrNull { it.channel.tvgCountry?.take(2)?.uppercase() }

                ChannelCategory(
                    name = displayName,
                    channelCount = allChannels.size,
                    channels = allChannels.sortedBy { it.channel.name.lowercase() },
                    qualityTags = allQualities,
                    countryCode = country,
                )
            }
            .sortedWith(
                compareBy<ChannelCategory> { it.countryCode?.lowercase() ?: "zzz" }
                    .thenBy { it.name.lowercase() },
            )

        return merged
    }
}
