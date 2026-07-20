package com.torve.android.ui.player

import android.app.Activity
import android.util.Log
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.torve.android.R
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import com.torve.android.BuildConfig
import com.torve.android.player.AudioEqualizer
import com.torve.android.device.DeviceFormFactor
import com.torve.android.cast.CastService
import com.torve.android.player.DeviceCodecProbe
import com.torve.android.player.ExoPlayerEngine
import com.torve.android.player.LiveAudioClientSurface
import com.torve.android.player.LiveAudioPathSnapshot
import com.torve.android.player.LivePlayerEngineId
import com.torve.android.player.MPVPlayerEngine
import com.torve.android.player.MPVView
import com.torve.android.player.TorvePlayerView
import com.torve.android.player.buildLiveAudioPathLog
import com.torve.android.player.clearPlayerSafely
import com.torve.android.player.setPlayerSafely
import com.torve.android.sync.SyncCoordinator
import com.torve.android.tv.settings.rememberTvReduceMotionPreference
import com.torve.android.voice.PlayerVoiceCommand
import com.torve.android.voice.PlayerVoiceCommandParser
import com.torve.android.voice.VoiceInputPhase
import com.torve.android.voice.rememberVoiceInputController
import com.torve.android.ui.sync.SyncDevicePickerDialog
import com.torve.android.ui.system.configureTorveEdgeToEdge
import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamRuntimeTelemetry
import com.torve.data.addon.StreamSelector
import com.torve.data.addon.isAddonHostedUrl
import com.torve.data.simkl.SimklClient
import com.torve.data.simkl.SimklIds
import com.torve.data.simkl.SimklSyncBody
import com.torve.data.simkl.SimklSyncItem
import com.torve.data.stats.WatchSessionMediaIdentity
import com.torve.data.stats.WatchSessionRecorder
import com.torve.data.trakt.TraktClient
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.data.trakt.TraktHistoryBody
import com.torve.data.trakt.TraktHistoryEpisodeEntry
import com.torve.data.trakt.TraktHistoryMovie
import com.torve.data.trakt.TraktHistorySeasonEntry
import com.torve.data.trakt.TraktHistoryShow
import com.torve.data.trakt.TraktIds
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import com.torve.domain.model.SourceAccelerationContext
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StartupCandidatesSnapshot
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.WatchProgress
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.data.addon.SubtitleAggregator
import com.torve.data.subtitles.OpenSubtitlesClient
import com.torve.data.subtitles.languageInfo
import com.torve.domain.player.ExternalSubtitle
import com.torve.domain.player.NextEpisodeHelper
import com.torve.domain.player.NextEpisodeInfo
import com.torve.domain.player.PlayerEngine
import com.torve.domain.player.StartupPlaybackPolicy
import com.torve.domain.player.SkipSegment
import com.torve.domain.player.SkipSegmentDetector
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.domain.player.TrackDescription
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.telemetry.StreamPathDiagnostics
import com.torve.domain.telemetry.StreamPathTelemetryContext
import com.torve.domain.telemetry.StreamPlaybackPath
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.player.TraktScrobbler
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.streampicker.StreamFallbackOrdering
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import kotlin.math.absoluteValue

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    fallbackUrl: String = "",
    autoSourceSelection: Boolean = false,
    title: String = "",
    mediaId: String = "",
    mediaType: String = "movie",
    posterUrl: String = "",
    backdropUrl: String = "",
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeName: String = "",
    showTmdbId: Int? = null,
    showImdbId: String? = null,
    startPositionMs: Long = 0L,
    onVoiceSearchCommand: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    watchProgressRepo: WatchProgressRepository = koinInject(),
    watchHistoryRepo: WatchHistoryRepository = koinInject(),
    metadataRepo: MetadataRepository = koinInject(),
    streamRepo: StreamRepository = koinInject(),
    streamSelector: StreamSelector = koinInject(),
    addonRepo: AddonRepository = koinInject(),
    channelsViewModel: ChannelsViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    syncCoordinator: SyncCoordinator = koinInject(),
    traktScrobbler: TraktScrobbler = koinInject(),
    traktClient: TraktClient = koinInject(),
    simklClient: SimklClient = koinInject(),
    integrationSecretStore: IntegrationSecretStore = koinInject(),
    prefsRepo: PreferencesRepository = koinInject(),
    subtitleAggregator: SubtitleAggregator = koinInject(),
    openSubtitlesClient: OpenSubtitlesClient = koinInject(),
    telemetry: TelemetryEmitter = koinInject(),
    watchSessionRecorder: WatchSessionRecorder = koinInject(),
) {
    val context = LocalContext.current
    val voiceInputUnavailableFallback = context.getString(R.string.voice_input_unavailable)
    val configuration = LocalConfiguration.current
    val isTv = remember(context) { DeviceFormFactor.isTv(context) }
    val enablePhoneAutoRotation = !isTv && configuration.smallestScreenWidthDp < 600
    // MPV remains unstable on Samsung/Android mobile teardown paths and can still
    // SIGABRT inside vo_mediacodec_embed when the window surface disappears.
    // Prefer ExoPlayer on phones/tablets to avoid the native WinID crash class.
    val forceExoPlayerOnMobile = !isTv
    val isLiveChannelPlayback = mediaType.equals("live", ignoreCase = true)

    // Google Cast (injected; no-op on Amazon builds)
    val castService: CastService = koinInject()
    val castAvailable = castService.isAvailable

    // Keep playback immersive, but let phones start in portrait and follow user rotation.
    DisposableEffect(enablePhoneAutoRotation) {
        val activity = context as? Activity ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val originalOrientation = activity.requestedOrientation
        val controller = configureTorveEdgeToEdge(window, window.decorView)

        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enablePhoneAutoRotation) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
            if (enablePhoneAutoRotation) {
                activity.requestedOrientation = originalOrientation
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val scope = rememberCoroutineScope()
    val syncState by syncCoordinator.state.collectAsState()
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    // isInPipMode is a Compose mutableStateOf — reads trigger recomposition automatically.
    val isInPip = ActivePlaybackState.isInPipMode
    var showControls by remember { mutableStateOf(true) }
    var orientationRotateFrom by remember { mutableStateOf<Int?>(null) }
    // Hide controls in PiP; restore when leaving PiP.
    LaunchedEffect(isInPip) {
        if (isInPip) {
            showControls = false
        } else {
            // Re-apply immersive mode when exiting PiP back to fullscreen.
            val activity = context as? Activity
            if (activity != null) {
                val window = activity.window
                val controller = configureTorveEdgeToEdge(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var trackDialogSubtitlesOnly by remember { mutableStateOf(true) }
    var subtitleFetchState by remember { mutableStateOf<SubtitleFetchState>(SubtitleFetchState.Idle) }
    var showSubtitleSearch by remember { mutableStateOf(false) }
    var pendingSubtitleAutoSelect by remember { mutableStateOf(false) }
    var backKeyDownAtMs by remember { mutableLongStateOf(0L) }
    val backLongPressJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showAudioDelayDialog by remember { mutableStateOf(false) }
    var showSubtitleDelayDialog by remember { mutableStateOf(false) }
    var showPictureFormatPicker by remember { mutableStateOf(false) }
    var subtitleTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var audioTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var useMpv by remember { mutableStateOf(false) }
    var mpvSurfaceReady by remember { mutableStateOf(false) }
    var mpvView by remember { mutableStateOf<MPVView?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var codecFallbackUsed by remember { mutableStateOf(false) }
    var codecFallbackInProgress by remember { mutableStateOf(false) }
    var playerExitInFlight by remember { mutableStateOf(false) }
    var exitSnapshotPositionMs by remember { mutableLongStateOf(-1L) }
    var exitSnapshotDurationMs by remember { mutableLongStateOf(-1L) }
    var audioDelayMs by remember { mutableIntStateOf(0) }
    var subtitleDelayMs by remember { mutableIntStateOf(0) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var mobileSheetStack by remember { mutableStateOf<List<MobilePlaybackSheet>>(emptyList()) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var pictureFormat by remember { mutableStateOf(PlayerPictureFormat.SOURCE) }
    var activeReplayProgramme by remember { mutableStateOf<com.torve.domain.model.EpgProgramme?>(null) }
    var liveBufferDurationMs by remember { mutableIntStateOf(ExoPlayerEngine.DEFAULT_LIVE_BUFFER_MS) }
    var audioEqualizer by remember { mutableStateOf<AudioEqualizer?>(null) }
    var exoPlayerView by remember { mutableStateOf<TorvePlayerView?>(null) }
    fun detachExoPlayerView() {
        exoPlayerView?.clearPlayerSafely("mobile_player_detach")
        exoPlayerView = null
    }
    var topMenuFocusTick by remember { mutableIntStateOf(0) }
    var lastTopMenuFocusTarget by remember { mutableStateOf(TopMenuFocusTarget.BACK) }
    var seekRepeatDirection by remember { mutableIntStateOf(0) }
    var seekRepeatCount by remember { mutableIntStateOf(0) }
    var seekRepeatLastAtMs by remember { mutableLongStateOf(0L) }
    var seekRepeatTargetMs by remember { mutableLongStateOf(-1L) }
    var tvSeekFeedbackVisible by remember { mutableStateOf(false) }
    var tvSeekFeedbackDeltaMs by remember { mutableLongStateOf(0L) }
    var tvSeekFeedbackTargetMs by remember { mutableLongStateOf(0L) }
    var tvSeekFeedbackCurrentMs by remember { mutableLongStateOf(0L) }
    var tvSeekFeedbackInteractionAtMs by remember { mutableLongStateOf(0L) }
    val resumePromptInitialPositionMs = remember(url, mediaId, startPositionMs) {
        startPositionMs.coerceAtLeast(0L)
    }
    var pendingStartPositionMs by remember(url, mediaId, startPositionMs) { mutableLongStateOf(0L) }
    var showResumePrompt by remember(url, mediaId, startPositionMs) {
        mutableStateOf(resumePromptInitialPositionMs >= 20_000L)
    }
    var initialStartPositionConsumed by remember(url, mediaId, startPositionMs) { mutableStateOf(false) }
    // Focus coordinator: state-driven focus management for TV playback.
    // Each region owns its own FocusRequesters; the coordinator mediates
    // inter-region navigation so no cross-tree requester references exist.
    val focusCoordinator = rememberPlaybackFocusCoordinator()
    val playerRootFocusRequester = remember { FocusRequester() }

    // Mutable episode state — updated when swapping to next episode
    var currentSeasonNumber by remember { mutableStateOf(seasonNumber) }
    var currentEpisodeNumber by remember { mutableStateOf(episodeNumber) }
    var currentUrl by remember { mutableStateOf(url) }
    var currentTitle by remember { mutableStateOf(title) }
    var currentPosterUrl by remember { mutableStateOf(posterUrl) }
    var currentWatchSessionId by remember { mutableStateOf<String?>(null) }
    val mobileActiveSheet = mobileSheetStack.lastOrNull()
    var autoFallbackInProgress by remember { mutableStateOf(false) }
    var currentStreamHostKey by remember { mutableStateOf(StreamRuntimeTelemetry.keyForUrl(url)) }
    var healthWindowStartedAtMs by remember(currentUrl) { mutableLongStateOf(0L) }
    var firstFrameAtMs by remember(currentUrl) { mutableLongStateOf(0L) }
    var earlyRebufferCount by remember(currentUrl) { mutableIntStateOf(0) }
    var earlyRebufferDurationMs by remember(currentUrl) { mutableLongStateOf(0L) }
    var inBufferingWindow by remember(currentUrl) { mutableStateOf(false) }
    var bufferStartedAtMs by remember(currentUrl) { mutableLongStateOf(0L) }
    var bufferingAttributedToUserSeek by remember(currentUrl) { mutableStateOf(false) }
    var earlyFallbackTriggered by remember(currentUrl) { mutableStateOf(false) }
    var seekSuppressionUntilMs by remember { mutableLongStateOf(0L) }
    var pendingAutoFallbackResumePositionMs by remember { mutableLongStateOf(-1L) }
    var pendingAutoFallbackResumeDeadlineMs by remember { mutableLongStateOf(0L) }
    var attemptedAutoStreamKeys by remember(mediaId, seasonNumber, episodeNumber, url) {
        mutableStateOf<Set<String>>(emptySet())
    }

    val earlyHealthWindowMs = 35_000L
    val earlyStartupTimeoutMs = 9_000L
    val earlyRebufferCountThreshold = 2
    val earlyRebufferDurationThresholdMs = 6_000L

    fun streamKey(stream: ParsedStream): String {
        return playerStreamKey(stream)
    }

    fun resetPlaybackHealthWindow() {
        healthWindowStartedAtMs = SystemClock.elapsedRealtime()
        firstFrameAtMs = 0L
        earlyRebufferCount = 0
        earlyRebufferDurationMs = 0L
        inBufferingWindow = false
        bufferStartedAtMs = 0L
        bufferingAttributedToUserSeek = false
        earlyFallbackTriggered = false
        seekSuppressionUntilMs = 0L
    }

    fun seekSuppressionWindowFor(deltaMs: Long? = null): Long {
        val absoluteDelta = deltaMs?.absoluteValue ?: 0L
        return when {
            absoluteDelta >= 10 * 60_000L -> 13_000L
            absoluteDelta >= 5 * 60_000L -> 11_000L
            absoluteDelta >= 60_000L -> 9_000L
            else -> 7_000L
        }
    }

    fun markUserSeekActivity(deltaMs: Long? = null) {
        val nowMs = SystemClock.elapsedRealtime()
        val extensionMs = seekSuppressionWindowFor(deltaMs)
        seekSuppressionUntilMs = maxOf(seekSuppressionUntilMs, nowMs + extensionMs)
    }

    fun isSeekSuppressionActive(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        return nowMs <= seekSuppressionUntilMs
    }

    // Season data for next-episode calculation
    var loadedSeasons by remember { mutableStateOf<List<Season>>(emptyList()) }

    // Next episode overlay state
    var nextEpisodeInfo by remember { mutableStateOf<NextEpisodeInfo?>(null) }
    var showNextEpisodeOverlay by remember { mutableStateOf(false) }
    var nextEpisodeCountdown by remember { mutableIntStateOf(15) }
    var nextEpisodeCancelled by remember { mutableStateOf(false) }
    var isResolvingNextEpisode by remember { mutableStateOf(false) }
    var completionDetected by remember { mutableStateOf(false) }

    // Skip intro/credits segments
    var skipSegments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var activeSkipSegment by remember { mutableStateOf<SkipSegment?>(null) }
    var dismissedSkipSegments by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Trakt scrobble state
    val channelsState by channelsViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val traktAccessToken = settingsState.traktAccessToken
    val traktScrobbleEnabled = settingsState.traktScrobbleEnabled
    // Resolve the TMDB id for this play session. For IMDB-only addons the
    // mediaId is e.g. "tt31810018" which has no embedded TMDB id — without
    // resolution, Trakt scrobble would silently no-op (canScrobble stays false)
    // and the title never appears in Trakt history. We call TMDB /find and
    // patch the tmdb id as soon as it lands.
    val rawTmdbFromMediaId = mediaId.extractTmdbIdOrNull() ?: 0
    var resolvedTmdbId by remember(mediaId, showTmdbId, showImdbId) {
        mutableStateOf(showTmdbId?.takeIf { it > 0 } ?: rawTmdbFromMediaId)
    }
    LaunchedEffect(mediaId, showTmdbId, showImdbId, mediaType) {
        if (resolvedTmdbId > 0) return@LaunchedEffect
        val imdb = showImdbId?.takeIf { it.startsWith("tt") }
            ?: mediaId.extractImdbIdOrNull()
            ?: return@LaunchedEffect
        runCatching {
            val preferredType = if (mediaType.equals("series", true) || mediaType.equals("tv", true)) "tv" else "movie"
            metadataRepo.findByImdbId(imdb, preferredType)
        }.getOrNull()?.tmdbId?.takeIf { it > 0 }?.let { resolvedTmdbId = it }
    }
    val tmdbId = resolvedTmdbId
    val parsedMediaType = MediaType.fromString(mediaType)
    fun currentWatchSessionIdentity(): WatchSessionMediaIdentity? {
        val isSeries = parsedMediaType == MediaType.SERIES
        val resolvedMediaId = if (isSeries) {
            showTmdbId?.takeIf { it > 0 }?.toString()
                ?: showImdbId?.takeIf { it.isNotBlank() }
                ?: mediaId.takeIf { it.isNotBlank() }
                ?: tmdbId.takeIf { it > 0 }?.toString()
        } else {
            mediaId.takeIf { it.isNotBlank() }
                ?: tmdbId.takeIf { it > 0 }?.toString()
                ?: showImdbId?.takeIf { it.isNotBlank() }
        } ?: return null
        return WatchSessionMediaIdentity(
            mediaId = resolvedMediaId,
            mediaType = parsedMediaType,
            title = currentTitle.ifBlank { title },
            showId = if (isSeries) resolvedMediaId else null,
            showTitle = if (isSeries) title.ifBlank { currentTitle } else null,
            seasonNumber = currentSeasonNumber,
            episodeNumber = currentEpisodeNumber,
            posterUrl = currentPosterUrl.takeIf { it.isNotBlank() } ?: posterUrl.takeIf { it.isNotBlank() },
            backdropUrl = backdropUrl.takeIf { it.isNotBlank() },
            tmdbId = tmdbId.takeIf { it > 0 },
            imdbId = showImdbId?.takeIf { it.isNotBlank() } ?: mediaId.extractImdbIdOrNull(),
        )
    }
    var hasMarkedWatched by remember { mutableStateOf(false) }
    val voiceCommandNotRecognizedLabel = "Voice command not recognized"
    val voiceCommandPlayLabel = "Play"
    val voiceCommandPauseLabel = "Pause"
    val voiceCommandForwardLabel = "Forward 10 seconds"
    val voiceCommandRewindLabel = "Rewind 10 seconds"
    val voiceCommandSearchLabel: (String) -> String = { query ->
        "Search: $query"
    }
    val playbackPrefsKey = remember(mediaType, mediaId, showTmdbId, showImdbId, title, url) {
        buildPlayerPlaybackPrefsKey(
            mediaType = mediaType,
            mediaId = mediaId,
            showTmdbId = showTmdbId,
            showImdbId = showImdbId,
            title = title,
            url = url,
        )
    }
    var playbackPrefsLoaded by remember(playbackPrefsKey) { mutableStateOf(false) }
    val trackPrefsKey = remember(playbackPrefsKey) { "${playbackPrefsKey}_tracks" }
    var trackPrefsLoaded by remember(trackPrefsKey) { mutableStateOf(false) }
    var preferredAudioTrackTag by remember(trackPrefsKey) { mutableStateOf<String?>(null) }
    var preferredSubtitleTrackTag by remember(trackPrefsKey) { mutableStateOf<String?>(null) }
    var subtitlesPreferredEnabled by remember(trackPrefsKey) { mutableStateOf(true) }
    var trackPrefsAppliedForUrl by remember { mutableStateOf(false) }

    // Helper to check if scrobbling should fire
    val canScrobble = traktScrobbleEnabled && traktAccessToken.isNotBlank() && tmdbId > 0

    val visibleTopMenuTargets = buildList {
        add(TopMenuFocusTarget.BACK)
        if (castAvailable) add(TopMenuFocusTarget.CAST)
        if (!isTv) add(TopMenuFocusTarget.HANDOFF)
        if (!isTv) add(TopMenuFocusTarget.VOICE)
        add(TopMenuFocusTarget.SUBTITLE_TRACKS)
        add(TopMenuFocusTarget.AUDIO_TRACKS)
        add(TopMenuFocusTarget.AUDIO_DELAY)
        if (audioEqualizer != null) add(TopMenuFocusTarget.EQUALIZER)
        if (isTv) add(TopMenuFocusTarget.PICTURE_FORMAT)
        add(TopMenuFocusTarget.SPEED)
    }

    fun topMenuNeighbor(target: TopMenuFocusTarget, delta: Int): TopMenuFocusTarget {
        val size = visibleTopMenuTargets.size
        if (size <= 0) return TopMenuFocusTarget.BACK
        val index = visibleTopMenuTargets.indexOf(target).takeIf { it >= 0 } ?: 0
        val neighborIndex = PlayerNavigationMath.cyclicIndex(index, size, delta)
        return visibleTopMenuTargets[neighborIndex]
    }

    // Create the player engine once (not keyed on URL for in-place swaps).
    // On TV: always use ExoPlayer — MPV's vo_mediacodec_embed SIGABRTs when
    // the Compose AndroidView hasn't attached a surface yet (WinID == 0).
    val engine = remember(forceExoPlayerOnMobile) {
        if (isTv || forceExoPlayerOnMobile) {
            val exoEngine = ExoPlayerEngine(context)
            exoEngine.initialize()
            exoEngine as PlayerEngine
        } else {
            try {
                val mpvEngine = MPVPlayerEngine(context)
                if (mpvEngine.initialize()) {
                    useMpv = true
                    mpvEngine as PlayerEngine
                } else {
                    val exoEngine = ExoPlayerEngine(context)
                    exoEngine.initialize()
                    exoEngine as PlayerEngine
                }
            } catch (e: Exception) {
                android.util.Log.e("Player", "MPV init failed, falling back to ExoPlayer", e)
                val exoEngine = ExoPlayerEngine(context)
                exoEngine.initialize()
                exoEngine as PlayerEngine
            }
        }
    }

    // Side-loaded subtitles fetched from all installed Stremio subtitle
    // addons (e.g. OpenSubtitles). Empty list is the common case; the engine
    // treats an empty list as a no-op. Re-fetched when the content key
    // changes (new title or new episode). MPV engine ignores side-loaded
    // subs in the current bindings — see PlayerEngine.play default.
    var externalSubtitles by remember { mutableStateOf<List<ExternalSubtitle>>(emptyList()) }

    LaunchedEffect(mediaId, mediaType, seasonNumber, episodeNumber, showImdbId) {
        // Prefer the series imdb id passed by nav; otherwise try to parse
        // mediaId (addon items sometimes carry "tt…" directly); skip if
        // neither is available. A skipped fetch is a graceful no-op —
        // playback still works, just without addon subs.
        val imdb = showImdbId?.trim()?.takeIf { it.isNotBlank() }
            ?: mediaId.extractImdbIdOrNull()
        if (imdb == null) {
            externalSubtitles = emptyList()
            return@LaunchedEffect
        }
        val addons = runCatching { addonRepo.getInstalledAddons() }.getOrNull().orEmpty()
        val typeEnum = if (mediaType.equals("tv", ignoreCase = true) ||
            mediaType.equals("series", ignoreCase = true)
        ) MediaType.SERIES else MediaType.MOVIE
        val fetched = runCatching {
            subtitleAggregator.fetchSubtitles(
                addons = addons,
                type = typeEnum,
                imdbId = imdb,
                season = seasonNumber,
                episode = episodeNumber,
            )
        }.getOrNull().orEmpty()
        externalSubtitles = fetched.map { stremio ->
            ExternalSubtitle(
                url = stremio.url,
                languageCode = stremio.lang.takeIf { it.isNotBlank() },
                label = stremio.label,
                mimeType = null, // engine infers from URL extension
            )
        }
    }

    fun requestPlayback(url: String) {
        if (url.isBlank()) return
        if (isLiveChannelPlayback) {
            StreamPathDiagnostics.record(
                path = StreamPlaybackPath.IPTV_DIRECT,
                telemetry = telemetry,
                context = StreamPathTelemetryContext(
                    contentType = "live_tv",
                    providerCategory = "iptv",
                ),
            )
        }
        currentUrl = url
        // Consume any LAN handoff staged before navigation. The route
        // schema can't carry HTTP headers, so the LAN-from-Home /
        // LAN-from-Details flows stage them in
        // PendingLanPlaybackHandoff. setNextRequestHeaders is
        // single-shot so it MUST be called before play() on the same
        // engine.
        com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff
            .consumeFor(url)
            ?.let { headers -> engine.setNextRequestHeaders(headers) }
        if (useMpv) {
            if (mpvSurfaceReady) {
                engine.play(url, externalSubtitles)
            }
        } else {
            engine.play(url, externalSubtitles)
        }
    }

    fun performSeekTo(
        targetMs: Long,
        userInitiated: Boolean,
        sourceDeltaMs: Long? = null,
        showTvFeedback: Boolean = false,
    ) {
        val maxPosition = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val clampedTarget = targetMs.coerceIn(0L, maxPosition)
        if (userInitiated) {
            markUserSeekActivity(sourceDeltaMs)
        }
        engine.seekTo(clampedTarget)
        if (showTvFeedback && isTv) {
            val currentSnapshot = currentPosition.coerceAtLeast(0L)
            tvSeekFeedbackCurrentMs = currentSnapshot
            tvSeekFeedbackTargetMs = clampedTarget
            tvSeekFeedbackDeltaMs = sourceDeltaMs ?: (clampedTarget - currentSnapshot)
            tvSeekFeedbackVisible = true
            tvSeekFeedbackInteractionAtMs = SystemClock.elapsedRealtime()
        }
    }

    suspend fun trySwitchToStableSource(reason: String): Boolean {
        if (!autoSourceSelection || autoFallbackInProgress) return false
        val imdbId = showImdbId?.trim().takeIf { !it.isNullOrBlank() } ?: return false
        android.util.Log.w("Player", "Auto stability fallback requested: $reason")

        val apiKey = settingsViewModel.getDebridApiKey()
        // Nullable provider: addon-hosted streams (Panda + cloud download
        // client) resolve without a local debrid key. Per-candidate
        // resolveStream will throw if a given stream actually needs one.
        val provider: DebridServiceType? =
            if (apiKey.isBlank()) null else settingsViewModel.getDebridProvider()

        autoFallbackInProgress = true
        try {
            val preferences = settingsViewModel.buildStreamPreferences()
            val addons = try { addonRepo.getInstalledAddons() } catch (_: Exception) { emptyList() }
            val debridAccounts = settingsViewModel.getDebridAccounts()
            val deviceCaps = DeviceCodecProbe.probe()

            val startupSelection = loadStartupPlaybackSelection(
                type = parsedMediaType,
                imdbId = imdbId,
                tmdbId = showTmdbId,
                contentTitle = title,
                season = currentSeasonNumber,
                episode = currentEpisodeNumber,
                streamRepo = streamRepo,
                streamSelector = streamSelector,
                addons = addons,
                debridAccounts = debridAccounts,
                preferences = preferences,
                deviceCaps = deviceCaps,
            )
            val rankedStartup = startupSelection.autoplayCandidates
            if (rankedStartup.isNotEmpty()) {
                android.util.Log.i(
                    "Player",
                    "startup_autoplay_candidates_available context=stability_fallback count=${rankedStartup.size}",
                )
            }

            for (candidate in rankedStartup) {
                val key = streamKey(candidate)
                if (key in attemptedAutoStreamKeys) continue
                attemptedAutoStreamKeys = attemptedAutoStreamKeys + key

                val hostKey = StreamRuntimeTelemetry.keyForStream(candidate)
                StreamRuntimeTelemetry.recordPlayAttempt(hostKey)

                val resolved = withTimeoutOrNull(45_000L) {
                    streamRepo.resolveStream(candidate, provider, apiKey)
                }
                if (resolved == null) {
                    android.util.Log.w(
                        "Player",
                        "startup_candidate_failed context=stability_fallback key=$key reason=timeout",
                    )
                    StreamRuntimeTelemetry.recordStartupTimeout(hostKey, 45_000L)
                    streamRepo.reportPlaybackOutcome(candidate, provider, success = false)
                    continue
                }
                // Addon-hosted URL? Probe before swapping — mid-playback we
                // can't wait 5 min for Panda's cloud client, so skip any
                // candidate that isn't serving right now. Non-addon URLs
                // skip the probe entirely.
                if (candidate.isAddonHostedUrl()) {
                    val readiness = streamRepo.probeStreamReadiness(resolved.url.orEmpty())
                    if (readiness !is com.torve.domain.repository.StreamReadiness.Ready) {
                        android.util.Log.i(
                            "Player",
                            "startup_candidate_skip context=stability_fallback key=$key reason=${readiness::class.simpleName}",
                        )
                        continue
                    }
                }

                val nextUrl = resolved.transcodeUrls?.mp4
                    ?: resolved.transcodeUrls?.hls
                    ?: resolved.url
                if (nextUrl.isBlank() || nextUrl == currentUrl) {
                    android.util.Log.w(
                        "Player",
                        "startup_candidate_failed context=stability_fallback key=$key reason=invalid_url",
                    )
                    streamRepo.reportPlaybackOutcome(candidate, provider, success = false)
                    continue
                }

                val resumePositionMs = maxOf(engine.state.positionMs, currentPosition).coerceAtLeast(0L)
                currentStreamHostKey = StreamRuntimeTelemetry.keyForUrl(nextUrl)
                errorMessage = null
                codecFallbackUsed = false
                currentUrl = nextUrl
                resetPlaybackHealthWindow()
                if (resumePositionMs > 0L) {
                    pendingAutoFallbackResumePositionMs = resumePositionMs
                    pendingAutoFallbackResumeDeadlineMs = SystemClock.elapsedRealtime() + 30_000L
                }
                engine.stop()
                requestPlayback(nextUrl)
                android.util.Log.i(
                    "Player",
                    "startup_candidate_used context=stability_fallback key=$key host=$hostKey",
                )
                Toast.makeText(context, context.getString(R.string.player_switched_source), Toast.LENGTH_SHORT).show()
                return true
            }

            android.util.Log.i(
                "Player",
                "fallback_to_full_fetch context=stability_fallback startupCount=${rankedStartup.size}",
            )
            val candidates = streamRepo.fetchStreams(
                type = parsedMediaType,
                imdbId = imdbId,
                contentId = showTmdbId?.let { "tmdb:$it" },
                title = title,
                season = currentSeasonNumber,
                episode = currentEpisodeNumber,
                addons = addons,
                debridAccounts = debridAccounts,
                preferences = preferences,
                fetchPolicy = StreamFetchPolicy.FULL,
            )
            val rankedBySelector = streamSelector.rankPlayableVariants(
                streams = candidates,
                preferences = preferences,
                deviceCaps = deviceCaps,
            )
            val ranked = StreamFallbackOrdering.streamsInTryOrder(
                streams = rankedBySelector,
                startupCandidates = startupSelection.snapshot.candidates,
                keyOf = ::playerStreamKey,
            )
            if (ranked.isEmpty()) return false

            for (candidate in ranked) {
                val key = streamKey(candidate)
                if (key in attemptedAutoStreamKeys) continue
                attemptedAutoStreamKeys = attemptedAutoStreamKeys + key

                val hostKey = StreamRuntimeTelemetry.keyForStream(candidate)
                StreamRuntimeTelemetry.recordPlayAttempt(hostKey)

                val resolved = withTimeoutOrNull(45_000L) {
                    streamRepo.resolveStream(candidate, provider, apiKey)
                }
                if (resolved == null) {
                    StreamRuntimeTelemetry.recordStartupTimeout(hostKey, 45_000L)
                    streamRepo.reportPlaybackOutcome(candidate, provider, success = false)
                    continue
                }
                if (candidate.isAddonHostedUrl()) {
                    val readiness = streamRepo.probeStreamReadiness(resolved.url.orEmpty())
                    if (readiness !is com.torve.domain.repository.StreamReadiness.Ready) {
                        android.util.Log.i(
                            "Player",
                            "startup_candidate_skip context=full_fetch_fallback reason=${readiness::class.simpleName}",
                        )
                        continue
                    }
                }

                val nextUrl = resolved.transcodeUrls?.mp4
                    ?: resolved.transcodeUrls?.hls
                    ?: resolved.url
                if (nextUrl.isBlank() || nextUrl == currentUrl) {
                    streamRepo.reportPlaybackOutcome(candidate, provider, success = false)
                    continue
                }

                val resumePositionMs = maxOf(engine.state.positionMs, currentPosition).coerceAtLeast(0L)
                currentStreamHostKey = StreamRuntimeTelemetry.keyForUrl(nextUrl)
                errorMessage = null
                codecFallbackUsed = false
                currentUrl = nextUrl
                resetPlaybackHealthWindow()
                if (resumePositionMs > 0L) {
                    pendingAutoFallbackResumePositionMs = resumePositionMs
                    pendingAutoFallbackResumeDeadlineMs = SystemClock.elapsedRealtime() + 30_000L
                }
                engine.stop()
                requestPlayback(nextUrl)
                android.util.Log.i(
                    "Player",
                    "full_fetch_winner_used context=stability_fallback key=$key host=$hostKey",
                )
                Toast.makeText(context, context.getString(R.string.player_switched_source), Toast.LENGTH_SHORT).show()
                return true
            }
            return false
        } catch (_: Exception) {
            return false
        } finally {
            autoFallbackInProgress = false
        }
    }

    // Apply global audio output preferences for all playback (not only live TV).
    LaunchedEffect(
        useMpv,
        channelsState.audioPassthroughEnabled,
        channelsState.preferSurroundCodecs,
        channelsState.liveAudioOutputMode,
    ) {
        if (useMpv) {
            (engine as? MPVPlayerEngine)?.setAudioOutputPreferences(
                passthroughEnabled = channelsState.audioPassthroughEnabled,
                preferSurround = channelsState.preferSurroundCodecs,
                outputMode = channelsState.liveAudioOutputMode,
            )
        } else {
            (engine as? ExoPlayerEngine)?.setAudioOutputPreferences(
                passthroughEnabled = channelsState.audioPassthroughEnabled,
                preferSurround = channelsState.preferSurroundCodecs,
                outputMode = channelsState.liveAudioOutputMode,
            )
        }
    }

    LaunchedEffect(
        currentUrl,
        useMpv,
        isLiveChannelPlayback,
        channelsState.audioPassthroughEnabled,
        channelsState.preferSurroundCodecs,
        channelsState.liveAudioOutputMode,
    ) {
        if (!isLiveChannelPlayback) return@LaunchedEffect
        android.util.Log.i(
            "Player",
            buildLiveAudioPathLog(
                LiveAudioPathSnapshot(
                    surface = LiveAudioClientSurface.MOBILE,
                    engineId = if (useMpv) LivePlayerEngineId.MPV else LivePlayerEngineId.EXOPLAYER,
                    channelName = title.ifBlank { currentUrl },
                    trackCount = audioTracks.size,
                    selectedTrack = audioTracks.firstOrNull { it.isSelected },
                    audioTracks = audioTracks,
                    passthroughEnabled = channelsState.audioPassthroughEnabled,
                    preferSurround = channelsState.preferSurroundCodecs,
                    outputMode = channelsState.liveAudioOutputMode,
                    rememberedHint = null,
                    note = "play_start",
                ),
            ),
        )
    }

    LaunchedEffect(
        isLiveChannelPlayback,
        currentUrl,
        useMpv,
        audioTracks,
        isPlaying,
        channelsState.audioPassthroughEnabled,
        channelsState.preferSurroundCodecs,
        channelsState.liveAudioOutputMode,
    ) {
        if (!isLiveChannelPlayback || !isPlaying) return@LaunchedEffect
        android.util.Log.i(
            "Player",
            buildLiveAudioPathLog(
                LiveAudioPathSnapshot(
                    surface = LiveAudioClientSurface.MOBILE,
                    engineId = if (useMpv) LivePlayerEngineId.MPV else LivePlayerEngineId.EXOPLAYER,
                    channelName = title.ifBlank { currentUrl },
                    trackCount = audioTracks.size,
                    selectedTrack = audioTracks.firstOrNull { it.isSelected },
                    audioTracks = audioTracks,
                    passthroughEnabled = channelsState.audioPassthroughEnabled,
                    preferSurround = channelsState.preferSurroundCodecs,
                    outputMode = channelsState.liveAudioOutputMode,
                    rememberedHint = null,
                    note = "steady_state",
                ),
            ),
        )
    }

    LaunchedEffect(useMpv, pictureFormat) {
        if (useMpv) {
            (engine as? MPVPlayerEngine)?.setPictureFormat(
                aspectRatio = pictureFormat.aspectRatio,
                fill = pictureFormat.fill,
            )
        }
    }

    val togglePlayback: () -> Unit = {
        if (isPlaying) {
            engine.pause()
            if (canScrobble) {
                val progress = if (duration > 0) {
                    (currentPosition.toDouble() / duration * 100).coerceIn(0.0, 100.0)
                } else 0.0
                scope.launch {
                    traktScrobbler.pause(
                        traktAccessToken,
                        tmdbId,
                        parsedMediaType,
                        progress,
                        season = currentSeasonNumber,
                        episode = currentEpisodeNumber,
                    )
                }
            }
        } else {
            engine.resume()
            if (canScrobble) {
                val progress = if (duration > 0) {
                    (currentPosition.toDouble() / duration * 100).coerceIn(0.0, 100.0)
                } else 0.0
                scope.launch {
                    traktScrobbler.start(
                        traktAccessToken,
                        tmdbId,
                        parsedMediaType,
                        progress,
                        season = currentSeasonNumber,
                        episode = currentEpisodeNumber,
                    )
                }
            }
        }
        showControls = true
    }

    fun seekBy(
        deltaMs: Long,
        userInitiated: Boolean = true,
        showTvFeedback: Boolean = false,
    ) {
        val basePosition = engine.state.positionMs.coerceAtLeast(0L)
        performSeekTo(
            targetMs = basePosition + deltaMs,
            userInitiated = userInitiated,
            sourceDeltaMs = deltaMs,
            showTvFeedback = showTvFeedback,
        )
    }

    fun resetSeekAcceleration() {
        seekRepeatDirection = 0
        seekRepeatCount = 0
        seekRepeatLastAtMs = 0L
        seekRepeatTargetMs = -1L
    }

    fun acceleratedSeekDelta(direction: Int): Long {
        val nowMs = SystemClock.uptimeMillis()
        val resetWindowMs = settingsState.tvSkipResetWindowMs.coerceIn(600, 4_000).toLong()
        val nextStepIndex = if (settingsState.tvProgressiveSkipEnabled) {
            PlayerNavigationMath.nextProgressiveSkipStepIndex(
                previousDirection = seekRepeatDirection,
                newDirection = direction,
                previousStepIndex = seekRepeatCount,
                previousPressAtMs = seekRepeatLastAtMs,
                nowMs = nowMs,
                resetWindowMs = resetWindowMs,
            )
        } else {
            0
        }
        seekRepeatCount = nextStepIndex
        seekRepeatDirection = direction
        seekRepeatLastAtMs = nowMs
        val stepMs = PlayerNavigationMath.progressiveSkipStepMs(nextStepIndex)
        return direction * stepMs
    }

    fun handleTvTransportSeek(direction: Int, hideControls: Boolean = true) {
        if (!settingsState.tvTransportSkipEnabled) {
            resetSeekAcceleration()
            if (settingsState.tvExplicitTimelineScrubEnabled) {
                showControls = true
                topMenuFocusTick++
            }
            return
        }

        val nowMs = SystemClock.uptimeMillis()
        val resetWindowMs = settingsState.tvSkipResetWindowMs.coerceIn(600, 4_000).toLong()
        val inBurst = seekRepeatDirection == direction &&
            seekRepeatDirection != 0 &&
            (nowMs - seekRepeatLastAtMs).coerceAtLeast(0L) <= resetWindowMs
        val basePosition = if (inBurst && seekRepeatTargetMs >= 0L) {
            seekRepeatTargetMs
        } else {
            engine.state.positionMs.coerceAtLeast(0L)
        }
        val deltaMs = acceleratedSeekDelta(direction)
        val maxPosition = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val targetPosition = (basePosition + deltaMs).coerceIn(0L, maxPosition)
        seekRepeatTargetMs = targetPosition
        if (hideControls) showControls = false
        performSeekTo(
            targetMs = targetPosition,
            userInitiated = true,
            sourceDeltaMs = deltaMs,
            showTvFeedback = true,
        )
    }

    var voiceFeedbackMessage by remember { mutableStateOf<String?>(null) }
    val voiceController = rememberVoiceInputController(
        prompt = "Control playback or say search for a title",
        onTranscript = { transcript ->
            when (val command = PlayerVoiceCommandParser.parse(transcript)) {
                PlayerVoiceCommand.Play -> {
                    if (!isPlaying) {
                        togglePlayback()
                    } else {
                        showControls = true
                    }
                    voiceFeedbackMessage = voiceCommandPlayLabel
                }

                PlayerVoiceCommand.Pause -> {
                    if (isPlaying) {
                        togglePlayback()
                    } else {
                        showControls = true
                    }
                    voiceFeedbackMessage = voiceCommandPauseLabel
                }

                is PlayerVoiceCommand.Seek -> {
                    seekBy(command.deltaMs)
                    voiceFeedbackMessage = if (command.deltaMs > 0) {
                        voiceCommandForwardLabel
                    } else {
                        voiceCommandRewindLabel
                    }
                }

                is PlayerVoiceCommand.Search -> {
                    val query = command.query.trim()
                    if (query.isNotBlank() && onVoiceSearchCommand != null) {
                        onVoiceSearchCommand.invoke(query)
                        voiceFeedbackMessage = voiceCommandSearchLabel(query)
                    } else {
                        voiceFeedbackMessage = voiceCommandNotRecognizedLabel
                    }
                }

                null -> {
                    voiceFeedbackMessage = voiceCommandNotRecognizedLabel
                }
            }
        },
    )

    val handleBackAction: () -> Boolean = {
        when {
            showResumePrompt -> {
                showResumePrompt = false
                initialStartPositionConsumed = true
                pendingStartPositionMs = 0L
                showControls = true
                topMenuFocusTick++
                true
            }
            showTrackDialog -> {
                showTrackDialog = false
                showControls = true
                topMenuFocusTick++
                true
            }
            showAudioDelayDialog -> {
                showAudioDelayDialog = false
                showControls = true
                topMenuFocusTick++
                true
            }
            showPictureFormatPicker -> {
                showPictureFormatPicker = false
                showControls = true
                topMenuFocusTick++
                true
            }
            showEqualizerSheet -> {
                showEqualizerSheet = false
                showControls = true
                topMenuFocusTick++
                true
            }
            showDevicePicker -> {
                showDevicePicker = false
                showControls = true
                topMenuFocusTick++
                true
            }
            !isTv && mobileActiveSheet != null -> {
                when (val result = reduceMobilePlaybackBack(showControls, mobileSheetStack)) {
                    is MobilePlaybackBackResult.Update -> {
                        showControls = result.controlsVisible
                        mobileSheetStack = result.sheetStack
                        true
                    }

                    MobilePlaybackBackResult.ExitPlayer -> false
                }
            }
            showNextEpisodeOverlay -> {
                showNextEpisodeOverlay = false
                nextEpisodeCancelled = true
                true
            }
            showControls -> false
            else -> {
                showControls = true
                true
            }
        }
    }

    fun requestExitPlayer() {
        if (playerExitInFlight) return
        playerExitInFlight = true
        exitSnapshotPositionMs = maxOf(engine.state.positionMs, currentPosition).coerceAtLeast(0L)
        exitSnapshotDurationMs = duration.coerceAtLeast(0L)
        if (!useMpv) {
            onBack()
            return
        }
        scope.launch {
            runCatching { engine.stop() }
            delay(80)
            mpvSurfaceReady = false
            mpvView?.releaseSurface("player_back_exit")
            withFrameNanos { }
            onBack()
        }
    }

    BackHandler {
        if (!handleBackAction()) {
            requestExitPlayer()
        }
    }

    LaunchedEffect(engine, playbackPrefsKey) {
        val persisted = prefsRepo.getString(playbackPrefsKey)?.let(::parsePlayerPlaybackPrefs)
        if (persisted != null) {
            audioDelayMs = persisted.audioDelayMs
            playbackSpeed = persisted.playbackSpeed
            pictureFormat = persisted.pictureFormat
            engine.setAudioDelay(audioDelayMs)
            engine.setSpeed(playbackSpeed)
        } else {
            audioDelayMs = engine.getAudioDelay()
            engine.setSpeed(playbackSpeed)
        }
        val trackPrefs = prefsRepo.getString(trackPrefsKey)?.let(::parsePlayerTrackPrefs)
        if (trackPrefs != null) {
            preferredAudioTrackTag = trackPrefs.audioTrackTag
            preferredSubtitleTrackTag = trackPrefs.subtitleTrackTag
            subtitlesPreferredEnabled = trackPrefs.subtitlesEnabled
        } else {
            preferredAudioTrackTag = null
            preferredSubtitleTrackTag = null
            subtitlesPreferredEnabled = true
        }
        playbackPrefsLoaded = true
        trackPrefsLoaded = true
        // Initialize software EQ via ExoPlayer audio processor pipeline
        val eqProcessor = (engine as? ExoPlayerEngine)?.equalizerProcessor
        if (eqProcessor != null) {
            val eq = AudioEqualizer(eqProcessor)
            val savedState = prefsRepo.getString("eq_state")
            if (savedState != null) eq.restoreFromState(savedState)
            audioEqualizer = eq
        }
    }

    LaunchedEffect(playbackPrefsLoaded, playbackPrefsKey, audioDelayMs, playbackSpeed, pictureFormat) {
        if (!playbackPrefsLoaded) return@LaunchedEffect
        prefsRepo.setString(
            playbackPrefsKey,
            serializePlayerPlaybackPrefs(
                PlayerPlaybackPrefs(
                    audioDelayMs = audioDelayMs,
                    playbackSpeed = playbackSpeed,
                    pictureFormat = pictureFormat,
                ),
            ),
        )
    }

    LaunchedEffect(trackPrefsLoaded, trackPrefsKey, preferredAudioTrackTag, preferredSubtitleTrackTag, subtitlesPreferredEnabled) {
        if (!trackPrefsLoaded) return@LaunchedEffect
        prefsRepo.setString(
            trackPrefsKey,
            serializePlayerTrackPrefs(
                PlayerTrackPrefs(
                    audioTrackTag = preferredAudioTrackTag,
                    subtitleTrackTag = preferredSubtitleTrackTag,
                    subtitlesEnabled = subtitlesPreferredEnabled,
                ),
            ),
        )
    }

    LaunchedEffect(currentUrl) {
        trackPrefsAppliedForUrl = false
        currentStreamHostKey = StreamRuntimeTelemetry.keyForUrl(currentUrl)
        currentStreamHostKey?.let { StreamRuntimeTelemetry.recordPlayAttempt(it) }
        resetPlaybackHealthWindow()
        resetSeekAcceleration()
        tvSeekFeedbackVisible = false
    }

    LaunchedEffect(trackPrefsLoaded, trackPrefsAppliedForUrl, audioTracks, subtitleTracks, currentUrl) {
        if (!trackPrefsLoaded || trackPrefsAppliedForUrl) return@LaunchedEffect
        if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) return@LaunchedEffect

        // Step 1: audio. Prefer the per-content remembered tag; if none,
        // try matching the global preferredAudioLanguage from Settings.
        // Track whether the user's preferred audio language is actually
        // present — drives the audio→sub fallback below.
        val preferredAudioLanguage = settingsState.preferredAudioLanguage
            .trim()
            .takeIf { it.isNotBlank() }
            ?.lowercase()
        var preferredAudioLanguageMatched = preferredAudioLanguage == null
        preferredAudioTrackTag?.let { preferredTag ->
            audioTracks.firstOrNull { trackPreferenceTag(it) == preferredTag }?.let { track ->
                if (!track.isSelected) engine.selectAudioTrack(track.id)
                if (preferredAudioLanguage != null && languageMatches(track.language, preferredAudioLanguage)) {
                    preferredAudioLanguageMatched = true
                }
            }
        }
        if (preferredAudioTrackTag == null && preferredAudioLanguage != null) {
            val langTrack = audioTracks.firstOrNull { languageMatches(it.language, preferredAudioLanguage) }
            if (langTrack != null) {
                if (!langTrack.isSelected) engine.selectAudioTrack(langTrack.id)
                preferredAudioLanguageMatched = true
            }
        }
        // If preferredAudioLanguage is set but no track matches, the
        // currently-selected audio is in a different language — the
        // audio→sub fallback below will try to compensate.

        // Step 2: subtitles. Priority order when subtitles are allowed:
        //   1. Per-content remembered track tag (user's explicit last pick).
        //   2. Global preferredSubtitleLanguage from Settings.
        //   3. Audio-unavailable fallback: if preferredAudioLanguage is set
        //      and no matching audio was found, enable a subtitle in that
        //      language so the user at least has text in their preferred
        //      language. Never forces subs on when the user has explicitly
        //      disabled them — subtitlesPreferredEnabled is authoritative.
        if (subtitlesPreferredEnabled) {
            val applied = applyPreferredSubtitle(
                subtitleTracks = subtitleTracks,
                perContentTag = preferredSubtitleTrackTag,
                preferredSubtitleLanguage = settingsState.preferredSubtitleLanguage
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.lowercase(),
                audioFallbackLanguage = preferredAudioLanguage?.takeIf { !preferredAudioLanguageMatched },
                selectTrack = { engine.selectSubtitleTrack(it) },
            )
            if (!applied && subtitleTracks.any { it.isSelected } && preferredSubtitleTrackTag == null) {
                // No explicit preference matched; leave ExoPlayer's default
                // selection alone so embedded "forced" tracks still work.
            }
        } else if (subtitleTracks.any { it.isSelected }) {
            engine.disableSubtitles()
        }

        trackPrefsAppliedForUrl = true
        pendingSubtitleAutoSelect = false
    }

    // After a downloaded subtitle is added, select it as soon as tracks are available.
    LaunchedEffect(subtitleTracks, pendingSubtitleAutoSelect) {
        if (!pendingSubtitleAutoSelect) return@LaunchedEffect
        if (subtitleTracks.isEmpty()) return@LaunchedEffect
        subtitleTracks.firstOrNull()?.let { engine.selectSubtitleTrack(it.id) }
        pendingSubtitleAutoSelect = false
    }

    // Load season data for next-episode calculation (TV shows only)
    LaunchedEffect(showTmdbId, currentSeasonNumber) {
        if (showTmdbId == null || showTmdbId <= 0 || mediaType != "tv") return@LaunchedEffect
        if (currentSeasonNumber == null) return@LaunchedEffect

        try {
            val detail = metadataRepo.getDetail("tv", showTmdbId)
            val validSeasons = detail.seasons
                .filter { it.seasonNumber > 0 }
                .sortedBy { it.seasonNumber }

            // Only load current season and next season to avoid excess API calls
            val seasonsToLoad = validSeasons.filter {
                it.seasonNumber == currentSeasonNumber || it.seasonNumber == currentSeasonNumber!! + 1
            }
            val loaded = seasonsToLoad.map { season ->
                try {
                    metadataRepo.getSeasonDetail(showTmdbId, season.seasonNumber)
                } catch (_: Exception) {
                    season
                }
            }
            loadedSeasons = loaded
        } catch (cancellationException: kotlinx.coroutines.CancellationException) {
            throw cancellationException
        } catch (_: Exception) { }
    }

    // Detect near-completion for next-episode trigger
    LaunchedEffect(currentPosition, duration, completionDetected) {
        if (currentSeasonNumber == null || currentEpisodeNumber == null) return@LaunchedEffect
        if (duration <= 0 || completionDetected || nextEpisodeCancelled) return@LaunchedEffect

        val prefs = settingsViewModel.buildStreamPreferences()
        if (!prefs.autoPlayNextEpisodeEnabled) return@LaunchedEffect

        val remainingMs = duration - currentPosition
        val progressPercent = currentPosition.toFloat() / duration

        val nearComplete = progressPercent >= 0.95f || (remainingMs in 1..30_000)

        if (nearComplete && !showNextEpisodeOverlay) {
            val nextEp = NextEpisodeHelper.getNextEpisode(
                currentSeason = currentSeasonNumber!!,
                currentEpisode = currentEpisodeNumber!!,
                seasons = loadedSeasons,
            )
            if (nextEp != null) {
                nextEpisodeInfo = nextEp
                showNextEpisodeOverlay = true
                completionDetected = true
                nextEpisodeCountdown = 15
            }
        }
    }

    LaunchedEffect(nextEpisodeInfo?.seasonNumber, nextEpisodeInfo?.episodeNumber, showImdbId) {
        val nextEp = nextEpisodeInfo ?: return@LaunchedEffect
        val imdbId = showImdbId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val prefs = settingsViewModel.buildStreamPreferences()
        if (!prefs.autoPlayNextEpisodeEnabled) return@LaunchedEffect

        try {
            val addons = try { addonRepo.getInstalledAddons() } catch (_: Exception) { emptyList() }
            val debridAccounts = settingsViewModel.getDebridAccounts()
            val request = SourceAccelerationRequest(
                mediaType = MediaType.SERIES,
                imdbId = imdbId,
                contentId = showTmdbId?.let { "tmdb:$it" },
                title = title,
                seasonNumber = nextEp.seasonNumber,
                episodeNumber = nextEp.episodeNumber,
                context = SourceAccelerationContext(
                    addons = addons,
                    debridAccounts = debridAccounts,
                    preferences = prefs,
                    startupFetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
                ),
            )
            streamRepo.warmupStartupCandidates(
                request = request,
                trigger = ContentWarmupTrigger.NEXT_EPISODE_AUTOPLAY,
            )
        } catch (_: Exception) { }
    }

    // Countdown timer for next episode overlay
    LaunchedEffect(showNextEpisodeOverlay) {
        if (!showNextEpisodeOverlay) return@LaunchedEffect
        for (i in 15 downTo 1) {
            nextEpisodeCountdown = i
            delay(1000)
            if (!showNextEpisodeOverlay) return@LaunchedEffect
        }
        nextEpisodeCountdown = 0
        // Auto-trigger next episode
        resolveAndPlayNextEpisode(
            nextEpisodeInfo = nextEpisodeInfo,
            showTmdbId = showTmdbId,
            showImdbId = showImdbId,
            seriesTitle = title,
            engine = engine,
            streamRepo = streamRepo,
            streamSelector = streamSelector,
            addonRepo = addonRepo,
            settingsViewModel = settingsViewModel,
            watchProgressRepo = watchProgressRepo,
            watchSessionRecorder = watchSessionRecorder,
            currentWatchSessionId = currentWatchSessionId,
            mediaId = mediaId,
            mediaType = mediaType,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            currentTitle = currentTitle,
            currentPosition = currentPosition,
            duration = duration,
            currentSeasonNumber = currentSeasonNumber,
            currentEpisodeNumber = currentEpisodeNumber,
            requestPlayback = ::requestPlayback,
            onStateUpdate = { newSeason, newEpisode, newUrl, newTitle, newWatchSessionId ->
                currentSeasonNumber = newSeason
                currentEpisodeNumber = newEpisode
                currentUrl = newUrl
                currentTitle = newTitle
                currentWatchSessionId = newWatchSessionId
                currentPosition = 0L
                duration = 0L
                sliderPosition = 0f
                showNextEpisodeOverlay = false
                nextEpisodeCancelled = false
                completionDetected = false
                isResolvingNextEpisode = false
                nextEpisodeInfo = null
                hasMarkedWatched = false
            },
            onResolvingChange = { isResolvingNextEpisode = it },
            onFailed = {
                isResolvingNextEpisode = false
                showNextEpisodeOverlay = false
            },
            traktScrobbler = if (canScrobble) traktScrobbler else null,
            traktAccessToken = traktAccessToken,
            tmdbId = tmdbId,
        )
    }

    // MediaSession for notification bar / lock screen controls.
    // ID must be unique per instance — reusing "" crashes when re-entering player
    // before the previous session's onDispose has released it.
    val mediaSession = remember(engine) {
        val exo = (engine as? ExoPlayerEngine)?.getExoPlayer() ?: return@remember null
        MediaSession.Builder(context, exo)
            .setId("torve-${System.nanoTime()}")
            .build()
    }

    DisposableEffect(mediaSession) {
        onDispose {
            mediaSession?.release()
        }
    }

    // Track player state via listener
    DisposableEffect(engine) {
        val listener = object : PlayerListener {
            override fun onStateChanged(state: PlayerState) {
                isPlaying = state.isPlaying
                ActivePlaybackState.isPlaying = state.isPlaying
                duration = state.durationMs
                if (!isSeeking) {
                    currentPosition = state.positionMs
                    sliderPosition = if (state.durationMs > 0) {
                        state.positionMs.toFloat() / state.durationMs
                    } else 0f
                }

                val nowMs = SystemClock.elapsedRealtime()
                if (healthWindowStartedAtMs == 0L) {
                    healthWindowStartedAtMs = nowMs
                }

                if (pendingAutoFallbackResumePositionMs > 0L) {
                    val resumeExpired = pendingAutoFallbackResumeDeadlineMs > 0L &&
                        nowMs > pendingAutoFallbackResumeDeadlineMs
                    if (resumeExpired) {
                        pendingAutoFallbackResumePositionMs = -1L
                        pendingAutoFallbackResumeDeadlineMs = 0L
                    } else if (!state.isBuffering && !state.isIdle) {
                        val targetPosition = if (state.durationMs > 0L) {
                            pendingAutoFallbackResumePositionMs
                                .coerceIn(0L, (state.durationMs - 1_000L).coerceAtLeast(0L))
                        } else {
                            pendingAutoFallbackResumePositionMs.coerceAtLeast(0L)
                        }
                        if ((state.positionMs - targetPosition).absoluteValue > 2_500L) {
                            performSeekTo(
                                targetMs = targetPosition,
                                userInitiated = false,
                            )
                        }
                        pendingAutoFallbackResumePositionMs = -1L
                        pendingAutoFallbackResumeDeadlineMs = 0L
                    }
                }

                if (state.isBuffering && !inBufferingWindow) {
                    inBufferingWindow = true
                    bufferStartedAtMs = nowMs
                    bufferingAttributedToUserSeek = isSeekSuppressionActive(nowMs)
                } else if (!state.isBuffering && inBufferingWindow) {
                    val bufferedMs = (nowMs - bufferStartedAtMs).coerceAtLeast(0L)
                    val withinEarlyWindow = nowMs - healthWindowStartedAtMs <= earlyHealthWindowMs
                    val ignoreAsUserSeek = bufferingAttributedToUserSeek || isSeekSuppressionActive(nowMs)
                    if (withinEarlyWindow && firstFrameAtMs > 0L && !ignoreAsUserSeek) {
                        earlyRebufferCount += 1
                        earlyRebufferDurationMs += bufferedMs
                        currentStreamHostKey?.let { host ->
                            StreamRuntimeTelemetry.recordEarlyRebuffer(host, bufferedMs)
                        }
                    }
                    inBufferingWindow = false
                    bufferStartedAtMs = 0L
                    bufferingAttributedToUserSeek = false
                }

                if (firstFrameAtMs == 0L && state.isPlaying && !state.isBuffering) {
                    firstFrameAtMs = nowMs
                    val startupMs = (firstFrameAtMs - healthWindowStartedAtMs).coerceAtLeast(0L)
                    currentStreamHostKey?.let { host ->
                        StreamRuntimeTelemetry.recordStartupSuccess(host, startupMs)
                    }
                }

                if (autoSourceSelection && !earlyFallbackTriggered && !autoFallbackInProgress) {
                    val elapsedMs = nowMs - healthWindowStartedAtMs
                    val seekSuppressed = isSeekSuppressionActive(nowMs)
                    if (elapsedMs <= earlyHealthWindowMs && !seekSuppressed) {
                        val startupTimeout = firstFrameAtMs == 0L &&
                            state.isBuffering &&
                            elapsedMs >= earlyStartupTimeoutMs
                        val unstableRebuffer = firstFrameAtMs > 0L && (
                            earlyRebufferCount >= earlyRebufferCountThreshold ||
                                earlyRebufferDurationMs >= earlyRebufferDurationThresholdMs
                            )
                        if (startupTimeout || unstableRebuffer) {
                            earlyFallbackTriggered = true
                            val reason = if (startupTimeout) "startup_timeout" else "early_rebuffer"
                            scope.launch {
                                val switched = trySwitchToStableSource(reason)
                                if (!switched && startupTimeout) {
                                    currentStreamHostKey?.let { host ->
                                        StreamRuntimeTelemetry.recordStartupTimeout(host, elapsedMs)
                                    }
                                }
                            }
                        }
                    }
                }

                // Content ended while countdown was active — trigger immediately
                if (state.isIdle && !state.isBuffering && completionDetected &&
                    showNextEpisodeOverlay && !isResolvingNextEpisode
                ) {
                    scope.launch {
                        resolveAndPlayNextEpisode(
                            nextEpisodeInfo = nextEpisodeInfo,
                            showTmdbId = showTmdbId,
                            showImdbId = showImdbId,
                            seriesTitle = title,
                            engine = engine,
                            streamRepo = streamRepo,
                            streamSelector = streamSelector,
                            addonRepo = addonRepo,
                            settingsViewModel = settingsViewModel,
                            watchProgressRepo = watchProgressRepo,
                            watchSessionRecorder = watchSessionRecorder,
                            currentWatchSessionId = currentWatchSessionId,
                            mediaId = mediaId,
                            mediaType = mediaType,
                            posterUrl = posterUrl,
                            backdropUrl = backdropUrl,
                            currentTitle = currentTitle,
                            currentPosition = currentPosition,
                            duration = duration,
                            currentSeasonNumber = currentSeasonNumber,
                            currentEpisodeNumber = currentEpisodeNumber,
                            requestPlayback = ::requestPlayback,
                            onStateUpdate = { newSeason, newEpisode, newUrl, newTitle, newWatchSessionId ->
                                currentSeasonNumber = newSeason
                                currentEpisodeNumber = newEpisode
                                currentUrl = newUrl
                                currentTitle = newTitle
                                currentWatchSessionId = newWatchSessionId
                                currentPosition = 0L
                                this@DisposableEffect.run { duration = 0L }
                                sliderPosition = 0f
                                showNextEpisodeOverlay = false
                                nextEpisodeCancelled = false
                                completionDetected = false
                                isResolvingNextEpisode = false
                                nextEpisodeInfo = null
                                hasMarkedWatched = false
                            },
                            onResolvingChange = { isResolvingNextEpisode = it },
                            onFailed = {
                                isResolvingNextEpisode = false
                                showNextEpisodeOverlay = false
                            },
                            traktScrobbler = if (canScrobble) traktScrobbler else null,
                            traktAccessToken = traktAccessToken,
                            tmdbId = tmdbId,
                        )
                    }
                }
            }

            override fun onTracksChanged(audio: List<TrackDescription>, subtitles: List<TrackDescription>) {
                audioTracks = audio
                subtitleTracks = subtitles
            }

            override fun onError(message: String) {
                val safeUrl = com.torve.domain.diagnostics.DiagnosticsRedactor.redact(currentUrl)
                val safeMessage = com.torve.domain.diagnostics.DiagnosticsRedactor.redact(message)
                if (safeMessage.isNonFatalRecoveryMessage()) {
                    android.util.Log.i("Player", "Ignoring non-fatal playback recovery notice for URL: $safeUrl - $safeMessage")
                    return
                }
                android.util.Log.e("Player", "Playback error for URL: $safeUrl - $safeMessage")
                val seekSuppressed = isSeekSuppressionActive()
                if (!seekSuppressed) {
                    currentStreamHostKey?.let { StreamRuntimeTelemetry.recordFatalError(it) }
                }
                if (seekSuppressed) {
                    android.util.Log.i("Player", "Ignoring transient playback error during seek suppression window")
                    return
                }
                if (autoSourceSelection && !codecFallbackInProgress && !autoFallbackInProgress) {
                    scope.launch {
                        val switched = trySwitchToStableSource("playback_error")
                        if (!switched && !codecFallbackInProgress) {
                            errorMessage = message
                        }
                    }
                    return
                }
                // Suppress error overlay while codec fallback is in progress
                if (!codecFallbackInProgress) {
                    errorMessage = message
                }
            }
        }
        engine.addListener(listener)

        // Wire codec-error recovery for ExoPlayer
        if (engine is ExoPlayerEngine) {
            // On video decoder failure, silently switch to
            // fallback URL (HLS transcode) or go back. Never show error to user.
            engine.onCodecError = { errorCode ->
                android.util.Log.w("Player", "Codec error ($errorCode) — attempting silent fallback")
                currentStreamHostKey?.let { StreamRuntimeTelemetry.recordFatalError(it) }
                codecFallbackInProgress = true
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    errorMessage = null // clear any error that snuck in
                    val switched = if (fallbackUrl.isNotBlank() && !codecFallbackUsed) {
                        val resumePositionMs = maxOf(engine.state.positionMs, currentPosition).coerceAtLeast(0L)
                        codecFallbackUsed = true
                        currentUrl = fallbackUrl
                        resetPlaybackHealthWindow()
                        if (resumePositionMs > 0L) {
                            pendingAutoFallbackResumePositionMs = resumePositionMs
                            pendingAutoFallbackResumeDeadlineMs = SystemClock.elapsedRealtime() + 30_000L
                        }
                        engine.stop()
                        requestPlayback(fallbackUrl)
                        true
                    } else if (autoSourceSelection) {
                        trySwitchToStableSource("codec_error")
                    } else {
                        false
                    }
                    if (!switched) {
                        // No fallback available — silently go back
                        requestExitPlayer()
                    }
                    // Allow errors again after a short delay for the new stream to start
                    kotlinx.coroutines.delay(3000)
                    codecFallbackInProgress = false
                }
            }
        }

        resetPlaybackHealthWindow()
        if (currentUrl.isBlank()) {
            errorMessage = "No playback URL available"
        } else if (!useMpv) {
            engine.play(currentUrl, externalSubtitles)
        }
        // mpv playback is started by the LaunchedEffect(mpvSurfaceReady) below
        if (!initialStartPositionConsumed && !showResumePrompt && resumePromptInitialPositionMs > 0L) {
            pendingStartPositionMs = resumePromptInitialPositionMs
            initialStartPositionConsumed = true
        }

        val playbackSessionStartedAt = System.currentTimeMillis()
        currentWatchSessionIdentity()?.let { identity ->
            scope.launch {
                runCatching {
                    currentWatchSessionId = watchSessionRecorder.startPlayerSession(
                        identity = identity,
                        startedAt = playbackSessionStartedAt,
                    )
                }
            }
        }

        // Scrobble start on initial playback
        if (canScrobble) {
            scope.launch {
                traktScrobbler.start(
                    traktAccessToken, tmdbId, parsedMediaType, 0.0,
                    season = currentSeasonNumber, episode = currentEpisodeNumber,
                )
            }
        }

        // Record a watch_history entry so the "Recently Watched" rail captures
        // this play. For episodes we key by the SHOW's TMDB id (never the episode
        // id) so the rail collapses every watched episode of a series into a
        // single show card, and so DetailScreen.refreshWatchState() sees the
        // episode as watched (marks it ✓ and advances Play → next episode).
        scope.launch {
            val isSeries = parsedMediaType == MediaType.SERIES
            val historyMediaId = if (isSeries) {
                showTmdbId?.toString()
                    ?: showImdbId
                    ?: tmdbId.takeIf { it > 0 }?.toString()
                    ?: mediaId
            } else {
                mediaId.ifBlank { tmdbId.takeIf { it > 0 }?.toString().orEmpty() }
            }
            if (historyMediaId.isBlank()) return@launch
            val nowMs = System.currentTimeMillis()
            val epSuffix = if (isSeries && currentSeasonNumber != null && currentEpisodeNumber != null) {
                "_s${currentSeasonNumber}e${currentEpisodeNumber}"
            } else ""
            // For series, the incoming `title` param is the show name; currentTitle
            // may have drifted to an episode name on auto-advance. Preserve the
            // show name in show_title so the rail renders "The Boys" (not "Pilot").
            val resolvedShowTitle = if (isSeries) title.ifBlank { currentTitle } else null
            runCatching {
                watchHistoryRepo.record(
                    WatchHistoryEntry(
                        id = "${historyMediaId}${epSuffix}_${nowMs}",
                        mediaId = historyMediaId,
                        mediaType = if (isSeries) MediaType.SERIES.name else "movie",
                        title = currentTitle,
                        posterUrl = posterUrl.takeIf { it.isNotBlank() },
                        backdropUrl = backdropUrl.takeIf { it.isNotBlank() },
                        watchedAt = nowMs,
                        durationWatchedMs = 0L,
                        seasonNumber = currentSeasonNumber,
                        episodeNumber = currentEpisodeNumber,
                        showTitle = resolvedShowTitle,
                    ),
                )
            }
        }

        onDispose {
            val finalPosition = exitSnapshotPositionMs.takeIf { it >= 0L } ?: engine.state.positionMs
            val finalDuration = exitSnapshotDurationMs.takeIf { it >= 0L } ?: duration
            if (useMpv) {
                mpvSurfaceReady = false
                if (!playerExitInFlight) {
                    runCatching { engine.stop() }
                }
                mpvView?.releaseSurface("player_dispose")
            }
            // Save final progress on dispose
            val finalContentId = mediaId.ifBlank { showTmdbId?.toString().orEmpty() }
            if (finalDuration > 0 && finalPosition >= (finalDuration * 0.9f).toLong()) {
                currentStreamHostKey?.let { StreamRuntimeTelemetry.recordCompletion(it) }
            }
            if (mediaId.isNotBlank() && finalDuration > 0) {
                scope.launch {
                    watchProgressRepo.saveProgress(
                        WatchProgress(
                            mediaId = mediaId,
                            mediaType = MediaType.fromString(mediaType),
                            title = currentTitle,
                            posterUrl = posterUrl.takeIf { it.isNotBlank() },
                            backdropUrl = backdropUrl.takeIf { it.isNotBlank() },
                            positionMs = finalPosition,
                            durationMs = finalDuration,
                            seasonNumber = currentSeasonNumber,
                            episodeNumber = currentEpisodeNumber,
                        ),
                    )
                }
            }
            scope.launch {
                runCatching {
                    watchSessionRecorder.finishPlayerSession(
                        sessionId = currentWatchSessionId,
                        positionMs = finalPosition,
                        durationMs = finalDuration.takeIf { it > 0L },
                        endedAt = System.currentTimeMillis(),
                    )
                }
            }
            if (finalContentId.isNotBlank() && finalPosition > 0L) {
                scope.launch {
                    syncCoordinator.reportWatchState(
                        contentId = finalContentId,
                        provider = "torve",
                        positionMs = finalPosition,
                    )
                }
            }
            // Scrobble stop on dispose
            if (canScrobble) {
                val progress = if (finalDuration > 0) {
                    (finalPosition.toDouble() / finalDuration * 100).coerceIn(0.0, 100.0)
                } else 0.0
                scope.launch {
                    traktScrobbler.stop(
                        traktAccessToken, tmdbId, parsedMediaType, progress,
                        season = currentSeasonNumber, episode = currentEpisodeNumber,
                    )
                }
            }
            ActivePlaybackState.isPlaying = false
            engine.removeListener(listener)
            audioEqualizer?.release()
            mpvView = null
            if (engine is ExoPlayerEngine) {
                detachExoPlayerView()
            }
            engine.release()
        }
    }

    // Start mpv playback only after its surface is attached
    LaunchedEffect(mpvSurfaceReady) {
        if (useMpv && mpvSurfaceReady && currentUrl.isNotBlank()) {
            engine.play(currentUrl, externalSubtitles)
        }
    }

    // Position updates for ExoPlayer (MPV uses property observers)
    LaunchedEffect(isPlaying, useMpv) {
        if (useMpv) return@LaunchedEffect // MPV updates via callbacks
        var saveCounter = 0
        while (isPlaying) {
            if (!isSeeking && engine is ExoPlayerEngine) {
                engine.updatePosition()
                val st = engine.state
                currentPosition = st.positionMs
                sliderPosition = if (duration > 0) currentPosition.toFloat() / duration else 0f
            }
            saveCounter++
            if (saveCounter >= 20 && mediaId.isNotBlank() && duration > 0) {
                saveCounter = 0
                watchProgressRepo.saveProgress(
                    WatchProgress(
                        mediaId = mediaId,
                        mediaType = MediaType.fromString(mediaType),
                        title = currentTitle,
                        posterUrl = posterUrl.takeIf { it.isNotBlank() },
                        backdropUrl = backdropUrl.takeIf { it.isNotBlank() },
                        positionMs = currentPosition,
                        durationMs = duration,
                        seasonNumber = currentSeasonNumber,
                        episodeNumber = currentEpisodeNumber,
                    ),
                )
                runCatching {
                    watchSessionRecorder.updatePlayerSessionProgress(
                        sessionId = currentWatchSessionId,
                        positionMs = currentPosition,
                        durationMs = duration.takeIf { it > 0L },
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
            delay(500)
        }
    }

    // Save progress for MPV engine
    LaunchedEffect(isPlaying, useMpv) {
        if (!useMpv) return@LaunchedEffect
        while (isPlaying) {
            if (mediaId.isNotBlank() && duration > 0) {
                watchProgressRepo.saveProgress(
                    WatchProgress(
                        mediaId = mediaId,
                        mediaType = MediaType.fromString(mediaType),
                        title = currentTitle,
                        posterUrl = posterUrl.takeIf { it.isNotBlank() },
                        backdropUrl = backdropUrl.takeIf { it.isNotBlank() },
                        positionMs = currentPosition,
                        durationMs = duration,
                        seasonNumber = currentSeasonNumber,
                        episodeNumber = currentEpisodeNumber,
                    ),
                )
                runCatching {
                    watchSessionRecorder.updatePlayerSessionProgress(
                        sessionId = currentWatchSessionId,
                        positionMs = currentPosition,
                        durationMs = duration.takeIf { it > 0L },
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
            delay(10_000)
        }
    }

    LaunchedEffect(mediaId, mediaType) {
        val handoffContentId = mediaId.ifBlank { showTmdbId?.toString().orEmpty() }
        if (handoffContentId.isBlank()) return@LaunchedEffect
        while (isActive) {
            delay(30_000)
            if (!isPlaying || currentPosition <= 0L) continue
            syncCoordinator.reportWatchState(
                contentId = handoffContentId,
                provider = "torve",
                positionMs = currentPosition,
            )
        }
    }

    // Auto-mark watched on Trakt + Simkl at >80% progress
    LaunchedEffect(currentPosition, duration, hasMarkedWatched) {
        if (hasMarkedWatched) return@LaunchedEffect
        if (duration <= 0 || tmdbId <= 0) return@LaunchedEffect
        val progressPercent = currentPosition.toDouble() / duration
        if (progressPercent >= 0.85) {
            hasMarkedWatched = true
            // Trakt
            if (canScrobble) {
                try {
                    val ids = TraktIds(tmdb = tmdbId)
                    val body = when {
                        parsedMediaType == MediaType.MOVIE -> {
                            TraktHistoryBody(movies = listOf(TraktHistoryMovie(ids = ids)))
                        }
                        currentSeasonNumber != null && currentEpisodeNumber != null -> {
                            TraktHistoryBody(
                                shows = listOf(
                                    TraktHistoryShow(
                                        ids = ids,
                                        seasons = listOf(
                                            TraktHistorySeasonEntry(
                                                number = currentSeasonNumber!!,
                                                episodes = listOf(TraktHistoryEpisodeEntry(number = currentEpisodeNumber!!)),
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        }
                        else -> null
                    }
                    if (body != null) {
                        traktClient.addToHistory(traktAccessToken, body)
                    }
                } catch (_: Exception) { }
            }
            // Simkl
            try {
                val simklToken = integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
                if (!simklToken.isNullOrBlank() && parsedMediaType == MediaType.MOVIE) {
                    val simklIds = SimklIds(tmdb = tmdbId)
                    val simklBody = SimklSyncBody(movies = listOf(SimklSyncItem(simklIds)))
                    simklClient.addToHistory(simklToken, simklBody)
                }
            } catch (_: Exception) { }
        }
    }

    // Detect skip segments when duration becomes available
    LaunchedEffect(duration, currentEpisodeNumber) {
        if (duration <= 0) return@LaunchedEffect
        val isEpisode = mediaType == "tv" && currentSeasonNumber != null
        skipSegments = SkipSegmentDetector.detectSegments(
            isEpisode = isEpisode,
            durationMs = duration,
            episodeNumber = currentEpisodeNumber,
        )
        dismissedSkipSegments = emptySet()
    }

    // Check for active skip segment at current position
    LaunchedEffect(currentPosition, skipSegments, dismissedSkipSegments) {
        val segment = SkipSegmentDetector.findActiveSegment(skipSegments, currentPosition)
        activeSkipSegment = if (segment != null && segment.type.name !in dismissedSkipSegments) {
            segment
        } else null
    }

    LaunchedEffect(showResumePrompt) {
        if (showResumePrompt) {
            showControls = false
        }
    }

    // Auto-hide controls after inactivity: 4s on TV, 5s on mobile.
    var controlsInteractionTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(showControls, controlsInteractionTick, isTv, showTrackDialog, showAudioDelayDialog, showPictureFormatPicker, showEqualizerSheet, showDevicePicker, showResumePrompt, mobileActiveSheet, showSubtitleSearch) {
        if (!showControls) return@LaunchedEffect
        if (showTrackDialog || showAudioDelayDialog || showPictureFormatPicker || showEqualizerSheet || showDevicePicker || showResumePrompt || mobileActiveSheet != null || showSubtitleSearch) return@LaunchedEffect
        delay(if (isTv) 4000L else 5000L)
        showControls = false
    }

    // Hide controls when subtitle search opens.
    LaunchedEffect(showSubtitleSearch) {
        if (showSubtitleSearch) showControls = false
    }

    LaunchedEffect(currentUrl, pendingStartPositionMs, showResumePrompt) {
        val seekTarget = pendingStartPositionMs
        if (seekTarget <= 0L || showResumePrompt) return@LaunchedEffect
        delay(450)
        performSeekTo(
            targetMs = seekTarget,
            userInitiated = false,
        )
        pendingStartPositionMs = 0L
    }

    LaunchedEffect(voiceFeedbackMessage) {
        if (voiceFeedbackMessage != null) {
            delay(2200)
            voiceFeedbackMessage = null
        }
    }

    LaunchedEffect(
        tvSeekFeedbackVisible,
        tvSeekFeedbackInteractionAtMs,
        settingsState.tvSkipResetWindowMs,
    ) {
        if (!tvSeekFeedbackVisible) return@LaunchedEffect
        // Persist for at least 1.2s, then let the burst window clear it
        val minShowMs = 1_200L
        val timeoutMs = settingsState.tvSkipResetWindowMs.coerceIn(600, 4_000).toLong() + 650L
        val waitMs = maxOf(minShowMs, timeoutMs)
        delay(waitMs)
        val elapsed = SystemClock.elapsedRealtime() - tvSeekFeedbackInteractionAtMs
        if (elapsed >= waitMs - 40L) {
            tvSeekFeedbackVisible = false
            if (elapsed >= settingsState.tvSkipResetWindowMs.coerceIn(600, 4_000).toLong()) {
                resetSeekAcceleration()
            }
        }
    }

    LaunchedEffect(Unit) {
        playerRootFocusRequester.requestFocus()
    }

    // Derive the coordinator uiMode from existing boolean state.
    // This is the single source of truth for focus topology.
    val derivedUiMode = when {
        showResumePrompt -> PlaybackUiMode.ResumePrompt
        showTrackDialog -> PlaybackUiMode.TrackSelection
        showAudioDelayDialog -> PlaybackUiMode.AudioDelay
        showPictureFormatPicker -> PlaybackUiMode.PictureFormat
        showEqualizerSheet -> PlaybackUiMode.Equalizer
        showDevicePicker -> PlaybackUiMode.DevicePicker
        showNextEpisodeOverlay -> PlaybackUiMode.NextEpisode
        showControls -> PlaybackUiMode.ControlsVisible
        else -> PlaybackUiMode.ChromeHidden
    }
    LaunchedEffect(derivedUiMode) {
        focusCoordinator.uiMode = derivedUiMode
    }

    // State-driven focus restoration: when uiMode changes, restore focus
    // through the coordinator. Only targets currently-registered active regions.
    LaunchedEffect(derivedUiMode, topMenuFocusTick) {
        withFrameNanos { }
        when (derivedUiMode) {
            is PlaybackUiMode.ChromeHidden -> {
                runCatching { playerRootFocusRequester.requestFocus() }
            }
            is PlaybackUiMode.ControlsVisible -> {
                val restored = focusCoordinator.restoreFocusForCurrentMode()
                if (isTv && !restored) {
                    runCatching { playerRootFocusRequester.requestFocus() }
                }
            }
            // Modal overlays handle their own initial focus internally.
            else -> Unit
        }
    }

    val handoffTargets = syncCoordinator.targetDevices()
        .filter { it.deviceType.contains("tv", ignoreCase = true) }
    val handoffContentId = mediaId.ifBlank { showTmdbId?.toString().orEmpty() }
    var mobileCategoryChannels by remember(currentUrl, channelsState.selectedPlaylistId) {
        mutableStateOf<List<com.torve.domain.model.EnrichedChannel>>(emptyList())
    }
    var mobileGuideProgrammes by remember(currentUrl, channelsState.selectedPlaylistId) {
        mutableStateOf<Map<String, List<com.torve.domain.model.EpgProgramme>>>(emptyMap())
    }
    var mobileSheetLoading by remember(currentUrl, channelsState.selectedPlaylistId) {
        mutableStateOf(false)
    }
    var mobileSheetError by remember(currentUrl, channelsState.selectedPlaylistId) {
        mutableStateOf<String?>(null)
    }
    val livePlaybackContext = if (isLiveChannelPlayback) {
        resolveLivePlaybackContext(
            url = currentUrl,
            title = currentTitle,
            channelsState = channelsState,
        )
    } else {
        ResolvedLivePlaybackContext()
    }
    val playbackRuntimeInfo = when {
        useMpv -> (engine as? MPVPlayerEngine)?.getPlaybackRuntimeInfo()
        else -> (engine as? ExoPlayerEngine)?.getPlaybackRuntimeInfo()
    }
    val supportsLiveBufferControl = isLiveChannelPlayback && engine is ExoPlayerEngine
    val displayedCurrentProgramme = activeReplayProgramme ?: livePlaybackContext.currentProgramme
    val displayedNextProgramme = if (activeReplayProgramme == null) {
        livePlaybackContext.nextProgramme
    } else {
        null
    }

    LaunchedEffect(engine, supportsLiveBufferControl) {
        liveBufferDurationMs = when {
            supportsLiveBufferControl -> engine.liveBufferDurationMs
            else -> ExoPlayerEngine.DEFAULT_LIVE_BUFFER_MS
        }
    }

    LaunchedEffect(showControls, mobileActiveSheet, isInPip) {
        if (isInPip) return@LaunchedEffect
        val activity = context as? Activity ?: return@LaunchedEffect
        val controller = configureTorveEdgeToEdge(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    LaunchedEffect(configuration.orientation, orientationRotateFrom, enablePhoneAutoRotation) {
        val rotateFrom = orientationRotateFrom ?: return@LaunchedEffect
        if (!enablePhoneAutoRotation || configuration.orientation == rotateFrom) return@LaunchedEffect
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        orientationRotateFrom = null
    }

    fun showMobileSheet(sheet: MobilePlaybackSheet) {
        mobileSheetStack = listOf(sheet)
        showControls = true
    }

    fun pushMobileSheet(sheet: MobilePlaybackSheet) {
        mobileSheetStack = mobileSheetStack + sheet
        showControls = true
    }

    fun popMobileSheet() {
        mobileSheetStack = mobileSheetStack.dropLast(1)
        showControls = true
    }

    fun rotatePlayerOrientation() {
        if (!enablePhoneAutoRotation) return
        val activity = context as? Activity ?: return
        val currentOrientation = configuration.orientation
        val targetOrientation = if (currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        orientationRotateFrom = currentOrientation
        activity.requestedOrientation = targetOrientation
    }

    fun switchToLiveChannel(channel: com.torve.domain.model.Channel) {
        if (!isLiveChannelPlayback || channel.url.isBlank()) return
        channelsViewModel.recordChannelViewed(channel)
        channelsViewModel.selectChannel(channel)
        currentUrl = channel.url
        currentTitle = channel.name
        currentPosterUrl = channel.tvgLogo ?: ""
        activeReplayProgramme = null
        currentPosition = 0L
        duration = 0L
        sliderPosition = 0f
        errorMessage = null
        codecFallbackUsed = false
        mobileSheetStack = emptyList()
        showControls = true
        pendingAutoFallbackResumePositionMs = -1L
        pendingAutoFallbackResumeDeadlineMs = 0L
        resetPlaybackHealthWindow()
        resetSeekAcceleration()
        currentStreamHostKey = StreamRuntimeTelemetry.keyForUrl(channel.url)
        (engine as? ExoPlayerEngine)?.setLiveBufferSize(liveBufferDurationMs)
        engine.stop()
        requestPlayback(channel.url)
    }

    fun canReplayProgramme(channel: com.torve.domain.model.Channel, programme: com.torve.domain.model.EpgProgramme): Boolean {
        if (!channelsViewModel.canCatchup(channel)) return false
        if (programme.endTime > System.currentTimeMillis()) return false
        return channelsViewModel.resolveCatchupUrl(channel, programme) != null
    }

    fun replayProgramme(
        channel: com.torve.domain.model.Channel,
        programme: com.torve.domain.model.EpgProgramme,
    ) {
        if (!isLiveChannelPlayback) return
        val replayUrl = channelsViewModel.resolveCatchupUrl(channel, programme)
        if (replayUrl.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.player_replay_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        channelsViewModel.recordChannelViewed(channel)
        channelsViewModel.selectChannel(channel)
        currentUrl = replayUrl
        currentTitle = channel.name
        currentPosterUrl = channel.tvgLogo ?: ""
        activeReplayProgramme = programme
        currentPosition = 0L
        duration = 0L
        sliderPosition = 0f
        errorMessage = null
        codecFallbackUsed = false
        mobileSheetStack = emptyList()
        showControls = true
        pendingAutoFallbackResumePositionMs = -1L
        pendingAutoFallbackResumeDeadlineMs = 0L
        resetPlaybackHealthWindow()
        resetSeekAcceleration()
        currentStreamHostKey = StreamRuntimeTelemetry.keyForUrl(replayUrl)
        (engine as? ExoPlayerEngine)?.setLiveBufferSize(liveBufferDurationMs)
        engine.stop()
        requestPlayback(replayUrl)
    }

    LaunchedEffect(
        mobileActiveSheet,
        isLiveChannelPlayback,
        channelsState.selectedPlaylistId,
        livePlaybackContext.currentCategoryName,
    ) {
        if (isTv) return@LaunchedEffect
        val sheet = mobileActiveSheet
        if (
            sheet != MobilePlaybackSheet.ChannelList &&
            sheet != MobilePlaybackSheet.Guide &&
            sheet != MobilePlaybackSheet.CurrentChannelGuide
        ) {
            mobileSheetLoading = false
            mobileSheetError = null
            return@LaunchedEffect
        }
        if (!isLiveChannelPlayback) {
            mobileCategoryChannels = emptyList()
            mobileGuideProgrammes = emptyMap()
            mobileSheetLoading = false
            mobileSheetError = null
            return@LaunchedEffect
        }
        val playlistId = channelsState.selectedPlaylistId
        val categoryName = livePlaybackContext.currentCategoryName
        if (playlistId.isNullOrBlank() || categoryName.isNullOrBlank()) {
            mobileCategoryChannels = emptyList()
            mobileGuideProgrammes = emptyMap()
            mobileSheetLoading = false
            mobileSheetError = "Guide data is unavailable for this channel."
            return@LaunchedEffect
        }
        mobileSheetLoading = true
        mobileSheetError = null
        try {
            val channels = channelsViewModel.getChannelsForCategoryDirect(playlistId, categoryName)
            mobileCategoryChannels = channels
            mobileGuideProgrammes = if (
                sheet == MobilePlaybackSheet.Guide ||
                sheet == MobilePlaybackSheet.CurrentChannelGuide
            ) {
                channelsViewModel.getProgrammesForChannelsDirect(playlistId, channels)
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            mobileCategoryChannels = emptyList()
            mobileGuideProgrammes = emptyMap()
            mobileSheetError = e.message ?: "Unable to load channel data."
        } finally {
            mobileSheetLoading = false
        }
    }

    // PlayerSurface region: always in composition (the root video surface)
    RegisterFocusRegion(
        coordinator = focusCoordinator,
        region = PlaybackFocusRegion.PlayerSurface,
        requestFocus = {
            runCatching { playerRootFocusRequester.requestFocus(); true }.getOrDefault(false)
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(playerRootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                // Subtitle search intercepts Back before the player's own handler.
                if (showSubtitleSearch && keyEvent.key == Key.Back) {
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        showSubtitleSearch = false
                        subtitleFetchState = SubtitleFetchState.Idle
                        scope.launch {
                            kotlinx.coroutines.delay(100)
                            runCatching { playerRootFocusRequester.requestFocus() }
                        }
                    }
                    return@onPreviewKeyEvent true // consume both KeyDown and KeyUp
                }

                // Back key: on TV, KeyDown starts a long-press coroutine (800ms → exit).
                // KeyUp cancels the coroutine and does short-press behavior if still active.
                if (keyEvent.type == KeyEventType.KeyUp) {
                    if (isTv && keyEvent.key == Key.Back) {
                        val job = backLongPressJob.value
                        backLongPressJob.value = null
                        if (job != null && job.isActive) {
                            // Released before long-press triggered → short press
                            job.cancel()
                            resetSeekAcceleration()
                            if (!handleBackAction()) {
                                if (showControls) showControls = false
                                else requestExitPlayer()
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (keyEvent.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                if ((showTrackDialog || showAudioDelayDialog || showPictureFormatPicker || showEqualizerSheet || showDevicePicker || showResumePrompt || showNextEpisodeOverlay || showSubtitleSearch) && keyEvent.key != Key.Back) {
                    return@onPreviewKeyEvent false
                }

                // When controls are visible, let D-pad navigate between buttons.
                // Only intercept Back and media keys at root level.
                if (showControls && !showNextEpisodeOverlay) {
                    return@onPreviewKeyEvent when (keyEvent.key) {
                        Key.Back -> {
                            if (isTv) {
                                // Long-press detection: Fire TV sends repeated KeyDown events
                                // while the button is held (~50ms apart). Exit after ~20 repeats (~1s).
                                if (keyEvent.nativeKeyEvent.repeatCount >= 20) {
                                    backLongPressJob.value?.cancel()
                                    backLongPressJob.value = null
                                    resetSeekAcceleration()
                                    requestExitPlayer()
                                } else if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                    backLongPressJob.value?.cancel()
                                    backLongPressJob.value = scope.launch {
                                        kotlinx.coroutines.delay(2_000)
                                        resetSeekAcceleration()
                                        requestExitPlayer()
                                    }
                                }
                            } else {
                                resetSeekAcceleration()
                                if (!handleBackAction()) showControls = false
                            }
                            true
                        }
                        Key.Spacebar, Key.MediaPlayPause -> {
                            resetSeekAcceleration()
                            togglePlayback()
                            true
                        }
                        Key.DirectionUp -> {
                            resetSeekAcceleration()
                            controlsInteractionTick++
                            if (isTv) {
                                val target = focusCoordinator.resolveDirectionalMove(FocusDirection.Up)
                                target != null && focusCoordinator.requestFocusToRegion(target)
                            } else {
                                false
                            }
                        }
                        Key.DirectionDown -> {
                            resetSeekAcceleration()
                            controlsInteractionTick++
                            if (isTv) {
                                val target = focusCoordinator.resolveDirectionalMove(FocusDirection.Down)
                                target != null && focusCoordinator.requestFocusToRegion(target)
                            } else {
                                false
                            }
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            val timelineActive = isTv && focusCoordinator.currentRegion == PlaybackFocusRegion.Timeline
                            if (isTv && !settingsState.tvExplicitTimelineScrubEnabled && !timelineActive) {
                                val direction = if (keyEvent.key == Key.DirectionLeft) -1 else 1
                                handleTvTransportSeek(direction)
                                true
                            } else {
                                resetSeekAcceleration()
                                controlsInteractionTick++
                                false
                            }
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            controlsInteractionTick++
                            false
                        }
                        else -> {
                            resetSeekAcceleration()
                            false
                        }
                    }
                }

                // When next episode popup is showing, block all navigation keys
                // from opening the playback menu — let the popup handle focus internally.
                if (showNextEpisodeOverlay) {
                    return@onPreviewKeyEvent false
                }

                // Controls hidden — handle all D-pad keys for media shortcuts
                when (keyEvent.key) {
                    Key.Back -> {
                        if (isTv) {
                            if (keyEvent.nativeKeyEvent.repeatCount >= 20) {
                                backLongPressJob.value?.cancel()
                                backLongPressJob.value = null
                                resetSeekAcceleration()
                                requestExitPlayer()
                            } else if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                backLongPressJob.value?.cancel()
                                backLongPressJob.value = scope.launch {
                                    kotlinx.coroutines.delay(2_000)
                                    resetSeekAcceleration()
                                    requestExitPlayer()
                                }
                            }
                        } else {
                            resetSeekAcceleration()
                            if (!handleBackAction()) requestExitPlayer()
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        resetSeekAcceleration()
                        if (isTv) {
                            // First OK press shows controls; user then presses play/pause explicitly
                            showControls = true
                        } else {
                            togglePlayback()
                        }
                        true
                    }
                    Key.Spacebar, Key.MediaPlayPause -> {
                        resetSeekAcceleration()
                        togglePlayback()
                        true
                    }
                    Key.DirectionLeft -> {
                        if (isTv) {
                            handleTvTransportSeek(direction = -1)
                        } else {
                            seekBy(-10_000L)
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (isTv) {
                            handleTvTransportSeek(direction = 1)
                        } else {
                            seekBy(10_000L)
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        resetSeekAcceleration()
                        showControls = true
                        true
                    }
                    Key.DirectionDown -> {
                        resetSeekAcceleration()
                        showControls = true
                        true
                    }
                    else -> {
                        resetSeekAcceleration()
                        false
                    }
                }
            }
            .background(Color.Black)
            .then(
                if (!isTv) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        showControls = !showControls
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        PlayerVideoSurface(
            useMpv = useMpv,
            engine = engine,
            pictureFormat = pictureFormat,
            onMpvViewCreated = { view ->
                mpvView = view
                view.onSurfaceAttachedStateChanged = { attached ->
                    mpvSurfaceReady = attached
                }
            },
            onMpvViewReleased = { view ->
                mpvSurfaceReady = false
                if (mpvView === view) {
                    mpvView = null
                }
            },
            onExoPlayerViewCreated = { view -> exoPlayerView = view },
            onExoPlayerViewReleased = { view ->
                if (exoPlayerView === view) exoPlayerView = null
            },
        )
        // Error overlay
        errorMessage?.let { msg ->
            if (isTv) {
                TvPlaybackErrorBanner(
                    message = msg,
                    onRetry = {
                        errorMessage = null
                        requestPlayback(currentUrl)
                    },
                    onDismiss = {
                        errorMessage = null
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 72.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFE8A838),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.player_playback_error),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        if (currentUrl.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = currentUrl.take(80) + if (currentUrl.length > 80) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.3f),
                                maxLines = 2,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    errorMessage = null
                                    requestPlayback(currentUrl)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE8A838),
                                    contentColor = Color.Black,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(stringResource(R.string.player_retry))
                            }
                            Button(
                                onClick = ::requestExitPlayer,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2E2E40),
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(stringResource(R.string.player_go_back))
                            }
                        }
                    }
                }
            }
        }

        // Subtitle search overlay (TV only)
        if (showSubtitleSearch && isTv) {
            TvSubtitleSearchOverlay(
                state = subtitleFetchState,
                onSelect = { candidate ->
                    showSubtitleSearch = false
                    subtitleFetchState = SubtitleFetchState.Idle
                    val savedPos = currentPosition.coerceAtLeast(0L)
                    scope.launch {
                        val subtitleUrl: String? = when {
                            candidate.directUrl != null -> candidate.directUrl
                            candidate.osFileId != null -> runCatching {
                                openSubtitlesClient.getDownloadUrl(candidate.osFileId)
                            }.getOrNull()
                            else -> null
                        }
                        if (subtitleUrl != null) {
                            val sub = ExternalSubtitle(
                                url = subtitleUrl,
                                languageCode = candidate.languageCode.takeIf { it.isNotBlank() },
                                label = "${candidate.flagEmoji} ${candidate.languageName} · ${candidate.displayLabel}",
                                mimeType = candidate.mimeType,
                            )
                            externalSubtitles = listOf(sub)
                            trackPrefsAppliedForUrl = false
                            subtitlesPreferredEnabled = true
                            pendingSubtitleAutoSelect = true
                            engine.play(currentUrl, externalSubtitles)
                            pendingStartPositionMs = savedPos
                        }
                        kotlinx.coroutines.delay(100)
                        runCatching { playerRootFocusRequester.requestFocus() }
                    }
                },
                onDismiss = {
                    showSubtitleSearch = false
                    subtitleFetchState = SubtitleFetchState.Idle
                    scope.launch {
                        kotlinx.coroutines.delay(100)
                        runCatching { playerRootFocusRequester.requestFocus() }
                    }
                },
            )
        }

        // Track selection dialog
        if (showTrackDialog) {
            if (isTv) {
                // Region registration: unregisters when overlay leaves composition
                RegisterFocusRegion(focusCoordinator, PlaybackFocusRegion.TrackSelectionOverlay) { true }
                TvTrackSelectionOverlay(
                    subtitleTracks = subtitleTracks,
                    audioTracks = audioTracks,
                    showSubtitlesOnly = trackDialogSubtitlesOnly,
                    onSelectSubtitle = { track ->
                        if (track == null) {
                            engine.disableSubtitles()
                            subtitlesPreferredEnabled = false
                            preferredSubtitleTrackTag = null
                        } else {
                            engine.selectSubtitleTrack(track.id)
                            subtitlesPreferredEnabled = true
                            preferredSubtitleTrackTag = trackPreferenceTag(track)
                        }
                        showTrackDialog = false
                        topMenuFocusTick++
                    },
                    onSelectAudio = { track ->
                        engine.selectAudioTrack(track.id)
                        preferredAudioTrackTag = trackPreferenceTag(track)
                        showTrackDialog = false
                        topMenuFocusTick++
                    },
                    onDismiss = {
                        showTrackDialog = false
                        topMenuFocusTick++
                    },
                    onSubtitleDelay = {
                        showTrackDialog = false
                        showSubtitleDelayDialog = true
                    },
                    onDownloadSubtitles = {
                        showTrackDialog = false
                        showSubtitleSearch = true
                        subtitleFetchState = SubtitleFetchState.Loading
                        scope.launch {
                            // Resolve IMDB ID
                            var imdb = showImdbId?.trim()?.takeIf { it.isNotBlank() }
                                ?: mediaId.extractImdbIdOrNull()
                            if (imdb == null && resolvedTmdbId > 0) {
                                val typeStr = if (mediaType.equals("tv", ignoreCase = true) ||
                                    mediaType.equals("series", ignoreCase = true)
                                ) "tv" else "movie"
                                imdb = runCatching {
                                    metadataRepo.getDetail(typeStr, resolvedTmdbId).imdbId
                                }.getOrNull()
                            }
                            if (imdb == null) {
                                subtitleFetchState = SubtitleFetchState.Empty
                                return@launch
                            }

                            if (openSubtitlesClient.isConfigured()) {
                                // Direct OpenSubtitles.com API — rich results with flags and full names
                                val results = runCatching {
                                    openSubtitlesClient.searchSubtitles(
                                        imdbId = imdb,
                                        seasonNumber = seasonNumber,
                                        episodeNumber = episodeNumber,
                                    )
                                }.getOrNull()
                                subtitleFetchState = when {
                                    results == null -> SubtitleFetchState.Error
                                    results.isEmpty() -> SubtitleFetchState.Empty
                                    else -> SubtitleFetchState.Results(
                                        results.map { r ->
                                            SubtitleCandidate(
                                                flagEmoji = r.flagEmoji,
                                                languageName = r.languageName,
                                                languageCode = r.language,
                                                displayLabel = r.fileName.ifBlank { r.release.ifBlank { "Subtitle" } },
                                                osFileId = r.fileId,
                                                downloadCount = r.downloadCount,
                                                fromTrusted = r.fromTrusted,
                                                hearingImpaired = r.hearingImpaired,
                                                aiTranslated = r.aiTranslated,
                                                ratings = r.ratings,
                                            )
                                        }
                                    )
                                }
                            } else {
                                // Fallback: Stremio subtitle addons
                                val addons = runCatching { addonRepo.getInstalledAddons() }.getOrNull().orEmpty()
                                val subtitleAddons = addons.filter { a ->
                                    a.isEnabled && a.manifest.resources.any { it == "subtitles" }
                                }
                                if (subtitleAddons.isEmpty()) {
                                    subtitleFetchState = SubtitleFetchState.NoKey
                                    return@launch
                                }
                                val typeEnum = if (mediaType.equals("tv", ignoreCase = true) ||
                                    mediaType.equals("series", ignoreCase = true)
                                ) com.torve.domain.model.MediaType.SERIES else com.torve.domain.model.MediaType.MOVIE
                                val fetched = runCatching {
                                    subtitleAggregator.fetchSubtitles(
                                        addons = addons,
                                        type = typeEnum,
                                        imdbId = imdb,
                                        season = seasonNumber,
                                        episode = episodeNumber,
                                    )
                                }.getOrNull()
                                subtitleFetchState = when {
                                    fetched == null -> SubtitleFetchState.Error
                                    fetched.isEmpty() -> SubtitleFetchState.Empty
                                    else -> SubtitleFetchState.Results(
                                        fetched.map { s ->
                                            val (flag, name) = languageInfo(s.lang.ifBlank { "" })
                                            SubtitleCandidate(
                                                flagEmoji = flag,
                                                languageName = name,
                                                languageCode = s.lang.ifBlank { "?" },
                                                displayLabel = s.label?.ifBlank { null } ?: name,
                                                directUrl = s.url,
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    },
                )
            } else {
                TrackSelectionDialog(
                    subtitleTracks = subtitleTracks,
                    audioTracks = audioTracks,
                    onSelectSubtitle = { track ->
                        if (track == null) {
                            engine.disableSubtitles()
                            subtitlesPreferredEnabled = false
                            preferredSubtitleTrackTag = null
                        } else {
                            engine.selectSubtitleTrack(track.id)
                            subtitlesPreferredEnabled = true
                            preferredSubtitleTrackTag = trackPreferenceTag(track)
                        }
                        showTrackDialog = false
                    },
                    onSelectAudio = { track ->
                        engine.selectAudioTrack(track.id)
                        preferredAudioTrackTag = trackPreferenceTag(track)
                        showTrackDialog = false
                    },
                    onDismiss = { showTrackDialog = false },
                )
            }
        }

        if (showAudioDelayDialog) {
            if (isTv) {
                RegisterFocusRegion(focusCoordinator, PlaybackFocusRegion.AudioDelayOverlay) { true }
                TvAudioDelayOverlay(
                    currentDelayMs = audioDelayMs,
                    onSave = { newDelay ->
                        audioDelayMs = newDelay
                        engine.setAudioDelay(newDelay)
                        showAudioDelayDialog = false
                        topMenuFocusTick++
                    },
                    onReset = {
                        audioDelayMs = 0
                        engine.setAudioDelay(0)
                    },
                    onDismiss = {
                        showAudioDelayDialog = false
                        topMenuFocusTick++
                    },
                )
            } else {
                AudioDelayDialog(
                    currentDelayMs = audioDelayMs,
                    onDelayChange = { newDelay ->
                        audioDelayMs = newDelay
                        engine.setAudioDelay(newDelay)
                    },
                    onReset = {
                        audioDelayMs = 0
                        engine.setAudioDelay(0)
                    },
                    onDismiss = { showAudioDelayDialog = false },
                )
            }
        }

        if (showSubtitleDelayDialog && isTv) {
            TvSubtitleDelayOverlay(
                currentDelayMs = subtitleDelayMs,
                onSave = { newDelay ->
                    subtitleDelayMs = newDelay
                    engine.setSubtitleDelay(newDelay)
                },
                onReset = {
                    subtitleDelayMs = 0
                    engine.setSubtitleDelay(0)
                },
                onDismiss = {
                    showSubtitleDelayDialog = false
                    topMenuFocusTick++
                },
            )
        }

        if (showPictureFormatPicker && isTv) {
            RegisterFocusRegion(focusCoordinator, PlaybackFocusRegion.PictureFormatOverlay) { true }
            TvPictureFormatOverlay(
                currentFormat = pictureFormat,
                onSelect = { selected ->
                    pictureFormat = selected
                    showControls = true
                    showPictureFormatPicker = false
                    topMenuFocusTick++
                },
                onDismiss = {
                    showPictureFormatPicker = false
                    topMenuFocusTick++
                },
            )
        }

        // Equalizer sheet
        if (showEqualizerSheet) {
            if (isTv) {
                RegisterFocusRegion(focusCoordinator, PlaybackFocusRegion.EqualizerOverlay) { true }
                audioEqualizer?.let { eq ->
                    TvEqualizerOverlay(
                        equalizer = eq,
                        onDismiss = {
                            showEqualizerSheet = false
                            topMenuFocusTick++
                        },
                        onStateChanged = { state ->
                            scope.launch { prefsRepo.setString("eq_state", state) }
                        },
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    audioEqualizer?.let { eq ->
                        EqualizerSheet(
                            equalizer = eq,
                            onDismiss = { showEqualizerSheet = false },
                            onStateChanged = { state ->
                                scope.launch { prefsRepo.setString("eq_state", state) }
                            },
                        )
                    }
                }
            }
        }

        // Skip Intro/Credits button — mobile only (TV uses D-pad seek)
        if (!isTv) activeSkipSegment?.let { segment ->
            val skipFocus = remember(segment.type) { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(segment.type) { runCatching { skipFocus.requestFocus() } }
            Button(
                onClick = {
                    val deltaMs = segment.endMs - currentPosition
                    performSeekTo(
                        targetMs = segment.endMs,
                        userInitiated = true,
                        sourceDeltaMs = deltaMs,
                        showTvFeedback = isTv && !showControls,
                    )
                    dismissedSkipSegments = dismissedSkipSegments + segment.type.name
                    activeSkipSegment = null
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 80.dp)
                    .focusRequester(skipFocus)
                    .focusable(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.9f),
                    contentColor = Color.Black,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = segment.label,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } // end if (!isTv)

        // Next Episode overlay — hide controls so the popup can be reached via D-pad
        LaunchedEffect(showNextEpisodeOverlay) {
            if (showNextEpisodeOverlay) showControls = false
        }
        if (showNextEpisodeOverlay && nextEpisodeInfo != null) {
            RegisterFocusRegion(focusCoordinator, PlaybackFocusRegion.NextEpisodeOverlay) { true }
            NextEpisodeOverlay(
                nextEpisodeInfo = nextEpisodeInfo!!,
                countdown = nextEpisodeCountdown,
                isResolving = isResolvingNextEpisode,
                onPlayNow = {
                    scope.launch {
                        resolveAndPlayNextEpisode(
                            nextEpisodeInfo = nextEpisodeInfo,
                            showTmdbId = showTmdbId,
                            showImdbId = showImdbId,
                            seriesTitle = title,
                            engine = engine,
                            streamRepo = streamRepo,
                            streamSelector = streamSelector,
                            addonRepo = addonRepo,
                            settingsViewModel = settingsViewModel,
                            watchProgressRepo = watchProgressRepo,
                            watchSessionRecorder = watchSessionRecorder,
                            currentWatchSessionId = currentWatchSessionId,
                            mediaId = mediaId,
                            mediaType = mediaType,
                            posterUrl = posterUrl,
                            backdropUrl = backdropUrl,
                            currentTitle = currentTitle,
                            currentPosition = currentPosition,
                            duration = duration,
                            currentSeasonNumber = currentSeasonNumber,
                            currentEpisodeNumber = currentEpisodeNumber,
                            requestPlayback = ::requestPlayback,
                            onStateUpdate = { newSeason, newEpisode, newUrl, newTitle, newWatchSessionId ->
                                currentSeasonNumber = newSeason
                                currentEpisodeNumber = newEpisode
                                currentUrl = newUrl
                                currentTitle = newTitle
                                currentWatchSessionId = newWatchSessionId
                                currentPosition = 0L
                                duration = 0L
                                sliderPosition = 0f
                                showNextEpisodeOverlay = false
                                nextEpisodeCancelled = false
                                completionDetected = false
                                isResolvingNextEpisode = false
                                nextEpisodeInfo = null
                                hasMarkedWatched = false
                            },
                            onResolvingChange = { isResolvingNextEpisode = it },
                            onFailed = {
                                isResolvingNextEpisode = false
                                showNextEpisodeOverlay = false
                            },
                            traktScrobbler = if (canScrobble) traktScrobbler else null,
                            traktAccessToken = traktAccessToken,
                            tmdbId = tmdbId,
                        )
                    }
                },
                onCancel = {
                    showNextEpisodeOverlay = false
                    nextEpisodeCancelled = true
                },
            )
        }

        if (showResumePrompt) {
            RegisterFocusRegion(focusCoordinator, PlaybackFocusRegion.ResumePromptOverlay) { true }
            val resumeTarget = if (duration > 0L) {
                resumePromptInitialPositionMs.coerceAtMost(duration)
            } else {
                resumePromptInitialPositionMs
            }
            if (isTv) {
                TvResumePlaybackOverlay(
                    title = currentTitle.ifBlank { stringResource(R.string.player_resume_title) },
                    resumeFromMs = resumeTarget,
                    onResume = {
                        pendingStartPositionMs = resumeTarget
                        initialStartPositionConsumed = true
                        showResumePrompt = false
                        showControls = false
                    },
                    onStartOver = {
                        pendingStartPositionMs = 0L
                        initialStartPositionConsumed = true
                        showResumePrompt = false
                        showControls = false
                    },
                )
            } else {
                AlertDialog(
                    onDismissRequest = {
                        pendingStartPositionMs = 0L
                        initialStartPositionConsumed = true
                        showResumePrompt = false
                    },
                    title = { Text(stringResource(R.string.player_resume_title)) },
                    text = { Text(stringResource(R.string.player_resume_message, formatTime(resumeTarget))) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingStartPositionMs = resumeTarget
                                initialStartPositionConsumed = true
                                showResumePrompt = false
                            },
                        ) {
                            Text(stringResource(R.string.player_resume))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                pendingStartPositionMs = 0L
                                initialStartPositionConsumed = true
                                showResumePrompt = false
                            },
                        ) {
                            Text(stringResource(R.string.player_start_over))
                        }
                    },
                )
            }
        }

        val tvModalOverlayOpen = isTv && (
            showTrackDialog ||
                showAudioDelayDialog ||
                showSubtitleDelayDialog ||
                showPictureFormatPicker ||
                showEqualizerSheet ||
                showResumePrompt ||
                showDevicePicker ||
                showSubtitleSearch
            )

        if (isTv && tvSeekFeedbackVisible && !tvModalOverlayOpen) {
            TvSeekFeedbackOverlay(
                deltaMs = tvSeekFeedbackDeltaMs,
                currentPositionMs = tvSeekFeedbackCurrentMs,
                targetPositionMs = tvSeekFeedbackTargetMs,
                durationMs = duration,
            )
        }

        // Controls overlay
        if (showControls && !tvModalOverlayOpen && isTv) {
            // Region-local requesters — scoped to this conditional block.
            // They live and die with the controls overlay; no cross-tree references.
            val topMenuRequesters = remember(visibleTopMenuTargets) {
                visibleTopMenuTargets.associateWith { FocusRequester() }
            }
            val playButtonFocusRequester = remember { FocusRequester() }
            val rewindButtonFocusRequester = remember { FocusRequester() }
            val forwardButtonFocusRequester = remember { FocusRequester() }
            val timelineFocusRequester = remember { FocusRequester() }

            // Build region-local modifier for top menu items: left/right cycle
            // within the region, no cross-region focusProperties references.
            fun topMenuItemModifier(target: TopMenuFocusTarget): Modifier {
                val requester = topMenuRequesters[target] ?: return Modifier
                val leftTarget = topMenuNeighbor(target, -1)
                val rightTarget = topMenuNeighbor(target, 1)
                val leftRequester = topMenuRequesters[leftTarget]
                val rightRequester = topMenuRequesters[rightTarget]
                return Modifier
                    .focusRequester(requester)
                    .focusProperties {
                        if (leftRequester != null) left = leftRequester
                        if (rightRequester != null) right = rightRequester
                        // down navigates to transport controls via coordinator, not a raw requester
                        down = playButtonFocusRequester
                    }
            }

            // Register TopActions region with the coordinator
            RegisterFocusRegion(
                coordinator = focusCoordinator,
                region = PlaybackFocusRegion.TopActions,
                requestFocus = { preferredItemKey ->
                    val preferredTarget = preferredItemKey?.let {
                        runCatching { TopMenuFocusTarget.valueOf(it) }.getOrNull()
                    }
                    val targets = (
                        listOfNotNull(preferredTarget, lastTopMenuFocusTarget, TopMenuFocusTarget.BACK) +
                            visibleTopMenuTargets
                        ).distinct()
                    for (target in targets) {
                        val requester = topMenuRequesters[target] ?: continue
                        val ok = runCatching { requester.requestFocus(); true }.getOrDefault(false)
                        if (ok) {
                            lastTopMenuFocusTarget = target
                            return@RegisterFocusRegion true
                        }
                    }
                    false
                },
            )

            // Register TransportControls region
            RegisterFocusRegion(
                coordinator = focusCoordinator,
                region = PlaybackFocusRegion.TransportControls,
                requestFocus = {
                    runCatching { playButtonFocusRequester.requestFocus(); true }.getOrDefault(false)
                },
            )

            // Register Timeline region
            RegisterFocusRegion(
                coordinator = focusCoordinator,
                region = PlaybackFocusRegion.Timeline,
                requestFocus = {
                    runCatching { timelineFocusRequester.requestFocus(); true }.getOrDefault(false)
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
            ) {
                // Top bar: back + title + settings
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusableIconButton(
                        onClick = ::requestExitPlayer,
                        modifier = topMenuItemModifier(TopMenuFocusTarget.BACK),
                        onFocused = {
                            lastTopMenuFocusTarget = TopMenuFocusTarget.BACK
                            focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.BACK.name)
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    if (currentTitle.isNotBlank() || (seasonNumber != null && episodeNumber != null)) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (currentTitle.isNotBlank()) {
                                Text(
                                    text = currentTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (seasonNumber != null && episodeNumber != null) {
                                Text(
                                    text = buildString {
                                        append("S${seasonNumber.toString().padStart(2, '0')}")
                                        append("E${episodeNumber.toString().padStart(2, '0')}")
                                        if (episodeName.isNotBlank()) append(" · $episodeName")
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = com.torve.android.ui.theme.Amber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    // Cast button
                    if (castAvailable) {
                        FocusableIconButton(
                            onClick = {
                                if (currentUrl.isNotBlank()) {
                                    castService.requestCast(
                                        url = currentUrl,
                                        title = currentTitle,
                                        posterUrl = currentPosterUrl.ifBlank { null },
                                    )
                                }
                                castService.showCastDialog()
                            },
                            modifier = topMenuItemModifier(TopMenuFocusTarget.CAST),
                            onFocused = {
                                lastTopMenuFocusTarget = TopMenuFocusTarget.CAST
                                focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.CAST.name)
                            },
                        ) {
                            Icon(
                                if (castService.isCasting) Icons.Default.CastConnected else Icons.Default.Cast,
                                contentDescription = stringResource(R.string.player_cast_cd),
                                tint = if (castService.isCasting) com.torve.android.ui.theme.Amber else Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // Hide handoff (TV icon) and mic buttons on TV — not useful on leanback devices.
                    val isTvDevice = remember {
                        context.packageManager.hasSystemFeature("android.software.leanback")
                    }
                    if (!isTvDevice) {
                        FocusableIconButton(
                            onClick = {
                                when {
                                    !syncState.isAuthenticated -> {
                                        Toast.makeText(context, context.getString(R.string.player_create_profile_first), Toast.LENGTH_SHORT).show()
                                    }
                                    handoffTargets.isEmpty() -> {
                                        syncCoordinator.refreshDevices()
                                        Toast.makeText(context, context.getString(R.string.player_no_paired_tv), Toast.LENGTH_SHORT).show()
                                    }
                                    else -> showDevicePicker = true
                                }
                            },
                            modifier = topMenuItemModifier(TopMenuFocusTarget.HANDOFF),
                            onFocused = {
                                lastTopMenuFocusTarget = TopMenuFocusTarget.HANDOFF
                                focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.HANDOFF.name)
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = stringResource(R.string.player_play_on_device_cd),
                                tint = if (syncState.isAuthenticated) com.torve.android.ui.theme.Amber else Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    if (!isTvDevice) {
                        FocusableIconButton(
                            onClick = {
                                if (
                                    voiceController.uiState.value.phase == VoiceInputPhase.Error ||
                                    voiceController.uiState.value.phase == VoiceInputPhase.Unsupported
                                ) {
                                    voiceController.clearState()
                                }
                                voiceController.launch()
                            },
                            modifier = topMenuItemModifier(TopMenuFocusTarget.VOICE),
                            onFocused = {
                                lastTopMenuFocusTarget = TopMenuFocusTarget.VOICE
                                focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.VOICE.name)
                            },
                        ) {
                            val voiceTint = when (voiceController.uiState.value.phase) {
                                VoiceInputPhase.Listening,
                                VoiceInputPhase.Processing,
                                -> com.torve.android.ui.theme.Amber

                                VoiceInputPhase.Error,
                                VoiceInputPhase.Unsupported,
                                -> Color(0xFFFFB8B8)

                                VoiceInputPhase.Idle -> Color.White
                            }
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = stringResource(R.string.player_voice_cd),
                                tint = voiceTint,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // Subtitle track button
                    FocusableIconButton(
                        onClick = { trackDialogSubtitlesOnly = true; showTrackDialog = true },
                        modifier = topMenuItemModifier(TopMenuFocusTarget.SUBTITLE_TRACKS),
                        onFocused = {
                            lastTopMenuFocusTarget = TopMenuFocusTarget.SUBTITLE_TRACKS
                            focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.SUBTITLE_TRACKS.name)
                        },
                    ) {
                        Icon(
                            Icons.Default.ClosedCaption,
                            contentDescription = stringResource(R.string.player_track_selection),
                            tint = if (subtitleTracks.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // Audio track button
                    FocusableIconButton(
                        onClick = { trackDialogSubtitlesOnly = false; showTrackDialog = true },
                        modifier = topMenuItemModifier(TopMenuFocusTarget.AUDIO_TRACKS),
                        onFocused = {
                            lastTopMenuFocusTarget = TopMenuFocusTarget.AUDIO_TRACKS
                            focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.AUDIO_TRACKS.name)
                        },
                    ) {
                        Icon(
                            Icons.Default.Headphones,
                            contentDescription = stringResource(R.string.player_track_selection),
                            tint = if (audioTracks.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    FocusableIconButton(
                        onClick = { showAudioDelayDialog = true },
                        modifier = topMenuItemModifier(TopMenuFocusTarget.AUDIO_DELAY),
                        onFocused = {
                            lastTopMenuFocusTarget = TopMenuFocusTarget.AUDIO_DELAY
                            focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.AUDIO_DELAY.name)
                        },
                    ) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = stringResource(R.string.player_audio_delay_cd),
                            tint = if (audioDelayMs != 0) com.torve.android.ui.theme.Amber else Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    if (audioEqualizer != null) {
                        FocusableIconButton(
                            onClick = {
                                showEqualizerSheet = !showEqualizerSheet
                                showControls = true
                            },
                            modifier = topMenuItemModifier(TopMenuFocusTarget.EQUALIZER),
                            onFocused = {
                                lastTopMenuFocusTarget = TopMenuFocusTarget.EQUALIZER
                                focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.EQUALIZER.name)
                            },
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = stringResource(R.string.player_equalizer_cd),
                                tint = if (audioEqualizer?.enabled == true) com.torve.android.ui.theme.Amber else Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    if (isTv) {
                        FocusableIconButton(
                            onClick = {
                                showPictureFormatPicker = true
                                showControls = true
                            },
                            modifier = topMenuItemModifier(TopMenuFocusTarget.PICTURE_FORMAT),
                            onFocused = {
                                lastTopMenuFocusTarget = TopMenuFocusTarget.PICTURE_FORMAT
                                focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.PICTURE_FORMAT.name)
                            },
                        ) {
                            Icon(
                                Icons.Default.AspectRatio,
                                contentDescription = "Picture format",
                                tint = if (pictureFormat == PlayerPictureFormat.SOURCE) Color.White else com.torve.android.ui.theme.Amber,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // Playback speed
                    FocusableIconButton(
                        onClick = {
                            val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                            val currentIndex = speeds.indexOf(playbackSpeed).takeIf { it >= 0 } ?: 2
                            val nextIndex = (currentIndex + 1) % speeds.size
                            playbackSpeed = speeds[nextIndex]
                            engine.setSpeed(playbackSpeed)
                            showControls = true
                        },
                        modifier = topMenuItemModifier(TopMenuFocusTarget.SPEED),
                        onFocused = {
                            lastTopMenuFocusTarget = TopMenuFocusTarget.SPEED
                            focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TopActions, TopMenuFocusTarget.SPEED.name)
                        },
                    ) {
                        Text(
                            text = "${playbackSpeed}x",
                            color = if (playbackSpeed != 1.0f) com.torve.android.ui.theme.Amber else Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }

                val voiceOverlayMessage = when (voiceController.uiState.value.phase) {
                    VoiceInputPhase.Listening -> "Listening"
                    VoiceInputPhase.Processing -> "Processing voice input"
                    VoiceInputPhase.Error,
                    VoiceInputPhase.Unsupported,
                    -> voiceController.uiState.value.message ?: voiceInputUnavailableFallback

                    VoiceInputPhase.Idle -> voiceFeedbackMessage
                }
                if (!voiceOverlayMessage.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 68.dp)
                            .background(Color(0xC0121B2B), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = voiceOverlayMessage,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                // Center controls: rewind / play / forward
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusableIconButton(
                        onClick = {
                            if (isTv) {
                                handleTvTransportSeek(-1, hideControls = false)
                                runCatching { rewindButtonFocusRequester.requestFocus() }
                            } else {
                                seekBy(-10_000)
                            }
                        },
                        modifier = Modifier
                            .focusRequester(rewindButtonFocusRequester)
                            .focusProperties { down = timelineFocusRequester }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    controlsInteractionTick++
                                    focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TransportControls)
                                }
                            },
                    ) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = stringResource(R.string.player_rewind),
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                    }

                    Spacer(Modifier.width(24.dp))

                    FocusableIconButton(
                        onClick = togglePlayback,
                        modifier = Modifier
                            .focusRequester(playButtonFocusRequester)
                            .focusProperties {
                                up = topMenuRequesters[lastTopMenuFocusTarget]
                                    ?: topMenuRequesters[TopMenuFocusTarget.BACK]
                                    ?: FocusRequester.Default
                                down = timelineFocusRequester
                            }
                            .onFocusChanged {
                                if (it.isFocused) focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TransportControls)
                            },
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.common_pause) else stringResource(R.string.common_play),
                            tint = Color.White,
                            modifier = Modifier.size(56.dp),
                        )
                    }

                    Spacer(Modifier.width(24.dp))

                    FocusableIconButton(
                        onClick = {
                            if (isTv) {
                                handleTvTransportSeek(1, hideControls = false)
                                runCatching { forwardButtonFocusRequester.requestFocus() }
                            } else {
                                seekBy(10_000)
                            }
                        },
                        modifier = Modifier
                            .focusRequester(forwardButtonFocusRequester)
                            .focusProperties { down = timelineFocusRequester }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    controlsInteractionTick++
                                    focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.TransportControls)
                                }
                            },
                    ) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = stringResource(R.string.player_forward),
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }

                // Bottom seekbar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    val seekPreviewPositionMs = if (isSeeking && duration > 0L) {
                        (sliderPosition * duration).toLong().coerceIn(0L, duration)
                    } else {
                        currentPosition
                    }
                    var isSliderFocused by remember { mutableStateOf(false) }
                    Slider(
                        value = sliderPosition,
                        onValueChange = {
                            isSeeking = true
                            sliderPosition = it
                        },
                        onValueChangeFinished = {
                            val target = (sliderPosition * duration).toLong()
                            val delta = target - currentPosition
                            performSeekTo(
                                targetMs = target,
                                userInitiated = true,
                                sourceDeltaMs = delta,
                                showTvFeedback = false,
                            )
                            isSeeking = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(timelineFocusRequester)
                            .focusProperties { up = playButtonFocusRequester }
                            .onFocusChanged { fs ->
                                isSliderFocused = fs.isFocused
                                if (fs.isFocused) {
                                    focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.Timeline)
                                    controlsInteractionTick++
                                } else if (isSeeking) {
                                    // Commit seek when focus leaves the slider
                                    val target = (sliderPosition * duration).toLong()
                                    val delta = target - currentPosition
                                    performSeekTo(target, userInitiated = true, sourceDeltaMs = delta, showTvFeedback = false)
                                    isSeeking = false
                                }
                            }
                            .onPreviewKeyEvent { e ->
                                if (!isTv || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                if (focusCoordinator.currentRegion != PlaybackFocusRegion.Timeline) return@onPreviewKeyEvent false
                                val stepFraction = if (duration > 0L) 10_000f / duration.toFloat() else 0.01f
                                when (e.key) {
                                    Key.DirectionLeft -> {
                                        sliderPosition = (sliderPosition - stepFraction).coerceAtLeast(0f)
                                        isSeeking = true
                                        controlsInteractionTick++
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        sliderPosition = (sliderPosition + stepFraction).coerceAtMost(1f)
                                        isSeeking = true
                                        controlsInteractionTick++
                                        true
                                    }
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        if (isSeeking) {
                                            val target = (sliderPosition * duration).toLong()
                                            val delta = target - currentPosition
                                            performSeekTo(target, userInitiated = true, sourceDeltaMs = delta, showTvFeedback = false)
                                            isSeeking = false
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                    if (duration > 0L && skipSegments.isNotEmpty()) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp),
                        ) {
                            skipSegments.forEach { segment ->
                                val startFraction = (segment.startMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                val endFraction = (segment.endMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                val segmentWidthFraction = (endFraction - startFraction).coerceAtLeast(0.004f)
                                Box(
                                    modifier = Modifier
                                        .padding(start = maxWidth * startFraction)
                                        .width((maxWidth * segmentWidthFraction).coerceAtLeast(2.dp))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0xB3E8A838)),
                                )
                            }
                        }
                    }
                    // Slider focus indicator
                    if (isTv && isSliderFocused) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(com.torve.android.ui.theme.Amber),
                        )
                    }
                    if (isSeeking) {
                        Text(
                            text = formatTime(seekPreviewPositionMs),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = com.torve.android.ui.theme.Amber,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = formatTime(currentPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = formatTime(duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        if (showControls && !isTv) {
            val seekPreviewPositionMs = if (isSeeking && duration > 0L) {
                (sliderPosition * duration).toLong().coerceIn(0L, duration)
            } else {
                currentPosition
            }
            val showGuideAction = isLiveChannelPlayback && livePlaybackContext.currentChannel != null
            val showChannelListAction = false
            val showFavoriteAction = isLiveChannelPlayback && livePlaybackContext.currentChannel != null
            val showPlaybackTransport = !isLiveChannelPlayback || activeReplayProgramme != null
            val availableMobileSettings = supportedMobilePlaybackSettings(
                isLivePlayback = isLiveChannelPlayback,
                supportsLiveBufferControl = supportsLiveBufferControl,
            )
            val voiceOverlayMessage = when (voiceController.uiState.value.phase) {
                VoiceInputPhase.Listening -> "Listening"
                VoiceInputPhase.Processing -> "Processing voice input"
                VoiceInputPhase.Error,
                VoiceInputPhase.Unsupported,
                -> voiceController.uiState.value.message ?: voiceInputUnavailableFallback

                VoiceInputPhase.Idle -> voiceFeedbackMessage
            }

            MobilePlayerControlsOverlay(
                title = currentTitle,
                badgeUrl = livePlaybackContext.currentChannel?.tvgLogo ?: currentPosterUrl.ifBlank { null },
                subtitle = buildPlayerProgrammeLine(
                    currentProgramme = displayedCurrentProgramme,
                    nextProgramme = displayedNextProgramme,
                ),
                isLivePlayback = isLiveChannelPlayback && activeReplayProgramme == null,
                isFavorite = livePlaybackContext.isFavorite,
                pictureFormatLabel = pictureFormat.shortLabel,
                showPictureFormatAction = true,
                showOrientationAction = enablePhoneAutoRotation,
                showGuideAction = showGuideAction,
                showChannelListAction = showChannelListAction,
                castAvailable = castAvailable,
                isCasting = castService.isCasting,
                showSubtitleAction = subtitleTracks.isNotEmpty(),
                showAudioAction = audioTracks.isNotEmpty(),
                showFavoriteAction = showFavoriteAction,
                showSettingsAction = availableMobileSettings.isNotEmpty(),
                showPlaybackTransport = showPlaybackTransport,
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                sliderPosition = sliderPosition,
                isSeeking = isSeeking,
                seekPreviewPositionMs = seekPreviewPositionMs,
                skipSegments = skipSegments,
                voiceOverlayMessage = voiceOverlayMessage,
                onBack = onBack,
                onOpenPictureFormat = { showMobileSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.PICTURE_FORMAT)) },
                onRotateOrientation = { rotatePlayerOrientation() },
                onOpenGuide = {
                    val targetSheet = if (livePlaybackContext.currentChannel != null) {
                        MobilePlaybackSheet.CurrentChannelGuide
                    } else {
                        MobilePlaybackSheet.Guide
                    }
                    showMobileSheet(targetSheet)
                },
                onOpenChannelList = { showMobileSheet(MobilePlaybackSheet.ChannelList) },
                onOpenMenu = { showMobileSheet(MobilePlaybackSheet.QuickActions) },
                onToggleFavorite = {
                    livePlaybackContext.currentChannel?.let(channelsViewModel::toggleFavorite)
                },
                onTogglePlayback = togglePlayback,
                onSeekBack = { seekBy(-10_000L) },
                onSeekForward = { seekBy(10_000L) },
                onOpenCast = {
                    if (currentUrl.isNotBlank()) {
                        castService.requestCast(
                            url = currentUrl,
                            title = currentTitle,
                            posterUrl = currentPosterUrl.ifBlank { null },
                        )
                    }
                    castService.showCastDialog()
                },
                onOpenSubtitlePicker = { showMobileSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.SUBTITLE_TRACK)) },
                onOpenAudioPicker = { showMobileSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.AUDIO_TRACK)) },
                onSliderValueChange = {
                    isSeeking = true
                    sliderPosition = it
                },
                onSliderValueChangeFinished = {
                    val target = (sliderPosition * duration).toLong()
                    val delta = target - currentPosition
                    performSeekTo(
                        targetMs = target,
                        userInitiated = true,
                        sourceDeltaMs = delta,
                        showTvFeedback = false,
                    )
                    isSeeking = false
                },
            )
        }

        if (!isTv && mobileActiveSheet != null) {
            MobilePlaybackSheetHost(
                activeSheet = mobileActiveSheet,
                canNavigateBack = mobileSheetStack.size > 1,
                sheetLoading = mobileSheetLoading,
                sheetError = mobileSheetError,
                isLivePlayback = isLiveChannelPlayback,
                isFavorite = livePlaybackContext.isFavorite,
                currentTitle = currentTitle,
                currentChannel = livePlaybackContext.currentChannel,
                currentProgramme = displayedCurrentProgramme,
                nextProgramme = displayedNextProgramme,
                playbackRuntimeInfo = playbackRuntimeInfo,
                subtitleTracks = subtitleTracks,
                audioTracks = audioTracks,
                audioDelayMs = audioDelayMs,
                playbackSpeed = playbackSpeed,
                pictureFormat = pictureFormat,
                liveBufferDurationMs = liveBufferDurationMs,
                liveAudioMode = channelsState.liveAudioOutputMode,
                availableSettings = supportedMobilePlaybackSettings(
                    isLivePlayback = isLiveChannelPlayback,
                    supportsLiveBufferControl = supportsLiveBufferControl,
                ),
                categoryChannels = mobileCategoryChannels,
                guideProgrammes = mobileGuideProgrammes,
                showEqualizerAction = audioEqualizer != null,
                handoffAvailable = handoffTargets.isNotEmpty() || !syncState.isAuthenticated,
                voiceState = voiceController.uiState.value.phase,
                onDismiss = {
                    mobileSheetStack = mobileSheetStack.dropLast(1)
                    showControls = true
                },
                onNavigateBack = { popMobileSheet() },
                onPushSheet = { pushMobileSheet(it) },
                onToggleFavorite = {
                    livePlaybackContext.currentChannel?.let(channelsViewModel::toggleFavorite)
                    mobileSheetStack = emptyList()
                },
                onOpenDevicePicker = {
                    when {
                        !syncState.isAuthenticated -> {
                            Toast.makeText(context, context.getString(R.string.player_create_profile_first), Toast.LENGTH_SHORT).show()
                        }
                        handoffTargets.isEmpty() -> {
                            syncCoordinator.refreshDevices()
                            Toast.makeText(context, context.getString(R.string.player_no_paired_tv), Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            showDevicePicker = true
                            mobileSheetStack = emptyList()
                        }
                    }
                },
                onLaunchVoice = {
                    if (
                        voiceController.uiState.value.phase == VoiceInputPhase.Error ||
                        voiceController.uiState.value.phase == VoiceInputPhase.Unsupported
                    ) {
                        voiceController.clearState()
                    }
                    voiceController.launch()
                    mobileSheetStack = emptyList()
                },
                onOpenEqualizer = {
                    showEqualizerSheet = true
                    mobileSheetStack = emptyList()
                },
                onSelectSubtitle = { track ->
                    if (track == null) {
                        engine.disableSubtitles()
                        subtitlesPreferredEnabled = false
                        preferredSubtitleTrackTag = null
                    } else {
                        engine.selectSubtitleTrack(track.id)
                        subtitlesPreferredEnabled = true
                        preferredSubtitleTrackTag = trackPreferenceTag(track)
                    }
                    mobileSheetStack = mobileSheetStack.dropLast(1)
                },
                onSelectAudio = { track ->
                    engine.selectAudioTrack(track.id)
                    preferredAudioTrackTag = trackPreferenceTag(track)
                    mobileSheetStack = mobileSheetStack.dropLast(1)
                },
                onApplyAudioDelay = { delayMs ->
                    audioDelayMs = delayMs
                    engine.setAudioDelay(delayMs)
                    mobileSheetStack = mobileSheetStack.dropLast(1)
                },
                onApplyPlaybackSpeed = { speed ->
                    playbackSpeed = speed
                    engine.setSpeed(speed)
                    mobileSheetStack = mobileSheetStack.dropLast(1)
                },
                onApplyPictureFormat = { selected ->
                    pictureFormat = selected
                    mobileSheetStack = mobileSheetStack.dropLast(1)
                },
                onApplyLiveAudioMode = { mode ->
                    channelsViewModel.setLiveAudioOutputMode(mode)
                    mobileSheetStack = mobileSheetStack.dropLast(1)
                },
                onApplyLiveBuffer = { durationMs ->
                    liveBufferDurationMs = durationMs
                    (engine as? ExoPlayerEngine)?.setLiveBufferSize(durationMs)
                    mobileSheetStack = mobileSheetStack.dropLast(1)
                },
                onPlayChannel = { channel ->
                    switchToLiveChannel(channel)
                },
                canReplayProgramme = { channel, programme ->
                    canReplayProgramme(channel, programme)
                },
                onReplayProgramme = { channel, programme ->
                    replayProgramme(channel, programme)
                },
                onToggleChannelFavorite = { channel ->
                    channelsViewModel.toggleFavorite(channel)
                },
            )
        }
    }

    if (showDevicePicker) {
        RegisterFocusRegion(focusCoordinator, PlaybackFocusRegion.DevicePickerOverlay) { true }
        SyncDevicePickerDialog(
            title = stringResource(R.string.player_play_on_device_title),
            devices = handoffTargets,
            onSelectDevice = { device ->
                showDevicePicker = false
                topMenuFocusTick++
                if (handoffContentId.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.player_cannot_handoff), Toast.LENGTH_SHORT).show()
                    return@SyncDevicePickerDialog
                }
                scope.launch {
                    val result = syncCoordinator.sendPlaybackIntent(
                        targetDeviceId = device.id,
                        contentId = handoffContentId,
                        providerTarget = "torve",
                        positionMs = currentPosition,
                        mediaType = mediaType,
                    )
                    if (result.isSuccess) {
                        Toast.makeText(context, context.getString(R.string.player_sent_to, device.deviceName), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            context,
                            result.exceptionOrNull()?.message ?: "Failed to transfer playback",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = {
                showDevicePicker = false
                topMenuFocusTick++
            },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerVideoSurface(
    useMpv: Boolean,
    engine: PlayerEngine,
    pictureFormat: PlayerPictureFormat,
    onMpvViewCreated: (MPVView) -> Unit,
    onMpvViewReleased: (MPVView) -> Unit,
    onExoPlayerViewCreated: (TorvePlayerView) -> Unit,
    onExoPlayerViewReleased: (TorvePlayerView) -> Unit,
) {
    if (useMpv) {
        val mpvBindingToken = remember { System.identityHashCode(engine) }
        AndroidView(
            factory = { ctx ->
                MPVView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    onMpvViewCreated(this)
                }
            },
            update = { view ->
                view.bindSurface(mpvBindingToken, "mobile_compose_update")
            },
            onRelease = { view ->
                view.releaseSurface("mobile_compose_release")
                onMpvViewReleased(view)
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        AndroidView(
            factory = { ctx ->
                TorvePlayerView(ctx).apply {
                    useController = false
                    resizeMode = pictureFormat.exoResizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    onExoPlayerViewCreated(this)
                }
            },
            update = { view ->
                view.useController = false
                view.resizeMode = pictureFormat.exoResizeMode
                view.setPlayerSafely((engine as? ExoPlayerEngine)?.getExoPlayer(), "mobile_player_update")
                onExoPlayerViewCreated(view)
            },
            onRelease = { view ->
                view.clearPlayerSafely("mobile_player_release")
                onExoPlayerViewReleased(view)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** IconButton wrapper that shows an Amber border when focused (for D-pad / TV navigation). */
@Composable
private fun FocusableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            reduceMotion -> 1f
            isPressed -> 0.9f
            focused -> 1.15f
            else -> 1f
        },
        label = "iconBtnScale",
    )
    val bgAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            isPressed -> 0.3f
            focused -> 0.15f
            else -> 0f
        },
        label = "iconBtnBg",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = bgAlpha), RoundedCornerShape(8.dp))
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) com.torve.android.ui.theme.Amber else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused?.invoke()
                }
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // custom feedback via scale + background
                onClick = onClick,
            )
            // 12dp padding ensures 24dp icon + 24dp padding = 48dp minimum touch target
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private data class PlayerStartupSelection(
    val snapshot: StartupCandidatesSnapshot,
    val startupCandidates: List<ParsedStream>,
    val autoplayCandidates: List<ParsedStream>,
) {
    val autoplayKeys: Set<String>
        get() = autoplayCandidates.mapTo(linkedSetOf()) { playerStreamKey(it) }
}

private suspend fun loadStartupPlaybackSelection(
    type: MediaType,
    imdbId: String,
    tmdbId: Int? = null,
    contentTitle: String? = null,
    season: Int?,
    episode: Int?,
    streamRepo: StreamRepository,
    streamSelector: StreamSelector,
    addons: List<com.torve.domain.model.InstalledAddon>,
    debridAccounts: Map<com.torve.domain.model.DebridServiceType, String>,
    preferences: com.torve.domain.model.StreamPreferences,
    deviceCaps: com.torve.domain.model.DeviceCodecCaps,
): PlayerStartupSelection {
    val request = SourceAccelerationRequest(
        mediaType = type,
        imdbId = imdbId,
        contentId = tmdbId?.let { "tmdb:$it" },
        title = contentTitle,
        seasonNumber = season,
        episodeNumber = episode,
        context = SourceAccelerationContext(
            addons = addons,
            debridAccounts = debridAccounts,
            preferences = preferences,
            startupFetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
        ),
    )
    val snapshot = runCatching {
        streamRepo.getWarmStartupCandidates(request)
            ?: streamRepo.getStartupCandidates(request)
    }.getOrDefault(
        StartupCandidatesSnapshot(
            request = request,
            readinessState = com.torve.domain.model.ReadinessState.EMPTY,
            candidates = emptyList(),
        ),
    )
    val startupStreams = runCatching {
        streamRepo.fetchStreams(
            type = type,
            imdbId = imdbId,
            contentId = request.resolvedContentId,
            title = request.title,
            season = season,
            episode = episode,
            addons = addons,
            debridAccounts = debridAccounts,
            preferences = preferences,
            fetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
        )
    }.getOrDefault(emptyList())
    if (startupStreams.isEmpty()) {
        return PlayerStartupSelection(
            snapshot = snapshot,
            startupCandidates = emptyList(),
            autoplayCandidates = emptyList(),
        )
    }

    val rankedStartup = streamSelector.rankPlayableVariants(
        streams = startupStreams,
        preferences = preferences,
        deviceCaps = deviceCaps,
    )
    val highConfidenceKeys = StartupPlaybackPolicy.highConfidenceCandidateKeys(snapshot.candidates)
    val autoplayCandidates = if (highConfidenceKeys.isEmpty()) {
        emptyList()
    } else {
        rankedStartup.filter { playerStreamKey(it) in highConfidenceKeys }
    }
    return PlayerStartupSelection(
        snapshot = snapshot,
        startupCandidates = rankedStartup,
        autoplayCandidates = autoplayCandidates,
    )
}

private suspend fun List<ParsedStream>.firstResolvedOrNull(
    streamRepo: StreamRepository,
    provider: com.torve.domain.model.DebridServiceType?,
    apiKey: String,
    timeoutMs: Long,
    contextLabel: String,
    eventLabel: String,
    onCandidateUsed: (ParsedStream) -> Unit = {},
): com.torve.domain.model.ResolvedStream? {
    for (candidate in this) {
        val key = playerStreamKey(candidate)
        val hostKey = StreamRuntimeTelemetry.keyForStream(candidate)
        StreamRuntimeTelemetry.recordPlayAttempt(hostKey)
        val resolved = try {
            withTimeoutOrNull(timeoutMs) {
                streamRepo.resolveStream(candidate, provider, apiKey)
            }
        } catch (_: Exception) {
            null
        }
        if (resolved != null && candidate.isAddonHostedUrl()) {
            // Next-episode auto-resolve can't host the preparing overlay;
            // skip any candidate that isn't serving right now rather than
            // waiting on the cloud client.
            val readiness = streamRepo.probeStreamReadiness(resolved.url.orEmpty())
            if (readiness !is com.torve.domain.repository.StreamReadiness.Ready) {
                android.util.Log.i(
                    "Player",
                    "${eventLabel}_skip context=$contextLabel key=$key reason=${readiness::class.simpleName}",
                )
                continue
            }
        }
        if (resolved == null) {
            android.util.Log.w(
                "Player",
                "${eventLabel}_failed context=$contextLabel key=$key reason=resolve_failed",
            )
            StreamRuntimeTelemetry.recordStartupTimeout(hostKey, timeoutMs)
            streamRepo.reportPlaybackOutcome(candidate, provider, success = false)
            continue
        }
        val nextUrl = resolved.transcodeUrls?.mp4 ?: resolved.transcodeUrls?.hls ?: resolved.url
        if (nextUrl.isBlank()) {
            android.util.Log.w(
                "Player",
                "${eventLabel}_failed context=$contextLabel key=$key reason=blank_url",
            )
            streamRepo.reportPlaybackOutcome(candidate, provider, success = false)
            continue
        }
        onCandidateUsed(candidate)
        return resolved
    }
    return null
}

private fun playerStreamKey(stream: ParsedStream): String {
    return stream.accelerationMemoryId
        ?: stream.accelerationSourceKey
        ?: stream.directUrl
        ?: stream.magnetUrl
        ?: stream.infoHash
        ?: "${stream.addonName}:${stream.title}"
}

private suspend fun resolveAndPlayNextEpisode(
    nextEpisodeInfo: NextEpisodeInfo?,
    showTmdbId: Int?,
    showImdbId: String?,
    seriesTitle: String,
    engine: PlayerEngine,
    streamRepo: StreamRepository,
    streamSelector: StreamSelector,
    addonRepo: AddonRepository,
    settingsViewModel: SettingsViewModel,
    watchProgressRepo: WatchProgressRepository,
    watchSessionRecorder: WatchSessionRecorder,
    currentWatchSessionId: String?,
    mediaId: String,
    mediaType: String,
    posterUrl: String,
    backdropUrl: String,
    currentTitle: String,
    currentPosition: Long,
    duration: Long,
    currentSeasonNumber: Int?,
    currentEpisodeNumber: Int?,
    requestPlayback: (String) -> Unit,
    onStateUpdate: (newSeason: Int, newEpisode: Int, newUrl: String, newTitle: String, newWatchSessionId: String?) -> Unit,
    onResolvingChange: (Boolean) -> Unit,
    onFailed: () -> Unit,
    traktScrobbler: TraktScrobbler? = null,
    traktAccessToken: String = "",
    tmdbId: Int = 0,
) {
    val nextEp = nextEpisodeInfo ?: run { onFailed(); return }
    val imdbId = showImdbId ?: run { onFailed(); return }

    onResolvingChange(true)
    try {
        val preferences = settingsViewModel.buildStreamPreferences()
        val addons = try { addonRepo.getInstalledAddons() } catch (_: Exception) { emptyList() }
        val debridAccounts = settingsViewModel.getDebridAccounts()
        val apiKey = settingsViewModel.getDebridApiKey()
        // Nullable provider: addon-hosted streams resolve without a local key.
        val provider: DebridServiceType? =
            if (apiKey.isBlank()) null else settingsViewModel.getDebridProvider()
        val deviceCaps = DeviceCodecProbe.probe()
        val startupSelection = loadStartupPlaybackSelection(
            type = MediaType.SERIES,
            imdbId = imdbId,
            tmdbId = showTmdbId,
            contentTitle = seriesTitle,
            season = nextEp.seasonNumber,
            episode = nextEp.episodeNumber,
            streamRepo = streamRepo,
            streamSelector = streamSelector,
            addons = addons,
            debridAccounts = debridAccounts,
            preferences = preferences,
            deviceCaps = deviceCaps,
        )
        if (startupSelection.autoplayCandidates.isNotEmpty()) {
            android.util.Log.i(
                "Player",
                "startup_autoplay_candidates_available context=next_episode count=${startupSelection.autoplayCandidates.size}",
            )
        }

        var selected: ParsedStream? = null
        var resolved = startupSelection.autoplayCandidates.firstResolvedOrNull(
            streamRepo = streamRepo,
            provider = provider,
            apiKey = apiKey,
            timeoutMs = 45_000L,
            contextLabel = "next_episode",
            eventLabel = "startup_candidate",
            onCandidateUsed = { candidate ->
                selected = candidate
            },
        )

        if (resolved == null) {
            android.util.Log.i(
                "Player",
                "fallback_to_full_fetch context=next_episode startupCount=${startupSelection.autoplayCandidates.size}",
            )
            val fullStreams = streamRepo.fetchStreams(
                type = MediaType.SERIES,
                imdbId = imdbId,
                contentId = showTmdbId?.let { "tmdb:$it" },
                title = seriesTitle,
                season = nextEp.seasonNumber,
                episode = nextEp.episodeNumber,
                addons = addons,
                debridAccounts = debridAccounts,
                preferences = preferences,
                fetchPolicy = StreamFetchPolicy.FULL,
            )
            if (fullStreams.isEmpty()) {
                onFailed()
                return
            }
            val rankedBySelector = streamSelector.rankPlayableVariants(
                streams = fullStreams,
                preferences = preferences,
                deviceCaps = deviceCaps,
            )
            val ranked = StreamFallbackOrdering.streamsInTryOrder(
                streams = rankedBySelector,
                startupCandidates = startupSelection.snapshot.candidates,
                keyOf = ::playerStreamKey,
            )
            val fallbackSelected = ranked.firstOrNull() ?: run {
                onFailed()
                return
            }
            selected = fallbackSelected
            resolved = withTimeoutOrNull(90_000L) {
                streamRepo.resolveStream(fallbackSelected, provider, apiKey)
            }
            if (resolved == null) {
                streamRepo.reportPlaybackOutcome(fallbackSelected, provider, success = false)
                onFailed()
                return
            }
            android.util.Log.i(
                "Player",
                "full_fetch_winner_used context=next_episode key=${playerStreamKey(fallbackSelected)} host=${StreamRuntimeTelemetry.keyForStream(fallbackSelected)}",
            )
        }

        val selectedStream = selected ?: run {
            onFailed()
            return
        }
        val playUrl = resolved.transcodeUrls?.mp4
            ?: resolved.transcodeUrls?.hls
            ?: resolved.url

        // Save progress for the current episode before switching
        if (mediaId.isNotBlank() && duration > 0) {
            watchProgressRepo.saveProgress(
                WatchProgress(
                    mediaId = mediaId,
                    mediaType = MediaType.fromString(mediaType),
                    title = currentTitle,
                    posterUrl = posterUrl.takeIf { it.isNotBlank() },
                    backdropUrl = backdropUrl.takeIf { it.isNotBlank() },
                    positionMs = currentPosition,
                    durationMs = duration,
                    seasonNumber = currentSeasonNumber,
                    episodeNumber = currentEpisodeNumber,
                ),
            )
        }

        // Build new title
        val sNum = nextEp.seasonNumber.toString().padStart(2, '0')
        val eNum = nextEp.episodeNumber.toString().padStart(2, '0')
        val newTitle = if (nextEp.episodeName.isNotBlank()) {
            "S${sNum}E${eNum} - ${nextEp.episodeName}"
        } else {
            "S${sNum}E${eNum}"
        }
        val rolloverAt = System.currentTimeMillis()

        // Scrobble stop for current episode before switching
        if (traktScrobbler != null && traktAccessToken.isNotBlank() && tmdbId > 0) {
            try {
                traktScrobbler.stop(
                    traktAccessToken, tmdbId, MediaType.SERIES, 100.0,
                    season = currentSeasonNumber, episode = currentEpisodeNumber,
                )
            } catch (_: Exception) {}
        }
        runCatching {
            watchSessionRecorder.finishPlayerSession(
                sessionId = currentWatchSessionId,
                positionMs = currentPosition,
                durationMs = duration.takeIf { it > 0L },
                endedAt = rolloverAt,
            )
        }
        val nextShowId = showTmdbId?.takeIf { it > 0 }?.toString()
            ?: imdbId.takeIf { it.isNotBlank() }
            ?: mediaId.takeIf { it.isNotBlank() }
        val nextWatchSessionId = nextShowId?.let { showId ->
            runCatching {
                watchSessionRecorder.startPlayerSession(
                    identity = WatchSessionMediaIdentity(
                        mediaId = showId,
                        mediaType = MediaType.SERIES,
                        title = newTitle,
                        showId = showId,
                        showTitle = seriesTitle,
                        seasonNumber = nextEp.seasonNumber,
                        episodeNumber = nextEp.episodeNumber,
                        posterUrl = posterUrl.takeIf { it.isNotBlank() },
                        backdropUrl = backdropUrl.takeIf { it.isNotBlank() },
                        tmdbId = showTmdbId?.takeIf { it > 0 } ?: tmdbId.takeIf { it > 0 },
                        imdbId = imdbId.takeIf { it.isNotBlank() },
                    ),
                    startedAt = rolloverAt,
                )
            }.getOrNull()
        }

        // Stop current and play new
        engine.stop()
        onStateUpdate(nextEp.seasonNumber, nextEp.episodeNumber, playUrl, newTitle, nextWatchSessionId)
        requestPlayback(playUrl)
        if (startupSelection.autoplayKeys.contains(playerStreamKey(selectedStream))) {
            android.util.Log.i(
                "Player",
                "startup_candidate_used context=next_episode key=${playerStreamKey(selectedStream)} host=${StreamRuntimeTelemetry.keyForStream(selectedStream)}",
            )
        }

        // Scrobble start for new episode
        if (traktScrobbler != null && traktAccessToken.isNotBlank() && tmdbId > 0) {
            try {
                traktScrobbler.start(
                    traktAccessToken, tmdbId, MediaType.SERIES, 0.0,
                    season = nextEp.seasonNumber, episode = nextEp.episodeNumber,
                )
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {
        onFailed()
    }
}


private enum class TopMenuFocusTarget {
    BACK,
    CAST,
    HANDOFF,
    VOICE,
    SUBTITLE_TRACKS,
    AUDIO_TRACKS,
    AUDIO_DELAY,
    EQUALIZER,
    PICTURE_FORMAT,
    SPEED,
}


internal enum class PlayerPictureFormat(
    val shortLabel: String,
    val label: String,
    val aspectRatio: Float?,
    val fill: Boolean,
    val exoResizeMode: Int,
) {
    SOURCE("SRC", "Source", null, false, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FULLSCREEN("FILL", "Fullscreen", null, true, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    RATIO_16_9("16:9", "16:9", 16f / 9f, false, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    RATIO_4_3("4:3", "4:3", 4f / 3f, false, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    RATIO_21_9("21:9", "21:9", 21f / 9f, false, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ;

    fun next(): PlayerPictureFormat {
        val all = entries
        val index = all.indexOf(this)
        return all[(index + 1) % all.size]
    }
}

private data class PlayerPlaybackPrefs(
    val audioDelayMs: Int,
    val playbackSpeed: Float,
    val pictureFormat: PlayerPictureFormat,
)

private data class PlayerTrackPrefs(
    val audioTrackTag: String?,
    val subtitleTrackTag: String?,
    val subtitlesEnabled: Boolean,
)

private fun buildPlayerPlaybackPrefsKey(
    mediaType: String,
    mediaId: String,
    showTmdbId: Int?,
    showImdbId: String?,
    title: String,
    url: String,
): String {
    val id = when {
        mediaId.isNotBlank() -> "${mediaType}:media:$mediaId"
        showTmdbId != null && showTmdbId > 0 -> "${mediaType}:tmdb:$showTmdbId"
        !showImdbId.isNullOrBlank() -> "${mediaType}:imdb:$showImdbId"
        title.isNotBlank() -> "${mediaType}:title:${title.lowercase()}"
        else -> "${mediaType}:url:${url.take(180)}"
    }
    return "player_playback_prefs_${id.hashCode().toUInt().toString(16)}"
}

private fun serializePlayerPlaybackPrefs(prefs: PlayerPlaybackPrefs): String {
    return "${prefs.audioDelayMs}|${prefs.playbackSpeed}|${prefs.pictureFormat.name}"
}

private fun parsePlayerPlaybackPrefs(raw: String): PlayerPlaybackPrefs? {
    val parts = raw.split('|')
    if (parts.size < 3) return null
    val delay = parts[0].toIntOrNull() ?: return null
    val speed = parts[1].toFloatOrNull() ?: return null
    val format = runCatching { PlayerPictureFormat.valueOf(parts[2]) }
        .getOrElse { PlayerPictureFormat.SOURCE }
    return PlayerPlaybackPrefs(
        audioDelayMs = delay.coerceIn(-2000, 2000),
        playbackSpeed = speed.coerceIn(0.25f, 3.0f),
        pictureFormat = format,
    )
}

private fun serializePlayerTrackPrefs(prefs: PlayerTrackPrefs): String {
    val audio = prefs.audioTrackTag.orEmpty()
    val subtitle = prefs.subtitleTrackTag.orEmpty()
    return "$audio|$subtitle|${if (prefs.subtitlesEnabled) "1" else "0"}"
}

private fun parsePlayerTrackPrefs(raw: String): PlayerTrackPrefs? {
    val parts = raw.split('|')
    if (parts.size < 3) return null
    return PlayerTrackPrefs(
        audioTrackTag = parts[0].ifBlank { null },
        subtitleTrackTag = parts[1].ifBlank { null },
        subtitlesEnabled = parts[2] == "1",
    )
}

private fun trackPreferenceTag(track: TrackDescription): String {
    return track.language
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: track.label.trim().lowercase().take(48)
}

/**
 * Thin delegate to the module-level [com.torve.android.player.subtitleLanguageMatches].
 * Kept as a file-local name so the composable's LaunchedEffect reads cleanly.
 */
private fun languageMatches(trackLanguage: String?, preferredLowercased: String): Boolean =
    com.torve.android.player.subtitleLanguageMatches(trackLanguage, preferredLowercased)

/**
 * Thin delegate to the module-level [com.torve.android.player.selectPreferredSubtitle].
 */
private fun applyPreferredSubtitle(
    subtitleTracks: List<TrackDescription>,
    perContentTag: String?,
    preferredSubtitleLanguage: String?,
    audioFallbackLanguage: String?,
    selectTrack: (Int) -> Unit,
): Boolean = com.torve.android.player.selectPreferredSubtitle(
    subtitleTracks = subtitleTracks,
    perContentTag = perContentTag,
    preferredSubtitleLanguage = preferredSubtitleLanguage,
    audioFallbackLanguage = audioFallbackLanguage,
    selectTrack = selectTrack,
)

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun formatSkipDeltaLabel(deltaMs: Long): String {
    val sign = if (deltaMs >= 0L) "+" else "-"
    val totalSeconds = (deltaMs.absoluteValue / 1000L).coerceAtLeast(1L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return when {
        minutes > 0L && seconds > 0L -> "$sign${minutes}m ${seconds}s"
        minutes > 0L -> "$sign${minutes}m"
        else -> "$sign${seconds}s"
    }
}

private fun String.isNonFatalRecoveryMessage(): Boolean {
    return this == "Audio recovered by changing the audio mode." ||
        this == "Switched to a compatible audio track." ||
        this == "Audio recovered by changing the playback engine." ||
        this == "Audio recovered with software decoding." ||
        this == "Audio confirmed with the mobile-reference playback path."
}

@Composable
private fun TvSeekFeedbackOverlay(
    deltaMs: Long,
    currentPositionMs: Long,
    targetPositionMs: Long,
    durationMs: Long,
) {
    val progressFraction = if (durationMs > 0L) {
        (targetPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val totalDurationLabel = if (durationMs > 0L) formatTime(durationMs) else "--:--"
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 46.dp)
                .fillMaxWidth(0.68f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xD9161D2A))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatSkipDeltaLabel(deltaMs),
                    style = MaterialTheme.typography.titleLarge,
                    color = com.torve.android.ui.theme.Amber,
                )
                Text(
                    text = "${formatTime(targetPositionMs)} / $totalDurationLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction)
                        .clip(RoundedCornerShape(999.dp))
                        .background(com.torve.android.ui.theme.Amber),
                )
            }
            Text(
                text = "${formatTime(currentPositionMs)} -> ${formatTime(targetPositionMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun TvPictureFormatOverlay(
    currentFormat: PlayerPictureFormat,
    onSelect: (PlayerPictureFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val options = PlayerPictureFormat.entries
    val itemRequesters = remember(options) { List(options.size) { FocusRequester() } }

    LaunchedEffect(currentFormat, options) {
        val index = options.indexOf(currentFormat).takeIf { it >= 0 } ?: 0
        val requester = itemRequesters.getOrNull(index) ?: return@LaunchedEffect
        repeat(8) {
            val requested = runCatching { requester.requestFocus(); true }.getOrDefault(false)
            if (requested) return@LaunchedEffect
            delay(40)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE0121620)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF111827))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.player_picture_format),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.player_enter_applies),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )

            options.forEachIndexed { index, option ->
                var focused by remember(option) { mutableStateOf(false) }
                val selected = option == currentFormat
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(itemRequesters[index])
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                onSelect(option)
                                true
                            } else {
                                false
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(option) },
                        )
                        .border(
                            width = if (focused) 2.dp else 1.dp,
                            color = when {
                                focused -> com.torve.android.ui.theme.Amber
                                selected -> com.torve.android.ui.theme.Amber.copy(alpha = 0.6f)
                                else -> Color.White.copy(alpha = 0.15f)
                            },
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                focused -> Color(0xFF22304A)
                                selected -> Color(0x332C3E62)
                                else -> Color(0x221B2438)
                            },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    if (selected) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelMedium,
                            color = com.torve.android.ui.theme.Amber,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackSelectionDialog(
    subtitleTracks: List<TrackDescription>,
    audioTracks: List<TrackDescription>,
    onSelectSubtitle: (TrackDescription?) -> Unit,
    onSelectAudio: (TrackDescription) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val subtitlesLabel = stringResource(R.string.player_subtitles)
    val audioLabel = stringResource(R.string.player_audio)
    val tabs = buildList {
        if (subtitleTracks.isNotEmpty()) add(subtitlesLabel)
        if (audioTracks.isNotEmpty()) add(audioLabel)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        title = { Text(stringResource(R.string.player_track_selection)) },
        text = {
            Column {
                if (tabs.size > 1) {
                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { index, tabTitle ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(tabTitle) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                val showSubtitles = tabs.getOrNull(selectedTab) == subtitlesLabel

                LazyColumn {
                    if (showSubtitles) {
                        val allOff = subtitleTracks.none { it.isSelected }
                        item {
                            TrackRow(
                                label = stringResource(R.string.common_off),
                                isSelected = allOff,
                                onClick = { onSelectSubtitle(null) },
                            )
                        }
                        items(subtitleTracks) { track ->
                            TrackRow(
                                label = track.label,
                                isSelected = track.isSelected,
                                onClick = { onSelectSubtitle(track) },
                            )
                        }
                    } else {
                        items(audioTracks) { track ->
                            TrackRow(
                                label = track.label,
                                isSelected = track.isSelected,
                                onClick = { onSelectAudio(track) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun TrackRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.player_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AudioDelayDialog(
    currentDelayMs: Int,
    onDelayChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var localDelay by remember { mutableIntStateOf(currentDelayMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        dismissButton = {
            TextButton(onClick = {
                localDelay = 0
                onReset()
            }) { Text(stringResource(R.string.common_reset)) }
        },
        title = { Text(stringResource(R.string.player_audio_delay)) },
        text = {
            Column {
                Text(stringResource(R.string.player_audio_delay_value, localDelay))
                Slider(
                    value = localDelay.toFloat(),
                    onValueChange = {
                        val v = it.toInt()
                        localDelay = v
                        onDelayChange(v)
                    },
                    valueRange = -2000f..2000f,
                    steps = 39,
                )
                Text(
                    "Use positive values if audio is ahead, negative if audio is behind.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun TvPlaybackErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth(0.88f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE0151A22))
            .border(1.dp, Color(0x77E8A838), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = Color(0xFFE8A838),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.player_retry)) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
    }
}

@Composable
private fun TvResumePlaybackOverlay(
    title: String,
    resumeFromMs: Long,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
) {
    BackHandler(onBack = onStartOver)
    val resumeRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(8) {
            val requested = runCatching { resumeRequester.requestFocus(); true }.getOrDefault(false)
            if (requested) return@LaunchedEffect
            delay(40)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA6000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.64f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF101621))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.player_resume_message, formatTime(resumeFromMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.84f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusableIconButton(
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(resumeRequester),
                ) {
                    Text(
                        text = stringResource(R.string.player_resume),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                FocusableIconButton(
                    onClick = onStartOver,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.player_start_over),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
