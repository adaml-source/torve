package com.torve.data.addon

internal fun ParsedStream.hasPreResolvedAddonPlaybackUrl(): Boolean {
    if (directUrl.isNullOrBlank()) return false
    return addonBaseUrl?.contains("torrentio.strem.fun", ignoreCase = true) == true
}
