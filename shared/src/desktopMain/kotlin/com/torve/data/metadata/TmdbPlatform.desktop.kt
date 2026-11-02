package com.torve.data.metadata

import java.net.InetAddress

internal actual fun platformMetadataBackendBaseUrl(): String? = null

actual fun tmdbApiKey(): String {
    return System.getProperty("TMDB_API_KEY")
        ?: System.getenv("TMDB_API_KEY")
        ?: ""
}

internal actual fun lookupHostAddresses(host: String): List<String>? {
    return runCatching {
        InetAddress.getAllByName(host).mapNotNull { address -> address.hostAddress }
    }.getOrNull()
}
