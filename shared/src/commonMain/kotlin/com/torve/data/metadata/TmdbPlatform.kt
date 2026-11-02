package com.torve.data.metadata

expect fun tmdbApiKey(): String

/** Null retains the existing provider on platforms outside the Apple rollout. */
internal expect fun platformMetadataBackendBaseUrl(): String?

/** Presentation snapshots must not restore artwork from a retired transport. */
internal fun metadataPresentationCacheKey(key: String, backend: String? = platformMetadataBackendBaseUrl()): String =
    if (com.torve.data.addon.ExtensionRuntimePolicy.userMetadataOnly) "${key}_user_extensions_v1"
    else if (backend == null) key else "${key}_apple_metadata_v1"

/** A non-empty saved URL can still belong to the provider retired on Apple. */
internal fun metadataArtworkNeedsRefresh(url: String?, backend: String? = platformMetadataBackendBaseUrl()): Boolean {
    if (url.isNullOrBlank()) return true
    if (backend == null) return false
    val host = runCatching { io.ktor.http.Url(url).host.lowercase() }.getOrNull() ?: return true
    return host.isBlank() || host == "tmdb.org" || host.endsWith(".tmdb.org") ||
        host == "themoviedb.org" || host.endsWith(".themoviedb.org")
}

internal expect fun lookupHostAddresses(host: String): List<String>?
