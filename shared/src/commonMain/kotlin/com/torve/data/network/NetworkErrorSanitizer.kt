package com.torve.data.network

private val rawUrlPattern = Regex("""https?://[^\s,\]]+""")
private val apiKeyPattern = Regex("""api_key=[^&\]\s]+""")

fun sanitizeNetworkDiagnosticText(value: String?): String? {
    if (value.isNullOrBlank()) return value
    return rawUrlPattern
        .replace(apiKeyPattern.replace(value, "api_key=<redacted>"), "<redacted-url>")
}

fun homeContentLoadErrorMessage(): String = "Home content could not be loaded. Please try again."

fun catalogContentLoadErrorMessage(mediaType: String): String = when (mediaType) {
    "movie" -> "Movies could not be loaded. Please try again."
    "tv" -> "TV Shows could not be loaded. Please try again."
    else -> "Content could not be loaded. Please try again."
}
