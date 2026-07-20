package com.torve.desktop.trailer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Resolves a YouTube video key into a directly-playable media URL using
 * NewPipeExtractor. We do this on desktop because VLC's bundled
 * `youtube.luac` script is broken whenever YouTube ships a new player
 * (which happens roughly monthly). NewPipe's extractor lives in active
 * weekly maintenance; bumping the dependency version is enough to stay
 * working.
 *
 * The resolver picks the highest-resolution muxed (audio+video) stream so
 * VLC doesn't have to demux a separate audio track. If no muxed stream is
 * available it falls back to the highest video stream - VLC then plays
 * silent video, which is rare but still better than failing entirely.
 */
object YouTubeResolver {

    @Volatile
    private var initialised: Boolean = false

    private fun ensureInit() {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            // NewPipe needs a Downloader implementation. We use a tiny
            // HttpURLConnection-backed one - no need to drag the project's
            // Ktor client in; this code path runs at most once per trailer
            // open.
            NewPipe.init(JvmHttpDownloader, defaultLocalization())
            initialised = true
        }
    }

    private fun defaultLocalization(): Localization {
        val locale = Locale.getDefault()
        val country = locale.country.takeIf { it.isNotBlank() } ?: "US"
        val language = locale.language.takeIf { it.isNotBlank() } ?: "en"
        return Localization(country, language)
    }

    /**
     * Fetch a direct stream URL for [youtubeKey]. Suspends; runs on
     * [Dispatchers.IO] so callers can launch from any context.
     *
     * Returns null when extraction failed for any reason - caller should
     * fall back (e.g. open in browser, or hand the raw watch URL to VLC
     * for one last attempt).
     */
    suspend fun resolveDirectUrl(youtubeKey: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            ensureInit()
            val watchUrl = "https://www.youtube.com/watch?v=$youtubeKey"
            val extractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
            extractor.fetchPage()

            val muxed = extractor.videoStreams
                ?.filter { stream -> !stream.isVideoOnly && !stream.url.isNullOrBlank() }
                ?.maxByOrNull { it.height }
            if (muxed != null) return@runCatching muxed.url

            // Fall back to the highest video-only stream. Rare for YouTube
            // (most have a 360p muxed mp4) but possible.
            extractor.videoStreams
                ?.filter { !it.url.isNullOrBlank() }
                ?.maxByOrNull { it.height }
                ?.url
        }.onFailure { t ->
            println("TORVE TRAILER | NewPipe resolve failed for $youtubeKey: ${t.message}")
        }.getOrNull()
    }
}

/**
 * Minimal Downloader that NewPipeExtractor can plug into - straight
 * HttpURLConnection, no third-party HTTP client. Adds a Chrome-ish UA so
 * YouTube serves the page we expect.
 */
private object JvmHttpDownloader : Downloader() {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun execute(request: Request): Response {
        val url = URL(request.url())
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = request.httpMethod()
        conn.setRequestProperty("User-Agent", USER_AGENT)
        request.headers().forEach { (key, values) ->
            values.forEach { v -> conn.addRequestProperty(key, v) }
        }
        request.dataToSend()?.let { body ->
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        val responseCode = conn.responseCode
        val body = (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
            .orEmpty()
        val responseHeaders: Map<String, List<String>> =
            conn.headerFields.filterKeys { it != null }
        return Response(
            responseCode,
            conn.responseMessage ?: "",
            responseHeaders,
            body,
            conn.url.toString(),
        )
    }
}
