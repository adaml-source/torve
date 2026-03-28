package com.torve.data.metadata

import platform.Foundation.NSBundle

actual fun tmdbApiKey(): String {
    return NSBundle.mainBundle.objectForInfoDictionaryKey("TMDB_API_KEY") as? String ?: ""
}

internal actual fun lookupHostAddresses(host: String): List<String>? = null
