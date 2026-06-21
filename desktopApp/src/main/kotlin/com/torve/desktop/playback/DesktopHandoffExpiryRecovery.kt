package com.torve.desktop.playback

internal const val DESKTOP_HANDOFF_REFRESH_MAX_ATTEMPTS: Int = 1

internal fun DesktopPlaybackSession.hasRefreshableTemporaryHandoff(): Boolean {
    return resolvedIsTemporary &&
        !resolvedUrl.isNullOrBlank() &&
        selectedCandidate?.accelerationMemoryId?.isNotBlank() == true
}

internal fun isDesktopExpiredHandoffFailure(code: String, message: String): Boolean {
    val text = "$code $message".lowercase()
    return listOf(
        "stream_expired",
        "invalid_handoff",
        "handoff expired",
        "expired handoff",
        "playback link expired",
        "http 401",
        "http 403",
        "http 410",
        "status 401",
        "status 403",
        "status 410",
        "410 gone",
    ).any { it in text }
}

internal fun shouldAttemptDesktopHandoffRefresh(
    session: DesktopPlaybackSession?,
    code: String,
    message: String,
    recoverable: Boolean,
    attempts: Int,
): Boolean {
    if (session?.hasRefreshableTemporaryHandoff() != true) return false
    if (!recoverable) return false
    if (attempts >= DESKTOP_HANDOFF_REFRESH_MAX_ATTEMPTS) return false

    // Some desktop engines only report a generic VLC/mpv network failure for
    // an expired HTTP handoff. Restrict generic recovery to temporary
    // memory-backed handoff sessions, where one refresh is expected and safe.
    return isDesktopExpiredHandoffFailure(code, message) || session.resolvedIsTemporary
}

internal fun desktopExpiredHandoffUserMessage(): String =
    "Playback link expired. Refreshing the stream..."

internal fun desktopExpiredHandoffFailureMessage(): String =
    "Playback link expired. Try playing this source again."
