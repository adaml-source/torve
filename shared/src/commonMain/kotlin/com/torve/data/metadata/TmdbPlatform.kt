package com.torve.data.metadata

expect fun tmdbApiKey(): String

internal expect fun lookupHostAddresses(host: String): List<String>?
