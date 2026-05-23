package com.torve.desktop.ui.v2.discovery

import com.torve.data.metadata.TmdbApiClient

data class BrandLogoRef(
    val id: String?,
    val name: String,
    val logoUrl: String?,
)

fun resolveBrandLogoUrl(
    absoluteLogoUrl: String? = null,
    tmdbLogoPath: String? = null,
    tmdbImageBase: String = TmdbApiClient.IMAGE_BASE,
    size: String = "w154",
): String? {
    absoluteLogoUrl
        ?.trim()
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        ?.let { return it }

    val path = tmdbLogoPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (path.startsWith("https://") || path.startsWith("http://")) return path
    val normalizedBase = tmdbImageBase.trimEnd('/')
    val normalizedPath = if (path.startsWith("/")) path else "/$path"
    return "$normalizedBase/$size$normalizedPath"
}

