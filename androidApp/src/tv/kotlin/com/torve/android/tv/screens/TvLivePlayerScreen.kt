package com.torve.android.tv.screens

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import com.torve.android.player.ExoPlayerEngine
import com.torve.android.player.LiveAudioClientSurface
import com.torve.android.player.LiveAudioCompatibilityHint
import com.torve.android.player.LiveAudioCompatibilityStore
import com.torve.android.player.LiveAudioPlaybackContext
import com.torve.android.player.LiveAudioRecoveryKind
import com.torve.android.player.LiveAudioTerminalFailureHint
import com.torve.android.player.LiveAudioTrackHint
import com.torve.android.player.LivePlayerEngineId
import com.torve.android.player.MPVPlayerEngine
import com.torve.android.player.MPVTextureView
import com.torve.android.player.PlaybackRuntimeInfo
import com.torve.android.player.PlayerFactory
import com.torve.android.player.TorvePlayerView
import com.torve.android.player.LiveAudioPathSnapshot
import com.torve.android.player.buildLiveAudioPreferencesKey
import com.torve.android.player.buildLiveAudioPathLog
import com.torve.android.player.clearPlayerSafely
import com.torve.android.player.setPlayerSafely
import com.torve.android.player.toDiagnosticSummary
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.screens.LiveChannelInfoOverlay
import com.torve.android.tv.screens.LiveChannelListOverlay
import com.torve.android.tv.screens.LiveEpgGuideOverlay
import com.torve.android.tv.screens.LiveMenuBarOverlay
import com.torve.android.tv.screens.LivePictureFormatOption
import com.torve.android.tv.screens.LivePlaybackMenuOverlay
import com.torve.android.tv.screens.LiveSettingsOverlay
import com.torve.android.tv.screens.buildTvLiveTerminalFailurePresentation
import com.torve.android.tv.screens.shouldShowTvLiveTuneProgress
import com.torve.data.auth.AuthClient
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.programmesForEpgChannel
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.LiveTuneState
import com.torve.domain.player.PlayerEngine
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.domain.player.TrackDescription
import com.torve.domain.telemetry.StreamPathDiagnostics
import com.torve.domain.telemetry.StreamPathTelemetryContext
import com.torve.domain.telemetry.StreamPlaybackPath
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.presentation.channels.CategoryNameCleaner
import com.torve.presentation.channels.ChannelsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import java.util.Locale

private const val AUTO_HIDE_DELAY_MS = 5_000L
private const val LONG_PRESS_THRESHOLD_MS = 800L
private const val ZAP_COALESCE_DELAY_MS = 180L
private const val ERROR_BANNER_DURATION_MS = 4_000L
private const val SILENT_AUDIO_PROBE_DELAY_MS = 5_500L
private const val TRACK_RECOVERY_SETTLE_DELAY_MS = 1_800L
private const val MOBILE_REFERENCE_TRACK_SETTLE_DELAY_MS = 1_800L
private const val MOBILE_REFERENCE_GRACE_WINDOW_MS = 5_500L
private const val MIN_ALTERNATE_TRACK_SCORE_DELTA = 35
private const val MPV_STALL_RECOVERY_DELAY_MS = 1_500L

