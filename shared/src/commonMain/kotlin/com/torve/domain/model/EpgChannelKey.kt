package com.torve.domain.model

private const val EPG_KEY_SEPARATOR = "::"

/**
 * Canonical EPG key used everywhere from ingest to UI lookups.
 * Format:
 * - tvgId present: "<playlistId>::<tvgId>"
 * - fallback:      "<playlistId>::<normalizedChannelName>"
 */
fun canonicalEpgChannelKey(
    playlistId: String,
    channel: Channel,
): String? {
    val normalizedPlaylistId = playlistId.trim().ifEmpty { return null }
    channel.tvgId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return normalizedPlaylistId + EPG_KEY_SEPARATOR + it }

    val normalizedName = normalizeEpgChannelName(channel.name)
    if (normalizedName.isEmpty()) return null
    return normalizedPlaylistId + EPG_KEY_SEPARATOR + normalizedName
}

fun canonicalEpgChannelKey(channel: Channel): String? {
    return canonicalEpgChannelKey(
        playlistId = channel.playlistId,
        channel = channel,
    )
}

fun epgChannelLookupKeys(
    playlistId: String,
    channel: Channel,
): List<String> {
    val normalizedPlaylistId = playlistId.trim().ifEmpty { return emptyList() }
    val keys = LinkedHashSet<String>()

    fun addRaw(value: String?) {
        value?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { keys += normalizedPlaylistId + EPG_KEY_SEPARATOR + it }
    }

    fun addNormalized(value: String?) {
        value?.let(::normalizeEpgChannelName)
            ?.takeIf { it.isNotEmpty() }
            ?.let { keys += normalizedPlaylistId + EPG_KEY_SEPARATOR + it }
    }

    canonicalEpgChannelKey(normalizedPlaylistId, channel)?.let(keys::add)
    addRaw(channel.tvgId)
    addNormalized(channel.tvgId)
    addNormalized(channel.tvgName)
    addNormalized(channel.name)

    return keys.toList()
}

fun epgChannelLookupKeys(channel: Channel): List<String> {
    return epgChannelLookupKeys(
        playlistId = channel.playlistId,
        channel = channel,
    )
}

fun programmesForEpgChannel(
    programmesByChannelKey: Map<String, List<EpgProgramme>>,
    playlistId: String,
    channel: Channel,
): List<EpgProgramme> {
    return LiveTvEpgResolver.resolveProgrammes(
        channel = channel,
        playlistId = playlistId,
        programmesByChannelKey = programmesByChannelKey,
    )
}

fun programmesForEpgChannel(
    programmesByChannelKey: Map<String, List<EpgProgramme>>,
    channel: Channel,
): List<EpgProgramme> {
    return programmesForEpgChannel(
        programmesByChannelKey = programmesByChannelKey,
        playlistId = channel.playlistId,
        channel = channel,
    )
}

private fun normalizeEpgChannelName(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    val out = StringBuilder(trimmed.length)
    trimmed.forEach { ch ->
        val lowered = when {
            ch in 'A'..'Z' -> (ch.code + 32).toChar()
            else -> ch
        }
        if ((lowered in 'a'..'z') || (lowered in '0'..'9')) {
            out.append(lowered)
        }
    }
    return out.toString()
}
