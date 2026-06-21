package com.torve.domain.model

private const val CHANNEL_KEY_SEPARATOR = "::"

fun stableChannelId(channel: Channel): String {
    return stableChannelId(
        playlistId = channel.playlistId,
        channel = channel,
    )
}

fun stableChannelId(
    playlistId: String,
    channel: Channel,
): String {
    val normalizedPlaylistId = playlistId.trim()
    channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { tvgId ->
        return normalizedPlaylistId + CHANNEL_KEY_SEPARATOR + tvgId
    }

    val normalizedUrl = channel.url
        .substringBefore('?')
        .trim()
        .lowercase()
    if (normalizedUrl.isNotEmpty()) {
        return normalizedPlaylistId + CHANNEL_KEY_SEPARATOR + normalizedUrl
    }

    val normalizedName = normalizeStableChannelName(channel.name)
    return normalizedPlaylistId + CHANNEL_KEY_SEPARATOR + normalizedName
}

fun legacyChannelId(channel: Channel): String {
    return channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }
        ?: "${channel.playlistId}_${channel.name}"
}

fun channelIdentityCandidates(channel: Channel): Set<String> {
    return buildSet {
        add(stableChannelId(channel))
        add(legacyChannelId(channel))
    }
}

fun channelMatchesIdentity(channel: Channel, candidate: String?): Boolean {
    if (candidate.isNullOrBlank()) return false
    return candidate in channelIdentityCandidates(channel)
}

private fun normalizeStableChannelName(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return "unknown"
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
    return out.toString().ifEmpty { "unknown" }
}