private enum class LivePictureFormat(
    val key: String,
    val label: String,
    val frameAspectRatio: Float?,
    val exoResizeMode: Int,
) {
    SOURCE("source", "Source", null, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ORIGINAL("original", "Original", null, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FULLSCREEN("fullscreen", "Full screen", null, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    RATIO_16_9("16_9", "16:9", 16f / 9f, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    RATIO_4_3("4_3", "4:3", 4f / 3f, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    RATIO_21_9("21_9", "21:9", 21f / 9f, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ;

    companion object {
        fun fromKey(key: String): LivePictureFormat = entries.firstOrNull { it.key == key } ?: SOURCE
    }
}

private data class LivePlayerEngineSession(
    val id: LivePlayerEngineId,
    val engine: PlayerEngine,
)

private data class PendingEngineRecovery(
    val engineId: LivePlayerEngineId,
    val playbackContext: LiveAudioPlaybackContext,
    val note: String,
)

private data class PendingTrackRecovery(
    val engineId: LivePlayerEngineId,
    val playbackContext: LiveAudioPlaybackContext,
    val trackId: Int,
    val recoveryKind: LiveAudioRecoveryKind,
)

private enum class MobileReferenceFirstPassStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

private data class MobileReferenceFirstPassState(
    val playbackContext: LiveAudioPlaybackContext,
    val engineId: LivePlayerEngineId,
    val startedAtElapsedMs: Long,
    val settleDeadlineElapsedMs: Long,
    val graceDeadlineElapsedMs: Long,
    val status: MobileReferenceFirstPassStatus = MobileReferenceFirstPassStatus.RUNNING,
    val failureReason: String? = null,
)

/**
 * TiviMate-style live TV player with overlays for channel info, menu bar,
 * EPG guide, channel list browser, and settings panel.
 */
@Composable
fun TvLivePlayerScreen(
    channelUrl: String,
    channelName: String,
    groupName: String,
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = koinInject(),
    localSettingsRepo: DeviceLocalSettingsRepository = koinInject(),
    telemetry: TelemetryEmitter = koinInject(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    // TiviMate uses ExoPlayer exclusively — disable MPV for TV live playback.
    // This eliminates the vo_mediacodec_embed SIGABRT crash entirely.
    val mpvAvailable = false




    // ── Player engine (same pattern as PlayerScreen L264-275) ──
    var engineSession by remember { mutableStateOf<LivePlayerEngineSession?>(null) }
    val engine = engineSession?.engine
    val useMpv = engineSession?.id == LivePlayerEngineId.MPV
    var pendingEngineRecovery by remember { mutableStateOf<PendingEngineRecovery?>(null) }
    var engineFallbackAttemptedForChannel by remember { mutableStateOf(false) }
    var pendingTrackRecovery by remember { mutableStateOf<PendingTrackRecovery?>(null) }
    var silentSessionRecoveryAttempted by remember { mutableStateOf(false) }
    var mobileReferenceRetryAttempted by remember { mutableStateOf(false) }
    var honorRememberedHintsForSession by remember { mutableStateOf(true) }
    var mobileReferenceFirstPass by remember { mutableStateOf<MobileReferenceFirstPassState?>(null) }
    var pendingFirstPassFailureReason by remember { mutableStateOf<String?>(null) }
    var mpvPlaybackObserved by remember { mutableStateOf(false) }
    var mpvStallFallbackAttempted by remember { mutableStateOf(false) }

    fun isMobileReferenceFirstPassActive(): Boolean {
        return mpvAvailable && mobileReferenceFirstPass?.status == MobileReferenceFirstPassStatus.RUNNING
    }

    fun isCompatibilityRecoveryEnabled(): Boolean {
        return mpvAvailable && mobileReferenceFirstPass?.status == MobileReferenceFirstPassStatus.FAILED
    }

    fun buildEngineSession(preferredEngine: LivePlayerEngineId): LivePlayerEngineSession {
        return when (preferredEngine) {
            LivePlayerEngineId.MPV -> {
                if (!mpvAvailable) {
                    Log.w(
                        "TvLivePlayerScreen",
                        "MPV unavailable for TV live playback; using ExoPlayer only for this session",
                    )
                    val exoEngine = ExoPlayerEngine(context)
                    exoEngine.initialize()
                    return LivePlayerEngineSession(LivePlayerEngineId.EXOPLAYER, exoEngine)
                }
                val mpvEngine = MPVPlayerEngine(context)
                if (mpvEngine.initialize()) {
                    LivePlayerEngineSession(LivePlayerEngineId.MPV, mpvEngine)
                } else {
                    Log.w(
                        "TvLivePlayerScreen",
                        "MPV unavailable for TV live playback; falling back to ExoPlayer for this session",
                    )
                    val exoEngine = ExoPlayerEngine(context)
                    exoEngine.initialize()
                    LivePlayerEngineSession(LivePlayerEngineId.EXOPLAYER, exoEngine)
                }
            }
            LivePlayerEngineId.EXOPLAYER -> {
                val exoEngine = ExoPlayerEngine(context)
                exoEngine.initialize()
                LivePlayerEngineSession(LivePlayerEngineId.EXOPLAYER, exoEngine)
            }
        }
    }

    // ── Player state observation ──
    var playerState by remember { mutableStateOf(PlayerState()) }
    var audioTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var errorBannerMessage by remember { mutableStateOf<String?>(null) }
    var knownTerminalFailureHint by remember { mutableStateOf<LiveAudioTerminalFailureHint?>(null) }
    var knownTerminalFailurePresentation by remember { mutableStateOf<TvLiveTerminalFailurePresentation?>(null) }
    var currentChannel by remember { mutableStateOf<Channel?>(null) }
    var currentGroupName by remember { mutableStateOf(groupName) }
    var channelNumber by remember { mutableIntStateOf(1) }
    var playbackGroupChannels by remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    var playbackGuideProgrammes by remember { mutableStateOf<Map<String, List<EpgProgramme>>>(emptyMap()) }
    var activeReplayProgramme by remember { mutableStateOf<EpgProgramme?>(null) }
    var playbackUrlOverride by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var playbackLaunchGeneration by remember { mutableIntStateOf(0) }
    var pendingPlaybackUrl by remember { mutableStateOf<String?>(null) }
    var mpvSurfaceAttached by remember { mutableStateOf(false) }
    var mpvSurfaceBindingToken by remember { mutableIntStateOf(-1) }
    var sleepTimerTargetElapsedMs by remember { mutableLongStateOf(0L) }
    var sleepTimerMinutes by remember { mutableStateOf<Int?>(null) }
    var playbackInfoRefreshTick by remember { mutableIntStateOf(0) }

    DisposableEffect(engineSession?.engine) {
        val activeEngine = engine ?: return@DisposableEffect onDispose { }
        val listener = object : PlayerListener {
            override fun onStateChanged(state: PlayerState) { playerState = state }
            override fun onTracksChanged(audio: List<TrackDescription>, subtitles: List<TrackDescription>) {
                audioTracks = audio
                subtitleTracks = subtitles
            }
            override fun onError(message: String) {
                errorBannerMessage = message
                if (isMobileReferenceFirstPassActive()) {
                    pendingFirstPassFailureReason = "hard_error:${message.take(160)}"
                }
            }
        }
        activeEngine.addListener(listener)
        onDispose { activeEngine.removeListener(listener) }
    }

    // ── Channel state ──

    // ── Overlay state ──
    var activeOverlay by remember { mutableStateOf(LivePlayerOverlay.NONE) }
    var overlayBackStack by remember { mutableStateOf<List<LivePlayerOverlay>>(emptyList()) }
    var overlayTimestamp by remember { mutableLongStateOf(0L) }
    var volumeOverlayPercent by remember { mutableStateOf<Int?>(null) }
    var volumeOverlayTimestamp by remember { mutableLongStateOf(0L) }
    var playbackVolume by rememberSaveable { mutableStateOf(1f) }

    // ── Stream info for menu bar ──
    var videoResolution by remember { mutableStateOf("") }
    var audioCodec by remember { mutableStateOf("") }

    // ── Long-press detection ──
    var centerKeyDownTime by remember { mutableLongStateOf(0L) }
    val playerRootFocusRequester = remember { FocusRequester() }
    var selectedPictureFormatKey by rememberSaveable { mutableStateOf(LivePictureFormat.SOURCE.key) }
    val selectedPictureFormat = LivePictureFormat.fromKey(selectedPictureFormatKey)
    var selectedBufferPreset by rememberSaveable { mutableStateOf(LiveBufferPreset.HIGH) }
    val bufferPrefs = remember { context.getSharedPreferences("live_buffer_prefs", Context.MODE_PRIVATE) }
    var audioDelayMs by rememberSaveable { mutableStateOf(0) }
    var exoPlayerView by remember { mutableStateOf<TorvePlayerView?>(null) }

    fun applyPlaybackVolumeToEngine(
        session: LivePlayerEngineSession? = engineSession,
        volume: Float = playbackVolume,
    ) {
        val clamped = volume.coerceIn(0f, 1f)
        when (val activeEngine = session?.engine) {
            is ExoPlayerEngine -> activeEngine.getExoPlayer()?.volume = clamped
            is MPVPlayerEngine -> activeEngine.setPlaybackVolume(clamped)
            else -> Unit
        }
    }

    fun detachExoPlayerView() {
        exoPlayerView?.clearPlayerSafely("tv_live_player_detach")
        exoPlayerView = null
    }

    fun releaseEngineSession(session: LivePlayerEngineSession?) {
        val activeSession = session ?: return
        if (activeSession.engine is ExoPlayerEngine) {
            detachExoPlayerView()
        }
        activeSession.engine.release()
    }

    fun saveChannelBufferPreset(channelUrl: String, preset: LiveBufferPreset) {
        bufferPrefs.edit().putString(channelUrl, preset.key).apply()
    }

    fun loadChannelBufferPreset(channelUrl: String): LiveBufferPreset {
        val key = bufferPrefs.getString(channelUrl, null) ?: return LiveBufferPreset.HIGH
        return LiveBufferPreset.entries.firstOrNull { it.key == key } ?: LiveBufferPreset.HIGH
    }

    fun applyLiveEngineSettings(
        session: LivePlayerEngineSession,
        channel: Channel?,
        honorRememberedHints: Boolean = true,
        allowAggressiveTrackReselection: Boolean = true,
    ) {
        // Restore per-channel buffer preference before playback starts.
        if (channel != null) {
            val restored = loadChannelBufferPreset(channel.url)
            selectedBufferPreset = restored
            (session.engine as? ExoPlayerEngine)?.setLiveBufferSize(restored.durationMs)
        }

        when (val activeEngine = session.engine) {
            is MPVPlayerEngine -> {
                activeEngine.setLivePlaybackContext(channel, honorRememberedHints)
                activeEngine.setAggressiveAutoTrackSelectionEnabled(allowAggressiveTrackReselection)
                activeEngine.setAudioOutputPreferences(
                    passthroughEnabled = state.audioPassthroughEnabled,
                    preferSurround = state.preferSurroundCodecs,
                    outputMode = state.liveAudioOutputMode,
                )
                activeEngine.setAudioDelay(audioDelayMs)
                activeEngine.setPictureFormat(
                    aspectRatio = selectedPictureFormat.frameAspectRatio,
                    fill = selectedPictureFormat == LivePictureFormat.FULLSCREEN,
                )
            }
            is ExoPlayerEngine -> {
                activeEngine.setLivePlaybackContext(channel, honorRememberedHints)
                activeEngine.setAudioOutputPreferences(
                    passthroughEnabled = state.audioPassthroughEnabled,
                    preferSurround = state.preferSurroundCodecs,
                    outputMode = state.liveAudioOutputMode,
                )
                activeEngine.setAudioDelay(audioDelayMs)
            }
        }
    }

    fun startLivePlayback(
        session: LivePlayerEngineSession,
        channel: Channel,
    ) {
        StreamPathDiagnostics.record(
            path = StreamPlaybackPath.IPTV_DIRECT,
            telemetry = telemetry,
            context = StreamPathTelemetryContext(
                contentType = "live_tv",
                providerCategory = "iptv",
            ),
        )
        val playbackUrl = playbackUrlOverride ?: channel.url
        if (session.id == LivePlayerEngineId.MPV) {
            pendingPlaybackUrl = playbackUrl
        } else {
            pendingPlaybackUrl = null
            session.engine.play(playbackUrl)
            applyPlaybackVolumeToEngine(session)
        }
        viewModel.recordChannelViewed(channel)
    }

    fun clearKnownTerminalFailureUi() {
        val terminalMessage = knownTerminalFailurePresentation?.bannerMessage
        if (!terminalMessage.isNullOrBlank() && errorBannerMessage == terminalMessage) {
            errorBannerMessage = null
        }
        knownTerminalFailureHint = null
        knownTerminalFailurePresentation = null
    }

    fun switchEngineForAudioRecovery(
        targetEngineId: LivePlayerEngineId,
        channel: Channel?,
        playbackContext: LiveAudioPlaybackContext?,
        bannerMessage: String,
        diagnostics: String,
        honorRememberedHints: Boolean = true,
        consumeEngineFallbackBudget: Boolean = true,
    ): Boolean {
        if (consumeEngineFallbackBudget && engineFallbackAttemptedForChannel) return false
        val currentSession = engineSession ?: return false
        val sameEngine = currentSession.id == targetEngineId
        if (sameEngine && honorRememberedHints == honorRememberedHintsForSession) {
            return false
        }

        if (consumeEngineFallbackBudget) {
            engineFallbackAttemptedForChannel = true
        }
        pendingTrackRecovery = null
        honorRememberedHintsForSession = honorRememberedHints
        pendingEngineRecovery = playbackContext?.let {
            PendingEngineRecovery(
                engineId = targetEngineId,
                playbackContext = it,
                note = if (honorRememberedHints) diagnostics else "mobile_reference:$diagnostics",
            )
        }
        mobileReferenceRetryAttempted = mobileReferenceRetryAttempted || !honorRememberedHints
        errorBannerMessage = bannerMessage
        Log.w(
            "TvLivePlayerScreen",
            "${if (sameEngine) "Restarting" else "Switching"} live player engine from " +
                "${currentSession.id.storageValue} to ${targetEngineId.storageValue} " +
                "for ${playbackContext?.displayName.orEmpty()} diagnostics=$diagnostics " +
                "honorRememberedHints=$honorRememberedHints " +
                "consumeFallbackBudget=$consumeEngineFallbackBudget",
        )
        playbackLaunchGeneration += 1
        scope.launch {
            pendingPlaybackUrl = null
            mpvSurfaceAttached = false
            mpvSurfaceBindingToken = -1
            val nextSession = if (sameEngine) {
                releaseEngineSession(currentSession)
                buildEngineSession(targetEngineId)
            } else {
                val alternate = buildEngineSession(targetEngineId)
                releaseEngineSession(currentSession)
                alternate
            }
            applyLiveEngineSettings(
                session = nextSession,
                channel = channel,
                honorRememberedHints = honorRememberedHints,
                allowAggressiveTrackReselection = isCompatibilityRecoveryEnabled(),
            )
            engineSession = nextSession
            channel?.let { activeChannel ->
                startLivePlayback(nextSession, activeChannel)
            }
        }
        return true
    }

    fun rememberMobileReferenceFirstPassSuccess(
        firstPass: MobileReferenceFirstPassState,
        selectedTrack: TrackDescription?,
    ) {
        LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
            context = context,
            playbackContext = firstPass.playbackContext,
            passthroughEnabled = state.audioPassthroughEnabled,
            preferSurround = state.preferSurroundCodecs,
            outputMode = state.liveAudioOutputMode,
            recoveryKind = LiveAudioRecoveryKind.MOBILE_REFERENCE_FIRST_PASS,
            engineId = firstPass.engineId,
            preferredTrack = selectedTrack?.toLiveAudioTrackHint(),
            audioSignature = null,
        )
        LiveAudioCompatibilityStore.clearSessionIncompatible(firstPass.playbackContext)
    }

    fun beginCompatibilityRecovery(
        channel: Channel,
        reason: String,
    ): Boolean {
        val playbackContext = LiveAudioPlaybackContext.fromChannel(channel)
        val firstPass = mobileReferenceFirstPass
        if (
            firstPass != null &&
            firstPass.status == MobileReferenceFirstPassStatus.RUNNING &&
            firstPass.playbackContext == playbackContext
        ) {
            mobileReferenceFirstPass = firstPass.copy(
                status = MobileReferenceFirstPassStatus.FAILED,
                failureReason = reason,
            )
        }
        if (!mpvAvailable) {
            Log.w(
                "EngineFallback",
                "MPV unavailable for terminal TV compatibility recovery on ${channel.name} reason=$reason",
            )
            return false
        }
        return switchEngineForAudioRecovery(
            targetEngineId = LivePlayerEngineId.MPV,
            channel = channel,
            playbackContext = playbackContext,
            bannerMessage = "Retrying playback with fallback engine.",
            diagnostics = reason,
        )
    }

    fun showTerminalFailureBannerIfNeeded(
        preferredEngine: LivePlayerEngineId,
        channel: Channel,
        terminalFailureHint: LiveAudioTerminalFailureHint?,
    ) {
        if (terminalFailureHint == null || preferredEngine != LivePlayerEngineId.EXOPLAYER) {
            clearKnownTerminalFailureUi()
            return
        }
        val presentation = buildTvLiveTerminalFailurePresentation(terminalFailureHint)
        knownTerminalFailureHint = terminalFailureHint
        knownTerminalFailurePresentation = presentation
        errorBannerMessage = presentation.bannerMessage
        Log.w(
            "LiveTuneUi",
            "Resolved TV terminal failure UI path at tune start " +
                "channel=${channel.name} mime=${terminalFailureHint.selectedMime ?: "unknown"} " +
                "kind=${terminalFailureHint.terminalFailureKind} " +
                "recovery=${terminalFailureHint.finalRecoveryMode}",
        )
    }

    fun configureLiveEngine(
        session: LivePlayerEngineSession,
        channel: Channel?,
        honorRememberedHints: Boolean = honorRememberedHintsForSession,
        allowAggressiveTrackReselection: Boolean = false,
    ) {
        applyLiveEngineSettings(
            session = session,
            channel = channel,
            honorRememberedHints = honorRememberedHints,
            allowAggressiveTrackReselection = allowAggressiveTrackReselection,
        )
        // TiviMate has NO audio compatibility failure handler — ExoPlayer + FFmpeg
        // extension handles all audio codecs automatically via decoder fallback.
        // Do NOT set onLiveAudioCompatibilityFailure — let ExoPlayer work.
        (session.engine as? ExoPlayerEngine)?.onLiveAudioCompatibilityFailure = null
    }

    suspend fun requestPlayerRootFocus() {
        runCatching { playerRootFocusRequester.requestFocus() }
    }

    LaunchedEffect(pendingFirstPassFailureReason, currentChannel?.url, mobileReferenceFirstPass) {
        val reason = pendingFirstPassFailureReason ?: return@LaunchedEffect
        val channel = currentChannel ?: return@LaunchedEffect
        if (mobileReferenceFirstPass?.status == MobileReferenceFirstPassStatus.RUNNING) {
            beginCompatibilityRecovery(channel, reason)
        }
        pendingFirstPassFailureReason = null
    }

    fun openOverlay(target: LivePlayerOverlay) {
        if (target == activeOverlay) return
        if (activeOverlay != LivePlayerOverlay.NONE) {
            overlayBackStack = (overlayBackStack + activeOverlay).takeLast(5)
        }
        activeOverlay = target
        overlayTimestamp = System.currentTimeMillis()
        com.torve.android.debug.AnrDebugLogger.logOverlayChange(target.name)
    }

    fun closeOverlayOrReturnToPrevious(): Boolean {
        if (activeOverlay == LivePlayerOverlay.NONE) return false
        val previous = overlayBackStack.lastOrNull()
        overlayBackStack = if (overlayBackStack.isNotEmpty()) overlayBackStack.dropLast(1) else emptyList()
        activeOverlay = previous ?: LivePlayerOverlay.NONE
        overlayTimestamp = System.currentTimeMillis()
        return true
    }

    fun closeAllOverlays() {
        overlayBackStack = emptyList()
        activeOverlay = LivePlayerOverlay.NONE
        overlayTimestamp = System.currentTimeMillis()
    }

    fun selectLiveChannel(
        channel: Channel,
        group: String,
        index: Int,
        dismissOverlays: Boolean = false,
    ) {
        playbackUrlOverride = null
        activeReplayProgramme = null
        currentChannel = channel
        currentGroupName = group
        channelNumber = index + 1
        overlayTimestamp = System.currentTimeMillis()
        if (dismissOverlays) {
            closeAllOverlays()
        }
    }

    fun canReplayProgramme(channel: Channel, programme: EpgProgramme): Boolean {
        if (!viewModel.canCatchup(channel)) return false
        if (programme.endTime > System.currentTimeMillis()) return false
        return viewModel.resolveCatchupUrl(channel, programme) != null
    }

    fun replayProgramme(channel: Channel, programme: EpgProgramme) {
        val replayUrl = viewModel.resolveCatchupUrl(channel, programme)
        if (replayUrl.isNullOrBlank()) {
            errorBannerMessage = "Replay is unavailable for this programme."
            return
        }
        playbackUrlOverride = replayUrl
        activeReplayProgramme = programme
        pendingEngineRecovery = null
        pendingTrackRecovery = null
        pendingFirstPassFailureReason = null
        engineFallbackAttemptedForChannel = false
        silentSessionRecoveryAttempted = false
        mobileReferenceRetryAttempted = false
        closeAllOverlays()
    }

    fun resumeLiveProgramme() {
        if (playbackUrlOverride == null && activeReplayProgramme == null) return
        playbackUrlOverride = null
        activeReplayProgramme = null
        pendingEngineRecovery = null
        pendingTrackRecovery = null
        pendingFirstPassFailureReason = null
        engineFallbackAttemptedForChannel = false
        silentSessionRecoveryAttempted = false
        mobileReferenceRetryAttempted = false
        closeAllOverlays()
    }

    // ── Resolve the initial channel from ViewModel state ──
    LaunchedEffect(channelUrl, state.categories) {
        if (currentChannel == null) {
            val ch = findChannelByUrl(channelUrl, state.categories.flatMap { it.channels })
            if (ch != null) {
                currentChannel = ch.channel
                val (group, idx) = findChannelGroupAndIndex(ch.channel, state.categories)
                currentGroupName = group ?: groupName
                channelNumber = idx + 1
            } else {
                // Fallback: create a minimal channel
                currentChannel = Channel(
                    name = channelName,
                    url = channelUrl,
                    groupTitle = groupName,
                )
                channelNumber = 1
            }
        }
    }

    // ── Start playback when channel is set ──
    //
    // TiviMate-style: reuse the current engine across channel switches.  Only
    // create a new engine on the very first tune or when the active engine is
    // null (e.g. after an explicit release).  Engine *type* switches (MPV ↔ Exo)
    // are reserved for explicit recovery/fallback paths — never during a normal
    // zap.  This keeps the surface alive and avoids the black-flash / race
    // conditions that come with tearing down and rebuilding the player.
    LaunchedEffect(currentGroupName, currentChannel?.playlistId, state.selectedPlaylistId) {
        val ch = currentChannel ?: return@LaunchedEffect
        val playlistId = ch.playlistId.takeIf { it.isNotBlank() }
            ?: state.selectedPlaylistId
            ?: return@LaunchedEffect
        val group = currentGroupName.takeIf { it.isNotBlank() }
            ?: ch.groupTitle?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect

        playbackGroupChannels = listOf(EnrichedChannel(channel = ch))
        playbackGuideProgrammes = emptyMap()
    }

    LaunchedEffect(currentChannel?.url, playbackUrlOverride, reloadNonce) {
        playbackLaunchGeneration += 1
        val launchGeneration = playbackLaunchGeneration
        val ch = currentChannel ?: return@LaunchedEffect
        clearKnownTerminalFailureUi()
        val playbackContext = LiveAudioPlaybackContext.fromChannel(ch)
        val activeSession = engineSession

        // ── Fast path: reuse existing engine (like TiviMate) ──
        if (activeSession != null) {
            pendingEngineRecovery = null
            pendingTrackRecovery = null
            pendingFirstPassFailureReason = null
            engineFallbackAttemptedForChannel = false
            silentSessionRecoveryAttempted = false
            mobileReferenceRetryAttempted = false
            mpvPlaybackObserved = false
            mpvStallFallbackAttempted = false
            mobileReferenceFirstPass = null
            configureLiveEngine(
                session = activeSession,
                channel = ch,
                honorRememberedHints = true,
                allowAggressiveTrackReselection = false,
            )
            delay(ZAP_COALESCE_DELAY_MS)
            if (playbackLaunchGeneration != launchGeneration) return@LaunchedEffect
            if (currentChannel?.url != ch.url) return@LaunchedEffect
            startLivePlayback(activeSession, ch)
            return@LaunchedEffect
        }

        // ── Cold start: TiviMate-style — just create ExoPlayer and play ──
        pendingEngineRecovery = null
        pendingTrackRecovery = null
        pendingFirstPassFailureReason = null
        engineFallbackAttemptedForChannel = false
        silentSessionRecoveryAttempted = false
        mobileReferenceRetryAttempted = false
        mpvPlaybackObserved = false
        mpvStallFallbackAttempted = false
        mobileReferenceFirstPass = null
        pendingPlaybackUrl = null
        mpvSurfaceAttached = false
        mpvSurfaceBindingToken = -1
        clearKnownTerminalFailureUi()
        val nextSession = buildEngineSession(LivePlayerEngineId.EXOPLAYER)
        configureLiveEngine(
            session = nextSession,
            channel = ch,
            honorRememberedHints = false,
            allowAggressiveTrackReselection = false,
        )
        engineSession = nextSession
        delay(ZAP_COALESCE_DELAY_MS)
        if (playbackLaunchGeneration != launchGeneration) return@LaunchedEffect
        if (currentChannel?.url != ch.url) return@LaunchedEffect
        if (engineSession !== nextSession) return@LaunchedEffect
        Log.i("LiveTune", "Cold start ExoPlayer for ${ch.name}")
        startLivePlayback(nextSession, ch)
    }

    LaunchedEffect(engineSession?.id, pendingPlaybackUrl, mpvSurfaceAttached, mpvSurfaceBindingToken) {
        val pendingUrl = pendingPlaybackUrl ?: return@LaunchedEffect
        val session = engineSession ?: return@LaunchedEffect
        if (session.id != LivePlayerEngineId.MPV || !mpvSurfaceAttached) return@LaunchedEffect
        val expectedBindingToken = System.identityHashCode(session.engine)
        if (mpvSurfaceBindingToken != expectedBindingToken) return@LaunchedEffect
        Log.d(
            "TvLivePlayerScreen",
            "Starting pending MPV playback after surface attach token=$mpvSurfaceBindingToken channel=${currentChannel?.name.orEmpty()}",
        )
        pendingPlaybackUrl = null
        session.engine.play(pendingUrl)
        applyPlaybackVolumeToEngine(session)
    }

    LaunchedEffect(engineSession?.engine, playbackVolume) {
        applyPlaybackVolumeToEngine()
    }

    LaunchedEffect(
        engineSession?.id,
        state.audioPassthroughEnabled,
        state.preferSurroundCodecs,
        state.liveAudioOutputMode,
    ) {
        engineSession?.let {
            configureLiveEngine(
                session = it,
                channel = currentChannel,
                honorRememberedHints = honorRememberedHintsForSession,
                allowAggressiveTrackReselection = isCompatibilityRecoveryEnabled(),
            )
        }
    }

    LaunchedEffect(currentChannel?.url, playerState.liveTuneState) {
        if (
            currentChannel != null &&
            playerState.liveTuneState == LiveTuneState.PLAYING_CONFIRMED
        ) {
            clearKnownTerminalFailureUi()
        }
    }

    LaunchedEffect(selectedPictureFormat, useMpv) {
        if (useMpv) {
            (engine as? MPVPlayerEngine)?.setPictureFormat(
                aspectRatio = selectedPictureFormat.frameAspectRatio,
                fill = selectedPictureFormat == LivePictureFormat.FULLSCREEN,
            )
        }
    }

    // ── Apply audio delay to engine ──
    LaunchedEffect(audioDelayMs, engineSession?.id) {
        engine?.setAudioDelay(audioDelayMs)
    }

    // ── Update stream info from ExoPlayer format (poll every 2s) ──
    LaunchedEffect(engineSession?.id) {
        if (!useMpv) {
            while (true) {
                delay(2000)
                val exo = (engine as? ExoPlayerEngine)?.getExoPlayer()
                exo?.let {
                    val vf = it.videoFormat
                    val af = it.audioFormat
                    videoResolution = if (vf != null) "${vf.width}×${vf.height}" else ""
                    audioCodec = af?.sampleMimeType?.substringAfterLast("/")?.uppercase() ?: ""
                }
            }
        }
    }

    LaunchedEffect(
        currentChannel?.url,
        engineSession?.id,
        playerState.isPlaying,
        playerState.isBuffering,
        audioTracks,
        mobileReferenceFirstPass,
    ) {
        val firstPass = mobileReferenceFirstPass ?: return@LaunchedEffect
        if (firstPass.status != MobileReferenceFirstPassStatus.RUNNING) return@LaunchedEffect
        val channel = currentChannel ?: return@LaunchedEffect
        if (engineSession?.id != firstPass.engineId) return@LaunchedEffect
        val waitMs = (firstPass.settleDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        delay(waitMs)
        if (mobileReferenceFirstPass?.status != MobileReferenceFirstPassStatus.RUNNING) return@LaunchedEffect
        if (currentChannel?.url != channel.url || engineSession?.id != firstPass.engineId) return@LaunchedEffect
        if (!playerState.isPlaying || playerState.isBuffering) return@LaunchedEffect

        val selectedTrack = audioTracks.firstOrNull { it.isSelected }
        if (selectedTrack != null) {
            rememberMobileReferenceFirstPassSuccess(firstPass, selectedTrack)
            mobileReferenceFirstPass = firstPass.copy(status = MobileReferenceFirstPassStatus.SUCCEEDED)
            Log.i(
                "TvLivePlayerScreen",
                "Mobile-reference first pass succeeded for ${channel.name} " +
                    "engine=${firstPass.engineId.storageValue} track=${selectedTrack.debugSummary()}",
            )
            return@LaunchedEffect
        }
        if (audioTracks.isNotEmpty()) {
            beginCompatibilityRecovery(
                channel = channel,
                reason = "no_selected_audio_track_after_settle",
            )
        }
    }

    LaunchedEffect(
        currentChannel?.url,
        engineSession?.id,
        playerState.isPlaying,
        playerState.isBuffering,
        audioTracks,
        mobileReferenceFirstPass,
    ) {
        val firstPass = mobileReferenceFirstPass ?: return@LaunchedEffect
        if (firstPass.status != MobileReferenceFirstPassStatus.RUNNING) return@LaunchedEffect
        val channel = currentChannel ?: return@LaunchedEffect
        if (engineSession?.id != firstPass.engineId) return@LaunchedEffect
        val waitMs = (firstPass.graceDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        delay(waitMs)
        if (mobileReferenceFirstPass?.status != MobileReferenceFirstPassStatus.RUNNING) return@LaunchedEffect
        if (currentChannel?.url != channel.url || engineSession?.id != firstPass.engineId) return@LaunchedEffect
        val selectedTrack = audioTracks.firstOrNull { it.isSelected }
        when {
            playerState.isBuffering || !playerState.isPlaying -> {
                beginCompatibilityRecovery(channel, "startup_not_stable_after_grace_window")
            }
            selectedTrack == null && audioTracks.isNotEmpty() -> {
                beginCompatibilityRecovery(channel, "no_selected_audio_track_after_grace_window")
            }
            selectedTrack == null && audioTracks.isEmpty() -> {
                beginCompatibilityRecovery(channel, "no_audio_track_metadata_after_grace_window")
            }
        }
    }

    LaunchedEffect(
        pendingEngineRecovery,
        engineSession?.id,
        playerState.isPlaying,
        playerState.isBuffering,
        audioTracks,
    ) {
        val pending = pendingEngineRecovery ?: return@LaunchedEffect
        if (engineSession?.id != pending.engineId) return@LaunchedEffect
        val selectedTrack = audioTracks.firstOrNull { it.isSelected }
        if (!playerState.isPlaying || playerState.isBuffering || selectedTrack == null) {
            return@LaunchedEffect
        }
        delay(1500)
        if (pendingEngineRecovery != pending || engineSession?.id != pending.engineId) {
            return@LaunchedEffect
        }
        if (!playerState.isPlaying || playerState.isBuffering) {
            return@LaunchedEffect
        }
        LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
            context = context,
            playbackContext = pending.playbackContext,
            passthroughEnabled = state.audioPassthroughEnabled,
            preferSurround = state.preferSurroundCodecs,
            outputMode = state.liveAudioOutputMode,
            recoveryKind = LiveAudioRecoveryKind.ENGINE_FALLBACK,
            engineId = pending.engineId,
            preferredTrack = selectedTrack.toLiveAudioTrackHint(),
            audioSignature = null,
        )
        errorBannerMessage = "Audio recovered with ${if (pending.engineId == LivePlayerEngineId.MPV) "MPV" else "ExoPlayer"}."
        pendingEngineRecovery = null
    }

    LaunchedEffect(
        pendingTrackRecovery,
        engineSession?.id,
        playerState.isPlaying,
        playerState.isBuffering,
        audioTracks,
    ) {
        val pending = pendingTrackRecovery ?: return@LaunchedEffect
        if (engineSession?.id != pending.engineId) return@LaunchedEffect
        val selectedTrack = audioTracks.firstOrNull { it.isSelected }
        if (!playerState.isPlaying || playerState.isBuffering || selectedTrack?.id != pending.trackId) {
            return@LaunchedEffect
        }
        delay(TRACK_RECOVERY_SETTLE_DELAY_MS)
        if (pendingTrackRecovery != pending || engineSession?.id != pending.engineId) {
            return@LaunchedEffect
        }
        if (!playerState.isPlaying || playerState.isBuffering) {
            return@LaunchedEffect
        }
        val confirmedTrack = audioTracks.firstOrNull { it.isSelected }
        if (confirmedTrack?.id != pending.trackId) {
            return@LaunchedEffect
        }
        LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
            context = context,
            playbackContext = pending.playbackContext,
            passthroughEnabled = state.audioPassthroughEnabled,
            preferSurround = state.preferSurroundCodecs,
            outputMode = state.liveAudioOutputMode,
            recoveryKind = pending.recoveryKind,
            engineId = pending.engineId,
            preferredTrack = confirmedTrack.toLiveAudioTrackHint(),
            audioSignature = null,
        )
        errorBannerMessage = "Audio recovered with an alternate track."
        pendingTrackRecovery = null
    }

    LaunchedEffect(
        currentChannel?.url,
        engineSession?.id,
        playerState.isPlaying,
        playerState.isBuffering,
        audioTracks,
        state.audioPassthroughEnabled,
        state.preferSurroundCodecs,
        state.liveAudioOutputMode,
        pendingEngineRecovery,
        pendingTrackRecovery,
        silentSessionRecoveryAttempted,
    ) {
        val channel = currentChannel ?: return@LaunchedEffect
        val session = engineSession ?: return@LaunchedEffect
        if (session.id == LivePlayerEngineId.EXOPLAYER) {
            return@LaunchedEffect
        }
        if (!isCompatibilityRecoveryEnabled()) {
            return@LaunchedEffect
        }
        if (silentSessionRecoveryAttempted || pendingEngineRecovery != null || pendingTrackRecovery != null) {
            return@LaunchedEffect
        }
        if (!playerState.isPlaying || playerState.isBuffering) return@LaunchedEffect

        val playbackContext = LiveAudioPlaybackContext.fromChannel(channel)
        val rememberedHint = LiveAudioCompatibilityStore.resolveHint(context, playbackContext)
        val selectedTrack = audioTracks.firstOrNull { it.isSelected }
        val alternateTrack = findBestAlternateAudioTrack(
            audioTracks = audioTracks,
            selectedTrack = selectedTrack,
            passthroughEnabled = state.audioPassthroughEnabled,
            preferSurround = state.preferSurroundCodecs,
            outputMode = state.liveAudioOutputMode,
        )
        if (!shouldAttemptSilentSessionRecovery(selectedTrack, alternateTrack, session.id, rememberedHint)) {
            return@LaunchedEffect
        }

        delay(SILENT_AUDIO_PROBE_DELAY_MS)
        if (silentSessionRecoveryAttempted || pendingEngineRecovery != null || pendingTrackRecovery != null) {
            return@LaunchedEffect
        }
        if (!playerState.isPlaying || playerState.isBuffering) return@LaunchedEffect
        if (currentChannel?.url != channel.url || engineSession?.id != session.id) return@LaunchedEffect

        silentSessionRecoveryAttempted = true
        val diagnostics = buildSilentAudioDiagnostics(
            engineId = session.id,
            rememberedHint = rememberedHint,
            selectedTrack = selectedTrack,
            alternateTrack = alternateTrack,
            audioTracks = audioTracks,
            passthroughEnabled = state.audioPassthroughEnabled,
            preferSurround = state.preferSurroundCodecs,
            outputMode = state.liveAudioOutputMode,
        )
        Log.w(
            "AudioRecover",
            "Live audio silent-session probe triggered for ${channel.name}: $diagnostics",
        )

        when {
            alternateTrack != null && alternateTrack.id != selectedTrack?.id -> {
                pendingTrackRecovery = PendingTrackRecovery(
                    engineId = session.id,
                    playbackContext = playbackContext,
                    trackId = alternateTrack.id,
                    recoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK,
                )
                errorBannerMessage = "Retrying audio with an alternate track."
                session.engine.selectAudioTrack(alternateTrack.id)
            }
            session.id == LivePlayerEngineId.MPV -> {
                if (rememberedHint != null && !mobileReferenceRetryAttempted) {
                    switchEngineForAudioRecovery(
                        targetEngineId = LivePlayerEngineId.MPV,
                        channel = channel,
                        playbackContext = playbackContext,
                        bannerMessage = "Retrying audio with clean mobile audio path.",
                        diagnostics = "hint_bypass:$diagnostics",
                        honorRememberedHints = false,
                    )
                } else if (mobileReferenceRetryAttempted) {
                    Log.w(
                        "TvLivePlayerScreen",
                        "Keeping TV live playback on mobile-reference MPV path for ${channel.name} " +
                            "instead of bouncing back to ExoPlayer. diagnostics=$diagnostics",
                    )
                } else {
                    switchEngineForAudioRecovery(
                        targetEngineId = LivePlayerEngineId.EXOPLAYER,
                        channel = channel,
                        playbackContext = playbackContext,
                        bannerMessage = "Retrying audio with ExoPlayer.",
                        diagnostics = "silent_session:$diagnostics",
                    )
                }
            }
        }
    }

    LaunchedEffect(
        currentChannel?.url,
        engineSession?.id,
        playerState.isPlaying,
        playerState.isBuffering,
        playerState.positionMs,
    ) {
        if (engineSession?.id != LivePlayerEngineId.MPV) return@LaunchedEffect
        if (playerState.isPlaying && !playerState.isBuffering && playerState.positionMs >= 500L) {
            mpvPlaybackObserved = true
        }
    }

    LaunchedEffect(
        currentChannel?.url,
        engineSession?.id,
        playerState.isIdle,
        playerState.isBuffering,
        pendingEngineRecovery,
        mpvPlaybackObserved,
        mpvStallFallbackAttempted,
    ) {
        val channel = currentChannel ?: return@LaunchedEffect
        val session = engineSession ?: return@LaunchedEffect
        if (session.id != LivePlayerEngineId.MPV) return@LaunchedEffect
        if (!mpvPlaybackObserved || mpvStallFallbackAttempted) return@LaunchedEffect
        if (pendingEngineRecovery != null || playerState.isBuffering || !playerState.isIdle) return@LaunchedEffect

        delay(MPV_STALL_RECOVERY_DELAY_MS)
        if (currentChannel?.url != channel.url || engineSession?.id != LivePlayerEngineId.MPV) return@LaunchedEffect
        if (pendingEngineRecovery != null || playerState.isBuffering || !playerState.isIdle) return@LaunchedEffect

        mpvStallFallbackAttempted = true
        (session.engine as? MPVPlayerEngine)?.recordLiveTvIssue("mpv_stall_or_end_file")
        switchEngineForAudioRecovery(
            targetEngineId = LivePlayerEngineId.EXOPLAYER,
            channel = channel,
            playbackContext = LiveAudioPlaybackContext.fromChannel(channel),
            bannerMessage = "Retrying stream with ExoPlayer.",
            diagnostics = "mpv_stall_or_end_file",
            honorRememberedHints = false,
            consumeEngineFallbackBudget = false,
        )
    }

    LaunchedEffect(
        currentChannel?.url,
        engineSession?.id,
        audioTracks,
        playerState.isPlaying,
        playerState.isBuffering,
        state.audioPassthroughEnabled,
        state.preferSurroundCodecs,
        state.liveAudioOutputMode,
    ) {
        val channel = currentChannel ?: return@LaunchedEffect
        val session = engineSession ?: return@LaunchedEffect
        if (!playerState.isPlaying || playerState.isBuffering) return@LaunchedEffect
        Log.i(
            "LiveTune",
            buildLiveAudioPathLog(
                LiveAudioPathSnapshot(
                    surface = LiveAudioClientSurface.TV,
                    engineId = session.id,
                    channelName = channel.name,
                    trackCount = audioTracks.size,
                    selectedTrack = audioTracks.firstOrNull { it.isSelected },
                    audioTracks = audioTracks,
                    passthroughEnabled = state.audioPassthroughEnabled,
                    preferSurround = state.preferSurroundCodecs,
                    outputMode = state.liveAudioOutputMode,
                    rememberedHint = if (isMobileReferenceFirstPassActive()) {
                        null
                    } else {
                        LiveAudioCompatibilityStore.resolveHint(
                            context = context,
                            playbackContext = LiveAudioPlaybackContext.fromChannel(channel),
                        )
                    },
                    tuneState = playerState.liveTuneState,
                    recoveryMode = playerState.audioRecoveryMode,
                    note = when {
                        isMobileReferenceFirstPassActive() -> "steady_state_mobile_reference_first_pass"
                        mobileReferenceRetryAttempted -> "steady_state_mobile_reference"
                        else -> "steady_state"
                    },
                ),
            ),
        )
    }

    LaunchedEffect(errorBannerMessage) {
        if (!errorBannerMessage.isNullOrBlank()) {
            delay(ERROR_BANNER_DURATION_MS)
            if (!errorBannerMessage.isNullOrBlank()) {
                errorBannerMessage = null
            }
        }
    }

    // ── Auto-hide timer for CHANNEL_INFO and MENU_BAR ──
    LaunchedEffect(activeOverlay, overlayTimestamp) {
        if (activeOverlay == LivePlayerOverlay.CHANNEL_INFO || activeOverlay == LivePlayerOverlay.MENU_BAR) {
            delay(AUTO_HIDE_DELAY_MS)
            if (activeOverlay == LivePlayerOverlay.CHANNEL_INFO || activeOverlay == LivePlayerOverlay.MENU_BAR) {
                closeAllOverlays()
            }
        }
    }

    LaunchedEffect(activeOverlay, engineSession?.id) {
        if (activeOverlay != LivePlayerOverlay.PLAYBACK_MENU) {
            playbackInfoRefreshTick = 0
            return@LaunchedEffect
        }
        while (activeOverlay == LivePlayerOverlay.PLAYBACK_MENU) {
            delay(1_000L)
            playbackInfoRefreshTick += 1
        }
    }

    // Explicit playback exit: stop audio immediately, then navigate.
    // This prevents double-audio when the user re-enters playback
    // before Compose disposes the old composable.
    fun exitPlayback() {
        Log.w("TvLivePlayer", "exitPlayback: stopping engine before nav pop session=${engineSession?.id}")
        engineSession?.engine?.stop()
        releaseEngineSession(engineSession)
        engineSession = null
        onBack()
    }

    LaunchedEffect(sleepTimerTargetElapsedMs, currentChannel?.url) {
        val targetElapsedMs = sleepTimerTargetElapsedMs
        if (targetElapsedMs <= 0L) return@LaunchedEffect
        val remainingMs = targetElapsedMs - android.os.SystemClock.elapsedRealtime()
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
        if (sleepTimerTargetElapsedMs != targetElapsedMs || currentChannel == null) return@LaunchedEffect
        errorBannerMessage = "Sleep timer elapsed. Stopping playback."
        sleepTimerTargetElapsedMs = 0L
        sleepTimerMinutes = null
        exitPlayback()
    }

    // ── Keep screen on + lifecycle stop ──
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Stop playback when Activity goes to background (Home button).
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    Log.w("TvLivePlayer", "ON_STOP: stopping engine (Home pressed or app backgrounded)")
                    engineSession?.engine?.stop()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            releaseEngineSession(engineSession)
        }
    }

    // ── Back handler ──
    BackHandler(enabled = true) {
        val closed = closeOverlayOrReturnToPrevious()
        Log.w("TvLivePlayer", "BackHandler: overlayClosed=$closed activeOverlay=$activeOverlay")
        if (!closed) {
            exitPlayback()
        }
    }

    LaunchedEffect(Unit) {
        requestPlayerRootFocus()
    }

    LaunchedEffect(activeOverlay) {
        centerKeyDownTime = 0L
        if (activeOverlay == LivePlayerOverlay.NONE) {
            if (overlayBackStack.isNotEmpty()) {
                overlayBackStack = emptyList()
            }
            delay(40)
            requestPlayerRootFocus()
        }
    }

    LaunchedEffect(volumeOverlayTimestamp) {
        val timestamp = volumeOverlayTimestamp
        if (timestamp <= 0L) return@LaunchedEffect
        delay(1_600L)
        if (volumeOverlayTimestamp == timestamp) {
            volumeOverlayPercent = null
        }
    }

    // ── Channel zapping helper ──
    // Cache the flattened channel list so flatMap doesn't run on every zap.
    val cachedLiveShelves = TvScreenCache
        .get<Map<String, LiveShelfLoad>>(liveShelvesCacheKey(state.selectedPlaylistId, state.xxxEnabled))
        .orEmpty()
    val zapChannels = remember(
        playbackGroupChannels,
        currentGroupName,
        currentChannel?.url,
        state.categories,
        state.guideChannels,
        cachedLiveShelves,
    ) {
        val currentUrl = currentChannel?.url
        val selectedCategoryChannels = state.categories
            .firstOrNull { categoryMatchesGroup(it, currentGroupName) }
            ?.channels
            .orEmpty()
        val containingCategoryChannels = if (currentUrl.isNullOrBlank()) {
            emptyList()
        } else {
            state.categories
                .firstOrNull { category ->
                    category.channels.any { it.channel.url == currentUrl }
                }
                ?.channels
                .orEmpty()
        }
        val cachedSelectedCategoryChannels = cachedLiveShelves.entries
            .firstOrNull { (name, _) -> categoryNameMatchesGroup(name, currentGroupName) }
            ?.value
            ?.channels
            .orEmpty()
        val cachedContainingCategoryChannels = if (currentUrl.isNullOrBlank()) {
            emptyList()
        } else {
            cachedLiveShelves.values
                .firstOrNull { shelf -> shelf.channels.any { it.channel.url == currentUrl } }
                ?.channels
                .orEmpty()
        }

        sequenceOf(
            selectedCategoryChannels,
            containingCategoryChannels,
            cachedSelectedCategoryChannels,
            cachedContainingCategoryChannels,
            state.guideChannels,
            playbackGroupChannels,
        )
            .firstOrNull { channels ->
                channels.count { isPlayableLiveChannel(it.channel) } > 1
            }
            .orEmpty()
            .filter { isPlayableLiveChannel(it.channel) }
    }
    // Throttle zap to prevent cascading channel switches from rapid D-pad repeats.
    var lastZapMs by remember { mutableLongStateOf(0L) }

    fun zapChannel(delta: Int) {
        val now = System.currentTimeMillis()
        if (now - lastZapMs < 180L) return // ZAP_COALESCE_DELAY_MS throttle
        lastZapMs = now

        val ch = currentChannel ?: return
        if (zapChannels.isEmpty()) return
        val currentIdx = zapChannels.indexOfFirst { it.channel.url == ch.url }.takeIf { it >= 0 } ?: 0

        val newIdx = (currentIdx + delta).mod(zapChannels.size)
        val newEnriched = zapChannels[newIdx]
        com.torve.android.debug.AnrDebugLogger.logZapChannel(delta, newEnriched.channel.name)
        selectLiveChannel(
            channel = newEnriched.channel,
            group = currentGroupName.ifBlank { newEnriched.channel.groupTitle.orEmpty() },
            index = newIdx,
        )
    }

    fun adjustPlaybackVolume(delta: Int) {
        val step = if (delta < 0) -0.05f else 0.05f
        playbackVolume = (playbackVolume + step).coerceIn(0f, 1f)
        applyPlaybackVolumeToEngine(volume = playbackVolume)
        volumeOverlayPercent = (playbackVolume * 100f).roundToInt().coerceIn(0, 100)
        volumeOverlayTimestamp = System.currentTimeMillis()
    }

    fun reloadCurrentChannel() {
        if (currentChannel == null) return
        pendingEngineRecovery = null
        pendingTrackRecovery = null
        pendingFirstPassFailureReason = null
        engineFallbackAttemptedForChannel = false
        silentSessionRecoveryAttempted = false
        mobileReferenceRetryAttempted = false
        errorBannerMessage = "Reloading stream."
        reloadNonce += 1
    }

    val sleepTimerRemainingLabel = remember(sleepTimerTargetElapsedMs, playbackInfoRefreshTick) {
        if (sleepTimerTargetElapsedMs <= 0L) {
            null
        } else {
            val remainingMs = (sleepTimerTargetElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val totalMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()
            if (totalMinutes >= 60) {
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                if (minutes == 0) "${hours}h" else "${hours}h ${minutes}m"
            } else {
                "${totalMinutes}m"
            }
        }
    }

    val playbackRuntimeInfo = remember(
        engineSession?.id,
        playerState,
        audioTracks,
        subtitleTracks,
        state.audioPassthroughEnabled,
        state.preferSurroundCodecs,
        state.liveAudioOutputMode,
        playbackInfoRefreshTick,
    ) {
        when (val activeEngine = engine) {
            is ExoPlayerEngine -> activeEngine.getPlaybackRuntimeInfo()
            is MPVPlayerEngine -> activeEngine.getPlaybackRuntimeInfo()
            else -> PlaybackRuntimeInfo(
                engineId = engineSession?.id ?: LivePlayerEngineId.EXOPLAYER,
                selectedAudioTrack = audioTracks.firstOrNull { it.isSelected },
                selectedSubtitleTrack = subtitleTracks.firstOrNull { it.isSelected },
                outputMode = state.liveAudioOutputMode,
                passthroughEnabled = state.audioPassthroughEnabled,
                preferSurround = state.preferSurroundCodecs,
            )
        }
    }

    // ── Build enriched current channel for overlays ──
    val enrichedCurrentChannel: EnrichedChannel? = remember(
        currentChannel?.url,
        playbackGroupChannels,
        playbackGuideProgrammes,
        state.categories,
    ) {
        currentChannel?.let { ch ->
            val base = findChannelByUrl(ch.url, playbackGroupChannels)
                ?: findChannelByUrl(ch.url, state.categories.flatMap { it.channels })
                ?: EnrichedChannel(channel = ch, currentProgramme = null, nextProgramme = null)
            val lookupChannel = base.channel
            val lookupPlaylistId = lookupChannel.playlistId.takeIf { it.isNotBlank() } ?: ch.playlistId
                .takeIf { it.isNotBlank() }
                ?: state.selectedPlaylistId.orEmpty()
            val programmes = programmesForEpgChannel(
                programmesByChannelKey = playbackGuideProgrammes,
                playlistId = lookupPlaylistId,
                channel = lookupChannel,
            )
            if (programmes.isEmpty()) {
                base
            } else {
                val now = System.currentTimeMillis()
                base.copy(
                    currentProgramme = programmes.firstOrNull { it.startTime <= now && it.endTime > now }
                        ?: base.currentProgramme,
                    nextProgramme = programmes.firstOrNull { it.startTime > now }
                        ?: base.nextProgramme,
                )
            }
        }
    }
    val displayCurrentChannel = remember(enrichedCurrentChannel, activeReplayProgramme) {
        val enriched = enrichedCurrentChannel ?: return@remember null
        if (activeReplayProgramme == null) {
            enriched
        } else {
            enriched.copy(
                currentProgramme = activeReplayProgramme,
                nextProgramme = null,
            )
        }
    }
    val currentChannelProgrammes = remember(enrichedCurrentChannel, playbackGuideProgrammes, state.guideProgrammes) {
        val enriched = enrichedCurrentChannel ?: return@remember emptyList()
        val lookupPlaylistId = enriched.channel.playlistId
            .takeIf { it.isNotBlank() }
            ?: state.selectedPlaylistId.orEmpty()
        programmesForEpgChannel(
            programmesByChannelKey = playbackGuideProgrammes,
            playlistId = lookupPlaylistId,
            channel = enriched.channel,
        ).ifEmpty {
            programmesForEpgChannel(
                programmesByChannelKey = state.guideProgrammes,
                playlistId = lookupPlaylistId,
                channel = enriched.channel,
            )
        }
            .sortedBy(EpgProgramme::startTime)
    }

    // ── UI ──
    val playbackCategories = remember(state.categories, playbackGroupChannels, currentGroupName) {
        if (playbackGroupChannels.isEmpty()) {
            state.categories
        } else {
            var mergedCurrentGroup = false
            val merged = state.categories.map { category ->
                if (categoryMatchesGroup(category, currentGroupName)) {
                    mergedCurrentGroup = true
                    category.copy(
                        channels = playbackGroupChannels,
                        channelCount = playbackGroupChannels.size,
                    )
                } else {
                    category
                }
            }
            if (mergedCurrentGroup || currentGroupName.isBlank()) {
                merged
            } else {
                merged + ChannelCategory(
                    name = currentGroupName,
                    channelCount = playbackGroupChannels.size,
                    channels = playbackGroupChannels,
                )
            }
        }
    }
    val playbackGuideChannels = remember(playbackGroupChannels, state.guideChannels) {
        playbackGroupChannels.ifEmpty { state.guideChannels }
    }
    val playbackProgrammes = remember(state.guideProgrammes, playbackGuideProgrammes) {
        state.guideProgrammes + playbackGuideProgrammes
    }

    fun withPlaybackGuide(enriched: EnrichedChannel): EnrichedChannel {
        val lookupPlaylistId = enriched.channel.playlistId
            .takeIf { it.isNotBlank() }
            ?: state.selectedPlaylistId.orEmpty()
        val programmes = programmesForEpgChannel(
            programmesByChannelKey = playbackProgrammes,
            playlistId = lookupPlaylistId,
            channel = enriched.channel,
        ).sortedBy(EpgProgramme::startTime)
        if (programmes.isEmpty()) return enriched
        val now = System.currentTimeMillis()
        return enriched.copy(
            currentProgramme = programmes.firstOrNull { it.startTime <= now && it.endTime > now }
                ?: enriched.currentProgramme,
            nextProgramme = programmes.firstOrNull { it.startTime > now }
                ?: enriched.nextProgramme,
        )
    }

    fun tuneToPlaybackChannel(channel: Channel, preferredGroupName: String? = null) {
        val group = preferredGroupName?.takeIf { it.isNotBlank() }
            ?: playbackCategories.firstOrNull { category ->
                category.channels.any { it.channel.url == channel.url }
            }?.name
            ?: channel.groupTitle.orEmpty()
        val index = playbackCategories
            .firstOrNull { categoryMatchesGroup(it, group) }
            ?.channels
            ?.indexOfFirst { it.channel.url == channel.url }
            ?.takeIf { it >= 0 }
            ?: 0
        selectLiveChannel(
            channel = channel,
            group = group,
            index = index,
            dismissOverlays = true,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .focusRequester(playerRootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                when {
                    // ── Center/Confirm key ──
                    event.key == Key.Enter || event.key == Key.DirectionCenter ||
                        event.key == Key.NumPadEnter -> {
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (activeOverlay != LivePlayerOverlay.NONE) {
                                    false
                                } else {
                                    if (centerKeyDownTime == 0L) {
                                        centerKeyDownTime = System.currentTimeMillis()
                                    }
                                    true
                                }
                            }
                            KeyEventType.KeyUp -> {
                                if (activeOverlay != LivePlayerOverlay.NONE) {
                                    return@onPreviewKeyEvent false
                                }
                                val downTime = centerKeyDownTime
                                centerKeyDownTime = 0L
                                if (downTime == 0L) {
                                    return@onPreviewKeyEvent false
                                }
                                val held = System.currentTimeMillis() - downTime
                                if (held >= LONG_PRESS_THRESHOLD_MS) {
                                    // Long press opens playback/video options.
                                    openOverlay(LivePlayerOverlay.PLAYBACK_MENU)
                                } else {
                                    // Short press on fullscreen playback → channel switch list
                                    openOverlay(LivePlayerOverlay.CHANNEL_LIST)
                                }
                                true
                            }
                            else -> false
                        }
                    }

                    // ── Menu key → Playback Menu ──
                    event.key == Key.Menu && event.type == KeyEventType.KeyDown -> {
                        if (activeOverlay == LivePlayerOverlay.PLAYBACK_MENU) {
                            closeOverlayOrReturnToPrevious()
                        } else {
                            openOverlay(LivePlayerOverlay.PLAYBACK_MENU)
                        }
                        true
                    }

                    // ── Media Play/Pause key ──
                    event.key == Key.MediaPlayPause && event.type == KeyEventType.KeyDown -> {
                        if (playerState.isPlaying) engine?.pause() else engine?.resume()
                        true
                    }

                    // D-pad left/right controls volume while fullscreen playback owns focus.
                    (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) &&
                        event.type == KeyEventType.KeyDown &&
                        activeOverlay == LivePlayerOverlay.NONE -> {
                        adjustPlaybackVolume(if (event.key == Key.DirectionLeft) -1 else 1)
                        true
                    }

                    // ── D-pad navigation during CHANNEL_INFO → reset auto-hide timer ──
                    (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) &&
                        event.type == KeyEventType.KeyDown &&
                        activeOverlay == LivePlayerOverlay.CHANNEL_INFO -> {
                        overlayTimestamp = System.currentTimeMillis()
                        false // let event propagate to bottom row
                    }

                    // ── Back key during CHANNEL_INFO → dismiss overlay ──
                    event.key == Key.Back && event.type == KeyEventType.KeyDown &&
                        activeOverlay == LivePlayerOverlay.CHANNEL_INFO -> {
                        closeOverlayOrReturnToPrevious()
                        true
                    }

                    // ── D-pad Up/Down → zap channels ──
                    event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown &&
                        (activeOverlay == LivePlayerOverlay.NONE || activeOverlay == LivePlayerOverlay.CHANNEL_INFO) -> {
                        zapChannel(-1)
                        openOverlay(LivePlayerOverlay.CHANNEL_INFO)
                        overlayTimestamp = System.currentTimeMillis()
                        true
                    }
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown &&
                        (activeOverlay == LivePlayerOverlay.NONE || activeOverlay == LivePlayerOverlay.CHANNEL_INFO) -> {
                        zapChannel(1)
                        openOverlay(LivePlayerOverlay.CHANNEL_INFO)
                        overlayTimestamp = System.currentTimeMillis()
                        true
                    }

                    // ── Channel Up/Down hardware keys → zap always ──
                    event.key == Key.ChannelUp && event.type == KeyEventType.KeyDown -> {
                        zapChannel(-1)
                        if (activeOverlay == LivePlayerOverlay.NONE) {
                            openOverlay(LivePlayerOverlay.CHANNEL_INFO)
                        } else {
                            overlayTimestamp = System.currentTimeMillis()
                        }
                        true
                    }
                    event.key == Key.ChannelDown && event.type == KeyEventType.KeyDown -> {
                        zapChannel(1)
                        if (activeOverlay == LivePlayerOverlay.NONE) {
                            openOverlay(LivePlayerOverlay.CHANNEL_INFO)
                        } else {
                            overlayTimestamp = System.currentTimeMillis()
                        }
                        true
                    }

                    else -> false
                }
            },
    ) {
        // ── Video surface ──
        val videoSurfaceModifier = selectedPictureFormat.frameAspectRatio?.let { ratio ->
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .align(Alignment.Center)
        } ?: Modifier.fillMaxSize()

        if (useMpv) {
            AndroidView(
                factory = { ctx ->
                    MPVTextureView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = false
                        isFocusableInTouchMode = false
                        onSurfaceAttachedStateChanged = { attached ->
                            mpvSurfaceAttached = attached
                        }
                    }
                },
                update = { view ->
                    val bindingToken = engineSession?.engine?.let(System::identityHashCode) ?: reloadNonce
                    view.onSurfaceAttachedStateChanged = { attached ->
                        mpvSurfaceAttached = attached
                    }
                    mpvSurfaceBindingToken = bindingToken
                    view.bindSurface(bindingToken, "tv_compose_update")
                },
                onRelease = { view ->
                    mpvSurfaceAttached = false
                    mpvSurfaceBindingToken = -1
                    view.releaseSurface("tv_compose_release")
                },
                modifier = videoSurfaceModifier,
            )
        } else {
            AndroidView(
                factory = {
                    TorvePlayerView(context).apply {
                        useController = false
                        resizeMode = selectedPictureFormat.exoResizeMode
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                },
                update = { view ->
                    exoPlayerView = view
                    view.useController = false
                    view.resizeMode = selectedPictureFormat.exoResizeMode
                    view.setPlayerSafely((engine as? ExoPlayerEngine)?.getExoPlayer(), "tv_live_player_update")
                },
                onRelease = { view ->
                    if (exoPlayerView === view) {
                        exoPlayerView = null
                    }
                    view.clearPlayerSafely("tv_live_player_release")
                },
                modifier = videoSurfaceModifier,
            )
        }

        // ── Buffering indicator ──
        val showTuneProgress = shouldShowTvLiveTuneProgress(
            playerState = playerState,
            engineId = engineSession?.id,
            terminalFailurePresentation = knownTerminalFailurePresentation,
        )
        if (
            !showTuneProgress &&
            knownTerminalFailurePresentation != null &&
            engineSession?.id == LivePlayerEngineId.EXOPLAYER &&
            playerState.liveTuneState != LiveTuneState.PLAYING_CONFIRMED &&
            playerState.liveTuneState != LiveTuneState.FALLBACK_ALLOWED
        ) {
            Log.d(
                "LiveTuneUi",
                "Suppressing TV tune spinner for known terminal failure " +
                    "channel=${currentChannel?.name.orEmpty()} mime=${knownTerminalFailureHint?.selectedMime ?: "unknown"}",
            )
        }
        if (showTuneProgress) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                color = Amber,
                strokeWidth = 3.dp,
            )
        }

        errorBannerMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .fillMaxWidth(0.82f)
                    .background(Color(0xE0181E29), RoundedCornerShape(10.dp))
                    .border(1.dp, Amber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = message,
                    color = Snow,
                    maxLines = 2,
                )
            }
        }

        volumeOverlayPercent?.let { percent ->
            LiveVolumeOverlay(
                percent = percent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 44.dp),
            )
        }

        // ── Channel Info Overlay ──
        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.CHANNEL_INFO,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            displayCurrentChannel?.let { ec ->
                LiveChannelInfoOverlay(
                    currentChannel = ec,
                    groupName = currentGroupName,
                    channelNumber = channelNumber,
                    recentChannels = state.recentlyViewedChannels,
                    favoriteChannels = state.favorites,
                    onOpenEpgGuide = {
                        openOverlay(LivePlayerOverlay.CURRENT_CHANNEL_GUIDE)
                    },
                    onOpenHistory = {
                        openOverlay(LivePlayerOverlay.CHANNEL_LIST)
                    },
                    onTuneChannel = { ch ->
                        tuneToPlaybackChannel(ch)
                    },
                    onClearRecent = {
                        viewModel.clearRecentlyViewed()
                    },
                    onDismiss = {
                        closeOverlayOrReturnToPrevious()
                    },
                )
            }
        }

        // ── Menu Bar Overlay ──
        val iptvPipEnabled = false
        fun enterPipMode(): Boolean = false

        val pipSupported = remember(iptvPipEnabled) {
            iptvPipEnabled
        }

        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.MENU_BAR,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
        ) {
            LiveMenuBarOverlay(
                videoResolution = videoResolution,
                audioCodec = audioCodec,
                onSearch = {
                    openOverlay(LivePlayerOverlay.CHANNEL_LIST)
                },
                onChannelList = {
                    openOverlay(LivePlayerOverlay.CHANNEL_LIST)
                },
                onGuide = {
                    openOverlay(LivePlayerOverlay.CURRENT_CHANNEL_GUIDE)
                },
                onPlaybackOptions = {
                    openOverlay(LivePlayerOverlay.PLAYBACK_MENU)
                },
                onPip = {
                    if (enterPipMode()) {
                        closeAllOverlays()
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.PLAYBACK_MENU,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LivePlaybackMenuOverlay(
                currentChannel = currentChannel,
                isFavorite = currentChannel?.let { channel -> state.favorites.any { it.url == channel.url } } == true,
                pictureFormats = LivePictureFormat.entries.map {
                    LivePictureFormatOption(
                        key = it.key,
                        label = it.label,
                    )
                },
                selectedPictureFormatKey = selectedPictureFormatKey,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                playbackRuntimeInfo = playbackRuntimeInfo,
                sleepTimerMinutes = sleepTimerMinutes,
                sleepTimerRemainingLabel = sleepTimerRemainingLabel,
                pipSupported = pipSupported,
                multiviewAvailable = false,
                selectedBufferPreset = selectedBufferPreset,
                onDismiss = { closeOverlayOrReturnToPrevious() },
                onOpenChannelList = { openOverlay(LivePlayerOverlay.CHANNEL_LIST) },
                onOpenGuide = { openOverlay(LivePlayerOverlay.CURRENT_CHANNEL_GUIDE) },
                onOpenChannelInfo = { openOverlay(LivePlayerOverlay.CHANNEL_INFO) },
                onToggleFavorite = { currentChannel?.let(viewModel::toggleFavorite) },
                onReloadStream = { reloadCurrentChannel() },
                onEnterPip = {
                    if (enterPipMode()) {
                        closeAllOverlays()
                    }
                },
                onSelectPictureFormat = { formatKey ->
                    selectedPictureFormatKey = formatKey
                },
                onSelectAudioTrack = { engine?.selectAudioTrack(it) },
                onSelectSubtitleTrack = { engine?.selectSubtitleTrack(it) },
                onDisableSubtitles = { engine?.disableSubtitles() },
                onSelectAudioOutputMode = { viewModel.setLiveAudioOutputMode(it) },
                onSelectBufferSize = { preset ->
                    selectedBufferPreset = preset
                    val exo = engine as? ExoPlayerEngine
                    exo?.setLiveBufferSize(preset.durationMs)
                    currentChannel?.let { ch -> saveChannelBufferPreset(ch.url, preset) }
                    errorBannerMessage = "Buffer set to ${preset.label} — applies on next tune or reload."
                },
                onSelectSleepTimer = { minutes ->
                    sleepTimerMinutes = minutes
                    sleepTimerTargetElapsedMs = minutes?.let {
                        android.os.SystemClock.elapsedRealtime() + (it * 60_000L)
                    } ?: 0L
                    errorBannerMessage = if (minutes == null) {
                        "Sleep timer disabled."
                    } else {
                        "Sleep timer set for $minutes minutes."
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.CURRENT_CHANNEL_GUIDE,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            displayCurrentChannel?.let { ec ->
                LiveCurrentChannelGuideOverlay(
                    channel = ec,
                    programmes = currentChannelProgrammes,
                    activeReplayProgramme = activeReplayProgramme,
                    canReplayProgramme = ::canReplayProgramme,
                    onReplayProgramme = ::replayProgramme,
                    onOpenFullGuide = { openOverlay(LivePlayerOverlay.EPG_GUIDE) },
                    onWatchLive = if (activeReplayProgramme != null) {
                        { resumeLiveProgramme() }
                    } else {
                        null
                    },
                )
            }
        }

        // ── EPG Guide Overlay ──
        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.EPG_GUIDE,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LiveEpgGuideOverlay(
                guideChannels = playbackGuideChannels,
                guideProgrammes = playbackProgrammes,
                playlistId = state.selectedPlaylistId,
                currentChannelUrl = currentChannel?.url ?: "",
                onTuneChannel = { ch ->
                    tuneToPlaybackChannel(ch)
                },
                onShowChannelList = {
                    openOverlay(LivePlayerOverlay.CHANNEL_LIST)
                },
            )
        }

        // ── Channel List Overlay ──
        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.CHANNEL_LIST,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            // Reuse the shelves that the channels screen already loaded into TvScreenCache.
            // Same key format/lookup as TvIptvScreen — pre-populates the overlay so most
            // categories render without a fresh DB query and no main-thread allocation churn.
            val preloadedShelfMap = remember(state.selectedPlaylistId, state.xxxEnabled, playbackProgrammes) {
                val key = liveShelvesCacheKey(state.selectedPlaylistId, state.xxxEnabled)
                TvScreenCache.get<Map<String, LiveShelfLoad>>(key)
                    ?.mapValues { (_, shelf) -> shelf.channels.map(::withPlaybackGuide) }
                    .orEmpty()
            }
            LiveChannelListOverlay(
                categories = playbackCategories,
                currentChannelUrl = currentChannel?.url ?: "",
                currentGroupName = currentGroupName,
                favoriteChannels = state.favorites,
                onTuneChannel = { ch, selectedGroupName ->
                    tuneToPlaybackChannel(ch, selectedGroupName)
                },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                // Local-cache-only restore for categories that were warmed by
                // CatalogWarmupWorker but not yet touched on the Channels page.
                // This deliberately avoids the old getChannelsForCategoryDirect()
                // DB/full-catalog path that caused playback overlay ANRs/OOMs.
                onLoadCategoryChannels = { categoryName ->
                    val playlistId = currentChannel?.playlistId?.takeIf { it.isNotBlank() }
                        ?: state.selectedPlaylistId
                    if (playlistId.isNullOrBlank()) {
                        emptyList()
                    } else {
                        val restored = withContext(Dispatchers.IO) {
                            val userId = localSettingsRepo.getString(AuthClient.KEY_AUTH_USER_ID)
                            userId?.let {
                                readLiveBootstrapShelf(
                                    localSettingsRepo = localSettingsRepo,
                                    userId = it,
                                    playlistId = playlistId,
                                    categoryName = categoryName,
                                )
                            }
                        }?.filterAdult(allowAdult = state.xxxEnabled)
                        val channels = restored?.channels.orEmpty().map(::withPlaybackGuide)
                        if (channels.isNotEmpty()) {
                            val key = liveShelvesCacheKey(state.selectedPlaylistId, state.xxxEnabled)
                            val existing = TvScreenCache.get<Map<String, LiveShelfLoad>>(key).orEmpty()
                            TvScreenCache.put(key, existing + (categoryName to restored!!))
                        }
                        channels
                    }
                },
                preloadedChannelsByCategory = preloadedShelfMap,
                onDismiss = {
                    closeOverlayOrReturnToPrevious()
                },
            )
        }

        // ── Settings Overlay ──
        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.SETTINGS_PANEL,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LiveSettingsOverlay(
                state = state,
                currentChannel = currentChannel,
                pictureFormats = LivePictureFormat.entries.map {
                    LivePictureFormatOption(
                        key = it.key,
                        label = it.label,
                    )
                },
                selectedPictureFormatKey = selectedPictureFormatKey,
                onSelectPlaylist = { viewModel.selectPlaylist(it) },
                onToggleCountry = { viewModel.toggleCountry(it) },
                onSelectAllCountries = { viewModel.setCountryFilter(state.availableCountries.toSet()) },
                onClearAllCountries = { viewModel.clearCountryFilter() },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onSetPictureFormat = { formatKey ->
                    selectedPictureFormatKey = formatKey
                },
                onSetXxxEnabled = { viewModel.setXxxEnabled(it) },
                onSetAudioPassthroughEnabled = { viewModel.setAudioPassthroughEnabled(it) },
                onSetPreferSurroundCodecs = { viewModel.setPreferSurroundCodecs(it) },
                onSetLiveAudioOutputMode = { viewModel.setLiveAudioOutputMode(it) },
                audioDelayMs = audioDelayMs,
                onSetAudioDelay = { audioDelayMs = it },
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                onSelectAudioTrack = { engine?.selectAudioTrack(it) },
                onSelectSubtitleTrack = { engine?.selectSubtitleTrack(it) },
                onDisableSubtitles = { engine?.disableSubtitles() },
            )
        }
    }
}

// ── Helper functions ──

@Composable
private fun LiveVolumeOverlay(
    percent: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(220.dp)
            .background(Color(0xD8181E29), RoundedCornerShape(18.dp))
            .border(1.dp, Amber.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Volume",
                color = Snow,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(64.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(999.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percent / 100f)
                        .height(5.dp)
                        .background(Amber, RoundedCornerShape(999.dp)),
                )
            }
            Text(
                text = "$percent%",
                color = Snow,
                fontSize = 12.sp,
                modifier = Modifier
                    .width(44.dp)
                    .padding(start = 10.dp),
            )
        }
    }
}

