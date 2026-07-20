package com.torve.desktop.ui.v2.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.torve.desktop.playback.DesktopPlayerController
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveListRow
import com.torve.desktop.ui.components.TorvePlaceholderState
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.recording.recordingFailureNotification
import com.torve.desktop.ui.v2.recording.recordingFolderValidationError
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.LiveTvChannelLogoResolver
import com.torve.domain.model.channelIdentityCandidates
import com.torve.presentation.channels.ChannelsSubTab
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.channels.EpgState
import com.torve.presentation.channels.LiveTvGuideSourceResolver

enum class DesktopLiveMode { CHANNELS, GUIDE, FAVORITES }

@Composable
fun V2LivePage(
    channelsState: ChannelsUiState,
    channelsViewModel: ChannelsViewModel,
    playerController: DesktopPlayerController,
    onPremiumBlocked: (() -> Unit)? = null,
    onDirectPlaybackStarted: () -> Unit = {},
    onRecordingEvent: (String) -> Unit = {},
) {
    val hasPremium = true
    var mode by remember { mutableStateOf(DesktopLiveMode.CHANNELS) }
    val visibleChannels = resolveVisibleChannels(channelsState, channelsViewModel)
    val activeGuideChannels = remember(channelsState) {
        LiveTvGuideSourceResolver.resolve(channelsState).ifEmpty { channelsState.guideChannels }
    }

    // ── EPG reminders ─────────────────────────────────────────────
    // File-backed store under desktopDataDir; survives restarts. Each active
    // reminder has a coroutine that fires a tray notification ~1 minute
    // before the show starts.
    // (Reminder firing now lives in the global ReminderScheduler - see Main.)
    val reminderStore = remember {
        // Process-wide singleton; the Settings reminder list page reads
        // the same instance so changes here propagate immediately.
        com.torve.desktop.globalReminderStore.also { it.pruneExpired(System.currentTimeMillis()) }
    }
    // Subscribe to the reminder store's StateFlow so the EPG context
    // menu shows the right "Set/Cancel reminder" label live. Firing
    // is handled by the global ReminderScheduler installed in Main.
    val storeReminders by reminderStore.state.collectAsState()
    val reminders = remember(storeReminders) { storeReminders.map { it.key }.toSet() }

    val reminderKey: (com.torve.domain.model.Channel, com.torve.domain.model.EpgProgramme) -> String =
        { ch, p -> com.torve.desktop.reminders.EpgReminderStore.reminderKey(ch, p) }
    val toggleReminder: (com.torve.domain.model.Channel, com.torve.domain.model.EpgProgramme) -> Unit = { ch, p ->
        val key = reminderKey(ch, p)
        if (reminders.contains(key)) {
            reminderStore.remove(key)
        } else {
            val stored = com.torve.desktop.reminders.StoredReminder(
                key = key,
                channelUrl = ch.url,
                channelName = ch.name,
                title = p.title,
                startMs = p.startTime,
                endMs = p.endTime,
            )
            reminderStore.add(stored)
        }
    }
    val isFavoriteFor: (com.torve.domain.model.Channel) -> Boolean = { ch ->
        channelsState.favorites.any { it.url == ch.url || it.name == ch.name }
    }

    // ── IPTV recording (Prompt 10B) ──────────────────────────────
    // Per-slot recording lookups + the Record/Cancel toggle. The VM is
    // bound in SharedModule so this composable just resolves it via
    // Koin. The EPG grid receives no-op defaults if the recording stack
    // ever fails to bind, so the live page never crashes on a missing
    // dependency.
    val recordingsVm = remember {
        org.koin.mp.KoinPlatform.getKoin()
            .get<com.torve.presentation.recording.RecordingsViewModel>()
    }
    val recordingsState by recordingsVm.state.collectAsState()
    // Load recording prefs once on entry; refresh when settings change.
    val prefsRepo = remember {
        org.koin.mp.KoinPlatform.getKoin()
            .get<com.torve.domain.repository.PreferencesRepository>()
    }
    var recordingPrefs by remember {
        mutableStateOf(com.torve.domain.recording.RecordingPreferences())
    }
    LaunchedEffect(Unit) {
        recordingPrefs = com.torve.domain.recording.RecordingPreferences.load(prefsRepo)
    }
    val recordingStatusFor: (com.torve.domain.model.Channel, com.torve.domain.model.EpgProgramme) -> com.torve.presentation.recording.RecordingSlotStatus =
        { ch, p -> recordingsVm.statusFor(ch.url, p.startTime, p.endTime) }
    // Helper: surface a toast/tray notification when the user has no
    // recordings folder set. The recording stack would otherwise just
    // mark the row as Failed, which is a poor UX.
    val settingsVmForRecording = remember {
        org.koin.mp.KoinPlatform.getKoin()
            .get<com.torve.presentation.settings.SettingsViewModel>()
    }
    fun ensureRecordingFolderConfigured(): Boolean {
        val configured = settingsVmForRecording.state.value.recordingDownloadPath.isNotBlank()
        if (!configured) {
            com.torve.desktop.desktopNotify(
                "Recording disabled",
                "Set a Recordings Folder under Settings → Preferences → Downloads first.",
            )
        }
        return configured
    }
    var knownRecordingFailureIds by remember {
        mutableStateOf(recordingsState.failed.map { it.id }.toSet())
    }
    LaunchedEffect(recordingsState.failed) {
        val failedIds = recordingsState.failed.map { it.id }.toSet()
        recordingsState.failed
            .filter { it.id !in knownRecordingFailureIds && it.status == com.torve.domain.recording.RecordingStatus.FAILED }
            .forEach { onRecordingEvent(recordingFailureNotification(it)) }
        knownRecordingFailureIds = failedIds
    }
    fun ensureRecordingFolderReady(): Boolean {
        val error = recordingFolderValidationError(settingsVmForRecording.state.value.recordingDownloadPath)
        if (error != null) {
            onRecordingEvent("Recording disabled: $error")
            com.torve.desktop.desktopNotify("Recording disabled", error)
            return false
        }
        return true
    }
    val onToggleRecord: (com.torve.domain.model.Channel, com.torve.domain.model.EpgProgramme) -> Unit = { ch, p ->
        val existing = recordingsVm.rowFor(ch.url, p.startTime, p.endTime)
        if (existing != null && (existing.status == com.torve.domain.recording.RecordingStatus.SCHEDULED ||
                    existing.status == com.torve.domain.recording.RecordingStatus.RECORDING)
        ) {
            recordingsVm.cancel(existing.id)
        } else if (!ensureRecordingFolderReady()) {
            // No-op: the helper notified the user.
        } else {
            // Apply pre-roll / post-roll buffers from settings so stations
            // that start a minute early or sports events that overrun get
            // captured. Defaults: pre=1min, post=5min.
            val metadata = com.torve.domain.recording.EpgRecordingMetadataResolver.scheduled(ch, p)
            recordingsVm.schedule(
                playlistId = channelsState.selectedPlaylistId.orEmpty(),
                channelId = ch.url,
                channelName = ch.name,
                streamUrl = ch.url,
                programmeTitle = metadata.programmeTitle,
                programmeDescription = metadata.programmeDescription,
                startMs = p.startTime - recordingPrefs.preRollMs,
                endMs = p.endTime + recordingPrefs.postRollMs,
                metadata = metadata,
            )
            onRecordingEvent("Recording scheduled: ${p.title}")
        }
    }
    // Variant of onToggleRecord that adds an explicit extra "overrun"
    // window on top of the post-roll. Useful for sports events that
    // routinely run 30-60+ minutes long. Caller picks the bonus from
    // a submenu (15 / 30 / 60 / 120 min).
    val onScheduleWithOverrun: (com.torve.domain.model.Channel, com.torve.domain.model.EpgProgramme, Int) -> Unit =
        { ch, p, overrunMin ->
            if (ensureRecordingFolderReady()) {
                val metadata = com.torve.domain.recording.EpgRecordingMetadataResolver.scheduled(ch, p)
                recordingsVm.schedule(
                    playlistId = channelsState.selectedPlaylistId.orEmpty(),
                    channelId = ch.url,
                    channelName = ch.name,
                    streamUrl = ch.url,
                    programmeTitle = metadata.programmeTitle,
                    programmeDescription = metadata.programmeDescription,
                    startMs = p.startTime - recordingPrefs.preRollMs,
                    endMs = p.endTime + recordingPrefs.postRollMs + overrunMin * 60_000L,
                    metadata = metadata,
                )
                onRecordingEvent("Recording scheduled: ${p.title}")
            }
        }

    // ── Live "record what I'm watching" (Prompt 10D) ─────────────
    // Toggle that records the currently selected channel for a default
    // 2-hour window starting NOW. Reuses the existing recording stack:
    // schedule(start=now, end=now+2h) is picked up by the scheduler's
    // 30 s tick (or sooner via the immediate isActive check) and the
    // recording starts. Stop = cancel the active row by streamUrl.
    val isRecordingChannel: (com.torve.domain.model.Channel) -> Boolean = { ch ->
        recordingsState.active.any { it.streamUrl == ch.url }
    }
    val onToggleRecordNow: (com.torve.domain.model.Channel) -> Unit = { ch ->
        val active = recordingsState.active.firstOrNull { it.streamUrl == ch.url }
        if (active != null) {
            recordingsVm.cancel(active.id)
        } else if (!ensureRecordingFolderReady()) {
            // No-op: helper already showed the notification.
        } else {
            // Use the user's configured default duration (Settings →
            // Recording). 0 sentinel = "Until I stop" → 24 hour cap.
            val now = System.currentTimeMillis()
            val metadata = com.torve.domain.recording.EpgRecordingMetadataResolver.live(
                channel = ch,
                programmes = channelsState.programmes,
                nowMs = now,
            )
            recordingsVm.schedule(
                playlistId = channelsState.selectedPlaylistId.orEmpty(),
                channelId = ch.url,
                channelName = ch.name,
                streamUrl = ch.url,
                programmeTitle = "Live recording — ${ch.name}",
                programmeDescription = metadata.programmeDescription,
                startMs = now,
                endMs = now + recordingPrefs.defaultDurationMs,
                metadata = metadata,
            )
            onRecordingEvent("Recording started: ${ch.name}")
        }
    }

    // Channel-number keypad: pure state machine + a Compose-side mirror of its
    // buffer + a 2.5 s idle-clear effect. The state machine itself is unit
    // tested in ChannelKeypadStateMachineTest.
    val keypad = remember { ChannelKeypadStateMachine() }
    var channelBuffer by remember { mutableStateOf("") }
    val liveKeypadFocus = remember { FocusRequester() }
    LaunchedEffect(channelBuffer) {
        if (channelBuffer.isEmpty()) return@LaunchedEffect
        delay(2500)
        if (keypad.applyIdleTick(System.currentTimeMillis())) {
            channelBuffer = keypad.buffer
        }
    }

    fun jumpToChannelNumber(number: Int) {
        val match = visibleChannels.firstOrNull { it.channel.channelNumber == number }
            ?: channelsState.channels.firstOrNull { it.channel.channelNumber == number }
            ?: return
        channelsViewModel.selectChannel(match.channel)
        channelsViewModel.recordChannelViewed(match.channel)
        onDirectPlaybackStarted()
        playerController.playDirectStream(
            title = match.channel.name,
            url = match.channel.url,
            artworkUrl = match.channel.tvgLogo,
            sourceSurface = "live_tv",
        )
    }

    fun handleLiveKey(keyEvent: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (keyEvent.type != KeyEventType.KeyDown) return false
        val event = keyToKeypadEvent(keyEvent.key) ?: return false
        val outcome = keypad.handle(event, System.currentTimeMillis())
        channelBuffer = keypad.buffer
        return when (outcome) {
            is ChannelKeypadStateMachine.Outcome.Commit -> {
                jumpToChannelNumber(outcome.channelNumber)
                true
            }
            ChannelKeypadStateMachine.Outcome.Consumed -> true
            ChannelKeypadStateMachine.Outcome.Ignored -> false
        }
    }

    LaunchedEffect(channelsState.playlists, channelsState.selectedPlaylistId) {
        if (channelsState.selectedPlaylistId == null) {
            channelsState.playlists.firstOrNull()?.let { channelsViewModel.selectPlaylist(it.id) }
        }
    }
    LaunchedEffect(channelsState.selectedPlaylistId) {
        if (channelsState.selectedPlaylistId != null) {
            channelsViewModel.ensureEpgLoaded()
        }
    }

    val playLive: (Channel) -> Unit = lambda@{ channel ->
        channelsViewModel.selectChannel(channel)
        channelsViewModel.recordChannelViewed(channel)
        onDirectPlaybackStarted()
        playerController.playDirectStream(
            title = channel.name,
            url = channel.url,
            artworkUrl = channel.tvgLogo,
            sourceSurface = "live_tv",
        )
    }

    LaunchedEffect(Unit) { runCatching { liveKeypadFocus.requestFocus() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color(0xFF050914),
                    0.48f to Color(0xFF07111F),
                    1.0f to TorveDesktopThemeTokens.colors.shellBackground,
                ),
            )
            .padding(start = 72.dp, end = 30.dp, top = 20.dp, bottom = 20.dp)
            .focusRequester(liveKeypadFocus)
            .focusable()
            .onPreviewKeyEvent(::handleLiveKey),
    ) {
        val upperGridHeight = when {
            maxHeight < 820.dp -> 360.dp
            maxHeight < 980.dp -> 430.dp
            else -> 480.dp
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        LiveTvPageHeader(
            title = ds("Live TV"),
            subtitle = "Search, switch playlists, then play. Toggle the Guide for an EPG view.",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (channelBuffer.isNotEmpty()) {
                        TorveBadge(
                            text = "→ $channelBuffer",
                            tone = TorveBadgeTone.Warning,
                        )
                    }
                    LiveTvStatusPill(
                        text = epgBadgeLabel(channelsState.epgState),
                        showDot = channelsState.epgState is EpgState.Loaded,
                    )
                }
            },
        )

        if (channelsState.playlists.isEmpty()) {
            TorvePlaceholderState(
                title = "No IPTV playlists configured",
                description = "Add an M3U or Xtream playlist in Settings > Playlists to enable channel browsing.",
                emoji = "📻",
            )
            return@Column
        }

        // Inline EPG error banner - surfaces the upstream message and offers a
        // one-click retry without forcing the user into Guide mode to see it.
        channelsState.guideError?.let { msg ->
            EpgErrorBanner(
                message = msg,
                onRetry = { channelsViewModel.retryGuideLoad() },
            )
        }

        // ── Top control row ─────────────────────────────────────────────
        // Search owns the row at full bleed; playlist + recent collapse into
        // dropdowns; the view-mode toggle anchors the right edge.
        LiveControlRow(
            searchQuery = channelsState.searchQuery,
            onSearchChange = {
                if (it.isBlank()) channelsViewModel.clearSearch()
                else channelsViewModel.updateSearchQuery(it)
            },
            playlists = channelsState.playlists,
            selectedPlaylistId = channelsState.selectedPlaylistId,
            onSelectPlaylist = { channelsViewModel.selectPlaylist(it) },
            recentChannels = channelsState.recentlyViewedChannels,
            onPlayRecent = playLive,
            mode = mode,
            favoritesCount = channelsState.favorites.size,
            guideChannelCount = activeGuideChannels.size,
            onSelectMode = { next ->
                mode = next
                when (next) {
                    DesktopLiveMode.CHANNELS -> channelsViewModel.selectSubTab(ChannelsSubTab.LIVE)
                    DesktopLiveMode.FAVORITES -> channelsViewModel.selectSubTab(ChannelsSubTab.FAVOURITES)
                    // Guide doesn't auto-build until the full playlist is in
                    // memory; for first-time entry without that load, kick off
                    // a guide build so users don't see an empty grid forever.
                    DesktopLiveMode.GUIDE -> channelsViewModel.requestGuideBuild()
                }
            },
        )

        when (mode) {
            DesktopLiveMode.GUIDE -> V2EpgGrid(
                playlistId = channelsState.selectedPlaylistId,
                guideChannels = activeGuideChannels,
                guideProgrammes = channelsState.guideProgrammes,
                isLoading = channelsState.isLoadingGuide,
                error = channelsState.guideError,
                onRetry = { channelsViewModel.retryGuideLoad() },
                canCatchup = channelsViewModel::canCatchup,
                resolveCatchupUrl = channelsViewModel::resolveCatchupUrl,
                onPlayChannel = playLive,
                onPlayCatchup = { channel, _, url ->
                    channelsViewModel.selectChannel(channel)
                    channelsViewModel.recordChannelViewed(channel)
                    onDirectPlaybackStarted()
                    playerController.playDirectStream(
                        title = channel.name,
                        url = url,
                        artworkUrl = channel.tvgLogo,
                        sourceSurface = "live_tv_catchup",
                    )
                },
                onSwitchToChannels = {
                    mode = DesktopLiveMode.CHANNELS
                    channelsViewModel.selectSubTab(ChannelsSubTab.LIVE)
                },
                epgProgrammeCount = (channelsState.epgState as? EpgState.Loaded)?.sourceProgrammeCount ?: 0,
                isFavorite = isFavoriteFor,
                onToggleFavorite = { ch -> channelsViewModel.toggleFavorite(ch) },
                isReminderSet = { ch, p -> reminders.contains(reminderKey(ch, p)) },
                onToggleReminder = toggleReminder,
                recordingStatusFor = recordingStatusFor,
                onToggleRecord = onToggleRecord,
                searchQuery = channelsState.guideSearchQuery,
                onSearchQueryChange = channelsViewModel::setGuideSearchQuery,
                sortMode = channelsState.guideSortMode,
                onSortModeChange = channelsViewModel::setGuideSortMode,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            DesktopLiveMode.CHANNELS -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(upperGridHeight),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    CategoryPane(
                        categories = channelsState.categories,
                        selectedGroup = channelsState.selectedGroup,
                        onSelectCategory = { channelsViewModel.loadCategoryChannels(it.name) },
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                    )

                    ChannelListPane(
                        visibleChannels = visibleChannels,
                        channelsState = channelsState,
                        onSelect = { channelsViewModel.selectChannel(it) },
                        onToggleFavorite = { channelsViewModel.toggleFavorite(it) },
                        onPlay = playLive,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )

                    PremiumSelectedChannelPane(
                        selectedChannel = channelsState.selectedChannel,
                        programmes = channelsState.programmes,
                        onPlay = playLive,
                        isRecording = isRecordingChannel,
                        onToggleRecord = onToggleRecordNow,
                        modifier = Modifier.width(440.dp).fillMaxHeight(),
                    )
                }

                LiveTvEpgPanel(
                    channelsState = channelsState,
                    guideChannels = activeGuideChannels,
                    channelsViewModel = channelsViewModel,
                    playLive = playLive,
                    hasPremium = hasPremium,
                    onPremiumBlocked = onPremiumBlocked,
                    onDirectPlaybackStarted = onDirectPlaybackStarted,
                    playerController = playerController,
                    isFavoriteFor = isFavoriteFor,
                    reminders = reminders,
                    reminderKey = reminderKey,
                    toggleReminder = toggleReminder,
                    recordingStatusFor = recordingStatusFor,
                    onToggleRecord = onToggleRecord,
                    onSwitchToChannels = {
                        mode = DesktopLiveMode.CHANNELS
                        channelsViewModel.selectSubTab(ChannelsSubTab.LIVE)
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp),
                )
            }

            DesktopLiveMode.FAVORITES -> ChannelListPane(
                visibleChannels = visibleChannels,
                channelsState = channelsState,
                onSelect = { channelsViewModel.selectChannel(it) },
                onToggleFavorite = { channelsViewModel.toggleFavorite(it) },
                onPlay = playLive,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // ── Conflict dialog (Prompt 10B) ──────────────────────────────
    // Surfaces overlapping schedule attempts with an explicit
    // "Schedule anyway" affordance. Dismiss = no rows changed.
    }
    recordingsState.conflict?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { recordingsVm.dismissConflict() },
            title = { androidx.compose.material3.Text("Recording conflict") },
            text = {
                androidx.compose.material3.Text(
                    "\"${pending.candidate.programmeTitle}\" overlaps with " +
                        "\"${pending.existing.programmeTitle}\" on " +
                        "${pending.existing.channelName}. Schedule anyway?",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { recordingsVm.confirmConflict() },
                ) { androidx.compose.material3.Text("Schedule anyway") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { recordingsVm.dismissConflict() },
                ) { androidx.compose.material3.Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LiveTvPageHeader(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.98f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            trailing()
        }
    }
}

