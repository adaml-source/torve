package com.torve.desktop.playback

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StremioSubtitle
import com.torve.data.addon.SubtitleAggregator
import com.torve.data.addon.canUseGenericStreamHandoff
import com.torve.data.addon.isAddonHostedUrl
import com.torve.domain.model.DebridServiceType
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.WatchProgress
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.telemetry.StreamPathDiagnostics
import com.torve.domain.telemetry.StreamPathTelemetryContext
import com.torve.domain.telemetry.StreamPlaybackPath
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.platform.TorveRuntimeDebug
import com.torve.presentation.detail.episodeKey
import com.torve.presentation.player.TraktScrobbler
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.detail.StreamFilterUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.LinkedHashMap

data class DesktopPlaybackRequest(
    val mediaId: String,
    val mediaType: MediaType,
    val title: String,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val artworkUrl: String? = null,
    val sourceSurface: String,
)

enum class DesktopPlayerPhase {
    IDLE,
    RESOLVING,
    RESOLVED,
    OPENING,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    RESOLUTION_FAILED,
    RUNTIME_ERROR,
    CLOSED,
}

enum class DesktopPlayerCommand {
    OPEN,
    PLAY,
    PAUSE,
    STOP,
    CLOSE,
}

private inline fun desktopVerboseLog(message: () -> String) {
    if (TorveRuntimeDebug.verboseLoggingEnabled) {
        println(DiagnosticsRedactor.redact(message()))
    }
}

data class DesktopPlayerError(
    val code: String,
    val message: String,
    val recoverable: Boolean = true,
)

enum class DesktopEpisodeResolutionMode {
    REQUESTED,
    RESUME_IN_PROGRESS,
    PLAY_FIRST_UNWATCHED,
    PLAY_FROM_START,
}

data class DesktopPlaybackEpisodeContext(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String? = null,
    val mode: DesktopEpisodeResolutionMode,
) {
    val label: String
        get() = buildString {
            append("S")
            append(seasonNumber)
            append("E")
            append(episodeNumber)
            episodeTitle?.takeIf { it.isNotBlank() }?.let {
                append(" • ")
                append(it)
            }
        }
}

data class DesktopPlaybackSourceCandidate(
    val candidateId: String,
    val addonName: String,
    val title: String,
    val quality: String,
    val score: Int,
    val fileIdx: Int? = null,
    val source: String? = null,
    val isCached: Boolean = false,
    val codec: String? = null,
    val audioCodec: String? = null,
    val size: String? = null,
    val infoHash: String? = null,
    val directUrl: String? = null,
    val accelerationMemoryId: String? = null,
    // The originating addon's manifest URL. Required so the resolver can tell
    // an addon-hosted stream (Panda's /u/<token>/...) from a hoster URL that
    // needs debrid unrestrict - without this, every Panda Usenet/NZB stream
    // gets misclassified as a torrent and routed through whatever debrid is
    // (or isn't) configured.
    val addonBaseUrl: String? = null,
    val languages: List<String> = emptyList(),
)

data class DesktopPlaybackSubtitleCandidate(
    val label: String,
    val language: String,
    val url: String,
)

data class DesktopPlaybackSession(
    val request: DesktopPlaybackRequest,
    val mediaItem: MediaItem,
    val episodeContext: DesktopPlaybackEpisodeContext? = null,
    val provider: DebridServiceType? = null,
    val streamCandidates: List<DesktopPlaybackSourceCandidate> = emptyList(),
    val recommendedCandidateId: String? = null,
    val selectedCandidate: DesktopPlaybackSourceCandidate? = null,
    val resolvedCandidateId: String? = null,
    val resolvedUrl: String? = null,
    val resolvedFileName: String? = null,
    val resolvedMimeType: String? = null,
    val resolvedFileSize: Long? = null,
    val resolvedIsTemporary: Boolean = false,
    val resolvedStreamId: String? = null,
    val resolvedExpiresInSeconds: Int? = null,
    val transcodeUrls: Map<String, String> = emptyMap(),
    val requestHeaders: Map<String, String> = emptyMap(),
    val subtitleCandidates: List<DesktopPlaybackSubtitleCandidate> = emptyList(),
    val filterHiddenCount: Int = 0,
    val streamRulesCacheKey: String = "",
    val notes: List<String> = emptyList(),
    val isMediaLevelOnly: Boolean = false,
)

data class DesktopPlayerRuntimeInfo(
    val backendLabel: String = "Embedded VLC player",
    val runtimeFound: Boolean? = null,
    val executablePath: String? = null,
    val discoverySource: String? = null,
    val attemptedPaths: List<String> = emptyList(),
    val processId: Long? = null,
    val activeMediaPath: String? = null,
    val playbackPositionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val bufferingPercent: Float? = null,
    val bufferingModeLabel: String? = null,
    val appliedHeaderCount: Int = 0,
    val selectedSubtitleLabel: String? = null,
    val requirementText: String =
        "Playback runtime is missing from this Torve build. Reinstall Torve from the official " +
            "release page, or contact support if reinstalling doesn't fix it.",
)

/**
 * Surface state for the "we resolved a Panda/addon URL but the upstream
 * download is still warming up" situation. Mirrors the Android
 * [com.torve.presentation.detail.PreparingStreamState] so the desktop
 * preparing overlay reads the same shape (title, elapsed, cancel).
 */
data class DesktopPreparingStreamState(
    val title: String,
    val serviceName: String,
    val startedAtEpochMs: Long,
    val attempt: Int,
    val maxAttempts: Int,
    val canCancel: Boolean = true,
)

data class DesktopPlayerUiState(
    val phase: DesktopPlayerPhase = DesktopPlayerPhase.IDLE,
    val currentRequest: DesktopPlaybackRequest? = null,
    val preparedSession: DesktopPlaybackSession? = null,
    val failedCandidateIds: Set<String> = emptySet(),
    val runtimeInfo: DesktopPlayerRuntimeInfo = DesktopPlayerRuntimeInfo(),
    val lastCommand: DesktopPlayerCommand? = null,
    val engineMessage: String = "Select Play to prepare a desktop playback session.",
    val error: DesktopPlayerError? = null,
    val preparingStream: DesktopPreparingStreamState? = null,
)