private fun shouldAttemptSilentSessionRecovery(
    selectedTrack: TrackDescription?,
    alternateTrack: TrackDescription?,
    engineId: LivePlayerEngineId,
    rememberedHint: LiveAudioCompatibilityHint?,
): Boolean {
    if (rememberedHint != null && rememberedHint.preferredEngineId() == engineId) {
        val preferredTrack = rememberedHint.preferredTrack
        if (preferredTrack == null || selectedTrack?.matches(preferredTrack) == true) {
            return false
        }
    }

    val riskySelectedTrack = selectedTrack == null || selectedTrack.isRiskyLiveAudioTrack()
    return riskySelectedTrack && (alternateTrack != null || rememberedHint == null)
}

private fun findBestAlternateAudioTrack(
    audioTracks: List<TrackDescription>,
    selectedTrack: TrackDescription?,
    passthroughEnabled: Boolean,
    preferSurround: Boolean,
    outputMode: LiveAudioOutputMode,
): TrackDescription? {
    if (audioTracks.size < 2) return null
    val selectedScore = selectedTrack?.compatibilityScore(
        passthroughEnabled = passthroughEnabled,
        preferSurround = preferSurround,
        outputMode = outputMode,
    ) ?: Int.MIN_VALUE
    return audioTracks
        .filter { track -> track.id != selectedTrack?.id }
        .maxByOrNull { track ->
            track.compatibilityScore(
                passthroughEnabled = passthroughEnabled,
                preferSurround = preferSurround,
                outputMode = outputMode,
            )
        }
        ?.takeIf { candidate ->
            val candidateScore = candidate.compatibilityScore(
                passthroughEnabled = passthroughEnabled,
                preferSurround = preferSurround,
                outputMode = outputMode,
            )
            selectedTrack == null || candidateScore >= selectedScore + MIN_ALTERNATE_TRACK_SCORE_DELTA
        }
}

