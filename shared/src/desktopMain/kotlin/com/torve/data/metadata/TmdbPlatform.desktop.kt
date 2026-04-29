package com.torve.data.metadata

import java.net.InetAddress

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
