package com.torve.data.channels

import io.ktor.http.Url

internal sealed class PlaylistIdentity {
    data class M3u(val url: String) : PlaylistIdentity()
    data class Xtream(val server: String, val username: String) : PlaylistIdentity()
}

internal fun m3uPlaylistIdentity(url: String?): PlaylistIdentity.M3u? {
    val normalized = normalizeM3uPlaylistUrl(url)
    return normalized?.let(PlaylistIdentity::M3u)
}

internal fun xtreamPlaylistIdentity(
    server: String?,
    username: String?,
): PlaylistIdentity.Xtream? {
    val normalizedServer = normalizeXtreamServer(server) ?: return null
    val normalizedUsername = username?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return PlaylistIdentity.Xtream(
        server = normalizedServer,
        username = normalizedUsername,
    )
}

internal fun playlistIdentityFor(
    type: String?,
    url: String?,
    server: String?,
    username: String?,
): PlaylistIdentity? {
    return if (type.equals("xtream", ignoreCase = true)) {
        xtreamPlaylistIdentity(server, username)
    } else {
        m3uPlaylistIdentity(url)
    }
}

private fun normalizeM3uPlaylistUrl(url: String?): String? {
    val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return normalizeUrlSchemeAndHost(trimmed, stripXtreamPlayerApi = false)
}

private fun normalizeXtreamServer(server: String?): String? {
    val trimmed = server
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return normalizeUrlSchemeAndHost(
        value = trimmed,
        stripXtreamPlayerApi = true,
    ).trimEnd('/')
}

private fun normalizeUrlSchemeAndHost(
    value: String,
    stripXtreamPlayerApi: Boolean,
): String {
    val candidate = if (stripXtreamPlayerApi) {
        value.substringBefore('?').trimEnd('/').removeSuffix("/player_api.php")
    } else {
        value
    }
    return runCatching {
        val parsed = Url(candidate)
        val scheme = parsed.protocol.name.lowercase()
        val host = parsed.host.lowercase()
        val port = parsed.port.takeIf { it > 0 && it != parsed.protocol.defaultPort }
            ?.let { ":$it" }
            .orEmpty()
        val path = parsed.encodedPath.ifBlank { "" }
        val query = parsed.encodedQuery.takeIf { it.isNotBlank() }
            ?.let { "?$it" }
            .orEmpty()
        "$scheme://$host$port$path$query"
    }.getOrElse {
        candidate
    }
}