private fun buildSilentAudioDiagnostics(
    engineId: LivePlayerEngineId,
    rememberedHint: LiveAudioCompatibilityHint?,
    selectedTrack: TrackDescription?,
    alternateTrack: TrackDescription?,
    audioTracks: List<TrackDescription>,
    passthroughEnabled: Boolean,
    preferSurround: Boolean,
    outputMode: LiveAudioOutputMode,
): String {
    val inventory = audioTracks.joinToString(separator = " | ") { it.debugSummary() }
    return listOf(
        "engine=${engineId.storageValue}",
        "selected=${selectedTrack?.debugSummary() ?: "none"}",
        "alternate=${alternateTrack?.debugSummary() ?: "none"}",
        "trackCount=${audioTracks.size}",
        "passthrough=$passthroughEnabled",
        "mode=${outputMode.storageValue}",
        "preferSurround=$preferSurround",
        "rememberedEngine=${rememberedHint?.preferredEngine ?: "none"}",
        "rememberedTrack=${rememberedHint?.preferredTrack?.formatKey ?: "none"}",
        "inventory=${inventory.take(1024)}",
    ).joinToString(separator = " ")
}

private fun TrackDescription.toLiveAudioTrackHint(): LiveAudioTrackHint {
    return LiveAudioTrackHint(
        label = label,
        language = language,
        formatKey = formatHint.normalizeAudioFormatHint(),
        channelCount = channelCount,
    )
}