@Composable
private fun LiveTvEpgPanel(
    channelsState: ChannelsUiState,
    guideChannels: List<EnrichedChannel>,
    channelsViewModel: ChannelsViewModel,
    playLive: (Channel) -> Unit,
    hasPremium: Boolean,
    onPremiumBlocked: (() -> Unit)?,
    onDirectPlaybackStarted: () -> Unit,
    playerController: DesktopPlayerController,
    isFavoriteFor: (Channel) -> Boolean,
    reminders: Set<String>,
    reminderKey: (Channel, com.torve.domain.model.EpgProgramme) -> String,
    toggleReminder: (Channel, com.torve.domain.model.EpgProgramme) -> Unit,
    recordingStatusFor: (Channel, com.torve.domain.model.EpgProgramme) -> com.torve.presentation.recording.RecordingSlotStatus,
    onToggleRecord: (Channel, com.torve.domain.model.EpgProgramme) -> Unit,
    onSwitchToChannels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTvGlassPanel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveTvPanelTitle(
                    title = "Guide (EPG)",
                    subtitle = "Browse current and upcoming programmes",
                    modifier = Modifier.weight(1f),
                )
                val count = (channelsState.epgState as? EpgState.Loaded)?.sourceProgrammeCount ?: 0
                if (count > 0) LiveTvStatusPill("EPG: $count programs")
            }
            V2EpgGrid(
                playlistId = channelsState.selectedPlaylistId,
                guideChannels = guideChannels,
                guideProgrammes = channelsState.guideProgrammes,
                isLoading = channelsState.isLoadingGuide,
                error = channelsState.guideError,
                onRetry = { channelsViewModel.retryGuideLoad() },
                canCatchup = channelsViewModel::canCatchup,
                resolveCatchupUrl = channelsViewModel::resolveCatchupUrl,
                onPlayChannel = playLive,
                onPlayCatchup = { channel, _, url ->
                    channelsViewModel.selectChannel(channel)
                    channelsViewModel.recordChannelViewed(channel)
                    onDirectPlaybackStarted()
                    playerController.playDirectStream(
                        title = channel.name,
                        url = url,
                        artworkUrl = channel.tvgLogo,
                        sourceSurface = "live_tv_catchup",
                    )
                },
                onSwitchToChannels = onSwitchToChannels,
                epgProgrammeCount = (channelsState.epgState as? EpgState.Loaded)?.sourceProgrammeCount ?: 0,
                isFavorite = isFavoriteFor,
                onToggleFavorite = { ch -> channelsViewModel.toggleFavorite(ch) },
                isReminderSet = { ch, p -> reminders.contains(reminderKey(ch, p)) },
                onToggleReminder = toggleReminder,
                recordingStatusFor = recordingStatusFor,
                onToggleRecord = onToggleRecord,
                searchQuery = channelsState.guideSearchQuery,
                onSearchQueryChange = channelsViewModel::setGuideSearchQuery,
                sortMode = channelsState.guideSortMode,
                onSortModeChange = channelsViewModel::setGuideSortMode,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChannelListPane(
    visibleChannels: List<EnrichedChannel>,
    channelsState: ChannelsUiState,
    onSelect: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTvGlassPanel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveTvPanelTitle("Channels", "${visibleChannels.size} visible")
            if (visibleChannels.isEmpty()) {
                TorvePlaceholderState(
                    modifier = Modifier.fillMaxSize(),
                    title = emptyTitleFor(channelsState.selectedSubTab),
                    description = emptyDescriptionFor(channelsState.selectedSubTab),
                )
                return@Column
            }
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = visibleChannels,
                        key = { index, enriched -> "${channelRowKey(enriched)}#$index" },
                    ) { _, enriched ->
                        PremiumChannelRow(
                            channel = enriched.channel,
                            currentProgramme = enriched.currentProgramme?.title,
                            nextProgramme = enriched.nextProgramme?.title,
                            isSelected = channelsState.selectedChannel?.url == enriched.channel.url,
                            isFavorite = channelsState.favorites.any {
                                it.url == enriched.channel.url || it.name == enriched.channel.name
                            },
                            onSelect = { onSelect(enriched.channel) },
                            onToggleFavorite = { onToggleFavorite(enriched.channel) },
                            onPlay = { onPlay(enriched.channel) },
                        )
                    }
                }
                PremiumVerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

