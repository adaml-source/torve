package com.torve.data.addon

/** A torrent file remains the same across add-on URLs. Never merge different files in a pack. */
internal fun ParsedStream.duplicateKey(): String {
    val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: magnetUrl?.let { Regex("(?i)(?:xt=urn:btih:)([a-z0-9]+)").find(it)?.groupValues?.get(1)?.lowercase() }
    return when {
        hash != null -> "hash:$hash:file:${fileIdx ?: -1}"
        !directUrl.isNullOrBlank() -> "url:$directUrl"
        !magnetUrl.isNullOrBlank() -> "magnet:$magnetUrl:file:${fileIdx ?: -1}"
        else -> "source:${addonBaseUrl ?: addonName}:$title:${size.orEmpty()}"
    }
}

internal fun deduplicateStreams(streams: List<ParsedStream>): List<ParsedStream> {
    val result = linkedMapOf<String, ParsedStream>()
    streams.forEach { candidate ->
        val key = candidate.duplicateKey()
        val old = result[key]
        // A ready/cached route is more useful than an uncached duplicate.
        if (old == null || (!old.isCached && candidate.isCached)) result[key] = candidate
    }
    return result.values.toList()
}

/** Deliberately requires pack markers; a normal S01E02 release is not a season pack. */
internal fun ParsedStream.isSeasonPack(): Boolean {
    val text = listOfNotNull(title, fileName).joinToString(" ").replace('.', ' ').replace('_', ' ')
    return Regex("""(?i)\b(?:complete\s+(?:series|season)|season\s+\d+\s*(?:complete|pack)|s\d{1,2}\s*(?:complete|pack)|season\s*pack|full\s+season)\b""").containsMatchIn(text) ||
        (Regex("""(?i)\bs\d{1,2}\b""").containsMatchIn(text) && !Regex("""(?i)\bs\d{1,2}\s*e\d{1,3}\b""").containsMatchIn(text))
}
