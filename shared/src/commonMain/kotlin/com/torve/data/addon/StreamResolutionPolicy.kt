package com.torve.data.addon

import io.ktor.http.Url

/** These are provider status clips, never movie or episode playback URLs. */
internal fun isProviderMessageVideo(value: String): Boolean {
    val url = runCatching { Url(value) }.getOrNull() ?: return false
    val path = url.encodedPath.lowercase()
    return (url.host.equals("torrentio.strem.fun", true) && path.startsWith("/videos/")) ||
        (url.host.equals("aiostreams.elfhosted.com", true) && path.startsWith("/static/") && path.endsWith(".mp4"))
}

internal fun ParsedStream.hasPreResolvedAddonPlaybackUrl(
    userManagedOnly: Boolean = ExtensionRuntimePolicy.userManagedOnly,
): Boolean {
    if (configuredNzb != null) return false
    val playbackUrl = directUrl?.takeUnless { it.isBlank() } ?: return false
    if (userManagedOnly) {
        // A user-configured HTTP/JSON extension resolves its own service accounts.
        // Its video URL may be on a CDN rather than the manifest's host. Do not
        // require an unrelated local debrid account or send that URL to one.
        val target = runCatching { Url(playbackUrl) }.getOrNull() ?: return false
        return !addonBaseUrl.isNullOrBlank() &&
            target.protocol.name in setOf("http", "https") && target.host.isNotBlank() &&
            !target.encodedPath.endsWith(".nzb", ignoreCase = true)
    }
    return addonBaseUrl?.contains("torrentio.strem.fun", ignoreCase = true) == true
}