private fun resolveVisibleChannels(
    channelsState: ChannelsUiState,
    channelsViewModel: ChannelsViewModel,
): List<EnrichedChannel> {
    // Favorites tab: always show saved favorites, enriched with live catalog data
    // when available so Now/Next programmes and logos come through.
    if (channelsState.selectedSubTab == ChannelsSubTab.FAVOURITES) {
        val favoriteIds = channelsState.favorites.flatMap(::channelIdentityCandidates).toSet()
        val enrichedPool = channelsState.categoryChannels + channelsState.channels
        val matched = enrichedPool.filter { enriched ->
            channelIdentityCandidates(enriched.channel).any(favoriteIds::contains)
        }
        if (matched.isNotEmpty()) return matched.distinctBy { it.channel.url }
        return channelsState.favorites.map { EnrichedChannel(channel = it) }
    }

    return when {
        channelsState.searchQuery.length >= 2 -> {
            // Enrich search results against whatever we already have in memory so
            // Now/Next programmes appear on typed-search hits as well.
            val byUrl = (channelsState.categoryChannels + channelsState.channels)
                .associateBy { it.channel.url }
            channelsState.searchResults.map { channel ->
                byUrl[channel.url] ?: EnrichedChannel(channel = channel)
            }
        }
        channelsState.selectedGroup != null && channelsState.categoryChannels.isNotEmpty() ->
            channelsState.categoryChannels
        else -> channelsViewModel.getDisplayChannels()
    }
}

