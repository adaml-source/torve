package com.torve.data.metadata

import com.torve.shared.BuildConfig
import java.net.InetAddress

actual fun tmdbApiKey(): String = BuildConfig.TMDB_API_KEY

internal actual fun lookupHostAddresses(host: String): List<String>? {
    return runCatching {
        InetAddress.getAllByName(host).mapNotNull { address -> address.hostAddress }
    }.getOrNull()
}
