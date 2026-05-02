package com.torve.desktop.trailer

import com.torve.desktop.platform.desktopDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional YouTube URL resolver backed by the `yt-dlp` command-line tool.
 *
 * yt-dlp is the de-facto standard YouTube extractor in 2026 - it gets
 * weekly releases tracking YouTube's player changes, and works where
 * NewPipeExtractor and VLC's bundled `youtube.luac` both regularly fail.
 *
 * We do **not** ship yt-dlp inside the Torve installer (it's a 25MB Python
 * binary with its own update cadence and security posture). Instead we
 * detect it in two places:
 *
 *  1. `desktopDataDir()/yt-dlp(.exe)` - the user can drop a binary here
 *     manually and Torve will use it for trailers.
 *  2. The system PATH - for users who already have yt-dlp installed via
 *     `brew install yt-dlp`, `pip install yt-dlp`, etc.
 *
 * If neither is present this resolver returns null and TrailerOverlay
 * falls back to NewPipeExtractor and ultimately to opening the trailer in
 * the user's browser.
 */
object YtDlpResolver {

    @Volatile
    private var cachedPath: String? = null

    @Volatile
    private var probed: Boolean = false

    /**
     * Resolve [youtubeKey] to a direct media URL by shelling out to
     * `yt-dlp -g`. Returns null when yt-dlp isn't installed, when the
     * process times out (15s), or when extraction errors.
     *
     * [maxHeight] caps the picked stream's vertical resolution. Pass null
     * to let yt-dlp pick whatever is best up to 1080p (the default).
     */
    suspend fun resolveDirectUrl(youtubeKey: String, maxHeight: Int? = null): String? = withContext(Dispatchers.IO) {
        val binary = locateBinary() ?: return@withContext null
        runCatching {
            val cap = maxHeight ?: 1080
            // Quality ladder: highest single-file mp4 we can get without
            // needing ffmpeg to mux separate video + audio tracks. We do
            // not request bestvideo+bestaudio because that needs ffmpeg
            // in PATH (which we don't bundle), so it would silently fail
            // and drop to a worse fallback.
            val format = "best[ext=mp4][height<=$cap]/best[height<=$cap]/best[ext=mp4]/best"
            val process = ProcessBuilder(
                binary,
                "-g",
                "-f",
                format,
                // Skip yt-dlp's own playlist/cache logic for faster
                // single-video extraction.
                "--no-playlist",
                "--no-cache-dir",
                "https://www.youtube.com/watch?v=$youtubeKey",
            )
                .redirectErrorStream(false)
                .start()

            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                println("TORVE TRAILER | yt-dlp timed out for $youtubeKey")
                return@runCatching null
            }
            if (process.exitValue() != 0) {
                val err = process.errorStream.bufferedReader().readText().trim()
                println("TORVE TRAILER | yt-dlp exit=${process.exitValue()} err=${err.take(240)}")
                return@runCatching null
            }
            // -g prints one URL per line. Take the first (the video).
            process.inputStream.bufferedReader().readLine()?.trim()?.takeIf { it.isNotBlank() }
        }.onFailure { t ->
            println("TORVE TRAILER | yt-dlp invocation failed: ${t.message}")
        }.getOrNull()
    }

    /** Whether a usable yt-dlp binary is reachable right now. */
    fun isAvailable(): Boolean = locateBinary() != null

    /** Public entry for the installer - same as [locateBinary] but exposed. */
    fun locate(): String? = locateBinary()

    /**
     * Forget the cached binary path. Call after installing or removing
     * yt-dlp so the next [resolveDirectUrl] re-probes the filesystem.
     */
    fun invalidate() {
        synchronized(this) {
            cachedPath = null
            probed = false
        }
    }

    private fun locateBinary(): String? {
        if (probed) return cachedPath
        synchronized(this) {
            if (probed) return cachedPath
            probed = true
            cachedPath = findInDataDir() ?: findOnPath()
            cachedPath?.let { println("TORVE TRAILER | yt-dlp located at $it") }
            return cachedPath
        }
    }

    private fun findInDataDir(): String? {
        val dir = desktopDataDir()
        val candidates = if (isWindows()) listOf("yt-dlp.exe", "yt-dlp") else listOf("yt-dlp")
        return candidates
            .map { File(dir, it) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun findOnPath(): String? {
        val pathEnv = System.getenv("PATH").orEmpty()
        if (pathEnv.isBlank()) return null
        val sep = File.pathSeparator
        val executable = if (isWindows()) "yt-dlp.exe" else "yt-dlp"
        return pathEnv.split(sep)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, executable) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