private fun channelRowKey(enriched: EnrichedChannel): String {
    val channel = enriched.channel
    return (channel.tvgId ?: channel.url).ifBlank { channel.name }
}

private fun sectionTitleFor(tab: ChannelsSubTab): String = when (tab) {
    ChannelsSubTab.LIVE, ChannelsSubTab.GUIDE -> "Channels"
    ChannelsSubTab.FAVOURITES -> "Favorite Channels"
    ChannelsSubTab.MOVIES -> "Movies"
}

private fun emptyTitleFor(tab: ChannelsSubTab): String = when (tab) {
    ChannelsSubTab.LIVE, ChannelsSubTab.GUIDE -> "No channels loaded"
    ChannelsSubTab.FAVOURITES -> "No favorites yet"
    ChannelsSubTab.MOVIES -> "No movies loaded"
}

private fun emptyDescriptionFor(tab: ChannelsSubTab): String = when (tab) {
    ChannelsSubTab.LIVE, ChannelsSubTab.GUIDE ->
        "Choose a category or enter at least two characters in search."
    ChannelsSubTab.FAVOURITES -> "Save channels from the Live tab to see them here."
    ChannelsSubTab.MOVIES -> "Choose a category or enter at least two characters in search."
}

private fun keyToKeypadEvent(key: Key): ChannelKeypadStateMachine.Event? = when (key) {
    Key.Zero, Key.NumPad0 -> ChannelKeypadStateMachine.Event.Digit0
    Key.One, Key.NumPad1 -> ChannelKeypadStateMachine.Event.Digit1
    Key.Two, Key.NumPad2 -> ChannelKeypadStateMachine.Event.Digit2
    Key.Three, Key.NumPad3 -> ChannelKeypadStateMachine.Event.Digit3
    Key.Four, Key.NumPad4 -> ChannelKeypadStateMachine.Event.Digit4
    Key.Five, Key.NumPad5 -> ChannelKeypadStateMachine.Event.Digit5
    Key.Six, Key.NumPad6 -> ChannelKeypadStateMachine.Event.Digit6
    Key.Seven, Key.NumPad7 -> ChannelKeypadStateMachine.Event.Digit7
    Key.Eight, Key.NumPad8 -> ChannelKeypadStateMachine.Event.Digit8
    Key.Nine, Key.NumPad9 -> ChannelKeypadStateMachine.Event.Digit9
    Key.Enter, Key.NumPadEnter -> ChannelKeypadStateMachine.Event.Enter
    Key.Escape -> ChannelKeypadStateMachine.Event.Esc
    else -> null
}

