package com.torve.data.metadata

actual fun tmdbApiKey(): String = ANDROID_TMDB_API_KEY

// Like iOS, let the HTTP engine resolve its own connection. A separate,
// synchronous diagnostic lookup bypasses request timeouts and can stall an
// entire startup batch while Fire OS waits for its system resolver.
internal actual fun lookupHostAddresses(host: String): List<String>? = null
