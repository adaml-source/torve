package com.torve.data.network

private val rawUrlPattern = Regex("""https?://[^\s,\]]+""")
private val apiKeyPattern = Regex("""api_key=[^&\]\s]+""")

fun sanitizeNetworkDiagnosticText(value: String?): String? {
    if (value.isNullOrBlank()) return value
    return rawUrlPattern
        .replace(apiKeyPattern.replace(value, "api_key=<redacted>"), "<redacted-url>")
}

fun homeContentLoadErrorMessage(cause: Throwable? = null): String {
    return if (cause.isLikelyDeviceDateOrCertificateProblem()) {
        "Home could not connect securely. Check the device date and time, then try again."
    } else {
        "Home content could not be loaded. Please try again."
    }
}

fun catalogContentLoadErrorMessage(mediaType: String): String = when (mediaType) {
    "movie" -> "Movies could not be loaded. Please try again."
    "tv" -> "TV Shows could not be loaded. Please try again."
    else -> "Content could not be loaded. Please try again."
}

private fun Throwable?.isLikelyDeviceDateOrCertificateProblem(): Boolean {
    val text = buildString {
        var current = this@isLikelyDeviceDateOrCertificateProblem
        var depth = 0
        while (current != null && depth < 8) {
            append(current::class.simpleName.orEmpty())
            append(' ')
            append(current.message.orEmpty())
            append(' ')
            current = current.cause
            depth += 1
        }
    }.lowercase()
    return text.contains("sslhandshake") ||
        text.contains("chain validation failed") ||
        text.contains("certificate") && (text.contains("expired") || text.contains("valid") || text.contains("trust"))
}
