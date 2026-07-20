package com.torve.desktop.trailer

import com.torve.desktop.platform.desktopDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the official yt-dlp binary from GitHub Releases on demand.
 *
 * yt-dlp is the only YouTube extractor that stays working in 2026 - every
 * other approach (NewPipeExtractor, VLC's youtube.luac) breaks within a
 * month or two of every YouTube player update. The yt-dlp project ships
 * weekly releases tracking those changes; embedding it as a side-binary
 * is what every serious media player (mpv, VLC's external scripts,
 * Stremio addons) does in practice.
 *
 * We do not bundle yt-dlp inside the Torve installer because:
 *   1. The binary is OS-specific and ~25MB; bundling would inflate every
 *      installer download even for users who never click Trailer.
 *   2. yt-dlp updates faster than Torve releases. Bundling would leave
 *      the bundled copy stale within weeks.
 *
 * Instead we fetch the binary lazily on the first trailer click and cache
 * it under [desktopDataDir]. Subsequent trailer plays reuse the cached
 * binary; updates can be triggered by the user through Settings (TBD).
 */
object YtDlpInstaller {

    enum class State { IDLE, DOWNLOADING, INSTALLED, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progressBytes = MutableStateFlow(0L)
    val progressBytes: StateFlow<Long> = _progressBytes.asStateFlow()

    private val _totalBytes = MutableStateFlow<Long?>(null)
    val totalBytes: StateFlow<Long?> = _totalBytes.asStateFlow()

    /**
     * Ensure a usable yt-dlp binary is available locally. Returns the
     * absolute path on success, null on failure (network error, GitHub
     * rate limit, write failure, etc.).
     *
     * Idempotent - if the binary is already cached or already on PATH,
     * returns immediately without re-downloading.
     */
    suspend fun ensureInstalled(): String? = withContext(Dispatchers.IO) {
        // Already installed? Use it.
        YtDlpResolver.locate()?.let {
            _state.value = State.INSTALLED
            return@withContext it
        }
        val target = targetBinaryFile() ?: run {
            _state.value = State.FAILED
            return@withContext null
        }
        _state.value = State.DOWNLOADING
        _progressBytes.value = 0L
        _totalBytes.value = null
        val downloaded = runCatching { downloadInto(target) }.getOrElse { t ->
            println("TORVE TRAILER | yt-dlp install failed: ${t.message}")
            runCatching { target.delete() }
            _state.value = State.FAILED
            return@withContext null
        }
        if (!downloaded || !target.exists() || target.length() < MIN_BINARY_SIZE_BYTES) {
            _state.value = State.FAILED
            return@withContext null
        }
        if (!isWindows()) {
            // Mark executable on POSIX. On Windows the .exe extension is
            // enough.
            runCatching { target.setExecutable(true, false) }
        }
        // Reset the resolver cache so the next probe sees the new binary.
        YtDlpResolver.invalidate()
        _state.value = State.INSTALLED
        target.absolutePath
    }

    private fun downloadInto(target: File): Boolean {
        val urlString = downloadUrl() ?: return false
        val url = URL(urlString)
        val tmp = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        runCatching { tmp.delete() }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            // GitHub serves the redirect to the actual binary on the
            // `latest/download/<asset>` path; HttpURLConnection follows it.
            setRequestProperty("User-Agent", "Torve/1.0 (+yt-dlp installer)")
        }
        if (connection.responseCode !in 200..299) {
            println("TORVE TRAILER | yt-dlp download HTTP ${connection.responseCode}")
            return false
        }
        val expected = connection.contentLengthLong.takeIf { it > 0 }
        _totalBytes.value = expected

        connection.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    _progressBytes.value = total
                }
            }
        }
        // Atomic-ish swap so a half-finished download can never look
        // installed.
        if (target.exists()) target.delete()
        return tmp.renameTo(target)
    }

    private fun targetBinaryFile(): File? {
        val dir = desktopDataDir()
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = if (isWindows()) "yt-dlp.exe" else "yt-dlp"
        return File(dir, name)
    }

    private fun downloadUrl(): String? {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            "win" in osName -> "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
            "mac" in osName || "darwin" in osName -> "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos"
            "nux" in osName || "nix" in osName -> "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
            else -> null
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /** Floor on what counts as a successful download. yt-dlp.exe is ~15MB+. */
    private const val MIN_BINARY_SIZE_BYTES: Long = 1_000_000L
}
