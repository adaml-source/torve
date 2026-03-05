package com.streamvault.android.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.streamvault.android.R
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.streamvault.android.player.AudioEqualizer
import com.streamvault.android.player.CastManager
import com.streamvault.android.player.ExoPlayerEngine
import com.streamvault.android.player.MPVPlayerEngine
import com.streamvault.android.player.MPVView
import com.streamvault.android.player.isCastFrameworkAvailable
import com.streamvault.android.sync.SyncCoordinator
import com.streamvault.android.voice.PlayerVoiceCommand
import com.streamvault.android.voice.PlayerVoiceCommandParser
import com.streamvault.android.voice.VoiceInputPhase
import com.streamvault.android.voice.rememberVoiceInputController
import com.streamvault.android.ui.sync.SyncDevicePickerDialog
import com.streamvault.data.trakt.TraktClient
import com.streamvault.data.trakt.TraktHistoryBody
import com.streamvault.data.trakt.TraktHistoryMovie
import com.streamvault.data.trakt.TraktHistoryShow
import com.streamvault.data.trakt.TraktIds
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.player.NextEpisodeHelper
import com.streamvault.domain.player.NextEpisodeInfo
import com.streamvault.domain.player.PlayerEngine
import com.streamvault.domain.player.SkipSegment
import com.streamvault.domain.player.SkipSegmentDetector
import com.streamvault.domain.player.PlayerListener
import com.streamvault.domain.player.PlayerState
import com.streamvault.domain.player.TrackDescription
import com.streamvault.domain.repository.AddonRepository
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.StreamRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.presentation.channels.ChannelsViewModel
import com.streamvault.presentation.player.TraktScrobbler
import com.streamvault.presentation.settings.SettingsViewModel
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
    prefsRepo: PreferencesRepository = koinInject(),
) {
    val context = LocalContext.current

    // Google Cast (guarded for devices without Play Services, e.g. Fire TV)
    val castAvailable = remember(context) { isCastFrameworkAvailable(context) }
    val castManager = remember(castAvailable, context) {
        if (!castAvailable) {
            null
        } else {
            try {
                CastManager(context)
            } catch (_: Throwable) {
                null
            }
        }
    }

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
    var subtitleTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var audioTracks by remember { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var useMpv by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var codecFallbackUsed by remember { mutableStateOf(false) }
    var codecFallbackInProgress by remember { mutableStateOf(false) }
    var audioDelayMs by remember { mutableIntStateOf(0) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var audioEqualizer by remember { mutableStateOf<AudioEqualizer?>(null) }
    var pendingStartPositionMs by remember(url, mediaId, startPositionMs) {
        mutableLongStateOf(startPositionMs.coerceAtLeast(0L))
    }
    val playerRootFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }

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

    // Helper to check if scrobbling should fire
    val canScrobble = traktScrobbleEnabled && traktAccessToken.isNotBlank() && tmdbId > 0

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
            showTrackDialog -> {
                showTrackDialog = false
                true
            }
            showAudioDelayDialog -> {
                showAudioDelayDialog = false
                true
            }
            showEqualizerSheet -> {
                showEqualizerSheet = false
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

    LaunchedEffect(engine) {
        audioDelayMs = engine.getAudioDelay()
        // Initialize equalizer from audio session
        val sessionId = engine.getAudioSessionId()
        if (sessionId > 0) {
            val eq = AudioEqualizer(sessionId)
            // Restore saved EQ state
            val savedState = prefsRepo.getString("eq_state")
            if (savedState != null) eq.restoreFromState(savedState)
            audioEqualizer = eq
        }
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
        if (pendingStartPositionMs > 0L) {
            val seekTarget = pendingStartPositionMs
            scope.launch {
                delay(450)
                engine.seekTo(seekTarget)
                pendingStartPositionMs = 0L
            }
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

    // Auto-mark watched on Trakt at >80% progress
    LaunchedEffect(currentPosition, duration, hasMarkedWatched) {
        if (hasMarkedWatched || !canScrobble) return@LaunchedEffect
        if (duration <= 0) return@LaunchedEffect
        val progressPercent = currentPosition.toDouble() / duration
        if (progressPercent > 0.80) {
            hasMarkedWatched = true
            try {
                val ids = TraktIds(tmdb = tmdbId)
                val body = if (parsedMediaType == MediaType.MOVIE) {
                    TraktHistoryBody(movies = listOf(TraktHistoryMovie(ids = ids)))
                } else {
                    TraktHistoryBody(shows = listOf(TraktHistoryShow(ids = ids)))
                }
                traktClient.addToHistory(traktAccessToken, body)
            } catch (_: Exception) {
                // Best-effort — don't crash the player
            }
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

    // Auto-hide controls after 5 seconds (works for live streams too)
    var controlsInteractionTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(showControls, controlsInteractionTick) {
        if (showControls) {
            delay(5000)
            showControls = false
        }
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

    // When controls appear, move focus to the play button so D-pad navigation works
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(50) // Let composition settle
            try {
                playButtonFocusRequester.requestFocus()
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
                if ((showTrackDialog || showAudioDelayDialog || showEqualizerSheet) && keyEvent.key != Key.Back) {
                    return@onPreviewKeyEvent false
                }

                // When controls are visible, let D-pad navigate between buttons.
                // Only intercept Back and media keys at root level.
                if (showControls) {
                    return@onPreviewKeyEvent when (keyEvent.key) {
                        Key.Back -> {
                            showControls = false
                            true
                        }
                        Key.Spacebar, Key.MediaPlayPause -> {
                            togglePlayback()
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown,
                        Key.DirectionLeft, Key.DirectionRight,
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                        -> {
                            // Reset auto-hide timer on any navigation
                            controlsInteractionTick++
                            false // let focus system handle navigation
                        }
                        else -> false
                    }
                }

                // Controls hidden — handle all D-pad keys for media shortcuts
                when (keyEvent.key) {
                    Key.Back -> {
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
                        togglePlayback()
                        true
                    }
                    Key.DirectionLeft -> {
                        seekBy(-10_000)
                        true
                    }
                    Key.DirectionRight -> {
                        seekBy(10_000)
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        showControls = true
                        true
                    }
                    else -> false
                }
            }
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                showControls = !showControls
            },
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
                    }
                },
                update = { view ->
                    view.player = (engine as? ExoPlayerEngine)?.getExoPlayer()
                },
                onRelease = { view ->
                    view.player = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Error overlay
        errorMessage?.let { msg ->
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

        // Track selection dialog
        if (showTrackDialog) {
            TrackSelectionDialog(
                subtitleTracks = subtitleTracks,
                audioTracks = audioTracks,
                onSelectSubtitle = { track ->
                    if (track == null) {
                        engine.disableSubtitles()
                    } else {
                        engine.selectSubtitleTrack(track.id)
                    }
                    showTrackDialog = false
                },
                onSelectAudio = { track ->
                    engine.selectAudioTrack(track.id)
                    showTrackDialog = false
                },
                onDismiss = { showTrackDialog = false },
            )
        }

        if (showAudioDelayDialog) {
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

        // Equalizer sheet
        if (showEqualizerSheet) {
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

        // Controls overlay
        if (showControls) {
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
                    FocusableIconButton(onClick = onBack) {
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
                        FocusableIconButton(onClick = {
                            try {
                                if (currentUrl.isNotBlank()) {
                                    castManager?.requestCast(
                                        url = currentUrl,
                                        title = currentTitle,
                                        posterUrl = posterUrl.ifBlank { null },
                                    )
                                }
                                val routeBtn = androidx.mediarouter.app.MediaRouteButton(context)
                                com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(context, routeBtn)
                                routeBtn.performClick()
                            } catch (_: Throwable) { }
                        }) {
                            Icon(
                                if (castManager?.isCasting == true) Icons.Default.CastConnected else Icons.Default.Cast,
                                contentDescription = "Cast",
                                tint = if (castManager?.isCasting == true) com.streamvault.android.ui.theme.Amber else Color.White,
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
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "Play on device",
                            tint = if (syncState.isAuthenticated) com.streamvault.android.ui.theme.Amber else Color.White,
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
                    ) {
                        val voiceTint = when (voiceController.uiState.value.phase) {
                            VoiceInputPhase.Listening,
                            VoiceInputPhase.Processing,
                            -> com.streamvault.android.ui.theme.Amber

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
                        FocusableIconButton(onClick = { showTrackDialog = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.player_track_selection),
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    FocusableIconButton(onClick = { showAudioDelayDialog = true }) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Audio delay",
                            tint = if (audioDelayMs != 0) com.streamvault.android.ui.theme.Amber else Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    if (audioEqualizer != null) {
                        FocusableIconButton(onClick = {
                            showEqualizerSheet = !showEqualizerSheet
                            showControls = true
                        }) {
                            Icon(
                                Icons.Default.Equalizer,
                                contentDescription = "Equalizer",
                                tint = if (audioEqualizer?.enabled == true) com.streamvault.android.ui.theme.Amber else Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
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
                        modifier = Modifier.focusRequester(playButtonFocusRequester),
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
            onDismiss = { showDevicePicker = false },
        )
    }
}

/** IconButton wrapper that shows an Amber border when focused (for D-pad / TV navigation). */
@Composable
private fun FocusableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1.15f else 1f,
        label = "iconBtnScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) com.streamvault.android.ui.theme.Amber else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { focused = it.isFocused }
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
