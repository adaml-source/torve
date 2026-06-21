package com.torve.desktop.player

/**
 * Central policy for LibVLC initialization arguments and per-media options.
 * Every option has a named rationale - no scattered VLC flags.
 */
object VlcMediaOptions {

    private data class StartupBufferingProfile(
        val networkCachingMs: Int?,
        val liveCachingMs: Int?,
        val fileCachingMs: Int?,
    )

    private val autoVodProfile = StartupBufferingProfile(
        networkCachingMs = 650,
        liveCachingMs = 450,
        fileCachingMs = 220,
    )
    private val autoLiveProfile = StartupBufferingProfile(
        networkCachingMs = 550,
        liveCachingMs = 250,
        fileCachingMs = 180,
    )
    private val autoLocalFileProfile = StartupBufferingProfile(
        networkCachingMs = null,
        liveCachingMs = null,
        // Higher cache for smooth local playback. 140ms was tuned for fast first-frame
        // seeks on VOD, but causes visible stutter when the decoder briefly gets behind.
        fileCachingMs = 1000,
    )

    /**
     * Factory-level arguments passed when creating the MediaPlayerFactory.
     * These apply to all media played by this factory instance.
     */
    fun factoryArgs(): List<String> = buildList {
        // ── Video output ──
        // Let VLC choose the best available output (Direct3D11 on modern Windows)
        // No forced vout - VLC auto-selects d3d11va or d3d9 as appropriate

        // ── Hardware decoding ──
        // Prefer hardware acceleration, fall back to software if GPU unavailable
        add("--avcodec-hw=any")
        // Use all available cores for software decode fallback paths
        add("--avcodec-threads=0")
        // Fast-path ffmpeg decoder heuristics (tiny quality cost, large CPU save)
        add("--avcodec-fast")
        // Skip deblocking for H.264 on slower systems (major boost, negligible visual loss)
        add("--avcodec-skiploopfilter=all")
        // Drop frames rather than stutter if the renderer falls behind
        add("--drop-late-frames")
        add("--skip-frames")

        // ── Network resilience ──
        // Default startup cache tuned for fast first frame on VOD
        add("--network-caching=650")
        // Lower live cache so live/opening does not stall behind large buffers
        add("--live-caching=250")
        // File caching - bumped to 1000ms for smooth sustained local playback
        add("--file-caching=1000")

        // ── Reconnect ──
        // Auto-reconnect on network streams when connection drops
        add("--http-reconnect")

        // ── Subtitle defaults ──
        // Render subtitles on video (not in a separate region)
        add("--sub-filter=freetype")

        // ── Disable UI ──
        // No VLC title overlay on media start
        add("--no-video-title-show")
        // No snapshot preview popup
        add("--no-snapshot-preview")
        // Quiet mode - reduce VLC stderr noise
        add("--quiet")

        // ── Audio output ──
        // Prefer a single modern Windows backend so device enumeration does not
        // duplicate the same speakers/headset through multiple legacy outputs.
        if (isWindows()) {
            add("--aout=mmdevice")
        }

        // ── Logging ──
        // Minimal VLC logging to avoid noise
        add("--verbose=0")
    }

    /**
     * Per-media MRL options derived from a playback session.
     */
    fun mediaOptions(
        mediaPath: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        subtitleUrl: String? = null,
        startTimeMs: Long? = null,
        bufferingPreset: DesktopBufferingPreset = DesktopBufferingPreset.AUTO,
    ): List<String> = buildList {
        // HTTP headers
        requestHeaders["Referer"]?.let { add(":http-referrer=$it") }
        requestHeaders["User-Agent"]?.let { add(":http-user-agent=$it") }

        // Start position
        if (startTimeMs != null && startTimeMs > 0) {
            val startSeconds = startTimeMs / 1000.0
            add(":start-time=$startSeconds")
        }

        // External subtitle
        if (!subtitleUrl.isNullOrBlank()) {
            add(":sub-file=$subtitleUrl")
        }

        val effectivePreset = if (bufferingPreset == DesktopBufferingPreset.AUTO) {
            autoStartupProfileFor(mediaPath)
        } else {
            bufferingPreset.toStartupProfile()
        }

        effectivePreset.networkCachingMs?.let { add(":network-caching=$it") }
        effectivePreset.liveCachingMs?.let { add(":live-caching=$it") }
        effectivePreset.fileCachingMs?.let { add(":file-caching=$it") }
    }

    /**
     * Options for live / IPTV streams requiring lower latency.
     */
    fun liveStreamOverrides(): List<String> = listOf(
        ":network-caching=800",
        ":live-caching=400",
    )

    private fun DesktopBufferingPreset.toStartupProfile(): StartupBufferingProfile =
        when (this) {
            DesktopBufferingPreset.AUTO -> autoVodProfile
            DesktopBufferingPreset.STABLE_STREAM -> StartupBufferingProfile(
                networkCachingMs = 2500,
                liveCachingMs = 1600,
                fileCachingMs = 1200,
            )
            DesktopBufferingPreset.LOW_LATENCY -> StartupBufferingProfile(
                networkCachingMs = 450,
                liveCachingMs = 180,
                fileCachingMs = 120,
            )
            DesktopBufferingPreset.AGGRESSIVE_BUFFER -> StartupBufferingProfile(
                networkCachingMs = 5000,
                liveCachingMs = 3200,
                fileCachingMs = 2500,
            )
        }

    private fun autoStartupProfileFor(mediaPath: String?): StartupBufferingProfile {
        val source = mediaPath?.trim()?.lowercase().orEmpty()
        if (source.isBlank()) return autoVodProfile
        if (source.startsWith("file:/") || Regex("^[a-z]:\\\\", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
            return autoLocalFileProfile
        }
        if (
            source.contains(".m3u8") ||
            source.contains("/live") ||
            source.contains("manifest") ||
            source.contains("playlist")
        ) {
            return autoLiveProfile
        }
        return autoVodProfile
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.contains("windows", ignoreCase = true) == true
}