private fun TrackDescription.matches(trackHint: LiveAudioTrackHint): Boolean {
    return formatHint.normalizeAudioFormatHint() == trackHint.formatKey &&
        language.equals(trackHint.language, ignoreCase = true) &&
        label.equals(trackHint.label, ignoreCase = true) &&
        (trackHint.channelCount == null || channelCount == null || channelCount == trackHint.channelCount)
}

private fun TrackDescription.debugSummary(): String {
    return listOf(
        "id=$id",
        "format=${formatHint ?: "unknown"}",
        "channels=${channelCount ?: -1}",
        "lang=${language ?: "und"}",
        "selected=$isSelected",
        "label=$label",
    ).joinToString(separator = ",")
}

private fun TrackDescription.isRiskyLiveAudioTrack(): Boolean {
    val format = formatHint.normalizeAudioFormatHint()
    return when {
        format == null -> true
        format in SAFE_LIVE_AUDIO_FORMATS -> false
        format in RISKY_LIVE_AUDIO_FORMATS -> true
        format.startsWith("audio/") -> format !in SAFE_LIVE_AUDIO_FORMATS
        else -> true
    }
}

private fun TrackDescription.compatibilityScore(
    passthroughEnabled: Boolean,
    preferSurround: Boolean,
    outputMode: LiveAudioOutputMode,
): Int {
    val normalizedFormat = formatHint.normalizeAudioFormatHint()
    var score = when (normalizedFormat) {
        "aac",
        "mp4a",
        "audio/aac",
        "audio/mp4a-latm" -> 210
        "opus",
        "audio/opus" -> 190
        "mp3",
        "mp2",
        "mpeg-l2",
        "audio/mp2",
        "audio/mpeg-l2",
        "mpeg",
        "audio/mpeg" -> 175
        "pcm",
        "audio/raw" -> 170
        "flac",
        "audio/flac" -> 160
        "vorbis",
        "audio/vorbis" -> 150
        "ac4",
        "audio/ac4" -> if (passthroughEnabled) 142 else 124
        "eac3",
        "ec-3",
        "audio/eac3",
        "audio/eac3-joc" -> when (outputMode) {
            LiveAudioOutputMode.FORCE_STEREO_PCM -> 18
            LiveAudioOutputMode.PREFER_COMPATIBLE -> 80
            LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) 148 else 92
        }
        "ac3",
        "audio/ac3" -> when (outputMode) {
            LiveAudioOutputMode.FORCE_STEREO_PCM -> 14
            LiveAudioOutputMode.PREFER_COMPATIBLE -> 74
            LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) 142 else 86
        }
        "dts",
        "audio/vnd.dts",
        "audio/vnd.dts.hd",
        "truehd",
        "mlp",
        "audio/true-hd" -> when (outputMode) {
            LiveAudioOutputMode.FORCE_STEREO_PCM -> -40
            LiveAudioOutputMode.PREFER_COMPATIBLE -> 12
            LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) 158 else 18
        }
        null -> -16
        else -> if (normalizedFormat in SAFE_LIVE_AUDIO_FORMATS) 130 else 24
    }

    score += when {
        channelCount in 1..2 -> when (outputMode) {
            LiveAudioOutputMode.FORCE_STEREO_PCM -> 70
            LiveAudioOutputMode.PREFER_COMPATIBLE -> 36
            LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) 8 else 24
        }
        (channelCount ?: 0) > 2 -> when (outputMode) {
            LiveAudioOutputMode.FORCE_STEREO_PCM -> -110
            LiveAudioOutputMode.PREFER_COMPATIBLE -> -36
            LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) 34 else -12
        }
        else -> 0
    }

    if (language.isNullOrBlank()) score -= 1
    if (label.isBlank()) score -= 2
    if (isSelected) score -= 18
    return score
}

