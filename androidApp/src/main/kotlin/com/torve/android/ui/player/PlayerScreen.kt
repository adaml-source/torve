package com.torve.android.ui.player

import android.app.Activity
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.torve.android.R
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.torve.android.player.AudioEqualizer
import com.torve.android.device.DeviceFormFactor
import com.torve.android.cast.CastService
import com.torve.android.player.ExoPlayerEngine
import com.torve.android.player.MPVPlayerEngine
import com.torve.android.player.MPVView
import com.torve.android.sync.SyncCoordinator
import com.torve.android.tv.settings.rememberTvReduceMotionPreference
import com.torve.android.voice.PlayerVoiceCommand
import com.torve.android.voice.PlayerVoiceCommandParser
import com.torve.android.voice.VoiceInputPhase
import com.torve.android.voice.rememberVoiceInputController
import com.torve.android.ui.sync.SyncDevicePickerDialog
import com.torve.data.simkl.SimklClient
import com.torve.data.simkl.SimklIds
import com.torve.data.simkl.SimklSyncBody
import com.torve.data.simkl.SimklSyncItem
import com.torve.data.trakt.TraktClient
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.data.trakt.TraktHistoryBody
import com.torve.data.trakt.TraktHistoryMovie
import com.torve.data.trakt.TraktHistoryShow
import com.torve.data.trakt.TraktIds
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import com.torve.domain.model.WatchProgress
import com.torve.domain.player.NextEpisodeHelper
import com.torve.domain.player.NextEpisodeInfo
import com.torve.domain.player.PlayerEngine
import com.torve.domain.player.SkipSegment
import com.torve.domain.player.SkipSegmentDetector
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.domain.player.TrackDescription
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.player.TraktScrobbler
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    fallbackUrl: String = "",
    title: String = "",
    mediaId: String = "",
    mediaType: String = "movie",
    posterUrl: String = "",
    backdropUrl: String = "",
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    showTmdbId: Int? = null,
    showImdbId: String? = null,
    startPositionMs: Long = 0L,
    onVoiceSearchCommand: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    watchProgressRepo: WatchProgressRepository = koinInject(),
    metadataRepo: MetadataRepository = koinInject(),
    streamRepo: StreamRepository = koinInject(),
    addonRepo: AddonRepository = koinInject(),
    channelsViewModel: ChannelsViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    syncCoordinator: SyncCoordinator = koinInject(),
    traktScrobbler: TraktScrobbler = koinInject(),
    traktClient: TraktClient = koinInject(),
    simklClient: SimklClient = koinInject(),
    integrationSecretStore: IntegrationSecretStore = koinInject(),
    prefsRepo: PreferencesRepository = koinInject(),
) {
    val context = LocalContext.current
    val isTv = remember(context) { DeviceFormFactor.isTv(context) }

    // Google Cast (injected; no-op on Amazon builds)
    val castService: CastService = koinInject()
    val castAvailable = castService.isAvailable

    // Immersive fullscreen + landscape + keep screen on
    DisposableEffect(Unit) {
        val activity = context as? Activity ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val originalOrientation = activity.requestedOrientation
        val controller = WindowInsetsControllerCompat(window, window.decorView)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
            activity.requestedOrientation = originalOrientation
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val scope = rememberCoroutineScope()
    val syncState by syncCoordinator.state.collectAsState()
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var showAudioDelayDialog by remember { mutableStateOf(false) }
    var showPictureFormatPicker by remember { mutableStateOf(false) }
    var subtitleTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var audioTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var useMpv by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var codecFallbackUsed by remember { mutableStateOf(false) }
    var codecFallbackInProgress by remember { mutableStateOf(false) }
    var audioDelayMs by remember { mutableIntStateOf(0) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var pictureFormat by remember { mutableStateOf(PlayerPictureFormat.SOURCE) }
    var audioEqualizer by remember { mutableStateOf<AudioEqualizer?>(null) }
    var exoPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    var topMenuFocusTick by remember { mutableIntStateOf(0) }
    var lastTopMenuFocusTarget by remember { mutableStateOf(TopMenuFocusTarget.BACK) }
    var seekRepeatDirection by remember { mutableIntStateOf(0) }
    var seekRepeatCount by remember { mutableIntStateOf(0) }
    var seekRepeatLastAtMs by remember { mutableLongStateOf(0L) }
    val resumePromptInitialPositionMs = remember(url, mediaId, startPositionMs) {
        startPositionMs.coerceAtLeast(0L)
    }
    var pendingStartPositionMs by remember(url, mediaId, startPositionMs) { mutableLongStateOf(0L) }
    var showResumePrompt by remember(url, mediaId, startPositionMs) {
        mutableStateOf(resumePromptInitialPositionMs >= 20_000L)
    }
    var initialStartPositionConsumed by remember(url, mediaId, startPositionMs) { mutableStateOf(false) }
    val playerRootFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val topMenuFocusRequester = remember { FocusRequester() }
    val topCastFocusRequester = remember { FocusRequester() }
    val topHandoffFocusRequester = remember { FocusRequester() }
    val topVoiceFocusRequester = remember { FocusRequester() }
    val topTracksFocusRequester = remember { FocusRequester() }
    val topAudioDelayFocusRequester = remember { FocusRequester() }
    val topEqualizerFocusRequester = remember { FocusRequester() }
    val topPictureFormatFocusRequester = remember { FocusRequester() }
    val topSpeedFocusRequester = remember { FocusRequester() }

    // Mutable episode state — updated when swapping to next episode
    var currentSeasonNumber by remember { mutableStateOf(seasonNumber) }
    var currentEpisodeNumber by remember { mutableStateOf(episodeNumber) }
    var currentUrl by remember { mutableStateOf(url) }
    var currentTitle by remember { mutableStateOf(title) }

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
    val tmdbId = mediaId.toIntOrNull() ?: 0
    val parsedMediaType = MediaType.fromString(mediaType)
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

    fun focusRequesterForTopTarget(target: TopMenuFocusTarget): FocusRequester {
        return when (target) {
            TopMenuFocusTarget.BACK -> topMenuFocusRequester
            TopMenuFocusTarget.CAST -> topCastFocusRequester
            TopMenuFocusTarget.HANDOFF -> topHandoffFocusRequester
            TopMenuFocusTarget.VOICE -> topVoiceFocusRequester
            TopMenuFocusTarget.TRACKS -> topTracksFocusRequester
            TopMenuFocusTarget.AUDIO_DELAY -> topAudioDelayFocusRequester
            TopMenuFocusTarget.EQUALIZER -> topEqualizerFocusRequester
            TopMenuFocusTarget.PICTURE_FORMAT -> topPictureFormatFocusRequester
            TopMenuFocusTarget.SPEED -> topSpeedFocusRequester
        }
    }

    val visibleTopMenuTargets = buildList {
        add(TopMenuFocusTarget.BACK)
        if (castAvailable) add(TopMenuFocusTarget.CAST)
        add(TopMenuFocusTarget.HANDOFF)
        add(TopMenuFocusTarget.VOICE)
        if (subtitleTracks.isNotEmpty() || audioTracks.isNotEmpty()) add(TopMenuFocusTarget.TRACKS)
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

    fun topMenuItemModifier(target: TopMenuFocusTarget): Modifier {
        return Modifier
            .focusRequester(focusRequesterForTopTarget(target))
            .focusProperties {
                left = focusRequesterForTopTarget(topMenuNeighbor(target, -1))
                right = focusRequesterForTopTarget(topMenuNeighbor(target, 1))
                down = playButtonFocusRequester
            }
    }

    fun requestTopMenuFocus(preferred: TopMenuFocusTarget? = null): Boolean {
        val targets = (
            listOfNotNull(preferred, lastTopMenuFocusTarget, TopMenuFocusTarget.BACK) +
                visibleTopMenuTargets
            ).distinct()
        for (target in targets) {
            val requested = runCatching {
                focusRequesterForTopTarget(target).requestFocus()
                true
            }.getOrDefault(false)
            if (requested) {
                lastTopMenuFocusTarget = target
                return true
            }
        }
        return false
    }

    // Create the player engine once (not keyed on URL for in-place swaps)
    val engine = remember {
        val mpvEngine = MPVPlayerEngine(context)
        if (mpvEngine.initialize()) {
            useMpv = true
            mpvEngine as PlayerEngine
        } else {
            val exoEngine = ExoPlayerEngine(context)
            exoEngine.initialize()
            exoEngine as PlayerEngine
        }
    }

    // Apply global audio output preferences for all playback (not only live TV).
    LaunchedEffect(useMpv, channelsState.audioPassthroughEnabled, channelsState.preferSurroundCodecs) {
        if (useMpv) {
            (engine as? MPVPlayerEngine)?.setAudioOutputPreferences(
                passthroughEnabled = channelsState.audioPassthroughEnabled,
                preferSurround = channelsState.preferSurroundCodecs,
            )
        } else {
            (engine as? ExoPlayerEngine)?.setAudioOutputPreferences(
                passthroughEnabled = channelsState.audioPassthroughEnabled,
                preferSurround = channelsState.preferSurroundCodecs,
            )
        }
    }

    LaunchedEffect(useMpv, pictureFormat, exoPlayerView) {
        if (useMpv) {
            (engine as? MPVPlayerEngine)?.setPictureFormat(
                aspectRatio = pictureFormat.aspectRatio,
                fill = pictureFormat.fill,
            )
        } else {
            exoPlayerView?.resizeMode = pictureFormat.exoResizeMode
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
                        currentSeasonNumber,
                        currentEpisodeNumber,
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
                        currentSeasonNumber,
                        currentEpisodeNumber,
                    )
                }
            }
        }
        showControls = true
    }

    val seekBy: (Long) -> Unit = { deltaMs ->
        engine.seekRelative(deltaMs)
        showControls = true
    }

    fun resetSeekAcceleration() {
        seekRepeatDirection = 0
        seekRepeatCount = 0
        seekRepeatLastAtMs = 0L
    }

    fun acceleratedSeekDelta(direction: Int): Long {
        val nowMs = SystemClock.uptimeMillis()
        val isRepeatBurst = seekRepeatDirection == direction && (nowMs - seekRepeatLastAtMs) <= 360L
        seekRepeatCount = if (isRepeatBurst) (seekRepeatCount + 1) else 0
        seekRepeatDirection = direction
        seekRepeatLastAtMs = nowMs
        val multiplier = PlayerNavigationMath.seekAccelerationMultiplier(seekRepeatCount)
        return direction * 10_000L * multiplier
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

    BackHandler {
        if (!handleBackAction()) {
            onBack()
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
    }

    LaunchedEffect(trackPrefsLoaded, trackPrefsAppliedForUrl, audioTracks, subtitleTracks, currentUrl) {
        if (!trackPrefsLoaded || trackPrefsAppliedForUrl) return@LaunchedEffect
        if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) return@LaunchedEffect

        preferredAudioTrackTag?.let { preferredTag ->
            audioTracks.firstOrNull { trackPreferenceTag(it) == preferredTag }?.let { track ->
                if (!track.isSelected) {
                    engine.selectAudioTrack(track.id)
                }
            }
        }
        if (subtitlesPreferredEnabled) {
            preferredSubtitleTrackTag?.let { preferredTag ->
                subtitleTracks.firstOrNull { trackPreferenceTag(it) == preferredTag }?.let { track ->
                    if (!track.isSelected) {
                        engine.selectSubtitleTrack(track.id)
                    }
                }
            }
        } else if (subtitleTracks.any { it.isSelected }) {
            engine.disableSubtitles()
        }

        trackPrefsAppliedForUrl = true
    }

    // Load season data for next-episode calculation (TV shows only)
    LaunchedEffect(showTmdbId, currentSeasonNumber) {
        if (showTmdbId == null || showTmdbId <= 0 || mediaType != "tv") return@LaunchedEffect
        if (currentSeasonNumber == null) return@LaunchedEffect

        try {
            val detail = metadataRepo.getDetail("tv", showTmdbId)
            val validSeasons = detail?.seasons
                ?.filter { it.seasonNumber > 0 }
                ?.sortedBy { it.seasonNumber }
                ?: return@LaunchedEffect

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
            showImdbId = showImdbId,
            engine = engine,
            streamRepo = streamRepo,
            addonRepo = addonRepo,
            settingsViewModel = settingsViewModel,
            watchProgressRepo = watchProgressRepo,
            mediaId = mediaId,
            mediaType = mediaType,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            currentTitle = currentTitle,
            currentPosition = currentPosition,
            duration = duration,
            currentSeasonNumber = currentSeasonNumber,
            currentEpisodeNumber = currentEpisodeNumber,
            onStateUpdate = { newSeason, newEpisode, newUrl, newTitle ->
                currentSeasonNumber = newSeason
                currentEpisodeNumber = newEpisode
                currentUrl = newUrl
                currentTitle = newTitle
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

    // MediaSession for notification bar / lock screen controls
    val mediaSession = remember(engine) {
        val exo = (engine as? ExoPlayerEngine)?.getExoPlayer() ?: return@remember null
        val metadata = MediaMetadata.Builder()
            .setTitle(title.ifBlank { "Torve" })
            .build()
        exo.mediaMetadata // trigger metadata
        MediaSession.Builder(context, exo)
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
                duration = state.durationMs
                if (!isSeeking) {
                    currentPosition = state.positionMs
                    sliderPosition = if (state.durationMs > 0) {
                        state.positionMs.toFloat() / state.durationMs
                    } else 0f
                }
                // Content ended while countdown was active — trigger immediately
                if (state.isIdle && !state.isBuffering && completionDetected &&
                    showNextEpisodeOverlay && !isResolvingNextEpisode
                ) {
                    scope.launch {
                        resolveAndPlayNextEpisode(
                            nextEpisodeInfo = nextEpisodeInfo,
                            showImdbId = showImdbId,
                            engine = engine,
                            streamRepo = streamRepo,
                            addonRepo = addonRepo,
                            settingsViewModel = settingsViewModel,
                            watchProgressRepo = watchProgressRepo,
                            mediaId = mediaId,
                            mediaType = mediaType,
                            posterUrl = posterUrl,
                            backdropUrl = backdropUrl,
                            currentTitle = currentTitle,
                            currentPosition = currentPosition,
                            duration = duration,
                            currentSeasonNumber = currentSeasonNumber,
                            currentEpisodeNumber = currentEpisodeNumber,
                            onStateUpdate = { newSeason, newEpisode, newUrl, newTitle ->
                                currentSeasonNumber = newSeason
                                currentEpisodeNumber = newEpisode
                                currentUrl = newUrl
                                currentTitle = newTitle
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
                android.util.Log.e("Player", "Playback error for URL: $currentUrl — $message")
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
                codecFallbackInProgress = true
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    errorMessage = null // clear any error that snuck in
                    if (fallbackUrl.isNotBlank() && !codecFallbackUsed) {
                        codecFallbackUsed = true
                        currentUrl = fallbackUrl
                        engine.stop()
                        engine.play(fallbackUrl)
                    } else {
                        // No fallback available — silently go back
                        onBack()
                    }
                    // Allow errors again after a short delay for the new stream to start
                    kotlinx.coroutines.delay(3000)
                    codecFallbackInProgress = false
                }
            }
        }

        engine.play(currentUrl)
        if (!initialStartPositionConsumed && !showResumePrompt && resumePromptInitialPositionMs > 0L) {
            pendingStartPositionMs = resumePromptInitialPositionMs
            initialStartPositionConsumed = true
        }

        // Scrobble start on initial playback
        if (canScrobble) {
            scope.launch {
                traktScrobbler.start(
                    traktAccessToken, tmdbId, parsedMediaType, 0.0,
                    currentSeasonNumber, currentEpisodeNumber,
                )
            }
        }

        onDispose {
            // Save final progress on dispose
            val finalPosition = engine.state.positionMs
            val finalDuration = duration
            val finalContentId = mediaId.ifBlank { showTmdbId?.toString().orEmpty() }
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
                        currentSeasonNumber, currentEpisodeNumber,
                    )
                }
            }
            engine.removeListener(listener)
            audioEqualizer?.release()
            engine.release()
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
        if (progressPercent > 0.80) {
            hasMarkedWatched = true
            // Trakt
            if (canScrobble) {
                try {
                    val ids = TraktIds(tmdb = tmdbId)
                    val body = if (parsedMediaType == MediaType.MOVIE) {
                        TraktHistoryBody(movies = listOf(TraktHistoryMovie(ids = ids)))
                    } else {
                        TraktHistoryBody(shows = listOf(TraktHistoryShow(ids = ids)))
                    }
                    traktClient.addToHistory(traktAccessToken, body)
                } catch (_: Exception) { }
            }
            // Simkl
            try {
                val simklToken = integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
                if (!simklToken.isNullOrBlank()) {
                    val simklIds = SimklIds(tmdb = tmdbId)
                    val simklBody = if (parsedMediaType == MediaType.MOVIE) {
                        SimklSyncBody(movies = listOf(SimklSyncItem(simklIds)))
                    } else {
                        SimklSyncBody(shows = listOf(SimklSyncItem(simklIds)))
                    }
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

    // Auto-hide controls after 5 seconds on non-TV devices.
    var controlsInteractionTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(showControls, controlsInteractionTick, isTv, showTrackDialog, showAudioDelayDialog, showPictureFormatPicker, showEqualizerSheet, showDevicePicker, showResumePrompt) {
        if (!showControls) return@LaunchedEffect
        if (isTv) return@LaunchedEffect
        if (showTrackDialog || showAudioDelayDialog || showPictureFormatPicker || showEqualizerSheet || showDevicePicker || showResumePrompt) return@LaunchedEffect
        delay(5000)
        showControls = false
    }

    LaunchedEffect(currentUrl, pendingStartPositionMs, showResumePrompt) {
        val seekTarget = pendingStartPositionMs
        if (seekTarget <= 0L || showResumePrompt) return@LaunchedEffect
        delay(450)
        engine.seekTo(seekTarget)
        pendingStartPositionMs = 0L
    }

    LaunchedEffect(voiceFeedbackMessage) {
        if (voiceFeedbackMessage != null) {
            delay(2200)
            voiceFeedbackMessage = null
        }
    }

    LaunchedEffect(Unit) {
        playerRootFocusRequester.requestFocus()
    }

    // Restore top-menu focus on TV so opening/closing overlays never leaves focus lost.
    LaunchedEffect(
        showControls,
        topMenuFocusTick,
        isTv,
        showTrackDialog,
        showAudioDelayDialog,
        showPictureFormatPicker,
        showEqualizerSheet,
        showDevicePicker,
        showResumePrompt,
    ) {
        if (showControls) {
            if (showTrackDialog || showAudioDelayDialog || showPictureFormatPicker || showEqualizerSheet || showDevicePicker || showResumePrompt) {
                return@LaunchedEffect
            }
            delay(50)
            try {
                if (isTv) {
                    var focused = false
                    for (attempt in 0 until 6) {
                        if (requestTopMenuFocus(lastTopMenuFocusTarget)) {
                            focused = true
                            break
                        }
                        if (attempt < 5) {
                            delay(40)
                        }
                    }
                    if (!focused) {
                        requestTopMenuFocus(TopMenuFocusTarget.BACK)
                    }
                } else {
                    playButtonFocusRequester.requestFocus()
                }
            } catch (_: IllegalStateException) {
                // Not yet attached
            }
        } else {
            try {
                playerRootFocusRequester.requestFocus()
            } catch (_: IllegalStateException) { }
        }
    }

    val handoffTargets = syncCoordinator.targetDevices()
        .filter { it.deviceType.contains("tv", ignoreCase = true) }
    val handoffContentId = mediaId.ifBlank { showTmdbId?.toString().orEmpty() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(playerRootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                if ((showTrackDialog || showAudioDelayDialog || showPictureFormatPicker || showEqualizerSheet || showDevicePicker || showResumePrompt) && keyEvent.key != Key.Back) {
                    return@onPreviewKeyEvent false
                }

                // When controls are visible, let D-pad navigate between buttons.
                // Only intercept Back and media keys at root level.
                if (showControls) {
                    return@onPreviewKeyEvent when (keyEvent.key) {
                        Key.Back -> {
                            resetSeekAcceleration()
                            if (!handleBackAction()) {
                                showControls = false
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
                                val focused = requestTopMenuFocus(lastTopMenuFocusTarget)
                                if (!focused) {
                                    runCatching { playButtonFocusRequester.requestFocus() }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionDown -> {
                            resetSeekAcceleration()
                            controlsInteractionTick++
                            if (isTv) {
                                val focused = runCatching {
                                    playButtonFocusRequester.requestFocus()
                                    true
                                }.getOrDefault(false)
                                if (!focused) {
                                    requestTopMenuFocus(lastTopMenuFocusTarget)
                                }
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            controlsInteractionTick++
                            false
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            resetSeekAcceleration()
                            controlsInteractionTick++
                            false
                        }
                        else -> {
                            resetSeekAcceleration()
                            false
                        }
                    }
                }

                // Controls hidden — handle all D-pad keys for media shortcuts
                when (keyEvent.key) {
                    Key.Back -> {
                        resetSeekAcceleration()
                        if (!handleBackAction()) {
                            onBack()
                        }
                        true
                    }
                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter,
                    Key.Spacebar,
                    Key.MediaPlayPause,
                    -> {
                        resetSeekAcceleration()
                        togglePlayback()
                        true
                    }
                    Key.DirectionLeft -> {
                        seekBy(acceleratedSeekDelta(direction = -1))
                        true
                    }
                    Key.DirectionRight -> {
                        seekBy(acceleratedSeekDelta(direction = 1))
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
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
        // Video surface
        if (useMpv) {
            // MPV SurfaceView
            AndroidView(
                factory = { ctx ->
                    MPVView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // ExoPlayer PlayerView
            AndroidView(
                factory = {
                    PlayerView(context).apply {
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        resizeMode = pictureFormat.exoResizeMode
                        exoPlayerView = this
                    }
                },
                update = { view ->
                    view.player = (engine as? ExoPlayerEngine)?.getExoPlayer()
                    view.resizeMode = pictureFormat.exoResizeMode
                    exoPlayerView = view
                },
                onRelease = { view ->
                    view.player = null
                    if (exoPlayerView == view) exoPlayerView = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Error overlay
        errorMessage?.let { msg ->
            if (isTv) {
                TvPlaybackErrorBanner(
                    message = msg,
                    onRetry = {
                        errorMessage = null
                        engine.play(currentUrl)
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
                                    engine.play(currentUrl)
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
                                onClick = onBack,
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

        // Track selection dialog
        if (showTrackDialog) {
            if (isTv) {
                TvTrackSelectionOverlay(
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

        if (showPictureFormatPicker && isTv) {
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

        // Skip Intro/Credits button
        activeSkipSegment?.let { segment ->
            Button(
                onClick = {
                    engine.seekTo(segment.endMs)
                    dismissedSkipSegments = dismissedSkipSegments + segment.type.name
                    activeSkipSegment = null
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 80.dp),
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
        }

        // Next Episode overlay
        if (showNextEpisodeOverlay && nextEpisodeInfo != null) {
            NextEpisodeOverlay(
                nextEpisodeInfo = nextEpisodeInfo!!,
                countdown = nextEpisodeCountdown,
                isResolving = isResolvingNextEpisode,
                onPlayNow = {
                    scope.launch {
                        resolveAndPlayNextEpisode(
                            nextEpisodeInfo = nextEpisodeInfo,
                            showImdbId = showImdbId,
                            engine = engine,
                            streamRepo = streamRepo,
                            addonRepo = addonRepo,
                            settingsViewModel = settingsViewModel,
                            watchProgressRepo = watchProgressRepo,
                            mediaId = mediaId,
                            mediaType = mediaType,
                            posterUrl = posterUrl,
                            backdropUrl = backdropUrl,
                            currentTitle = currentTitle,
                            currentPosition = currentPosition,
                            duration = duration,
                            currentSeasonNumber = currentSeasonNumber,
                            currentEpisodeNumber = currentEpisodeNumber,
                            onStateUpdate = { newSeason, newEpisode, newUrl, newTitle ->
                                currentSeasonNumber = newSeason
                                currentEpisodeNumber = newEpisode
                                currentUrl = newUrl
                                currentTitle = newTitle
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
            val resumeTarget = if (duration > 0L) {
                resumePromptInitialPositionMs.coerceAtMost(duration)
            } else {
                resumePromptInitialPositionMs
            }
            if (isTv) {
                TvResumePlaybackOverlay(
                    title = currentTitle.ifBlank { "Resume Playback" },
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
                    title = { Text("Resume Playback") },
                    text = { Text("Continue from ${formatTime(resumeTarget)} or start over?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingStartPositionMs = resumeTarget
                                initialStartPositionConsumed = true
                                showResumePrompt = false
                            },
                        ) {
                            Text("Resume")
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
                            Text("Start Over")
                        }
                    },
                )
            }
        }

        val tvModalOverlayOpen = isTv && (
            showTrackDialog ||
                showAudioDelayDialog ||
                showPictureFormatPicker ||
                showEqualizerSheet ||
                showResumePrompt ||
                showDevicePicker
            )

        // Controls overlay
        if (showControls && !tvModalOverlayOpen) {
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
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusableIconButton(
                        onClick = onBack,
                        modifier = topMenuItemModifier(TopMenuFocusTarget.BACK),
                        onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.BACK },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    if (currentTitle.isNotBlank()) {
                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
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
                                        posterUrl = posterUrl.ifBlank { null },
                                    )
                                }
                                castService.showCastDialog()
                            },
                            modifier = topMenuItemModifier(TopMenuFocusTarget.CAST),
                            onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.CAST },
                        ) {
                            Icon(
                                if (castService.isCasting) Icons.Default.CastConnected else Icons.Default.Cast,
                                contentDescription = "Cast",
                                tint = if (castService.isCasting) com.torve.android.ui.theme.Amber else Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    FocusableIconButton(
                        onClick = {
                            when {
                                !syncState.isAuthenticated -> {
                                    Toast.makeText(context, "Create a local profile to transfer playback", Toast.LENGTH_SHORT).show()
                                }
                                handoffTargets.isEmpty() -> {
                                    syncCoordinator.refreshDevices()
                                    Toast.makeText(context, "No paired TV devices found", Toast.LENGTH_SHORT).show()
                                }
                                else -> showDevicePicker = true
                            }
                        },
                        modifier = topMenuItemModifier(TopMenuFocusTarget.HANDOFF),
                        onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.HANDOFF },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "Play on device",
                            tint = if (syncState.isAuthenticated) com.torve.android.ui.theme.Amber else Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }

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
                        onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.VOICE },
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
                            contentDescription = "Voice command",
                            tint = voiceTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    if (subtitleTracks.isNotEmpty() || audioTracks.isNotEmpty()) {
                        FocusableIconButton(
                            onClick = { showTrackDialog = true },
                            modifier = topMenuItemModifier(TopMenuFocusTarget.TRACKS),
                            onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.TRACKS },
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.player_track_selection),
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    FocusableIconButton(
                        onClick = { showAudioDelayDialog = true },
                        modifier = topMenuItemModifier(TopMenuFocusTarget.AUDIO_DELAY),
                        onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.AUDIO_DELAY },
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Audio delay",
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
                            onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.EQUALIZER },
                        ) {
                            Icon(
                                Icons.Default.Equalizer,
                                contentDescription = "Equalizer",
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
                            onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.PICTURE_FORMAT },
                        ) {
                            Text(
                                text = pictureFormat.shortLabel,
                                color = if (pictureFormat == PlayerPictureFormat.SOURCE) Color.White else com.torve.android.ui.theme.Amber,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
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
                        onFocused = { lastTopMenuFocusTarget = TopMenuFocusTarget.SPEED },
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
                    -> voiceController.uiState.value.message ?: "Voice input is not available on this device"

                    VoiceInputPhase.Idle -> voiceFeedbackMessage
                }
                if (!voiceOverlayMessage.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
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
                        onClick = { seekBy(-10_000) },
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
                                up = focusRequesterForTopTarget(lastTopMenuFocusTarget)
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
                        onClick = { seekBy(10_000) },
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
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    val seekPreviewPositionMs = if (isSeeking && duration > 0L) {
                        (sliderPosition * duration).toLong().coerceIn(0L, duration)
                    } else {
                        currentPosition
                    }
                    Slider(
                        value = sliderPosition,
                        onValueChange = {
                            isSeeking = true
                            sliderPosition = it
                        },
                        onValueChangeFinished = {
                            engine.seekTo((sliderPosition * duration).toLong())
                            isSeeking = false
                        },
                        modifier = Modifier.fillMaxWidth(),
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
                    if (isSeeking) {
                        Text(
                            text = "Preview ${formatTime(seekPreviewPositionMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.86f),
                            modifier = Modifier.align(Alignment.End),
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
    }

    if (showDevicePicker) {
        SyncDevicePickerDialog(
            title = "Play On Device",
            devices = handoffTargets,
            onSelectDevice = { device ->
                showDevicePicker = false
                topMenuFocusTick++
                if (handoffContentId.isBlank()) {
                    Toast.makeText(context, "This title cannot be handed off", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(context, "Playback sent to ${device.deviceName}", Toast.LENGTH_SHORT).show()
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
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (reduceMotion) 1f else if (focused) 1.15f else 1f,
        label = "iconBtnScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
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
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private suspend fun resolveAndPlayNextEpisode(
    nextEpisodeInfo: NextEpisodeInfo?,
    showImdbId: String?,
    engine: PlayerEngine,
    streamRepo: StreamRepository,
    addonRepo: AddonRepository,
    settingsViewModel: SettingsViewModel,
    watchProgressRepo: WatchProgressRepository,
    mediaId: String,
    mediaType: String,
    posterUrl: String,
    backdropUrl: String,
    currentTitle: String,
    currentPosition: Long,
    duration: Long,
    currentSeasonNumber: Int?,
    currentEpisodeNumber: Int?,
    onStateUpdate: (newSeason: Int, newEpisode: Int, newUrl: String, newTitle: String) -> Unit,
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

        val streams = streamRepo.fetchStreams(
            type = MediaType.SERIES,
            imdbId = imdbId,
            season = nextEp.seasonNumber,
            episode = nextEp.episodeNumber,
            addons = addons,
            debridAccounts = debridAccounts,
            preferences = preferences,
        )

        if (streams.isEmpty()) {
            onFailed()
            return
        }

        val provider = settingsViewModel.getDebridProvider()
        val apiKey = settingsViewModel.getDebridApiKey()
        val resolved = streamRepo.resolveStream(streams.first(), provider, apiKey)
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

        // Scrobble stop for current episode before switching
        if (traktScrobbler != null && traktAccessToken.isNotBlank() && tmdbId > 0) {
            try {
                traktScrobbler.stop(
                    traktAccessToken, tmdbId, MediaType.SERIES, 100.0,
                    currentSeasonNumber, currentEpisodeNumber,
                )
            } catch (_: Exception) {}
        }

        // Stop current and play new
        engine.stop()
        onStateUpdate(nextEp.seasonNumber, nextEp.episodeNumber, playUrl, newTitle)
        engine.play(playUrl)

        // Scrobble start for new episode
        if (traktScrobbler != null && traktAccessToken.isNotBlank() && tmdbId > 0) {
            try {
                traktScrobbler.start(
                    traktAccessToken, tmdbId, MediaType.SERIES, 0.0,
                    nextEp.seasonNumber, nextEp.episodeNumber,
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
    TRACKS,
    AUDIO_DELAY,
    EQUALIZER,
    PICTURE_FORMAT,
    SPEED,
}


private enum class PlayerPictureFormat(
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

private fun formatTime(ms: Long): String {
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
                text = "Picture Format",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = "Enter applies instantly. Back closes.",
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
                text = "Continue from ${formatTime(resumeFromMs)} or start over?",
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
                        text = "Resume",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                FocusableIconButton(
                    onClick = onStartOver,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "Start Over",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