private fun epgBadgeLabel(state: EpgState): String = when (state) {
    EpgState.NotConfigured -> "EPG: Off"
    EpgState.Loading -> "EPG: Loading"
    is EpgState.Loaded -> "EPG: ${state.sourceProgrammeCount} progs"
    is EpgState.Error -> "EPG: Error"
}

private fun epgBadgeTone(state: EpgState): TorveBadgeTone = when (state) {
    EpgState.NotConfigured -> TorveBadgeTone.Neutral
    EpgState.Loading -> TorveBadgeTone.Accent
    is EpgState.Loaded -> TorveBadgeTone.Success
    is EpgState.Error -> TorveBadgeTone.Warning
}

private fun compactChannelCount(count: Int): String = when {
    count >= 10_000 -> "${count / 1000}K"
    count >= 1_000 -> {
        val tenths = count / 100
        "${tenths / 10}.${tenths % 10}K"
    }
    else -> count.toString()
}

@Composable
private fun LiveControlRow(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    playlists: List<com.torve.domain.model.ChannelPlaylist>,
    selectedPlaylistId: String?,
    onSelectPlaylist: (String) -> Unit,
    recentChannels: List<Channel>,
    onPlayRecent: (Channel) -> Unit,
    mode: DesktopLiveMode,
    favoritesCount: Int,
    guideChannelCount: Int,
    onSelectMode: (DesktopLiveMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveTvSearchBar(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.weight(1f),
        )
        PlaylistDropdown(
            playlists = playlists,
            selectedPlaylistId = selectedPlaylistId,
            onSelect = onSelectPlaylist,
        )
        if (recentChannels.isNotEmpty()) {
            RecentChannelsDropdown(
                channels = recentChannels,
                onPlay = onPlayRecent,
            )
        }
        LiveModeToggle(
            mode = mode,
            favoritesCount = favoritesCount,
            guideChannelCount = guideChannelCount,
            onSelect = onSelectMode,
        )
    }
}