class DesktopPlayerController(
    private val metadataRepository: MetadataRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val subtitleAggregator: SubtitleAggregator,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val traktScrobbler: TraktScrobbler,
    private val settingsViewModel: SettingsViewModel,
    val playbackEngine: DesktopPlaybackEngine,
    /** Phase 3 Slice C: when present, [openPreResolvedStream] consults
     *  this router and rewrites the stream URL to `file://...` for any
     *  PlaybackRoute.LocalFile pick. Null in tests / legacy paths. */
    private val localFirstPlaybackRouter: com.torve.desktop.lanlibrary.LocalFirstPlaybackRouter? = null,
    private val telemetry: TelemetryEmitter = NoOpTelemetryEmitter(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(DesktopPlayerUiState())
    private var resolutionJob: Job? = null
    private var subtitleJob: Job? = null
    private var runtimeWarmJob: Job? = null
    private var progressSyncJob: Job? = null
    private var stopJob: Job? = null
    private var closeJob: Job? = null
    private var handoffRefreshJob: Job? = null
    private var handoffRefreshKey: String? = null
    private var handoffRefreshAttempts: Int = 0
    private val engineEventJob: Job
    private var activeStartupTrace: PlaybackStartupTrace? = null
    private var recordedHistorySessionKey: String? = null
    private var activeScrobbleSessionKey: String? = null
    private val preparedSessionCache = object : LinkedHashMap<String, DesktopPlaybackSession>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DesktopPlaybackSession>?): Boolean = size > 8
    }

    val state: StateFlow<DesktopPlayerUiState> = _state.asStateFlow()

    /** Whether the active engine renders inside the Torve window. */
    val isEmbedded: Boolean get() = playbackEngine.isEmbedded

    init {
        engineEventJob = scope.launch {
            playbackEngine.events.collect { event ->
                applyEngineEvent(event)
            }
        }
        warmPlaybackEngine()
    }

    /**
     * Bypass the Stremio-addon resolution pipeline and play a stream URL
     * we've already resolved through some other channel (e.g. the
     * desktop Adult catalog, which uploads NZBs to TorBox directly and
     * receives a streamable URL back). Builds a synthetic media item +
     * playback request, primes the session cache so [open] short-
     * circuits to RESOLVED, and immediately calls [play] so the user
     * sees the player surface mount and start fetching the first frame.
     */
    fun openPreResolvedStream(
        streamUrl: String,
        title: String,
        sizeBytes: Long? = null,
        sourceSurface: String = "adult",
        /** Optional full MediaItem - when present, its logoUrl,
         *  posterUrl, backdropUrl, ratings, and type (MOVIE / SERIES)
         *  flow through to the player chrome and the playback dock.
         *  Without this, the player renders "title" as plain text and
         *  the dock badge says "movies" regardless of content. */
        mediaItem: com.torve.domain.model.MediaItem? = null,
        /** TV-only: season + episode + episode title. Drives the
         *  S01E02 • Title label in the player surface and the
         *  history/scrobble row. */
        episodeContext: DesktopPlaybackEpisodeContext? = null,
    ) {
        val mediaType = mediaItem?.type ?: com.torve.domain.model.MediaType.MOVIE
        // Stable mediaId so dedup against the Continue Watching / source
        // cache works across replays of the same NZB → TorBox URL.
        val mediaId = "direct:${streamUrl.hashCode().toUInt().toString(16)}"
        // Local-first short-circuit: if the same media is on disk under
        // the configured download folder, swap to a `file://` URL. Only
        // runs when a router is wired into the controller; provider
        // fallback remains unchanged for every other route type.
        val resolvedStreamUrl: String = localFirstPlaybackRouter?.let { router ->
            val lookupId = mediaItem?.tmdbId?.toString() ?: mediaItem?.id ?: mediaId
            val pick = kotlinx.coroutines.runBlocking {
                router.route(lookupId, providerStreamUrl = streamUrl).pick()
            }
            when (pick) {
                is com.torve.domain.lanlibrary.PlaybackRoute.LocalFile ->
                    "file://${pick.absolutePath.replace('\\', '/')}"
                else -> streamUrl
            }
        } ?: streamUrl
        val request = DesktopPlaybackRequest(
            mediaId = mediaId,
            mediaType = mediaType,
            title = title,
            sourceSurface = sourceSurface,
            seasonNumber = episodeContext?.seasonNumber,
            episodeNumber = episodeContext?.episodeNumber,
        )
        // Carry the user-supplied MediaItem through verbatim if given;
        // otherwise build the same minimal fallback we used before.
        val resolvedMediaItem = mediaItem?.copy(id = mediaId)
            ?: com.torve.domain.model.MediaItem(
                id = mediaId,
                type = mediaType,
                title = title,
            )
        val candidate = DesktopPlaybackSourceCandidate(
            candidateId = "direct",
            addonName = sourceSurface,
            title = title,
            quality = "auto",
            score = 0,
            directUrl = resolvedStreamUrl,
        )
        val session = DesktopPlaybackSession(
            request = request,
            mediaItem = resolvedMediaItem,
            selectedCandidate = candidate,
            resolvedCandidateId = candidate.candidateId,
            resolvedUrl = resolvedStreamUrl,
            resolvedFileName = title,
            resolvedFileSize = sizeBytes,
            episodeContext = episodeContext,
        )
        cachePreparedSession(session)
        open(request)
        play()
    }

    fun open(request: DesktopPlaybackRequest) {
        resolutionJob?.cancel()
        subtitleJob?.cancel()
        resetHandoffRefreshState()
        progressSyncJob?.cancel()
        recordedHistorySessionKey = null
        activeScrobbleSessionKey = null
        com.torve.desktop.diagnostics.SentryBootstrap.breadcrumb(
            category = "playback",
            message = "open() request",
            data = mapOf(
                "mediaType" to request.mediaType.name,
                "sourceSurface" to request.sourceSurface,
                "hasImdbId" to (!request.imdbId.isNullOrBlank()).toString(),
                "hasTmdbId" to (request.tmdbId != null).toString(),
            ),
        )
        val cachedSession = cachedSessionFor(request)
        _state.update {
            it.copy(
                phase = if (cachedSession != null) DesktopPlayerPhase.RESOLVED else DesktopPlayerPhase.IDLE,
                currentRequest = request,
                preparedSession = cachedSession,
                failedCandidateIds = emptySet(),
                lastCommand = DesktopPlayerCommand.OPEN,
                engineMessage = when {
                    cachedSession?.resolvedUrl != null -> "Playback target is already warm and ready to start."
                    cachedSession != null -> "Cached playback session restored. Select Play to launch immediately."
                    else -> "Playback request captured. Select Play to resolve streams and prepare a desktop session."
                },
                error = null,
            )
        }
    }

    fun prepareRequest(
        request: DesktopPlaybackRequest,
    ) {
        open(request)
        prepareCurrentRequest()
    }

    fun probeRuntime() {
        warmPlaybackEngine()
    }

    fun prepareCurrentRequest() {
        val currentRequest = _state.value.currentRequest
        if (currentRequest == null) {
            failRuntime(
                code = "NO_REQUEST",
                message = "No playback request is active.",
            )
            return
        }
        resolutionJob?.cancel()
        resolutionJob = scope.launch {
            preparePlaybackSession(
                request = currentRequest,
                openAfterResolve = false,
                autoPlay = false,
                fetchPolicy = StreamFetchPolicy.FULL,
                startupTrace = null,
            )
        }
    }

    fun selectCandidate(
        candidateId: String,
    ) {
        _state.update { current ->
            val session = current.preparedSession ?: return@update current
            val selectedCandidate = session.streamCandidates.firstOrNull { it.candidateId == candidateId }
                ?: return@update current
            val updatedSession = session.copy(
                selectedCandidate = selectedCandidate,
                resolvedCandidateId = if (session.resolvedCandidateId == candidateId) {
                    session.resolvedCandidateId
                } else {
                    null
                },
                resolvedUrl = if (session.resolvedCandidateId == candidateId) session.resolvedUrl else null,
                resolvedFileName = if (session.resolvedCandidateId == candidateId) session.resolvedFileName else null,
                resolvedMimeType = if (session.resolvedCandidateId == candidateId) session.resolvedMimeType else null,
                resolvedFileSize = if (session.resolvedCandidateId == candidateId) session.resolvedFileSize else null,
                resolvedIsTemporary = if (session.resolvedCandidateId == candidateId) session.resolvedIsTemporary else false,
                resolvedStreamId = if (session.resolvedCandidateId == candidateId) session.resolvedStreamId else null,
                resolvedExpiresInSeconds = if (session.resolvedCandidateId == candidateId) session.resolvedExpiresInSeconds else null,
                transcodeUrls = if (session.resolvedCandidateId == candidateId) session.transcodeUrls else emptyMap(),
            )
            if (session.selectedCandidate?.candidateId != candidateId) {
                resetHandoffRefreshState()
            }
            cachePreparedSession(updatedSession)
            current.copy(
                phase = DesktopPlayerPhase.RESOLVED,
                preparedSession = updatedSession,
                engineMessage = if (session.resolvedCandidateId == candidateId) {
                    "Candidate selected. The current playback target already matches this stream."
                } else {
                    "Candidate selected. Use Open or Play to resolve and launch this stream."
                },
                error = null,
            )
        }
    }

    fun playCandidate(
        candidateId: String,
    ) {
        selectCandidate(candidateId)
        play()
    }

    /**
     * Play a locally-stored file directly in VLC. Skips URL encoding (VLC accepts raw paths
     * on Windows) and bypasses debrid resolution.
     */
    fun playLocalFile(
        title: String,
        absolutePath: String,
        artworkUrl: String? = null,
    ) {
        desktopVerboseLog { "TORVE_CONTROLLER playLocalFile path_configured=${absolutePath.isNotBlank()}" }
        val normalizedTitle = title.trim().ifBlank { "Local File" }
        val file = java.io.File(absolutePath)
        if (!file.exists() || !file.isFile) {
            failRuntime(
                code = "LOCAL_FILE_MISSING",
                message = "File not found on disk: $absolutePath",
            )
            return
        }
        resolutionJob?.cancel()
        subtitleJob?.cancel()
        resetHandoffRefreshState()

        val request = DesktopPlaybackRequest(
            mediaId = "local:${absolutePath.hashCode().toUInt().toString(16)}",
            mediaType = MediaType.MOVIE,
            title = normalizedTitle,
            artworkUrl = artworkUrl,
            sourceSurface = "local_download",
        )
        val siblingSubtitles = scanSiblingSubtitles(file)
        val session = DesktopPlaybackSession(
            request = request,
            mediaItem = MediaItem(
                id = request.mediaId,
                title = normalizedTitle,
                type = MediaType.MOVIE,
                posterUrl = artworkUrl,
                backdropUrl = artworkUrl,
            ),
            resolvedCandidateId = "local",
            resolvedUrl = file.absolutePath,
            subtitleCandidates = siblingSubtitles,
            notes = if (siblingSubtitles.isNotEmpty()) {
                listOf("Local file playback session.", "Found ${siblingSubtitles.size} sibling subtitle file(s).")
            } else {
                listOf("Local file playback session.")
            },
        )
        cachePreparedSession(session)
        _state.update {
            it.copy(
                phase = DesktopPlayerPhase.RESOLVED,
                currentRequest = request,
                preparedSession = session,
                failedCandidateIds = emptySet(),
                lastCommand = DesktopPlayerCommand.PLAY,
                engineMessage = "Opening local file in the desktop player.",
                error = null,
            )
        }
        play()
    }

    fun playDirectStream(
        title: String,
        url: String,
        artworkUrl: String? = null,
        sourceSurface: String = "live_tv",
    ) {
        val selectedPath = when (sourceSurface) {
            "live_tv", "live_tv_catchup", "iptv_vod" -> StreamPlaybackPath.IPTV_DIRECT
            else -> StreamPlaybackPath.DIRECT_FREE
        }
        StreamPathDiagnostics.record(
            path = selectedPath,
            telemetry = telemetry,
            context = StreamPathTelemetryContext(
                contentType = when (sourceSurface) {
                    "live_tv", "live_tv_catchup" -> "live_tv"
                    "iptv_vod" -> "iptv_vod"
                    else -> "direct"
                },
                providerCategory = if (selectedPath == StreamPlaybackPath.IPTV_DIRECT) "iptv" else "direct",
            ),
        )
        desktopVerboseLog {
            "TORVE_CONTROLLER playDirectStream source_surface=$sourceSurface path=${selectedPath.wireValue}"
        }
        val normalizedUrl = url.trim().replace(" ", "%20")
        val normalizedTitle = title.trim().ifBlank { "Live Channel" }
        if (normalizedUrl.isBlank()) {
            failRuntime(
                code = "DIRECT_STREAM_URL_MISSING",
                message = "This live channel does not expose a playable URL.",
            )
            return
        }

        resolutionJob?.cancel()
        subtitleJob?.cancel()
        resetHandoffRefreshState()

        val request = DesktopPlaybackRequest(
            mediaId = buildString {
                append("live:")
                append((normalizedTitle + "|" + normalizedUrl).hashCode().toUInt().toString(16))
            },
            mediaType = MediaType.MOVIE,
            title = normalizedTitle,
            artworkUrl = artworkUrl,
            sourceSurface = sourceSurface,
        )
        val session = DesktopPlaybackSession(
            request = request,
            mediaItem = MediaItem(
                id = request.mediaId,
                title = normalizedTitle,
                type = MediaType.MOVIE,
                posterUrl = artworkUrl,
                backdropUrl = artworkUrl,
            ),
            resolvedCandidateId = "direct",
            resolvedUrl = normalizedUrl,
            notes = listOf("Direct stream playback session created for live TV."),
        )
        cachePreparedSession(session)
        _state.update {
            it.copy(
                phase = DesktopPlayerPhase.RESOLVED,
                currentRequest = request,
                preparedSession = session,
                failedCandidateIds = emptySet(),
                lastCommand = DesktopPlayerCommand.PLAY,
                engineMessage = "Opening live channel directly in the desktop player.",
                error = null,
            )
        }
        play()
    }

    fun openPreparedSession() {
        val currentRequest = _state.value.currentRequest
        val preparedSession = _state.value.preparedSession
        when {
            preparedSession != null -> {
                scope.launch {
                    launchPreparedSession(
                        session = preparedSession,
                        autoPlay = false,
                        command = DesktopPlayerCommand.OPEN,
                        startupTrace = null,
                    )
                }
            }

            currentRequest != null -> {
                resolutionJob?.cancel()
                resolutionJob = scope.launch {
                    preparePlaybackSession(
                        request = currentRequest,
                        openAfterResolve = true,
                        autoPlay = false,
                        fetchPolicy = StreamFetchPolicy.FULL,
                        startupTrace = null,
                    )
                }
            }

            else -> failRuntime(
                code = "NO_REQUEST",
                message = "No playback request is active.",
            )
        }
    }

    fun play() {
        println("TORVE CONTROLLER ┃ play() - engine=${playbackEngine::class.simpleName}, isEmbedded=$isEmbedded, phase=${_state.value.phase}")
        val currentRequest = _state.value.currentRequest
        if (currentRequest == null) {
            _state.update {
                it.copy(
                    phase = DesktopPlayerPhase.RESOLUTION_FAILED,
                    lastCommand = DesktopPlayerCommand.PLAY,
                    error = DesktopPlayerError(
                        code = "NO_REQUEST",
                        message = "No playback request is active.",
                    ),
                    engineMessage = "Select an item from Search or Library before trying to play.",
                )
            }
            return
        }

        val currentSession = _state.value.preparedSession
        when (_state.value.phase) {
            DesktopPlayerPhase.PAUSED -> {
                scope.launch {
                    runCatching { playbackEngine.play() }
                        .onFailure { failRuntime("ENGINE_PLAY_FAILED", it.message ?: "Failed to resume playback.") }
                }
                return
            }

            DesktopPlayerPhase.PLAYING -> {
                _state.update {
                    it.copy(
                        lastCommand = DesktopPlayerCommand.PLAY,
                        engineMessage = "Playback is already running.",
                        error = null,
                    )
                }
                return
            }

            else -> Unit
        }

        resetHandoffRefreshState()
        val startupTrace = PlaybackStartupTelemetry.begin(currentRequest).also {
            it.mark("play action received", "phase=${_state.value.phase}")
        }
        activeStartupTrace = startupTrace

        if (currentSession != null) {
            scope.launch {
                launchPreparedSession(
                    session = currentSession,
                    autoPlay = true,
                    command = DesktopPlayerCommand.PLAY,
                    startupTrace = startupTrace,
                )
            }
            return
        }

        resolutionJob?.cancel()
        resolutionJob = scope.launch {
            preparePlaybackSession(
                request = currentRequest,
                openAfterResolve = true,
                autoPlay = true,
                fetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
                startupTrace = startupTrace,
            )
        }
    }

    fun pause() {
        val preparedSession = _state.value.preparedSession
        if (preparedSession == null) {
            failRuntime(
                code = "NO_PREPARED_SESSION",
                message = "Resolve a playback session before using transport commands.",
            )
            return
        }
        scope.launch {
            runCatching { playbackEngine.pause() }
                .onFailure { failRuntime("ENGINE_PAUSE_FAILED", it.message ?: "Failed to pause playback.") }
        }
    }

    fun stop() {
        if (closeJob?.isActive == true || stopJob?.isActive == true) return
        if (_state.value.phase == DesktopPlayerPhase.STOPPED) return
        val preparedSession = _state.value.preparedSession
        if (preparedSession == null) {
            failRuntime(
                code = "NO_PREPARED_SESSION",
                message = "Resolve a playback session before using transport commands.",
            )
            return
        }
        stopJob = scope.launch {
            runCatching { playbackEngine.stop() }
                .onFailure { failRuntime("ENGINE_STOP_FAILED", it.message ?: "Failed to stop playback.") }
        }
    }

    fun close() {
        if (closeJob?.isActive == true) return
        if (_state.value.phase == DesktopPlayerPhase.CLOSED && _state.value.lastCommand == DesktopPlayerCommand.CLOSE) return
        stopJob?.cancel()
        resolutionJob?.cancel()
        progressSyncJob?.cancel()
        closeJob = scope.launch {
            // Save playback position and volume before closing
            savePlaybackProgress()
            stopTraktScrobble()
            saveVolumeIfNeeded()
            runCatching { playbackEngine.close() }
                .onFailure { failRuntime("ENGINE_CLOSE_FAILED", it.message ?: "Failed to close playback.") }
            _state.update {
                it.copy(
                    phase = DesktopPlayerPhase.CLOSED,
                    lastCommand = DesktopPlayerCommand.CLOSE,
                    engineMessage = "Playback session closed. You can resume or dismiss it from desktop.",
                    error = null,
                    preparingStream = null,
                )
            }
        }
    }

    /**
     * Cancel an in-flight Panda/addon "still preparing" poll loop. Called from
     * the preparing overlay's Cancel button. The poll runs inside
     * [resolutionJob], so [close] is enough to tear it down - it cancels the
     * job and clears [DesktopPreparingStreamState] alongside the session.
     */
    fun cancelPreparing() {
        close()
    }

    /**
     * Run an OpenSubtitles-style search against every installed subtitle
     * addon for the currently-active media. Returns an empty list if there's
     * no playback session yet, no IMDb id (Stremio addons key on imdbId), or
     * every addon timed out / errored.
     *
     * The search runs on [Dispatchers.IO] so the caller can `runBlocking` /
     * `launch` it from the player chrome without blocking the EDT.
     */
    suspend fun searchOnlineSubtitles(): List<com.torve.data.addon.StremioSubtitle> {
        val session = _state.value.preparedSession ?: return emptyList()
        val imdbId = session.mediaItem.imdbId ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val addons = addonRepository.getInstalledAddons()
            runCatching {
                subtitleAggregator.fetchSubtitles(
                    addons = addons,
                    type = session.mediaItem.type,
                    imdbId = imdbId,
                    season = session.episodeContext?.seasonNumber,
                    episode = session.episodeContext?.episodeNumber,
                    addonTimeoutMs = 6_000,
                )
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Block until the addon-hosted [initialUrl] reports Ready, the user
     * cancels, the probe surfaces a hard failure, or we exhaust the attempt
     * budget. While polling we drive [DesktopPreparingStreamState] so the
     * Compose preparing overlay can render an elapsed-time spinner.
     *
     * Returns the (possibly redirected) Ready URL, or null when the loop
     * resolved into Cancel/Failed/Timeout - the caller already had the
     * failure surfaced via [_state] and should bail out cleanly.
     */
    private suspend fun awaitStreamReady(
        initialUrl: String,
        streamTitle: String,
        serviceName: String,
        startupTrace: PlaybackStartupTrace?,
    ): String? {
        val first = runCatching {
            withContext(Dispatchers.IO) { streamRepository.probeStreamReadiness(initialUrl) }
        }.getOrElse { com.torve.domain.repository.StreamReadiness.Failed(it.message ?: "probe failed") }

        when (first) {
            is com.torve.domain.repository.StreamReadiness.Ready -> return first.finalUrl
            is com.torve.domain.repository.StreamReadiness.Failed -> {
                startupTrace?.complete("resolution-failed:STREAM_NOT_READY")
                failRuntime(
                    code = "STREAM_NOT_READY",
                    message = first.reason,
                    markSelectedCandidateFailed = true,
                )
                return null
            }
            com.torve.domain.repository.StreamReadiness.Preparing -> Unit
        }

        val maxAttempts = PREPARING_MAX_ATTEMPTS
        val intervalMs = PREPARING_PROBE_INTERVAL_MS
        val startedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                preparingStream = DesktopPreparingStreamState(
                    title = streamTitle,
                    serviceName = serviceName,
                    startedAtEpochMs = startedAt,
                    attempt = 1,
                    maxAttempts = maxAttempts,
                ),
                error = null,
            )
        }

        try {
            for (attemptIdx in 0 until maxAttempts) {
                kotlinx.coroutines.delay(intervalMs)
                val readiness = runCatching {
                    withContext(Dispatchers.IO) { streamRepository.probeStreamReadiness(initialUrl) }
                }.getOrElse { com.torve.domain.repository.StreamReadiness.Failed(it.message ?: "probe failed") }
                when (readiness) {
                    is com.torve.domain.repository.StreamReadiness.Ready -> {
                        _state.update { it.copy(preparingStream = null) }
                        return readiness.finalUrl
                    }
                    com.torve.domain.repository.StreamReadiness.Preparing -> {
                        _state.update { s ->
                            val current = s.preparingStream ?: return@update s
                            s.copy(preparingStream = current.copy(attempt = attemptIdx + 2))
                        }
                    }
                    is com.torve.domain.repository.StreamReadiness.Failed -> {
                        startupTrace?.complete("resolution-failed:STREAM_NOT_READY")
                        _state.update { it.copy(preparingStream = null) }
                        failRuntime(
                            code = "STREAM_NOT_READY",
                            message = readiness.reason,
                            markSelectedCandidateFailed = true,
                        )
                        return null
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(preparingStream = null) }
            throw cancelled
        }

        // Budget exhausted (~5 min). Tell the user to pick a different source.
        _state.update { it.copy(preparingStream = null) }
        startupTrace?.complete("resolution-failed:STREAM_PREPARING_TIMEOUT")
        failRuntime(
            code = "STREAM_PREPARING_TIMEOUT",
            message = "The source is still warming up after several minutes. Try a different source.",
            markSelectedCandidateFailed = true,
        )
        return null
    }

    private companion object {
        const val PREPARING_PROBE_INTERVAL_MS: Long = 15_000L
        const val PREPARING_MAX_ATTEMPTS: Int = 20

        // Sources whose playbacks must NOT contribute to the Continue
        // Watching rail. Live TV is open-ended; Adult and Sports are
        // excluded by product policy.
        val PROGRESS_EXCLUDED_SURFACES = setOf("live_tv", "adult", "sports")
    }

    private suspend fun savePlaybackProgress() {
        val request = _state.value.currentRequest ?: return
        // Surfaces excluded from watch-progress persistence:
        //  - live_tv: streams are open-ended, no useful resume point.
        //  - adult / sports: per product policy these never appear in
        //    Continue Watching on the Home tab. Their mediaIds are
        //    synthetic `direct:<hash>` strings with no catalog metadata,
        //    so even if we did persist they'd render as posterless rows.
        if (request.sourceSurface in PROGRESS_EXCLUDED_SURFACES ||
            request.mediaId.startsWith("live:")
        ) return
        val session = _state.value.preparedSession ?: return
        val runtimeInfo = _state.value.runtimeInfo
        val positionMs = (runtimeInfo.playbackPositionSeconds?.times(1000))?.toLong() ?: return
        val durationMs = (runtimeInfo.durationSeconds?.times(1000))?.toLong() ?: return
        if (positionMs <= 0 || durationMs <= 0) return
        val syncIds = buildPlaybackSyncIds(request, session)
        val progress = WatchProgress(
            mediaId = syncIds.mediaId,
            mediaType = request.mediaType,
            title = session.episodeContext?.label ?: request.title,
            posterUrl = session.mediaItem.posterUrl ?: request.artworkUrl,
            backdropUrl = session.mediaItem.backdropUrl ?: request.artworkUrl,
            positionMs = positionMs,
            durationMs = durationMs,
            seasonNumber = session.episodeContext?.seasonNumber ?: request.seasonNumber,
            episodeNumber = session.episodeContext?.episodeNumber ?: request.episodeNumber,
            showTitle = session.mediaItem.title,
            updatedAt = System.currentTimeMillis(),
        )
        runCatching { watchProgressRepository.saveProgress(progress) }
    }

    private fun applyPlaybackPreferences() {
        val settings = settingsViewModel.state.value
        // Set preferred subtitle language on the engine before media open
        val engine = playbackEngine
        if (engine is com.torve.desktop.vlc.VlcPlaybackEngine) {
            engine.preferredSubtitleLanguage = settings.preferredSubtitleLanguage.takeIf { it.isNotBlank() }
        }
    }

    private fun saveVolumeIfNeeded() {
        val settings = settingsViewModel.state.value
        if (!settings.rememberVolume) return
        val engine = playbackEngine
        if (engine is com.torve.desktop.vlc.VlcPlaybackEngine) {
            val currentVolume = engine.getVolume().coerceIn(0, 200)
            // Only save if within normal range (0-100)
            settingsViewModel.setLastVolume(currentVolume.coerceIn(0, 100))
        }
    }

    /** Called by UI when user changes volume via slider or keys. */
    fun onVolumeChanged(volume: Int) {
        val settings = settingsViewModel.state.value
        if (settings.rememberVolume) {
            settingsViewModel.setLastVolume(volume.coerceIn(0, 100))
        }
    }

    /** Current playback preferences for the VLC surface. */
    fun getSeekStepMs(): Long = settingsViewModel.state.value.seekStepSeconds * 1000L

    fun retryCurrentRequest() {
        val currentRequest = _state.value.currentRequest
        val preparedSession = _state.value.preparedSession
        when {
            preparedSession != null -> {
                scope.launch {
                    launchPreparedSession(
                        session = preparedSession,
                        autoPlay = true,
                        command = DesktopPlayerCommand.PLAY,
                        startupTrace = activeStartupTrace,
                    )
                }
            }

            currentRequest != null -> {
                prepareRequest(currentRequest)
            }

            else -> failRuntime(
                code = "NO_REQUEST",
                message = "No playback request is available to retry.",
            )
        }
    }

    fun clearFailureState() {
        _state.update { current ->
            current.copy(
                error = null,
                engineMessage = if (current.preparedSession != null) {
                    "Playback session is ready for another attempt."
                } else {
                    "Playback state cleared."
                },
                phase = when {
                    current.preparedSession != null -> DesktopPlayerPhase.RESOLVED
                    current.currentRequest != null -> DesktopPlayerPhase.IDLE
                    else -> DesktopPlayerPhase.IDLE
                },
            )
        }
    }

    fun clearSession() {
        resolutionJob?.cancel()
        progressSyncJob?.cancel()
        recordedHistorySessionKey = null
        activeScrobbleSessionKey = null
        _state.value = DesktopPlayerUiState(
            runtimeInfo = _state.value.runtimeInfo.copy(
                backendLabel = playbackEngine.backendLabel,
                requirementText = playbackEngine.runtimeRequirement,
            ),
        )
    }

    fun clearClosedState() {
        if (_state.value.phase == DesktopPlayerPhase.CLOSED) {
            _state.value = DesktopPlayerUiState()
        }
    }

    fun dispose() {
        resolutionJob?.cancel()
        subtitleJob?.cancel()
        runtimeWarmJob?.cancel()
        engineEventJob.cancel()
        playbackEngine.dispose()
        scope.cancel()
    }

    private fun warmPlaybackEngine(startupTrace: PlaybackStartupTrace? = null) {
        if (runtimeWarmJob?.isActive == true) return
        if (_state.value.runtimeInfo.runtimeFound == true) return
        runtimeWarmJob = scope.launch {
            runCatching { playbackEngine.probeRuntime(startupTrace) }
                .onFailure { failRuntime("RUNTIME_PROBE_FAILED", it.message ?: "Desktop playback runtime probe failed.") }
        }
    }

    private suspend fun ensurePlaybackEngineWarm(startupTrace: PlaybackStartupTrace?) {
        warmPlaybackEngine(startupTrace)
        runtimeWarmJob?.join()
    }

    private fun cachedSessionFor(request: DesktopPlaybackRequest): DesktopPlaybackSession? =
        synchronized(preparedSessionCache) { preparedSessionCache[request.cacheKey()] }
            ?.takeIf { it.streamRulesCacheKey == currentStreamRulesCacheKey() }

    private fun currentStreamRulesCacheKey(): String {
        if (!com.torve.desktop.premium.DesktopPremiumStateHolder.isPremium()) return "free"
        val state = settingsViewModel.state.value
        return buildString {
            append("premium|regex=")
            append(state.regexPatterns.joinToString(";") { "${it.label}:${it.pattern}:${it.enabled}" })
            append("|groups=")
            append(state.streamGroups.joinToString(";") { "${it.name}:${it.matchPattern}:${it.priority}:${it.enabled}" })
        }
    }

    private fun cachePreparedSession(session: DesktopPlaybackSession) {
        synchronized(preparedSessionCache) {
            preparedSessionCache[session.request.cacheKey()] = session.withoutTemporaryResolvedUrl()
        }
    }

    private fun DesktopPlaybackSession.withoutTemporaryResolvedUrl(): DesktopPlaybackSession {
        if (!resolvedIsTemporary) return this
        return copy(
            resolvedCandidateId = null,
            resolvedUrl = null,
            resolvedFileName = null,
            resolvedMimeType = null,
            resolvedFileSize = null,
            resolvedIsTemporary = false,
            resolvedStreamId = null,
            resolvedExpiresInSeconds = null,
            transcodeUrls = emptyMap(),
        )
    }

    private fun resetHandoffRefreshState() {
        handoffRefreshJob?.cancel()
        handoffRefreshJob = null
        handoffRefreshKey = null
        handoffRefreshAttempts = 0
    }

    private fun DesktopPlaybackSession.handoffRefreshKey(): String? {
        val candidate = selectedCandidate ?: return null
        val memoryId = candidate.accelerationMemoryId?.takeIf { it.isNotBlank() } ?: return null
        return "${request.cacheKey()}:${candidate.candidateId}:$memoryId"
    }

    private fun loadSubtitlesInBackground(session: DesktopPlaybackSession) {
        val imdbId = session.mediaItem.imdbId ?: return
        if (session.subtitleCandidates.isNotEmpty()) return
        subtitleJob?.cancel()
        subtitleJob = scope.launch {
            val subtitles = withContext(Dispatchers.IO) {
                val addons = addonRepository.getInstalledAddons()
                runCatching {
                    subtitleAggregator.fetchSubtitles(
                        addons = addons,
                        type = session.mediaItem.type,
                        imdbId = imdbId,
                        season = session.episodeContext?.seasonNumber,
                        episode = session.episodeContext?.episodeNumber,
                        addonTimeoutMs = 2_500,
                    )
                }.getOrDefault(emptyList())
            }
            if (subtitles.isEmpty()) return@launch
            val updatedSession = session.copy(
                subtitleCandidates = subtitles.map { it.toDesktopSubtitleCandidate() },
            )
            cachePreparedSession(updatedSession)
            _state.update { current ->
                if (current.currentRequest?.cacheKey() != session.request.cacheKey()) {
                    current
                } else {
                    current.copy(preparedSession = updatedSession)
                }
            }
        }
    }

    private suspend fun preparePlaybackSession(
        request: DesktopPlaybackRequest,
        openAfterResolve: Boolean,
        autoPlay: Boolean,
        fetchPolicy: StreamFetchPolicy,
        startupTrace: PlaybackStartupTrace?,
    ) {
        resetHandoffRefreshState()
        _state.update {
            it.copy(
                phase = DesktopPlayerPhase.RESOLVING,
                currentRequest = request,
                preparedSession = null,
                failedCandidateIds = emptySet(),
                lastCommand = DesktopPlayerCommand.PLAY,
                engineMessage = "Resolving streams and preparing a desktop playback session...",
                error = null,
            )
        }

        try {
            startupTrace?.mark("source resolution start", fetchPolicy.label)
            val preparedSession = withContext(Dispatchers.IO) {
                val resolvedMedia = resolveMediaItem(request)
                val episodeContext = resolveEpisodeContext(request, resolvedMedia)
                val resolvedImdbId = resolvedMedia.imdbId
                val sessionSeed = DesktopPlaybackSession(
                    request = request,
                    mediaItem = resolvedMedia,
                    episodeContext = episodeContext,
                    provider = settingsViewModel.getDebridProvider(),
                    streamRulesCacheKey = currentStreamRulesCacheKey(),
                    notes = buildList {
                        if (request.mediaType == MediaType.SERIES && episodeContext == null) {
                            add("Series request is still media-level only. No concrete episode target could be derived yet.")
                        }
                    },
                    isMediaLevelOnly = request.mediaType == MediaType.SERIES && episodeContext == null,
                )

                if (resolvedImdbId.isNullOrBlank()) {
                    throw SessionPreparationException(
                        session = sessionSeed,
                        code = "NO_IMDB_ID",
                        message = "This title does not have an IMDb identifier yet, so source resolution cannot start.",
                    )
                }

                if (resolvedMedia.type == MediaType.SERIES && episodeContext == null) {
                    throw SessionPreparationException(
                        session = sessionSeed,
                        code = "SERIES_EPISODE_CONTEXT_REQUIRED",
                        message = "Series playback needs a concrete season and episode target before streams can be resolved.",
                    )
                }

                val addons = addonRepository.getInstalledAddons()
                if (addons.none { it.isEnabled }) {
                    throw SessionPreparationException(
                        session = sessionSeed,
                        code = "NO_STREAM_ADDONS",
                        message = "No enabled stream addons are configured for desktop playback preparation.",
                    )
                }

                val provider = settingsViewModel.getDebridProvider()
                val preferences = settingsViewModel.buildStreamPreferences()
                val debridAccounts = settingsViewModel.getDebridAccounts()
                val initialFetch = streamRepository.fetchStreamsWithFeedback(
                    type = resolvedMedia.type,
                    imdbId = resolvedImdbId,
                    contentId = resolvedMedia.tmdbId?.let { "tmdb:$it" },
                    title = resolvedMedia.title,
                    season = episodeContext?.seasonNumber,
                    episode = episodeContext?.episodeNumber,
                    addons = addons,
                    debridAccounts = debridAccounts,
                    preferences = preferences,
                    fetchPolicy = fetchPolicy,
                )
                val finalFetch = if (initialFetch.streams.isEmpty() && fetchPolicy == StreamFetchPolicy.PLAYBACK_STARTUP) {
                    streamRepository.fetchStreamsWithFeedback(
                        type = resolvedMedia.type,
                        imdbId = resolvedImdbId,
                        contentId = resolvedMedia.tmdbId?.let { "tmdb:$it" },
                        title = resolvedMedia.title,
                        season = episodeContext?.seasonNumber,
                        episode = episodeContext?.episodeNumber,
                        addons = addons,
                        debridAccounts = debridAccounts,
                        preferences = preferences,
                        fetchPolicy = StreamFetchPolicy.FULL,
                    )
                } else {
                    initialFetch
                }
                val streamCandidates = finalFetch.streams
                val filterHiddenCount = finalFetch.filterFeedback.hiddenCount

                if (streamCandidates.isEmpty()) {
                    val allHiddenMessage = StreamFilterUiText.allHiddenMessage(
                        visibleCount = streamCandidates.size,
                        hiddenCount = filterHiddenCount,
                    )
                    throw SessionPreparationException(
                        session = sessionSeed.copy(
                            filterHiddenCount = filterHiddenCount,
                            notes = sessionSeed.notes + "Stream aggregation returned zero candidates.",
                        ),
                        code = if (allHiddenMessage != null) "ALL_STREAMS_FILTERED" else "NO_STREAMS_FOUND",
                        message = allHiddenMessage ?: "No playable source candidates were returned for this request.",
                    )
                }

                val desktopCandidates = streamCandidates.mapIndexed { index, candidate ->
                    candidate.toDesktopSourceCandidate(index)
                }
                val recommendedCandidate = desktopCandidates.maxByOrNull { it.score } ?: desktopCandidates.first()
                sessionSeed.copy(
                    provider = provider,
                    streamCandidates = desktopCandidates,
                    recommendedCandidateId = recommendedCandidate.candidateId,
                    selectedCandidate = recommendedCandidate,
                    filterHiddenCount = filterHiddenCount,
                    requestHeaders = emptyMap(),
                    subtitleCandidates = emptyList(),
                    notes = buildList {
                        addAll(sessionSeed.notes)
                        add("Playback session prepared with ${desktopCandidates.size} source candidates.")
                        add("Select a candidate, then use Open or Play to resolve it for desktop playback.")
                        add("Subtitles continue loading in the background to protect startup latency.")
                        if (settingsViewModel.getDebridApiKey().isBlank()) {
                            add("No debrid credential is currently loaded. Candidate launch will fail until one is connected.")
                        }
                    },
                )
            }
            startupTrace?.mark("source resolution end", "candidates=${preparedSession.streamCandidates.size}")
            cachePreparedSession(preparedSession)

            _state.update {
                it.copy(
                    phase = DesktopPlayerPhase.RESOLVED,
                    preparedSession = preparedSession,
                    engineMessage = "Playback session prepared successfully. Choose a source candidate, then open or play it.",
                    error = null,
                )
            }
            loadSubtitlesInBackground(preparedSession)

            if (openAfterResolve) {
                launchPreparedSession(
                    session = preparedSession,
                    autoPlay = autoPlay,
                    command = if (autoPlay) DesktopPlayerCommand.PLAY else DesktopPlayerCommand.OPEN,
                    startupTrace = startupTrace,
                )
            }
        } catch (e: SessionPreparationException) {
            startupTrace?.complete("resolution-failed:${e.code}")
            failPreparation(
                session = e.session,
                code = e.code,
                message = e.message,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            startupTrace?.complete("resolution-failed:SESSION_PREPARATION_FAILED")
            failPreparation(
                session = _state.value.preparedSession,
                code = "SESSION_PREPARATION_FAILED",
                message = e.message ?: "Desktop playback session preparation failed.",
            )
        }
    }

    private suspend fun launchPreparedSession(
        session: DesktopPlaybackSession,
        autoPlay: Boolean,
        command: DesktopPlayerCommand,
        startupTrace: PlaybackStartupTrace?,
    ) {
        println("TORVE CONTROLLER ┃ launchPreparedSession - engine=${playbackEngine::class.simpleName}, isEmbedded=$isEmbedded, autoPlay=$autoPlay")
        ensurePlaybackEngineWarm(startupTrace)
        val resolvedSession = resolveSelectedCandidate(session, startupTrace) ?: return

        _state.update {
            it.copy(
                phase = DesktopPlayerPhase.OPENING,
                preparedSession = resolvedSession,
                lastCommand = command,
                engineMessage = if (autoPlay) {
                    "Launching Windows playback engine for the resolved stream..."
                } else {
                    "Opening resolved stream in the Windows playback engine..."
                },
                error = null,
            )
        }

        // Restore saved position for resume
        val savedProgress = runCatching {
            val req = _state.value.currentRequest
            if (req != null) watchProgressRepository.getProgress(req.mediaId) else null
        }.getOrNull()
        val resumePositionMs = savedProgress?.positionMs?.takeIf { it > 0 && savedProgress.progressPercent < 0.95f }

        // Apply playback preferences to engine before opening
        applyPlaybackPreferences()

        runCatching {
            playbackEngine.open(
                session = resolvedSession,
                autoPlay = autoPlay,
                startupTrace = startupTrace,
                resumePositionMs = resumePositionMs,
            )
        }.onFailure {
            val existingError = _state.value.error
            if (existingError?.code?.startsWith("VLC_") == true) {
                return@onFailure
            }
            startupTrace?.complete("engine-open-failed")
            failRuntime(
                code = "ENGINE_OPEN_FAILED",
                message = it.message ?: "The Windows playback engine could not open the resolved stream.",
                markSelectedCandidateFailed = true,
            )
        }
    }

    private fun tryStartExpiredHandoffRefresh(
        event: DesktopPlaybackEngineEvent.Error,
    ): Boolean {
        val session = _state.value.preparedSession ?: return false
        val refreshKey = session.handoffRefreshKey() ?: return false
        val attempts = if (handoffRefreshKey == refreshKey) handoffRefreshAttempts else 0
        if (!shouldAttemptDesktopHandoffRefresh(
                session = session,
                code = event.code,
                message = event.message,
                recoverable = event.recoverable,
                attempts = attempts,
            )
        ) {
            return false
        }
        if (handoffRefreshJob?.isActive == true) return true

        handoffRefreshKey = refreshKey
        handoffRefreshAttempts = attempts + 1
        handoffRefreshJob = scope.launch {
            refreshExpiredHandoffAndRetry(session)
        }
        return true
    }

    private suspend fun refreshExpiredHandoffAndRetry(
        session: DesktopPlaybackSession,
    ) {
        val resumePositionMs = _state.value.runtimeInfo.playbackPositionSeconds
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1000).toLong() }
        val seed = session.copy(
            resolvedCandidateId = null,
            resolvedUrl = null,
            resolvedFileName = null,
            resolvedMimeType = null,
            resolvedFileSize = null,
            resolvedIsTemporary = false,
            resolvedStreamId = null,
            resolvedExpiresInSeconds = null,
            transcodeUrls = emptyMap(),
        )
        _state.update { current ->
            if (current.currentRequest?.cacheKey() != session.request.cacheKey()) {
                current
            } else {
                current.copy(
                    phase = DesktopPlayerPhase.RESOLVING,
                    preparedSession = seed,
                    engineMessage = desktopExpiredHandoffUserMessage(),
                    error = null,
                )
            }
        }

        val refreshedSession = resolveSelectedCandidate(
            session = seed,
            startupTrace = null,
            failureMessageOverride = desktopExpiredHandoffFailureMessage(),
        ) ?: run {
            onPlaybackEnded()
            return
        }

        _state.update { current ->
            current.copy(
                phase = DesktopPlayerPhase.OPENING,
                preparedSession = refreshedSession,
                lastCommand = DesktopPlayerCommand.PLAY,
                engineMessage = "Opening refreshed playback link...",
                error = null,
            )
        }

        runCatching {
            playbackEngine.open(
                session = refreshedSession,
                autoPlay = true,
                startupTrace = null,
                resumePositionMs = resumePositionMs,
            )
        }.onFailure {
            failRuntime(
                code = "HANDOFF_REFRESH_FAILED",
                message = desktopExpiredHandoffFailureMessage(),
                markSelectedCandidateFailed = true,
            )
            onPlaybackEnded()
        }
    }

    private suspend fun resolveSelectedCandidate(
        session: DesktopPlaybackSession,
        startupTrace: PlaybackStartupTrace?,
        failureMessageOverride: String? = null,
    ): DesktopPlaybackSession? {
        if (!session.resolvedUrl.isNullOrBlank()) {
            startupTrace?.mark("final media URL ready", "direct")
            return session
        }

        val selectedCandidate = session.selectedCandidate
        if (selectedCandidate == null) {
            startupTrace?.complete("resolution-failed:NO_SELECTED_CANDIDATE")
            failRuntime(
                code = "NO_SELECTED_CANDIDATE",
                message = "Select a source candidate before launching playback.",
            )
            return null
        }

        if (
            session.resolvedCandidateId == selectedCandidate.candidateId &&
            !session.resolvedUrl.isNullOrBlank()
        ) {
            startupTrace?.mark("final media URL ready", "cache-hit")
            return session
        }

        // Build a provider chain. The user's currently-active provider
        // (session.provider, falling back to the active one in settings) goes
        // first; everything else from connectedDebridProviders trails. If a
        // source isn't cached on the active provider we still get a chance on
        // the others before we surrender.
        val activeProvider = session.provider ?: settingsViewModel.getDebridProvider()
        val accounts = settingsViewModel.getDebridAccounts()
        val orderedProviders = LinkedHashMap<DebridServiceType, String>().apply {
            accounts[activeProvider]?.takeIf { it.isNotBlank() }?.let { put(activeProvider, it) }
            for ((p, k) in accounts) {
                if (p == activeProvider) continue
                if (k.isBlank()) continue
                put(p, k)
            }
            // Fallback for legacy single-key state where connectedDebridProviders
            // wasn't populated but debridApiKey is set.
            if (isEmpty()) {
                val legacyKey = settingsViewModel.getDebridApiKey()
                if (legacyKey.isNotBlank()) put(activeProvider, legacyKey)
            }
        }

        val parsedSelectedForPolicy = selectedCandidate.toParsedStream()
        if (
            orderedProviders.isEmpty() &&
            !parsedSelectedForPolicy.isAddonHostedUrl() &&
            !parsedSelectedForPolicy.canUseGenericStreamHandoff()
        ) {
            startupTrace?.complete("resolution-failed:NO_DEBRID_ACCOUNT")
            failRuntime(
                code = "NO_DEBRID_ACCOUNT",
                message = "This candidate cannot be launched because no debrid credential is currently loaded.",
            )
            return null
        }

        _state.update {
            it.copy(
                phase = DesktopPlayerPhase.RESOLVING,
                preparedSession = session,
                lastCommand = it.lastCommand ?: DesktopPlayerCommand.PLAY,
                engineMessage = if (orderedProviders.size > 1) {
                    "Resolving via ${orderedProviders.size} debrid providers in order..."
                } else {
                    "Resolving the selected candidate into a playable desktop stream..."
                },
                error = null,
            )
        }

        val resolvedStream = runCatching {
            withContext(Dispatchers.IO) {
                streamRepository.resolveStreamWithFallback(
                    stream = parsedSelectedForPolicy,
                    providers = orderedProviders,
                )
            }
        }.getOrElse {
            startupTrace?.complete("resolution-failed:CANDIDATE_RESOLUTION_FAILED")
            failRuntime(
                code = "CANDIDATE_RESOLUTION_FAILED",
                message = failureMessageOverride
                    ?: it.message
                    ?: "The selected source candidate could not be resolved.",
                markSelectedCandidateFailed = true,
            )
            return null
        }

        // Mirror the Android player flow: addon-hosted URLs (Panda's
        // /u/<token>/nzb/...) may legitimately answer 504 with `nzb_not_ready`
        // while the upstream Usenet download warms up. Handing that straight to
        // VLC produces an opaque "VLC encountered a playback error" - instead,
        // probe the URL and, when Preparing, drive a polling overlay that
        // ticks every 15 s until Ready / Failed / cancelled.
        val parsedSelected = parsedSelectedForPolicy
        var playableUrl = resolvedStream.url
        if (parsedSelected.isAddonHostedUrl() && playableUrl.isNotBlank()) {
            val readyUrl = awaitStreamReady(
                initialUrl = playableUrl,
                streamTitle = selectedCandidate.title,
                serviceName = selectedCandidate.source
                    ?: selectedCandidate.addonName.takeIf { it.isNotBlank() }
                    ?: "Your cloud service",
                startupTrace = startupTrace,
            ) ?: return null
            playableUrl = readyUrl
        }

        val resolvedSession = session.copy(
            // Preserve the active provider on the session for downstream use.
            // The actual provider that succeeded isn't surfaced today (resolved.service
            // is set when the debrid client populates it) but the active label is
            // still correct for telemetry.
            provider = activeProvider,
            resolvedCandidateId = selectedCandidate.candidateId,
            resolvedUrl = playableUrl,
            resolvedFileName = resolvedStream.fileName,
            resolvedMimeType = resolvedStream.mimeType,
            resolvedFileSize = resolvedStream.fileSize,
            resolvedIsTemporary = resolvedStream.isTemporary,
            resolvedStreamId = resolvedStream.streamId,
            resolvedExpiresInSeconds = resolvedStream.expiresInSeconds,
            transcodeUrls = resolvedStream.toDesktopTranscodeUrls(),
            notes = session.notes + "Resolved candidate '${selectedCandidate.addonName} / ${selectedCandidate.quality}' for desktop playback.",
        )
        startupTrace?.mark("final media URL ready", resolvedStream.fileName ?: selectedCandidate.title)
        cachePreparedSession(resolvedSession)

        _state.update {
            it.copy(
                phase = DesktopPlayerPhase.RESOLVED,
                preparedSession = resolvedSession,
                engineMessage = "Selected candidate resolved successfully. Desktop playback can now launch it.",
                error = null,
            )
        }
        return resolvedSession
    }

    private suspend fun resolveMediaItem(
        request: DesktopPlaybackRequest,
    ): MediaItem {
        val type = request.mediaType.toMetadataType()
        val directMatch = when {
            request.tmdbId != null -> runCatching {
                metadataRepository.getDetail(type, request.tmdbId)
            }.getOrElse {
                null
            }

            !request.imdbId.isNullOrBlank() -> metadataRepository.findByImdbId(
                imdbId = request.imdbId,
                preferredType = type,
            )

            else -> null
        }
        if (directMatch != null) return directMatch

        val title = request.title.trim()
        if (title.isNotBlank()) {
            val searchedMatch = runCatching {
                metadataRepository.searchMultiPaged(query = title, page = 1, type = type).items
            }.getOrDefault(emptyList())
                .asSequence()
                .filter { it.type == request.mediaType }
                .sortedWith(
                    compareByDescending<MediaItem> { normalizeTitle(it.title) == normalizeTitle(title) }
                        .thenByDescending { it.tmdbId != null }
                        .thenByDescending { it.popularity ?: 0.0 }
                )
                .firstOrNull()

            if (searchedMatch != null) {
                val enrichedMatch = searchedMatch.tmdbId?.let { tmdbId ->
                    runCatching { metadataRepository.getDetail(type, tmdbId) }.getOrNull()
                }
                return enrichedMatch ?: searchedMatch
            }
        }

        return fallbackMediaItem(request)
    }

    private suspend fun resolveEpisodeContext(
        request: DesktopPlaybackRequest,
        mediaItem: MediaItem,
    ): DesktopPlaybackEpisodeContext? {
        if (mediaItem.type != MediaType.SERIES) {
            return null
        }

        if (request.seasonNumber != null && request.episodeNumber != null) {
            val seasonNumber = request.seasonNumber
            val episodeNumber = request.episodeNumber
            return DesktopPlaybackEpisodeContext(
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = null,
                mode = DesktopEpisodeResolutionMode.REQUESTED,
            )
        }

        val progress = runCatching { watchProgressRepository.getAllProgress() }.getOrDefault(emptyList())
        val inProgress = progress
            .filter { it.seasonNumber != null && it.episodeNumber != null }
            .filter { it.showTitle == mediaItem.title || it.mediaId == mediaItem.id || it.mediaId.startsWith("${mediaItem.id}_") }
            .filter { it.progressPercent > 0.02f && it.progressPercent < 0.9f }
            .maxByOrNull { it.updatedAt }

        if (inProgress != null) {
            val seasonNumber = inProgress.seasonNumber!!
            val episodeNumber = inProgress.episodeNumber!!
            return DesktopPlaybackEpisodeContext(
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = null,
                mode = DesktopEpisodeResolutionMode.RESUME_IN_PROGRESS,
            )
        }

        val watchedEpisodes = runCatching {
            watchHistoryRepository.getForMedia(mediaItem.id)
                .filter { it.seasonNumber != null && it.episodeNumber != null }
                .map { episodeKey(it.seasonNumber!!, it.episodeNumber!!) }
                .toSet()
        }.getOrDefault(emptySet())

        val seasons = mediaItem.seasons
            .filter { it.seasonNumber > 0 }
            .sortedBy { it.seasonNumber }

        for (season in seasons) {
            for (episode in 1..season.episodeCount) {
                if (episodeKey(season.seasonNumber, episode) !in watchedEpisodes) {
                    return DesktopPlaybackEpisodeContext(
                        seasonNumber = season.seasonNumber,
                        episodeNumber = episode,
                        episodeTitle = null,
                        mode = DesktopEpisodeResolutionMode.PLAY_FIRST_UNWATCHED,
                    )
                }
            }
        }

        val firstSeason = seasons.firstOrNull() ?: return null
        return DesktopPlaybackEpisodeContext(
            seasonNumber = firstSeason.seasonNumber,
            episodeNumber = 1,
            episodeTitle = null,
            mode = DesktopEpisodeResolutionMode.PLAY_FROM_START,
        )
    }

    private suspend fun lookupEpisodeTitle(
        tmdbId: Int?,
        seasonNumber: Int,
        episodeNumber: Int,
    ): String? {
        if (tmdbId == null) return null
        return runCatching {
            metadataRepository.getSeasonDetail(tmdbId, seasonNumber)
                .episodes
                .firstOrNull { it.episodeNumber == episodeNumber }
                ?.name
        }.getOrNull()
    }

    private fun fallbackMediaItem(
        request: DesktopPlaybackRequest,
    ): MediaItem {
        return MediaItem(
            id = request.mediaId,
            tmdbId = request.tmdbId,
            imdbId = request.imdbId,
            title = request.title,
            type = request.mediaType,
            posterUrl = request.artworkUrl,
            backdropUrl = request.artworkUrl,
        )
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun safePlaybackMessage(code: String, message: String): String {
        val redacted = DiagnosticsRedactor.redact(message).ifBlank { "Playback failed." }
        val lower = "$code $redacted".lowercase()
        return when {
            "stream_expired" in lower || "invalid_handoff" in lower ->
                desktopExpiredHandoffFailureMessage()
            "stream_reference_required" in lower || "stream_reference_not_found" in lower ->
                "This source is no longer available. Refresh sources or try another one."
            "stream_handoff_unavailable" in lower ->
                "This stream is temporarily unavailable. Try again or pick another source."
            else -> redacted
        }
    }

    private fun failPreparation(
        session: DesktopPlaybackSession?,
        code: String,
        message: String,
    ) {
        val safeMessage = safePlaybackMessage(code, message)
        activeStartupTrace = null
        _state.update {
            it.copy(
                phase = DesktopPlayerPhase.RESOLUTION_FAILED,
                preparedSession = session,
                engineMessage = "Desktop playback session could not be prepared.",
                error = DesktopPlayerError(
                    code = code,
                    message = safeMessage,
                ),
            )
        }
    }

    private fun failRuntime(
        code: String,
        message: String,
        markSelectedCandidateFailed: Boolean = false,
    ) {
        val safeMessage = safePlaybackMessage(code, message)
        desktopVerboseLog { "TORVE_CONTROLLER failRuntime code=$code message=$safeMessage" }
        activeStartupTrace = null
        _state.update {
            val selectedCandidateId = it.preparedSession?.selectedCandidate?.candidateId
            it.copy(
                phase = DesktopPlayerPhase.RUNTIME_ERROR,
                preparedSession = it.preparedSession?.withoutTemporaryResolvedUrl(),
                lastCommand = it.lastCommand ?: DesktopPlayerCommand.PLAY,
                failedCandidateIds = if (markSelectedCandidateFailed && selectedCandidateId != null) {
                    it.failedCandidateIds + selectedCandidateId
                } else {
                    it.failedCandidateIds
                },
                engineMessage = safeMessage,
                error = DesktopPlayerError(
                    code = code,
                    message = safeMessage,
                ),
            )
        }
    }

    private fun applyEngineEvent(
        event: DesktopPlaybackEngineEvent,
    ) {
        if (event is DesktopPlaybackEngineEvent.Error && tryStartExpiredHandoffRefresh(event)) {
            return
        }
        _state.update { current ->
            when (event) {
                is DesktopPlaybackEngineEvent.RuntimeProbe -> current.copy(
                    runtimeInfo = current.runtimeInfo.copy(
                        backendLabel = playbackEngine.backendLabel,
                        runtimeFound = event.found,
                        executablePath = event.executablePath,
                        discoverySource = event.discoverySource,
                        attemptedPaths = event.attemptedPaths,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                    engineMessage = event.message,
                    error = if (event.found) {
                        null
                    } else {
                        DesktopPlayerError(
                            code = "VLC_NOT_FOUND",
                            message = event.message,
                        )
                    },
                    phase = if (event.found) current.phase else DesktopPlayerPhase.RUNTIME_ERROR,
                )

                is DesktopPlaybackEngineEvent.RuntimeReady -> current.copy(
                    runtimeInfo = current.runtimeInfo.copy(
                        backendLabel = playbackEngine.backendLabel,
                        runtimeFound = true,
                        executablePath = event.executablePath,
                        discoverySource = event.discoverySource,
                        processId = event.processId,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                    engineMessage = "Windows playback engine is ready.",
                    error = null,
                )

                is DesktopPlaybackEngineEvent.Opening -> current.copy(
                    phase = DesktopPlayerPhase.OPENING,
                    runtimeInfo = current.runtimeInfo.copy(
                        backendLabel = playbackEngine.backendLabel,
                        activeMediaPath = event.mediaPath,
                        bufferingPercent = 0f,
                        bufferingModeLabel = event.bufferingModeLabel,
                        appliedHeaderCount = event.appliedHeaderCount,
                        selectedSubtitleLabel = event.selectedSubtitleLabel,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                    engineMessage = if (event.autoPlay) {
                        "Windows playback engine is opening the resolved stream."
                    } else {
                        "Windows playback engine opened the resolved stream in paused mode."
                    },
                    error = null,
                )

                is DesktopPlaybackEngineEvent.Buffering -> current.copy(
                    phase = if (current.phase == DesktopPlayerPhase.PAUSED) {
                        DesktopPlayerPhase.PAUSED
                    } else {
                        DesktopPlayerPhase.BUFFERING
                    },
                    runtimeInfo = current.runtimeInfo.copy(
                        backendLabel = playbackEngine.backendLabel,
                        activeMediaPath = event.mediaPath ?: current.runtimeInfo.activeMediaPath,
                        bufferingPercent = event.cachePercent,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                    engineMessage = "Buffering stream...",
                    error = null,
                )

                is DesktopPlaybackEngineEvent.Playing -> current.copy(
                    phase = DesktopPlayerPhase.PLAYING,
                    runtimeInfo = current.runtimeInfo.copy(
                        backendLabel = playbackEngine.backendLabel,
                        activeMediaPath = event.mediaPath ?: current.runtimeInfo.activeMediaPath,
                        bufferingPercent = null,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                    engineMessage = "Playback is running through the Windows engine.",
                    error = null,
                )

                is DesktopPlaybackEngineEvent.FirstFrame -> current.copy(
                    phase = DesktopPlayerPhase.PLAYING,
                    runtimeInfo = current.runtimeInfo.copy(
                        backendLabel = playbackEngine.backendLabel,
                        activeMediaPath = event.mediaPath ?: current.runtimeInfo.activeMediaPath,
                        bufferingPercent = null,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                    engineMessage = "Playback frame is on screen.",
                    error = null,
                )

                is DesktopPlaybackEngineEvent.Paused -> current.copy(
                    phase = DesktopPlayerPhase.PAUSED,
                    runtimeInfo = current.runtimeInfo.copy(
                        backendLabel = playbackEngine.backendLabel,
                        activeMediaPath = event.mediaPath ?: current.runtimeInfo.activeMediaPath,
                        bufferingPercent = null,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                    engineMessage = "Playback is paused.",
                    error = null,
                )

                is DesktopPlaybackEngineEvent.Stopped -> current.copy(
                    phase = DesktopPlayerPhase.STOPPED,
                    runtimeInfo = current.runtimeInfo.copy(
                        bufferingPercent = null,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                    engineMessage = event.reason,
                    error = null,
                )

                is DesktopPlaybackEngineEvent.Position -> current.copy(
                    runtimeInfo = current.runtimeInfo.copy(
                        playbackPositionSeconds = event.positionSeconds ?: current.runtimeInfo.playbackPositionSeconds,
                        durationSeconds = event.durationSeconds ?: current.runtimeInfo.durationSeconds,
                        requirementText = playbackEngine.runtimeRequirement,
                    ),
                )

                is DesktopPlaybackEngineEvent.Closed -> current.copy(
                    phase = if (current.phase == DesktopPlayerPhase.CLOSED) {
                        DesktopPlayerPhase.CLOSED
                    } else {
                        DesktopPlayerPhase.STOPPED
                    },
                    engineMessage = event.reason,
                    runtimeInfo = current.runtimeInfo.copy(
                        activeMediaPath = null,
                        bufferingPercent = null,
                        selectedSubtitleLabel = current.runtimeInfo.selectedSubtitleLabel,
                    ),
                    error = null,
                )

                is DesktopPlaybackEngineEvent.Error -> {
                    val safeMessage = safePlaybackMessage(event.code, event.message)
                    desktopVerboseLog { "TORVE_CONTROLLER engine error code=${event.code} message=$safeMessage" }
                    current.copy(
                        phase = DesktopPlayerPhase.RUNTIME_ERROR,
                        preparedSession = current.preparedSession?.withoutTemporaryResolvedUrl(),
                        runtimeInfo = current.runtimeInfo.copy(
                            bufferingPercent = null,
                            requirementText = playbackEngine.runtimeRequirement,
                        ),
                        engineMessage = safeMessage,
                        error = DesktopPlayerError(
                            code = event.code,
                            message = safeMessage,
                            recoverable = event.recoverable,
                        ),
                    )
                }
            }
        }
        when (event) {
            is DesktopPlaybackEngineEvent.Playing,
            is DesktopPlaybackEngineEvent.FirstFrame -> onPlaybackActivated()
            is DesktopPlaybackEngineEvent.Paused -> onPlaybackPaused()
            is DesktopPlaybackEngineEvent.Stopped,
            is DesktopPlaybackEngineEvent.Closed,
            is DesktopPlaybackEngineEvent.Error -> onPlaybackEnded()
            else -> Unit
        }
    }

    private fun onPlaybackActivated() {
        progressSyncJob?.cancel()
        progressSyncJob = scope.launch {
            recordPlaybackLaunch()
            startTraktScrobble()
            while (true) {
                kotlinx.coroutines.delay(15_000L)
                savePlaybackProgress()
            }
        }
    }

    private fun onPlaybackPaused() {
        progressSyncJob?.cancel()
        scope.launch {
            savePlaybackProgress()
            pauseTraktScrobble()
        }
    }

    private fun onPlaybackEnded() {
        progressSyncJob?.cancel()
        scope.launch {
            savePlaybackProgress()
            stopTraktScrobble()
        }
    }

    private suspend fun recordPlaybackLaunch() {
        val request = _state.value.currentRequest ?: return
        // Mirror the saveProgress filter: adult / sports never enter the
        // Library history rail. Without this guard the per-surface
        // origin-tag set on openPreResolvedStream would still leak into
        // recentlyWatched on the Library page.
        if (request.sourceSurface in PROGRESS_EXCLUDED_SURFACES ||
            request.mediaId.startsWith("live:")
        ) return
        val session = _state.value.preparedSession ?: return
        val syncIds = buildPlaybackSyncIds(request, session)
        val episodeContext = session.episodeContext
        val sessionKey = buildPlaybackSessionKey(syncIds.mediaId, episodeContext)
        if (recordedHistorySessionKey == sessionKey) return
        recordedHistorySessionKey = sessionKey

        val nowMs = System.currentTimeMillis()
        val episodeSuffix = if (request.mediaType == MediaType.SERIES && episodeContext != null) {
            "_s${episodeContext.seasonNumber}e${episodeContext.episodeNumber}"
        } else {
            ""
        }
        runCatching {
            watchHistoryRepository.record(
                WatchHistoryEntry(
                    id = "${syncIds.mediaId}${episodeSuffix}_$nowMs",
                    mediaId = syncIds.mediaId,
                    mediaType = if (request.mediaType == MediaType.SERIES) MediaType.SERIES.name else "movie",
                    title = episodeContext?.label ?: request.title,
                    posterUrl = session.mediaItem.posterUrl ?: request.artworkUrl,
                    backdropUrl = session.mediaItem.backdropUrl ?: request.artworkUrl,
                    watchedAt = nowMs,
                    durationWatchedMs = 0L,
                    seasonNumber = episodeContext?.seasonNumber ?: request.seasonNumber,
                    episodeNumber = episodeContext?.episodeNumber ?: request.episodeNumber,
                    showTitle = session.mediaItem.title.takeIf { request.mediaType == MediaType.SERIES },
                ),
            )
        }
    }

    private suspend fun startTraktScrobble() {
        val request = _state.value.currentRequest ?: return
        val session = _state.value.preparedSession ?: return
        val settings = settingsViewModel.state.value
        if (!settings.traktScrobbleEnabled || settings.traktAccessToken.isBlank()) return
        val syncIds = buildPlaybackSyncIds(request, session)
        val tmdbId = syncIds.tmdbId ?: return
        val sessionKey = buildPlaybackSessionKey(syncIds.mediaId, session.episodeContext)
        if (activeScrobbleSessionKey == sessionKey) return
        traktScrobbler.start(
            accessToken = settings.traktAccessToken,
            tmdbId = tmdbId,
            type = request.mediaType,
            progress = currentProgressPercent(),
            imdbId = syncIds.imdbId,
            season = session.episodeContext?.seasonNumber ?: request.seasonNumber,
            episode = session.episodeContext?.episodeNumber ?: request.episodeNumber,
        )
        activeScrobbleSessionKey = sessionKey
    }

    private suspend fun pauseTraktScrobble() {
        val request = _state.value.currentRequest ?: return
        val session = _state.value.preparedSession ?: return
        val settings = settingsViewModel.state.value
        if (settings.traktAccessToken.isBlank()) return
        val syncIds = buildPlaybackSyncIds(request, session)
        val tmdbId = syncIds.tmdbId ?: return
        traktScrobbler.pause(
            accessToken = settings.traktAccessToken,
            tmdbId = tmdbId,
            type = request.mediaType,
            progress = currentProgressPercent(),
            imdbId = syncIds.imdbId,
            season = session.episodeContext?.seasonNumber ?: request.seasonNumber,
            episode = session.episodeContext?.episodeNumber ?: request.episodeNumber,
        )
    }

    private suspend fun stopTraktScrobble() {
        val request = _state.value.currentRequest ?: return
        val session = _state.value.preparedSession ?: return
        val settings = settingsViewModel.state.value
        if (settings.traktAccessToken.isBlank()) {
            activeScrobbleSessionKey = null
            return
        }
        val syncIds = buildPlaybackSyncIds(request, session)
        val tmdbId = syncIds.tmdbId ?: run {
            activeScrobbleSessionKey = null
            return
        }
        traktScrobbler.stop(
            accessToken = settings.traktAccessToken,
            tmdbId = tmdbId,
            type = request.mediaType,
            progress = currentProgressPercent(),
            imdbId = syncIds.imdbId,
            season = session.episodeContext?.seasonNumber ?: request.seasonNumber,
            episode = session.episodeContext?.episodeNumber ?: request.episodeNumber,
        )
        activeScrobbleSessionKey = null
    }

    private fun currentProgressPercent(): Double {
        val runtimeInfo = _state.value.runtimeInfo
        val durationSeconds = runtimeInfo.durationSeconds ?: return 0.0
        val positionSeconds = runtimeInfo.playbackPositionSeconds ?: return 0.0
        if (durationSeconds <= 0.0) return 0.0
        return ((positionSeconds / durationSeconds) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun buildPlaybackSyncIds(
        request: DesktopPlaybackRequest,
        session: DesktopPlaybackSession,
    ): PlaybackSyncIds {
        val tmdbId = session.mediaItem.tmdbId
            ?: request.tmdbId
            ?: request.mediaId.extractTmdbIdOrNull()
        val imdbId = session.mediaItem.imdbId
            ?: request.imdbId
            ?: request.mediaId.extractImdbIdOrNull()
        return PlaybackSyncIds(
            mediaId = tmdbId?.toString() ?: imdbId ?: request.mediaId,
            tmdbId = tmdbId,
            imdbId = imdbId,
        )
    }

    private fun buildPlaybackSessionKey(
        mediaId: String,
        episodeContext: DesktopPlaybackEpisodeContext?,
    ): String {
        return buildString {
            append(mediaId)
            append(':')
            append(episodeContext?.seasonNumber ?: 0)
            append(':')
            append(episodeContext?.episodeNumber ?: 0)
        }
    }
}

private data class PlaybackSyncIds(
    val mediaId: String,
    val tmdbId: Int?,
    val imdbId: String?,
)

private class SessionPreparationException(
    val session: DesktopPlaybackSession?,
    val code: String,
    override val message: String,
) : IllegalStateException(message)

private fun MediaType.toMetadataType(): String = when (this) {
    MediaType.MOVIE -> "movie"
    MediaType.SERIES -> "tv"
}

private fun DesktopPlaybackRequest.cacheKey(): String = buildString {
    append(mediaType.name)
    append(':')
    append(imdbId ?: mediaId)
    append(':')
    append(seasonNumber ?: 0)
    append(':')
    append(episodeNumber ?: 0)
}

private fun ParsedStream.toDesktopSourceCandidate(
    index: Int,
): DesktopPlaybackSourceCandidate {
    return DesktopPlaybackSourceCandidate(
        candidateId = buildString {
            append(addonName)
            append('|')
            append(accelerationMemoryId ?: infoHash ?: directUrl ?: title)
            append('|')
            append(fileIdx ?: index)
            append('|')
            append(index)
        },
        addonName = addonName,
        title = title,
        quality = quality,
        score = score,
        fileIdx = fileIdx,
        source = source,
        isCached = isCached,
        codec = codec,
        audioCodec = audioCodec,
        size = size,
        infoHash = infoHash,
        directUrl = directUrl,
        accelerationMemoryId = accelerationMemoryId,
        addonBaseUrl = addonBaseUrl,
        languages = languages,
    )
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
        addonBaseUrl = addonBaseUrl,
    )
}

private fun StremioSubtitle.toDesktopSubtitleCandidate(): DesktopPlaybackSubtitleCandidate {
    return DesktopPlaybackSubtitleCandidate(
        label = label?.takeIf { it.isNotBlank() } ?: lang.ifBlank { "Subtitle" },
        language = lang.ifBlank { "unknown" },
        url = url,
    )
}

private fun ResolvedStream.toDesktopTranscodeUrls(): Map<String, String> {
    return buildMap {
        transcodeUrls?.mp4?.takeIf { it.isNotBlank() }?.let { put("mp4", it) }
        transcodeUrls?.hls?.takeIf { it.isNotBlank() }?.let { put("hls", it) }
        transcodeUrls?.webm?.takeIf { it.isNotBlank() }?.let { put("webm", it) }
    }
}

fun MediaItem.toDesktopPlaybackRequest(
    sourceSurface: String,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): DesktopPlaybackRequest {
    return DesktopPlaybackRequest(
        mediaId = id,
        mediaType = type,
        title = title,
        tmdbId = tmdbId,
        imdbId = imdbId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        artworkUrl = posterUrl ?: backdropUrl,
        sourceSurface = sourceSurface,
    )
}
