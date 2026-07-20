package com.torve.desktop.download

import com.torve.data.addon.ParsedStream
import com.torve.data.download.BulkDownloadManager
import com.torve.data.download.EpisodeTarget
import com.torve.desktop.playback.DesktopPlaybackSession
import com.torve.desktop.playback.DesktopPlaybackSourceCandidate
import com.torve.domain.model.DeviceCodecCaps
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.StreamRepository
import com.torve.presentation.settings.SettingsViewModel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DesktopLocalMediaEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class DesktopLocalMediaGroup(
    val title: String,
    val sourcePath: String,
    val sourceLabel: String,
    val entries: List<DesktopLocalMediaEntry>,
    val isShow: Boolean,
    val totalSizeBytes: Long,
)

data class DesktopDownloadManagerState(
    val isProcessing: Boolean = false,
    val activeDownloadId: String? = null,
    val activeDownloadTitle: String? = null,
    val activeProgress: Float = 0f,
    val lastEvent: String? = null,
    val lastEventNonce: Long = 0L,
    val scannedGroups: List<DesktopLocalMediaGroup> = emptyList(),
)

class DesktopDownloadManager(
    private val downloadRepo: DownloadRepository,
    private val bulkDownloadManager: BulkDownloadManager,
    private val streamRepository: StreamRepository,
    private val settingsViewModel: SettingsViewModel,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DesktopDownloadManagerState())
    val state: StateFlow<DesktopDownloadManagerState> = _state.asStateFlow()

    var onDownloadsChanged: (() -> Unit)? = null

    private var lastScanSignature: String? = null

    init {
        scope.launch {
            recoverInterruptedDownloads()
            refreshLocalMedia()
            while (isActive) {
                val handled = processNextDownload()
                if (!handled) {
                    refreshLocalMediaIfNeeded()
                    delay(2_000)
                }
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    /** Surface a one-off event to the top-of-window status overlay. */
    fun publishEvent(message: String) {
        _state.update { it.copy(lastEvent = message, lastEventNonce = it.lastEventNonce + 1L) }
    }

    fun queueMovieDownload(session: DesktopPlaybackSession) {
        scope.launch {
            val existing = downloadRepo.getAllDownloads().firstOrNull {
                it.mediaId == session.mediaItem.id &&
                    it.mediaType == MediaType.MOVIE &&
                    it.status != DownloadStatus.FAILED
            }
            if (existing != null) {
                _state.update { it.copy(lastEvent = "${session.mediaItem.title} is already queued or downloaded.") }
                notifyDownloadsChanged()
                return@launch
            }

            val resolved = resolveSessionForDownload(session) ?: run {
                _state.update { it.copy(lastEvent = "Could not resolve a downloadable source for ${session.mediaItem.title}.") }
                return@launch
            }
            val downloadUrl = resolved.resolvedUrl?.takeIf { it.isNotBlank() }
                ?: resolved.transcodeUrls["mp4"]?.takeIf { it.isNotBlank() }
                ?: return@launch

            val now = System.currentTimeMillis()
            val download = Download(
                id = "movie_${session.mediaItem.id}_${now}_${UUID.randomUUID().toString().take(8)}",
                mediaId = session.mediaItem.id,
                mediaType = MediaType.MOVIE,
                title = session.mediaItem.title,
                posterUrl = session.mediaItem.posterUrl,
                streamUrl = downloadUrl,
                fileSizeBytes = resolved.resolvedFileSize,
                status = DownloadStatus.PENDING,
                createdAt = now,
            )
            downloadRepo.enqueueDownload(download)
            _state.update { it.copy(lastEvent = "Queued ${session.mediaItem.title} for download.") }
            notifyDownloadsChanged()
        }
    }

    /**
     * The catalog surface that originated an NZB download. Encoded into
     * the [Download.mediaId] (`nzb_<surface>_<hash>`) so [buildTarget]
     * can pick the right per-surface root path on disk later. Also drives
     * which user setting we consult to gate the download.
     */
    enum class NzbDownloadSurface(val tag: String, val label: String) {
        MOVIES("movies", "Movies"),
        ADULT("adult", "Adult"),
        SPORTS("sports", "Sports"),
    }

    sealed interface QueueResult {
        data object Ok : QueueResult
        /** No download folder configured for [surface]; UI should route
         *  the user to Settings → Downloads. [settingLabel] is the human
         *  name of the missing path, useful for the prompt copy. */
        data class NeedsFolder(val surface: NzbDownloadSurface, val settingLabel: String) : QueueResult
        data object AlreadyQueued : QueueResult
        data class Error(val message: String) : QueueResult
    }

    /**
     * Direct download entry-point for the NZB-driven catalog pages
     * (Adult / Sports / Movies-via-Usenet). The caller has already
     * resolved the NZB to a TorBox-served stream URL.
     *
     * If the per-surface download folder isn't configured, returns
     * [QueueResult.NeedsFolder] without enqueuing - the UI should
     * surface a prompt and route to Settings → Downloads. Otherwise
     * the download lands in the configured per-surface folder.
     */
    suspend fun queueNzbDownload(
        title: String,
        streamUrl: String,
        sizeBytes: Long?,
        surface: NzbDownloadSurface,
        posterUrl: String? = null,
    ): QueueResult {
        if (streamUrl.isBlank()) {
            _state.update { it.copy(lastEvent = "Cannot download: empty stream URL.") }
            return QueueResult.Error("empty stream URL")
        }
        val s = settingsViewModel.state.value
        val configured = when (surface) {
            NzbDownloadSurface.MOVIES -> s.movieDownloadPath
            NzbDownloadSurface.ADULT -> s.adultDownloadPath
            NzbDownloadSurface.SPORTS -> s.sportsDownloadPath
        }
        if (configured.isBlank()) {
            _state.update {
                it.copy(
                    lastEvent = "Set a ${surface.label} download folder in Settings → Downloads first.",
                )
            }
            return QueueResult.NeedsFolder(
                surface = surface,
                settingLabel = "${surface.label} download folder",
            )
        }
        // Surface-tagged mediaId so buildTarget can pick the right root
        // (and so an Adult release doesn't collide with a Movie release
        // sharing the same stream-URL hash).
        val mediaId = "nzb_${surface.tag}_${streamUrl.hashCode().toUInt().toString(16)}"
        val existing = downloadRepo.getAllDownloads().firstOrNull {
            it.mediaId == mediaId &&
                it.mediaType == MediaType.MOVIE &&
                it.status != DownloadStatus.FAILED
        }
        if (existing != null) {
            _state.update { it.copy(lastEvent = "$title is already queued or downloaded.") }
            notifyDownloadsChanged()
            return QueueResult.AlreadyQueued
        }
        val now = System.currentTimeMillis()
        val download = Download(
            id = "nzb_${now}_${UUID.randomUUID().toString().take(8)}",
            mediaId = mediaId,
            mediaType = MediaType.MOVIE,
            title = title,
            posterUrl = posterUrl,
            streamUrl = streamUrl,
            fileSizeBytes = sizeBytes ?: 0L,
            status = DownloadStatus.PENDING,
            createdAt = now,
        )
        downloadRepo.enqueueDownload(download)
        _state.update { it.copy(lastEvent = "Queued $title for download (${surface.label}).") }
        notifyDownloadsChanged()
        return QueueResult.Ok
    }

    fun queueEpisodeDownload(
        mediaItem: MediaItem,
        seasonNumber: Int,
        episodeNumber: Int,
    ) {
        queueShowTargets(
            mediaItem = mediaItem,
            targets = listOf(EpisodeTarget(seasonNumber, episodeNumber)),
            successLabel = "${mediaItem.title} S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}",
        )
    }

    fun queueSeasonDownload(
        mediaItem: MediaItem,
        seasonNumber: Int,
        episodeCount: Int,
    ) {
        queueShowTargets(
            mediaItem = mediaItem,
            targets = bulkDownloadManager.buildSeasonTargets(seasonNumber, episodeCount),
            successLabel = "${mediaItem.title} season $seasonNumber",
        )
    }

    fun queueAllEpisodesDownload(
        mediaItem: MediaItem,
    ) {
        queueShowTargets(
            mediaItem = mediaItem,
            targets = bulkDownloadManager.buildAllSeasonsTargets(mediaItem),
            successLabel = "${mediaItem.title} all episodes",
        )
    }

    fun deleteManagedFile(filePath: String) {
        runCatching {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            }
        }
        scope.launch { refreshLocalMedia() }
    }

    fun refreshLocalMediaAsync() {
        scope.launch { refreshLocalMedia() }
    }

    private fun queueShowTargets(
        mediaItem: MediaItem,
        targets: List<EpisodeTarget>,
        successLabel: String,
    ) {
        scope.launch {
            if (targets.isEmpty()) {
                _state.update { it.copy(lastEvent = "No episodes available for download.") }
                return@launch
            }
            if (settingsViewModel.getDebridApiKey().isBlank()) {
                _state.update { it.copy(lastEvent = "Connect a debrid account before downloading.") }
                return@launch
            }

            val ids = bulkDownloadManager.enqueueBulk(
                mediaItem = mediaItem,
                episodes = targets,
                debridProvider = settingsViewModel.getDebridProvider(),
                debridApiKey = settingsViewModel.getDebridApiKey(),
                debridAccounts = settingsViewModel.getDebridAccounts(),
                preferences = settingsViewModel.buildStreamPreferences(),
                deviceCaps = DeviceCodecCaps.SAFE_BASELINE,
            )
            _state.update {
                it.copy(
                    lastEvent = if (ids.isEmpty()) {
                        "No downloadable debrid sources were found for $successLabel."
                    } else {
                        "Queued ${ids.size} download(s) for $successLabel."
                    },
                )
            }
            notifyDownloadsChanged()
        }
    }

    private suspend fun processNextDownload(): Boolean {
        val next = downloadRepo.getPendingDownloads()
            .firstOrNull { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
            ?: run {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        activeDownloadId = null,
                        activeDownloadTitle = null,
                        activeProgress = 0f,
                    )
                }
                return false
            }

        _state.update {
            it.copy(
                isProcessing = true,
                activeDownloadId = next.id,
                activeDownloadTitle = next.title,
                activeProgress = next.progressPercent,
            )
        }
        notifyDownloadsChanged()

        return runCatching {
            downloadFile(next)
            _state.update { it.copy(lastEvent = "Downloaded ${next.title}.") }
            true
        }.getOrElse { error ->
            downloadRepo.updateProgress(next.id, next.downloadedBytes, DownloadStatus.FAILED)
            _state.update { it.copy(lastEvent = error.message ?: "Download failed for ${next.title}.") }
            notifyDownloadsChanged()
            true
        }
    }

    private suspend fun downloadFile(download: Download) {
        val target = buildTarget(download)
        target.directory.mkdirs()

        val tempFile = File(target.directory, "${target.baseName}.part")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val connection = openConnection(download.streamUrl)
        connection.connect()
        val contentLength = connection.contentLengthLong.takeIf { it > 0L }
        if (contentLength != null) {
            downloadRepo.updateFileSize(download.id, contentLength)
        }

        val extension = resolveExtension(
            url = connection.url.toString(),
            contentType = connection.contentType,
            preferredFileName = download.title,
        )
        val finalFile = File(target.directory, target.baseName + extension)
        if (finalFile.exists() && finalFile.length() > 0L) {
            downloadRepo.markCompleted(download.id, finalFile.absolutePath)
            downloadRepo.updateProgress(download.id, finalFile.length(), DownloadStatus.COMPLETED)
            notifyDownloadsChanged()
            refreshLocalMedia()
            return
        }

        connection.inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                downloadRepo.updateProgress(download.id, 0L, DownloadStatus.DOWNLOADING)
                notifyDownloadsChanged()

                // Large buffer keeps syscalls/read cycles low on fast links.
                val buffer = ByteArray(256 * 1024)
                var downloadedBytes = 0L
                var lastUiUpdateAt = 0L
                var lastPersistedBytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    val now = System.currentTimeMillis()
                    // Persist progress + fan out UI updates at most ~twice per second.
                    // Writing to DB and StateFlow on every 8KB chunk previously caused
                    // tens of thousands of recompositions and blocked the download loop.
                    if (now - lastUiUpdateAt >= 500L) {
                        downloadRepo.updateProgress(download.id, downloadedBytes, DownloadStatus.DOWNLOADING)
                        _state.update {
                            it.copy(
                                activeDownloadId = download.id,
                                activeDownloadTitle = download.title,
                                activeProgress = if (contentLength != null && contentLength > 0L) {
                                    downloadedBytes.toFloat() / contentLength.toFloat()
                                } else {
                                    0f
                                },
                            )
                        }
                        notifyDownloadsChanged()
                        lastUiUpdateAt = now
                        lastPersistedBytes = downloadedBytes
                    }
                }
                // Final flush so the last bytes past the throttle window aren't lost from the DB.
                if (lastPersistedBytes < downloadedBytes) {
                    downloadRepo.updateProgress(download.id, downloadedBytes, DownloadStatus.DOWNLOADING)
                    _state.update {
                        it.copy(
                            activeProgress = if (contentLength != null && contentLength > 0L) {
                                downloadedBytes.toFloat() / contentLength.toFloat()
                            } else 1f,
                        )
                    }
                }
            }
        }

        Files.move(
            tempFile.toPath(),
            finalFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        val finalSize = finalFile.length()
        downloadRepo.markCompleted(download.id, finalFile.absolutePath)
        downloadRepo.updateProgress(download.id, finalSize, DownloadStatus.COMPLETED)
        notifyDownloadsChanged()
        refreshLocalMedia()
    }

    private suspend fun resolveSessionForDownload(
        session: DesktopPlaybackSession,
    ): DesktopPlaybackSession? {
        val selectedCandidate = session.selectedCandidate ?: session.streamCandidates.firstOrNull() ?: return null
        if (
            session.resolvedCandidateId == selectedCandidate.candidateId &&
            !session.resolvedUrl.isNullOrBlank()
        ) {
            return session
        }

        val provider = session.provider ?: settingsViewModel.getDebridProvider()
        val apiKey = settingsViewModel.getDebridApiKey().takeIf { it.isNotBlank() } ?: return null
        val resolvedStream = withContext(Dispatchers.IO) {
            streamRepository.resolveStream(
                stream = selectedCandidate.toParsedStream(),
                provider = provider,
                apiKey = apiKey,
            )
        }
        return session.copy(
            provider = provider,
            resolvedCandidateId = selectedCandidate.candidateId,
            resolvedUrl = resolvedStream.transcodeUrls?.mp4
                ?: resolvedStream.url,
            resolvedFileName = resolvedStream.fileName,
            resolvedMimeType = resolvedStream.mimeType,
            resolvedFileSize = resolvedStream.fileSize,
            transcodeUrls = buildMap {
                resolvedStream.transcodeUrls?.mp4?.takeIf { it.isNotBlank() }?.let { put("mp4", it) }
                resolvedStream.transcodeUrls?.hls?.takeIf { it.isNotBlank() }?.let { put("hls", it) }
            },
        )
    }

    private suspend fun recoverInterruptedDownloads() {
        downloadRepo.getPendingDownloads()
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .forEach { download ->
                downloadRepo.updateProgress(download.id, 0L, DownloadStatus.PENDING)
            }
        notifyDownloadsChanged()
    }

    private suspend fun refreshLocalMediaIfNeeded() {
        val signature = currentScanRoots().joinToString("|")
        if (signature != lastScanSignature) {
            refreshLocalMedia()
        }
    }

    private fun currentScanRoots(): List<String> {
        val s = settingsViewModel.state.value
        // Scan both the user-configured extra folders AND every download
        // target (movies, shows, adult, sports) so the Library tab
        // reflects everything in the configured locations regardless of
        // which surface queued it.
        return (s.downloadScanFolders + listOfNotNull(
            s.movieDownloadPath.takeIf { it.isNotBlank() },
            s.showDownloadPath.takeIf { it.isNotBlank() },
            s.adultDownloadPath.takeIf { it.isNotBlank() },
            s.sportsDownloadPath.takeIf { it.isNotBlank() },
        ))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun refreshLocalMedia() {
        val roots = currentScanRoots()
        lastScanSignature = roots.joinToString("|")
        val groups = roots.flatMap { rootPath ->
            scanRoot(File(rootPath))
        }.sortedBy { it.title.lowercase(Locale.getDefault()) }

        _state.update { it.copy(scannedGroups = groups) }
    }

    private fun scanRoot(root: File): List<DesktopLocalMediaGroup> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.getDefault()) in MEDIA_EXTENSIONS }
            .toList()
        if (files.isEmpty()) return emptyList()

        val grouped = linkedMapOf<Pair<String, String>, MutableList<DesktopLocalMediaEntry>>()
        files.forEach { file ->
            val parsed = parseLocalMediaEntry(root, file)
            val key = parsed.first to root.absolutePath
            val entries = grouped.getOrPut(key) { mutableListOf() }
            entries += parsed.second
        }

        return grouped.map { (key, entries) ->
            val title = key.first
            val isShow = entries.any { it.seasonNumber != null && it.episodeNumber != null }
            DesktopLocalMediaGroup(
                title = title,
                sourcePath = key.second,
                sourceLabel = "Local Folder",
                entries = entries.sortedWith(
                    compareBy<DesktopLocalMediaEntry> { it.seasonNumber ?: 0 }
                        .thenBy { it.episodeNumber ?: 0 }
                        .thenBy { it.name.lowercase(Locale.getDefault()) },
                ),
                isShow = isShow,
                totalSizeBytes = entries.sumOf { it.sizeBytes },
            )
        }
    }

    private fun parseLocalMediaEntry(
        root: File,
        file: File,
    ): Pair<String, DesktopLocalMediaEntry> {
        val relativePath = file.relativeTo(root).invariantSeparatorsPath
        val episodeMatch = EPISODE_REGEX.find(relativePath)
        if (episodeMatch != null) {
            val rawShowTitle = episodeMatch.groupValues[1]
                .replace('.', ' ')
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
            val seasonNumber = episodeMatch.groupValues[2].toIntOrNull()
            val episodeNumber = episodeMatch.groupValues[3].toIntOrNull()
            return rawShowTitle to DesktopLocalMediaEntry(
                name = file.nameWithoutExtension,
                path = file.absolutePath,
                sizeBytes = file.length(),
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        }

        val title = file.parentFile?.takeIf { it != root }?.name?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        return title to DesktopLocalMediaEntry(
            name = file.nameWithoutExtension,
            path = file.absolutePath,
            sizeBytes = file.length(),
        )
    }

    private fun buildTarget(download: Download): DownloadTarget {
        // NZB rows carry a surface tag inside the mediaId
        // (`nzb_<surface>_<hash>`) so we can route Adult downloads to a
        // separate folder from Sports / Movies. queueNzbDownload already
        // gated on this folder being set; if it's been cleared by the
        // time the worker picks the row up, fall back to the Movies path
        // (or its own default) so we never crash mid-download.
        val s = settingsViewModel.state.value
        val nzbSurface = parseNzbSurface(download.mediaId)
        val root = when {
            nzbSurface == NzbDownloadSurface.ADULT -> resolveRootDirectory(
                configured = s.adultDownloadPath,
                fallbackChild = "Adult",
            )
            nzbSurface == NzbDownloadSurface.SPORTS -> resolveRootDirectory(
                configured = s.sportsDownloadPath,
                fallbackChild = "Sports",
            )
            nzbSurface == NzbDownloadSurface.MOVIES ||
                download.mediaType == MediaType.MOVIE -> resolveRootDirectory(
                configured = s.movieDownloadPath,
                fallbackChild = "Movies",
            )
            else -> resolveRootDirectory(
                configured = s.showDownloadPath,
                fallbackChild = "Shows",
            )
        }
        return if (download.mediaType == MediaType.SERIES) {
            val showTitle = extractShowTitle(download.title)
            val seasonLabel = "Season ${(download.seasonNumber ?: 1).toString().padStart(2, '0')}"
            val directory = File(File(root, sanitizeFileName(showTitle)), seasonLabel)
            val episodeLabel = buildString {
                append(sanitizeFileName(showTitle))
                append(" - S")
                append((download.seasonNumber ?: 1).toString().padStart(2, '0'))
                append("E")
                append((download.episodeNumber ?: 1).toString().padStart(2, '0'))
            }
            DownloadTarget(directory = directory, baseName = episodeLabel)
        } else {
            val movieTitle = sanitizeFileName(download.title)
            val directory = File(root, movieTitle)
            DownloadTarget(directory = directory, baseName = movieTitle)
        }
    }

    private fun resolveRootDirectory(
        configured: String,
        fallbackChild: String,
    ): File {
        if (configured.isNotBlank()) {
            return File(configured)
        }
        return File(defaultTorveDownloadsRoot(), fallbackChild)
    }

    /** Inverse of the mediaId encoding in [queueNzbDownload]. Returns
     *  null for non-NZB rows or unknown surface tags. */
    private fun parseNzbSurface(mediaId: String): NzbDownloadSurface? {
        if (!mediaId.startsWith("nzb_")) return null
        val tag = mediaId.removePrefix("nzb_").substringBefore('_')
        return NzbDownloadSurface.entries.firstOrNull { it.tag == tag }
    }

    private fun defaultTorveDownloadsRoot(): File {
        val userProfile = System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() }
        val base = if (userProfile != null) {
            File(userProfile, "Downloads")
        } else {
            File(System.getProperty("user.home"), "Downloads")
        }
        return File(base, "Torve")
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Torve Desktop")
        }
    }

    private fun resolveExtension(
        url: String,
        contentType: String?,
        preferredFileName: String,
    ): String {
        val normalizedUrl = url.lowercase(Locale.getDefault())
        if (normalizedUrl.endsWith(".mkv")) return ".mkv"
        if (normalizedUrl.endsWith(".mp4")) return ".mp4"
        if (normalizedUrl.endsWith(".avi")) return ".avi"
        if (normalizedUrl.endsWith(".mov")) return ".mov"
        if (normalizedUrl.endsWith(".ts")) return ".ts"

        val normalizedType = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.getDefault())
        return when (normalizedType) {
            "video/x-matroska" -> ".mkv"
            "video/mp4" -> ".mp4"
            "video/x-msvideo" -> ".avi"
            "video/quicktime" -> ".mov"
            "video/mp2t" -> ".ts"
            else -> if (preferredFileName.contains('.')) {
                "." + preferredFileName.substringAfterLast('.').lowercase(Locale.getDefault())
            } else {
                ".mp4"
            }
        }
    }

    private fun extractShowTitle(title: String): String {
        val match = SHOW_TITLE_REGEX.find(title)
        val raw = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: title
        return raw
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
    }

    private fun sanitizeFileName(value: String): String {
        return value
            .replace(INVALID_FILE_CHARS, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .ifBlank { "Torve Download" }
    }

    private fun notifyDownloadsChanged() {
        onDownloadsChanged?.invoke()
    }

    private data class DownloadTarget(
        val directory: File,
        val baseName: String,
    )

    companion object {
        private val INVALID_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SHOW_TITLE_REGEX = Regex("^(.*?)[\\s._-]+S\\d{2}E\\d{2}.*$", RegexOption.IGNORE_CASE)
        private val EPISODE_REGEX = Regex("(.+?)[\\s._-]+S(\\d{1,2})E(\\d{1,2}).*", RegexOption.IGNORE_CASE)
        private val MEDIA_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v", "ts")
    }
}

private fun DesktopPlaybackSourceCandidate.toParsedStream(): ParsedStream {
    return ParsedStream(
        addonName = addonName,
        quality = quality,
        title = title,
        infoHash = infoHash,
        fileIdx = fileIdx,
        directUrl = directUrl,
        accelerationMemoryId = accelerationMemoryId,
        size = size,
        codec = codec,
        source = source,
        isCached = isCached,
        score = score,
        audioCodec = audioCodec,
    )
}