@Composable
private fun PlaylistDropdown(
    playlists: List<com.torve.domain.model.ChannelPlaylist>,
    selectedPlaylistId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = playlists.firstOrNull { it.id == selectedPlaylistId }
    val label = active?.channelCount?.takeIf { it > 0 }?.let { compactChannelCount(it) }
        ?: active?.name?.takeIf { it.isNotBlank() }
        ?: "Select playlist"
    Box {
        LiveTvTabPill(
            text = "Playlist: $label",
            selected = false,
            onClick = { expanded = true },
            contentDescription = "Playlist",
        )
        com.torve.desktop.ui.components.TorveDropdownScaffold(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = playlists.map { playlist ->
                playlist.name to {
                    onSelect(playlist.id)
                    expanded = false
                }
            },
        )
    }
}

@Composable
private fun RecentChannelsDropdown(
    channels: List<Channel>,
    onPlay: (Channel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LiveTvTabPill(
            text = "Recent (${channels.size})",
            selected = false,
            onClick = { expanded = true },
            contentDescription = "Recent channels",
        )
        com.torve.desktop.ui.components.TorveDropdownScaffold(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = channels.take(15).map { channel ->
                channel.name to {
                    onPlay(channel)
                    expanded = false
                }
            },
        )
    }
}

@Composable
private fun LiveModeToggle(
    mode: DesktopLiveMode,
    favoritesCount: Int,
    guideChannelCount: Int,
    onSelect: (DesktopLiveMode) -> Unit,
) {
    val channelsLabel = ds("Channels")
    val guideLabel = ds("Guide")
    val favoritesLabel = ds("Favorites")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LiveTvTabPill(
            text = channelsLabel,
            selected = mode == DesktopLiveMode.CHANNELS,
            onClick = { onSelect(DesktopLiveMode.CHANNELS) },
            contentDescription = "Channels",
        )
        LiveTvTabPill(
            text = if (guideChannelCount > 0) "$guideLabel ($guideChannelCount)" else guideLabel,
            selected = mode == DesktopLiveMode.GUIDE,
            onClick = { onSelect(DesktopLiveMode.GUIDE) },
            contentDescription = "Guide",
        )
        LiveTvTabPill(
            text = if (favoritesCount > 0) "$favoritesLabel ($favoritesCount)" else favoritesLabel,
            selected = mode == DesktopLiveMode.FAVORITES,
            onClick = { onSelect(DesktopLiveMode.FAVORITES) },
            contentDescription = "Favorites",
        )
    }
}

@Composable
private fun EpgErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        com.torve.desktop.ui.components.TorveBanner(
            title = "EPG didn't load",
            description = "Unable to load guide data right now.",
            tone = com.torve.desktop.ui.components.TorveBannerTone.Warning,
            modifier = Modifier.weight(1f),
        )
        TorveGhostButton(text = "Retry EPG", onClick = onRetry)
    }
}

@Composable
private fun CategoryPane(
    categories: List<ChannelCategory>,
    selectedGroup: String?,
    onSelectCategory: (ChannelCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTvGlassPanel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveTvPanelTitle("Categories", "Choose a channel group")
        if (categories.isEmpty()) {
            TorvePlaceholderState(
                title = "No categories yet",
                description = "Select a playlist and let Torve load category counts.",
            )
        } else {
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxHeight().fillMaxWidth().padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(
                    items = categories,
                    key = { it.name },
                ) { category ->
                    CategoryRow(
                        category = category,
                        selected = selectedGroup == category.name,
                        onClick = { onSelectCategory(category) },
                    )
                }
            }
                PremiumVerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        }
    }
}