private fun String?.normalizeAudioFormatHint(): String? {
    return this
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
}

private val SAFE_LIVE_AUDIO_FORMATS = setOf(
    "aac",
    "mp4a",
    "audio/aac",
    "audio/mp4a-latm",
    "opus",
    "audio/opus",
    "mp3",
    "mp2",
    "mpeg-l2",
    "audio/mp2",
    "audio/mpeg-l2",
    "mpeg",
    "audio/mpeg",
    "pcm",
    "audio/raw",
    "flac",
    "audio/flac",
    "vorbis",
    "audio/vorbis",
)

private val RISKY_LIVE_AUDIO_FORMATS = setOf(
    "ac3",
    "audio/ac3",
    "eac3",
    "ec-3",
    "audio/eac3",
    "audio/eac3-joc",
    "dts",
    "audio/vnd.dts",
    "audio/vnd.dts.hd",
    "truehd",
    "mlp",
    "audio/true-hd",
)

private fun findChannelByUrl(
    url: String,
    enrichedChannels: List<EnrichedChannel>,
): EnrichedChannel? = enrichedChannels.firstOrNull { it.channel.url == url }

private fun isPlayableLiveChannel(channel: Channel): Boolean {
    val url = channel.url.trim()
    if (url.isBlank()) return false
    if (url == "#" || url.equals("about:blank", ignoreCase = true)) return false
    if (channel.contentType == ChannelContentType.VOD_MOVIE || channel.contentType == ChannelContentType.VOD_SERIES) {
        return false
    }
    return !isDecorativeLiveChannelName(channel.name)
}

