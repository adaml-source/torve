package com.torve.domain.recording

/**
 * Stream container the recorder needs to know how to ingest. Drives
 * the choice between "pull bytes verbatim" (TS / unknown) and
 * "follow the manifest, fetch segments" (HLS).
 */
enum class StreamFormat {
    /** HLS / Apple HTTP Live Streaming — `.m3u8` manifest. */
    HLS,
    /** MPEG-TS or any byte stream the recorder can write verbatim. */
    TS,
    /** Couldn't classify — caller should treat as TS and log. */
    UNKNOWN,
}

/**
 * Pure helper that classifies a stream URL by the [StreamFormat] the
 * recorder needs. Lives in the domain layer so the desktop service
 * (and a future mobile client) can decide whether to spin up an
 * HLS-aware capture path or fall back to byte-tee.
 *
 * Detection order (each step is testable in isolation):
 *  1. Path extension — `.m3u8` / `.m3u` → HLS, `.ts` / `.mpegts` /
 *     `.m2ts` / `.mp4` → TS.
 *  2. `contentType` MIME — `application/vnd.apple.mpegurl` /
 *     `application/x-mpegurl` → HLS; `video/mp2t` / `video/mp4` /
 *     `application/octet-stream` → TS.
 *  3. Else → UNKNOWN.
 *
 * Query strings, fragments, and capitalisation are ignored. The
 * function is intentionally conservative — when both signals
 * disagree, the explicit MIME wins because IPTV providers commonly
 * serve HLS from extensionless URLs.
 */
object StreamFormatDetector {

    fun classify(url: String, contentType: String? = null): StreamFormat {
        val mime = contentType?.takeIf { it.isNotBlank() }
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()

        // MIME wins when present so an HLS provider serving a `.bin`
        // URL still gets classified correctly.
        when (mime) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl" -> return StreamFormat.HLS
            "video/mp2t",
            "video/mpeg",
            "video/mp4",
            "application/octet-stream" -> return StreamFormat.TS
            else -> Unit
        }

        val path = url
            .substringBefore('#')
            .substringBefore('?')
            .lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> StreamFormat.HLS
            path.endsWith(".ts") ||
                path.endsWith(".mpegts") ||
                path.endsWith(".m2ts") ||
                path.endsWith(".mp4") -> StreamFormat.TS
            else -> StreamFormat.UNKNOWN
        }
    }
}