@Composable
private fun CategoryRow(
    category: ChannelCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = focused || hovered
    val scale by androidx.compose.animation.core.animateFloatAsState(if (active) 1.018f else 1f, tween(140), label = "categoryScale")
    val background by animateColorAsState(
        when {
            selected -> colors.accent.copy(alpha = 0.15f)
            active -> Color(0xFF111C2F).copy(alpha = 0.68f)
            else -> Color(0xFF0A1322).copy(alpha = 0.44f)
        },
        tween(140),
        label = "categoryBg",
    )
    val border by animateColorAsState(
        when {
            selected -> colors.accent.copy(alpha = 0.60f)
            active -> colors.accent.copy(alpha = 0.52f)
            else -> Color.White.copy(alpha = 0.10f)
        },
        tween(140),
        label = "categoryBorder",
    )

    Surface(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .focusable(true, interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                contentDescription = "Category ${category.name}, ${category.channelCount} channels" +
                    if (selected) ", selected" else ""
            },
        color = background,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiveTvFallbackIcon()
            Text(
                formatCategoryLabel(category),
                color = Color.White.copy(alpha = 0.93f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LiveTvQualityBadge(category.channelCount.toString())
        }
    }
}

private fun formatCategoryLabel(category: ChannelCategory): String {
    val countryCode = category.countryCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    return if (countryCode != null) "[$countryCode] ${category.name}" else category.name
}

@Composable
private fun PremiumChannelRow(
    channel: Channel,
    currentProgramme: String?,
    nextProgramme: String?,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = focused || hovered
    val scale by animateFloatAsState(if (active) 1.012f else 1f, tween(140), label = "channelRowScale")
    val background by animateColorAsState(
        when {
            isSelected -> colors.accent.copy(alpha = 0.15f)
            active -> Color(0xFF111C2F).copy(alpha = 0.70f)
            else -> Color(0xFF08111F).copy(alpha = 0.45f)
        },
        tween(140),
        label = "channelRowBg",
    )
    val border by animateColorAsState(
        when {
            isSelected -> colors.accent.copy(alpha = 0.62f)
            active -> colors.accent.copy(alpha = 0.56f)
            else -> Color.White.copy(alpha = 0.10f)
        },
        tween(140),
        label = "channelRowBorder",
    )
    val subtitle = buildString {
        append(channel.groupTitle ?: "Ungrouped")
        currentProgramme?.takeIf { it.isNotBlank() }?.let {
            append(" - Now: ")
            append(it)
        }
        nextProgramme?.takeIf { it.isNotBlank() }?.let {
            append(" - Next: ")
            append(it)
        }
    }
    val badges = remember(channel.name) { qualityBadgesFor(channel.name) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .focusable(true, interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .semantics {
                contentDescription = "Channel ${channel.name}, ${channel.groupTitle ?: "Ungrouped"}" +
                    if (isSelected) ", selected" else ""
            },
        color = background,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.height(74.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val logo = remember(channel.name, channel.tvgLogo) {
                LiveTvChannelLogoResolver.resolveLogo(channel)
            }
            LiveTvChannelLogo(
                logo = logo,
                channelName = channel.name,
                modifier = Modifier.size(44.dp),
                maxLogoWidth = 44.dp,
                maxLogoHeight = 34.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        text = channel.name,
                        color = Color.White.copy(alpha = 0.96f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    LiveTvFavoriteMark(isFavorite)
                }
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.66f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badges.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        badges.forEach { badge -> LiveTvQualityBadge(badge) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveTvGlassButton(
                    text = if (isFavorite) "Saved" else "Save",
                    selected = isFavorite,
                    onClick = onToggleFavorite,
                    contentDescription = "Save channel",
                )
                LiveTvPlayButton(
                    onClick = onPlay,
                    contentDescription = "Play channel",
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    currentProgramme: String?,
    nextProgramme: String?,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
) {
    TorveListRow(
        title = channel.name,
        subtitle = buildString {
            append(channel.groupTitle ?: "Ungrouped")
            currentProgramme?.takeIf { it.isNotBlank() }?.let {
                append(" • Now: ")
                append(it)
            }
            nextProgramme?.takeIf { it.isNotBlank() }?.let {
                append(" • Next: ")
                append(it)
            }
        },
        selected = isSelected,
        onClick = onSelect,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isFavorite) {
                    TorveBadge("Fav", tone = TorveBadgeTone.Warning)
                }
                TorveGhostButton(
                    text = if (isFavorite) "Unsave" else "Save",
                    onClick = onToggleFavorite,
                )
                TorvePrimaryButton(
                    text = "Play",
                    onClick = onPlay,
                )
            }
        },
    )
}

@Composable
private fun PremiumSelectedChannelPane(
    selectedChannel: Channel?,
    programmes: List<com.torve.domain.model.EpgProgramme>,
    onPlay: (Channel) -> Unit,
    isRecording: (Channel) -> Boolean = { false },
    onToggleRecord: (Channel) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LiveTvGlassPanel(modifier) {
        if (selectedChannel == null) {
            TorvePlaceholderState(
                modifier = Modifier.fillMaxSize(),
                title = "Nothing selected",
                description = "Select a channel from the center list to view guide details and start playback.",
            )
        } else {
            val colors = TorveDesktopThemeTokens.colors
            val scrollState = rememberScrollState()
            val nowMs = remember(selectedChannel, programmes) { System.currentTimeMillis() }
            val current = programmes.firstOrNull { it.startTime <= nowMs && it.endTime > nowMs }
                ?: programmes.firstOrNull()
            val next = programmes.firstOrNull { programme ->
                programme.startTime > nowMs && programme != current
            }
            val progress = current
                ?.takeIf { it.startTime <= nowMs && it.endTime > nowMs && it.endTime > it.startTime }
                ?.let {
                    ((nowMs - it.startTime).toFloat() / (it.endTime - it.startTime).toFloat())
                        .coerceIn(0f, 1f)
                }

            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 10.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val logo = remember(selectedChannel.name, selectedChannel.tvgLogo) {
                            LiveTvChannelLogoResolver.resolveLogo(selectedChannel)
                        }
                        LiveTvChannelLogo(
                            logo = logo,
                            channelName = selectedChannel.name,
                            modifier = Modifier.size(54.dp),
                            maxLogoWidth = 72.dp,
                            maxLogoHeight = 50.dp,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = selectedChannel.name,
                                color = Color.White.copy(alpha = 0.98f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = selectedChannel.groupTitle ?: "Ungrouped",
                                color = Color.White.copy(alpha = 0.68f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        LiveTvQualityBadge(selectedChannel.tvgCountry?.takeIf { it.isNotBlank() } ?: "LIVE")
                    }

                    if (current == null) {
                        TorvePlaceholderState(
                            title = "No guide data yet",
                            description = "Guide data is still loading or this channel is not matched to an EPG entry.",
                        )
                    } else {
                        LiveTvStatusPill("EPG: Available", showDot = true, modifier = Modifier.widthIn(max = 180.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                "Now",
                                color = colors.accent.copy(alpha = 0.94f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = current.title,
                                color = Color.White.copy(alpha = 0.96f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatLiveTimeRange(current.startTime, current.endTime),
                                color = Color.White.copy(alpha = 0.68f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            progress?.let { pct ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Color.White.copy(alpha = 0.10f)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(pct)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(colors.accent.copy(alpha = 0.78f)),
                                    )
                                }
                            }
                            current.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    color = Color.White.copy(alpha = 0.68f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    next?.let { programme ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Next", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = programme.title,
                                color = Color.White.copy(alpha = 0.90f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatLiveTimeRange(programme.startTime, programme.endTime),
                                color = Color.White.copy(alpha = 0.62f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveTvPlayButton(
                            text = "Play Channel",
                            onClick = { onPlay(selectedChannel) },
                            modifier = Modifier.weight(1f),
                            contentDescription = "Play selected channel",
                        )
                        val recording = isRecording(selectedChannel)
                        LiveTvGlassButton(
                            text = if (recording) "Stop Recording" else "Record Now (2h)",
                            onClick = { onToggleRecord(selectedChannel) },
                            contentDescription = if (recording) "Stop recording" else "Record channel for two hours",
                        )
                    }
                    if (isRecording(selectedChannel)) {
                        Text(
                            text = "Recording in progress. Files appear in your recordings folder as they are written.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.66f),
                        )
                    }
                    Text(
                        text = "Source: ${selectedChannel.groupTitle ?: "Live TV"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PremiumVerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun SelectedChannelPane(
    selectedChannel: Channel?,
    programmes: List<com.torve.domain.model.EpgProgramme>,
    onPlay: (Channel) -> Unit,
    isRecording: (Channel) -> Boolean = { false },
    onToggleRecord: (Channel) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TorveSectionCard(
            title = selectedChannel?.name ?: "Channel Details",
            supportingText = selectedChannel?.groupTitle ?: "Select a channel to see current guide data and quick actions.",
        ) {
            if (selectedChannel == null) {
                TorvePlaceholderState(
                    title = "Nothing selected",
                    description = "Select a channel from the center list to view EPG details and start playback.",
                )
            } else {
                Text(
                    // Redact Xtream username/password before showing the
                    // stream URL — the raw URL surfaces credentials in
                    // plain text on the channel detail pane (provider
                    // path is `/live/<user>/<pass>/<id>.ts`).
                    text = redactStreamUrlForDisplay(selectedChannel.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                selectedChannel.tvgCountry?.let {
                    Text(
                        text = "Country: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorvePrimaryButton(text = "Play Channel", onClick = { onPlay(selectedChannel) })
                    val recording = isRecording(selectedChannel)
                    TorveGhostButton(
                        text = if (recording) "● Stop Recording" else "● Record Now (2h)",
                        onClick = { onToggleRecord(selectedChannel) },
                    )
                }
                if (isRecording(selectedChannel)) {
                    Text(
                        text = "Recording in progress. Files appear in your recordings folder " +
                            "as they're written. Default duration is 2 hours; tap Stop to end early.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                if (programmes.isEmpty()) {
                    TorvePlaceholderState(
                        // The previous copy ("This channel has no EPG
                        // data loaded yet") implied the EPG itself
                        // hadn't loaded — but the playlist's EPG is
                        // loaded, this single channel just has no
                        // matching tvg-id in the EPG source. Be honest
                        // about which case it is.
                        title = "No guide entries for this channel",
                        description = "Either the EPG is still loading, or this channel's tvg-id doesn't match any entry in the EPG source.",
                    )
                } else {
                    programmes.take(8).forEach { programme ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = programme.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = programme.description ?: "No description",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val ReminderTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun formatReminderTime(epochMs: Long): String =
    ReminderTimeFormatter.format(Instant.ofEpochMilli(epochMs))

private fun formatLiveTimeRange(startMs: Long, endMs: Long): String =
    "${formatReminderTime(startMs)}-${formatReminderTime(endMs)}"

/**
 * Strip Xtream username/password segments and `username=` /
 * `password=` query parameters from a stream URL before showing it
 * to the user. Without this, the channel-detail pane prints the raw
 * URL including credentials in plain text — anyone glancing at the
 * screen (or screen-sharing) walks away with the provider login.
 *
 * Visible in the live recording 2026-05-02 181744.mp4: the right
 * pane showed `http://smatv.pro/live/c55e6450464d/53ec13d581/...`
 * with the username and password readable.
 */
private val XTREAM_PATH_CREDS = Regex("(/(?:live|movie|series))/[^/]+/[^/]+/")
private val URL_QUERY_CREDS = Regex("([?&])(username|password|api_key|apikey|token)=[^&]*", RegexOption.IGNORE_CASE)

internal fun redactStreamUrlForDisplay(url: String): String {
    if (url.isBlank()) return url
    return url
        .replace(XTREAM_PATH_CREDS, "$1/***/***/")
        .replace(URL_QUERY_CREDS, "$1$2=***")
}