private fun isDecorativeLiveChannelName(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isBlank()) return true
    if (trimmed.all { it == '#' || it == '-' || it == '_' || it == '*' || it == '=' || it.isWhitespace() }) {
        return true
    }
    val hashCount = trimmed.count { it == '#' }
    if (hashCount >= 6 && trimmed.startsWith("#") && trimmed.endsWith("#")) return true
    val stripped = trimmed.trim('#', '-', '_', '*', '=', ' ')
    return stripped.isBlank()
}

private fun categoryMatchesGroup(
    category: ChannelCategory,
    groupName: String,
): Boolean = categoryNameMatchesGroup(category.name, groupName)

private fun categoryNameMatchesGroup(
    categoryName: String,
    groupName: String,
): Boolean {
    val rawGroupName = groupName.trim()
    if (rawGroupName.isBlank()) return false

    val trimmedCategoryName = categoryName.trim()
    if (trimmedCategoryName.equals(rawGroupName, ignoreCase = true)) return true

    val cleanedGroupName = CategoryNameCleaner.clean(rawGroupName).name.trim()
    if (cleanedGroupName.isNotBlank() && trimmedCategoryName.equals(cleanedGroupName, ignoreCase = true)) {
        return true
    }

    val cleanedCategoryName = CategoryNameCleaner.clean(trimmedCategoryName).name.trim()
    return cleanedCategoryName.isNotBlank() &&
        (
            cleanedCategoryName.equals(rawGroupName, ignoreCase = true) ||
                cleanedCategoryName.equals(cleanedGroupName, ignoreCase = true)
            )
}

private fun findChannelGroupAndIndex(
    channel: Channel,
    categories: List<com.torve.domain.model.ChannelCategory>,
    preferredGroupName: String? = null,
): Pair<String?, Int> {
    preferredGroupName
        ?.takeIf { it.isNotBlank() }
        ?.let { preferred ->
            val preferredCategory = categories.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
            if (preferredCategory != null) {
                val preferredIdx = preferredCategory.channels.indexOfFirst { it.channel.url == channel.url }
                if (preferredIdx >= 0) {
                    return preferredCategory.name to preferredIdx
                }
            }
        }

    for (cat in categories) {
        val idx = cat.channels.indexOfFirst { it.channel.url == channel.url }
        if (idx >= 0) return cat.name to idx
    }
    return null to 0
}

private inline fun tuneToChannel(
    channel: Channel,
    categories: List<com.torve.domain.model.ChannelCategory>,
    preferredGroupName: String? = null,
    onResult: (Channel, String, Int) -> Unit,
) {
    val (group, idx) = findChannelGroupAndIndex(channel, categories, preferredGroupName)
    onResult(channel, group ?: channel.groupTitle.orEmpty(), idx)
}
