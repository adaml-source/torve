package com.torve.desktop.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.playback.DesktopPlaybackSession
import com.torve.desktop.playback.DesktopPlaybackSourceCandidate
import com.torve.desktop.playback.DesktopPlayerPhase
import com.torve.desktop.playback.DesktopPlayerUiState
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePill
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.detail.DesktopTrustBadge
import com.torve.desktop.ui.detail.sourceTrustBadges
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.data.addon.containsKnownTorrentProviderMarker
import com.torve.data.addon.containsUsenetMarker
import com.torve.presentation.detail.StreamFilterUiText

enum class DesktopDockVisualState {
    HIDDEN,
    ACTIVE,
    PAUSED,
    FAILED,
    RESUMABLE,
    DISMISSED,
}

fun desktopDockVisualState(
    playerState: DesktopPlayerUiState,
    dismissed: Boolean,
): DesktopDockVisualState {
    val hasRequest = playerState.currentRequest != null || playerState.preparedSession != null
    if (!hasRequest) return DesktopDockVisualState.HIDDEN
    return when (playerState.phase) {
        DesktopPlayerPhase.OPENING,
        DesktopPlayerPhase.BUFFERING,
        DesktopPlayerPhase.PLAYING,
        -> DesktopDockVisualState.ACTIVE

        DesktopPlayerPhase.PAUSED -> DesktopDockVisualState.PAUSED

        DesktopPlayerPhase.RESOLUTION_FAILED,
        DesktopPlayerPhase.RUNTIME_ERROR,
        -> if (dismissed) DesktopDockVisualState.DISMISSED else DesktopDockVisualState.FAILED

        DesktopPlayerPhase.RESOLVED,
        DesktopPlayerPhase.STOPPED,
        DesktopPlayerPhase.CLOSED,
        -> if (dismissed) DesktopDockVisualState.DISMISSED else DesktopDockVisualState.RESUMABLE

        DesktopPlayerPhase.IDLE,
        DesktopPlayerPhase.RESOLVING,
        -> DesktopDockVisualState.HIDDEN
    }
}

enum class DesktopSourcePickerIntent { PLAY, DOWNLOAD }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopSourcePickerOverlay(
    visible: Boolean,
    playerState: DesktopPlayerUiState,
    intent: DesktopSourcePickerIntent = DesktopSourcePickerIntent.PLAY,
    onDismiss: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onPlayCandidate: (String) -> Unit,
    onDownloadCandidate: ((String) -> Unit)? = null,
    onDownloadSelected: (() -> Unit)?,
    onRetry: () -> Unit,
    onPlaySelected: () -> Unit,
) {
    if (!visible) return
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val session = playerState.preparedSession
    val selectedCandidateId = session?.selectedCandidate?.candidateId
    val hasPremium = com.torve.desktop.premium.rememberHasPremium()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA6000000))
            // Click outside the surface dismisses; clicks on the surface
            // itself are absorbed by the disabled clickable below.
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(980.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    enabled = false,
                    onClick = {},
                ),
            color = colors.drawerSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(radii.xxl),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle),
            shadowElevation = TorveDesktopThemeTokens.elevations.overlay,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (intent == DesktopSourcePickerIntent.DOWNLOAD) "Choose Source to Download" else "Choose Source",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = playerState.currentRequest?.title ?: "Current playback request",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                    TorveGhostButton(
                        text = "Close",
                        onClick = onDismiss,
                    )
                }

                when {
                    playerState.phase == DesktopPlayerPhase.RESOLVING && session == null -> {
                        TorveBanner(
                            title = "Preparing sources",
                            description = "Torve is resolving source candidates for this request.",
                            tone = TorveBannerTone.Info,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    session == null -> {
                        TorveBanner(
                            title = "No sources ready",
                            description = playerState.error?.message ?: "Resolve a playback session before choosing a source.",
                            tone = if (playerState.error != null) TorveBannerTone.Error else TorveBannerTone.Info,
                        )
                    }

                    else -> {
                        playerState.error
                            ?.takeUnless {
                                it.code == "ALL_STREAMS_FILTERED" &&
                                    session.streamCandidates.isEmpty() &&
                                    hasPremium
                            }
                            ?.let { error ->
                            TorveBanner(
                                title = "Playback recovery",
                                description = error.message,
                                tone = TorveBannerTone.Warning,
                            )
                        }

                        if (session.streamCandidates.isEmpty()) {
                            val allHiddenMessage = StreamFilterUiText.allHiddenMessage(
                                visibleCount = 0,
                                hiddenCount = session.filterHiddenCount,
                            ).takeIf { hasPremium }
                            TorveBanner(
                                title = allHiddenMessage ?: "No sources ready",
                                description = if (allHiddenMessage != null) {
                                    StreamFilterUiText.ADJUST_REGEX_HINT
                                } else {
                                    "No playable source candidates were returned for this request."
                                },
                                tone = if (allHiddenMessage != null) TorveBannerTone.Warning else TorveBannerTone.Info,
                            )
                        } else {
                            DesktopSourcePickerHeader(session = session)
                            StreamFilterUiText.hiddenCountMessage(
                                hiddenCount = session.filterHiddenCount,
                                premiumFeedbackEnabled = hasPremium,
                            )?.let { message ->
                                TorveBanner(
                                    title = message,
                                    description = "Regex Patterns hid matching streams before this list was shown.",
                                    tone = TorveBannerTone.Info,
                                )
                            }

                            // Two-axis filter: provider TYPE (Debrid /
                            // Torrent / Usenet / Direct) and specific
                            // addon. Most users care about type ("only
                            // show debrid-cached") which is why that's the
                            // top row.
                            val typeOf: (DesktopPlaybackSourceCandidate) -> SourceProviderType = { c ->
                                classifyCandidate(c)
                            }
                            val typeCounts = session.streamCandidates
                                .groupingBy(typeOf)
                                .eachCount()
                            val addonNames = session.streamCandidates
                                .map { it.addonName }
                                .filter { it.isNotBlank() }
                                .groupingBy { it }
                                .eachCount()
                                .entries
                                .sortedByDescending { it.value }
                                .map { it.key }

                        var selectedType: SourceProviderType? by remember(session.streamCandidates) {
                            mutableStateOf(null)
                        }
                        var selectedAddon: String? by remember(session.streamCandidates) {
                            mutableStateOf(null)
                        }

                        // Top row — provider type. Always visible when
                        // there's at least one stream so the
                        // categorisation is discoverable; a single-type
                        // result still gets a one-chip row that makes
                        // it obvious *what* category this title's
                        // streams fell into ("you're seeing only Usenet
                        // -- try installing Torrentio for torrents").
                        if (session.streamCandidates.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TorveFilterChip(
                                    text = "All (${session.streamCandidates.size})",
                                    selected = selectedType == null,
                                    onClick = { selectedType = null },
                                )
                                SourceProviderType.entries.forEach { type ->
                                    val count = typeCounts[type] ?: 0
                                    if (count > 0) {
                                        TorveFilterChip(
                                            text = "${type.label} ($count)",
                                            selected = selectedType == type,
                                            onClick = {
                                                selectedType = if (selectedType == type) null else type
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // Bottom row — specific addon (when more than one)
                        if (addonNames.size > 1) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                addonNames.forEach { name ->
                                    val count = session.streamCandidates.count { it.addonName == name }
                                    TorveFilterChip(
                                        text = "$name ($count)",
                                        selected = selectedAddon == name,
                                        onClick = {
                                            selectedAddon = if (selectedAddon == name) null else name
                                        },
                                    )
                                }
                            }
                        }

                        val visibleCandidates = session.streamCandidates.filter { c ->
                            (selectedType == null || typeOf(c) == selectedType) &&
                                (selectedAddon == null || c.addonName == selectedAddon)
                        }

                        if (visibleCandidates.isEmpty()) {
                            TorveBanner(
                                title = "No matches for current filter",
                                description = "Loosen the filter or pick \"All\" to see every source for this title.",
                                tone = TorveBannerTone.Info,
                            )
                        }

                            visibleCandidates.forEach { candidate ->
                                DesktopSourceCandidateCard(
                                    candidate = candidate,
                                    session = session,
                                    selected = selectedCandidateId == candidate.candidateId,
                                    failedPreviously = playerState.failedCandidateIds.contains(candidate.candidateId),
                                    intent = intent,
                                    onSelect = { onSelectCandidate(candidate.candidateId) },
                                    onPlay = { onPlayCandidate(candidate.candidateId) },
                                    onDownload = onDownloadCandidate?.let { { it(candidate.candidateId) } },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (playerState.phase == DesktopPlayerPhase.RESOLUTION_FAILED ||
                        playerState.phase == DesktopPlayerPhase.RUNTIME_ERROR
                    ) {
                        TorveSecondaryButton(
                            text = "Retry",
                            onClick = onRetry,
                        )
                    }
                    if (intent == DesktopSourcePickerIntent.PLAY) {
                        TorvePrimaryButton(
                            text = "Play Selected",
                            onClick = onPlaySelected,
                            enabled = selectedCandidateId != null,
                        )
                        if (onDownloadSelected != null) {
                            TorveSecondaryButton(
                                text = "Download Selected",
                                onClick = onDownloadSelected,
                                enabled = selectedCandidateId != null,
                            )
                        }
                    } else if (onDownloadSelected != null) {
                        TorvePrimaryButton(
                            text = "Download Selected",
                            onClick = onDownloadSelected,
                            enabled = selectedCandidateId != null,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopSourcePickerHeader(
    session: DesktopPlaybackSession,
) {
    TorveSectionCard(
        title = "Playback confidence",
        supportingText = "Human-readable source choice comes first. Technical plumbing stays out of the primary decision path.",
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            session.provider?.label?.let { TorvePill(it) }
            session.episodeContext?.label?.let { TorvePill(it) }
            if (session.subtitleCandidates.isNotEmpty()) {
                TorveBadge(
                    text = "${session.subtitleCandidates.size} subtitles",
                    tone = TorveBadgeTone.Accent,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopSourceCandidateCard(
    candidate: DesktopPlaybackSourceCandidate,
    session: DesktopPlaybackSession,
    selected: Boolean,
    failedPreviously: Boolean,
    intent: DesktopSourcePickerIntent,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val trustBadges = sourceTrustBadges(
        candidate = candidate,
        session = session,
        isRecommended = session.recommendedCandidateId == candidate.candidateId,
        failedPreviously = failedPreviously,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        color = if (selected) colors.accentContainer else colors.cardSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(radii.lg),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                selected -> colors.accent
                failedPreviously -> colors.warning.copy(alpha = 0.5f)
                else -> colors.borderSubtle
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Stremio Stream.title can be multi-line; second line carries
                    // Panda's metadata (resolution/size/codec + 🗣️ language tags).
                    val titleLines = candidate.title.split('\n')
                    Text(
                        text = titleLines.first(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (titleLines.size > 1) {
                        Text(
                            text = titleLines.drop(1).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    Text(
                        text = buildString {
                            append(candidate.addonName)
                            candidate.source?.takeIf { it.isNotBlank() }?.let {
                                append(" • ")
                                append(it)
                            }
                            if (candidate.languages.isNotEmpty()) {
                                append(" • \uD83D\uDDE3 ")
                                append(candidate.languages.joinToString(", "))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                if (intent == DesktopSourcePickerIntent.DOWNLOAD && onDownload != null) {
                    TorveSecondaryButton(
                        text = "Download",
                        onClick = onDownload,
                    )
                } else {
                    TorveSecondaryButton(
                        text = "Play",
                        onClick = onPlay,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                trustBadges.forEach { badge ->
                    CandidateTrustBadge(badge)
                }
            }
        }
    }
}

@Composable
private fun CandidateTrustBadge(
    badge: DesktopTrustBadge,
) {
    if (badge.accent) {
        TorveBadge(text = badge.label, tone = badge.tone)
    } else {
        TorvePill(text = badge.label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopPlaybackDock(
    playerState: DesktopPlayerUiState,
    dismissed: Boolean,
    onReturn: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onChooseSource: () -> Unit,
    onDismissResumable: () -> Unit,
) {
    val visualState = desktopDockVisualState(playerState, dismissed)
    if (visualState == DesktopDockVisualState.HIDDEN || visualState == DesktopDockVisualState.DISMISSED) {
        return
    }

    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val session = playerState.preparedSession
    val selected = session?.selectedCandidate
    val title = playerState.currentRequest?.title ?: session?.mediaItem?.title ?: "Playback session"
    // TMDB show / movie logo, when the playback session brought one.
    // For NZB-launched playback, [V2App.playNzbHint] fetches images and
    // patches it onto the synthetic MediaItem, so the dock can render
    // the show's branded title art at this thumbnail size instead of
    // plain text.
    val dockLogo = com.torve.desktop.ui.v2.components.rememberCachedBitmap(session?.mediaItem?.logoUrl)
    val episodeLabel = session?.episodeContext?.label
    val subtitle = buildString {
        append(
            when (visualState) {
                DesktopDockVisualState.ACTIVE -> "Playback active"
                DesktopDockVisualState.PAUSED -> "Playback paused"
                DesktopDockVisualState.FAILED -> "Playback failed"
                DesktopDockVisualState.RESUMABLE -> "Ready to resume"
                else -> "Playback"
            },
        )
        // Surface S/E first - "Ready to resume • S1E1 • Piya Wiconi"
        // beats "Ready to resume • shows • auto" for context.
        if (!episodeLabel.isNullOrBlank()) {
            append(" • ")
            append(episodeLabel)
        }
        // Friendlier media-type label for the dock when the addon name
        // is a synthetic surface tag from openPreResolvedStream.
        val addonLabel = when (selected?.addonName) {
            "shows" -> "TV Show"
            "movies" -> "Movie"
            "adult" -> "Adult"
            "sports" -> "Sports"
            else -> selected?.addonName
        }
        addonLabel?.let {
            append(" • ")
            append(it)
        }
        selected?.quality?.takeIf { it.isNotBlank() && it != "auto" }?.let {
            append(" • ")
            append(it)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (visualState == DesktopDockVisualState.FAILED) {
            colors.error.copy(alpha = 0.12f)
        } else {
            colors.dockSurface
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(radii.xl),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (visualState == DesktopDockVisualState.FAILED) colors.error.copy(alpha = 0.45f) else colors.borderSubtle,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dockLogo != null) {
                    androidx.compose.foundation.Image(
                        bitmap = dockLogo,
                        contentDescription = title,
                        modifier = Modifier.height(28.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Hide the text title when the branded logo is
                    // rendered - duplicate naming clutters the dock.
                    if (dockLogo == null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = playerState.error?.message ?: subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                selected?.let {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sourceTrustBadges(
                            candidate = it,
                            session = session ?: return@FlowRow,
                            isRecommended = session.recommendedCandidateId == it.candidateId,
                            failedPreviously = playerState.failedCandidateIds.contains(it.candidateId),
                        ).take(3).forEach { badge ->
                            CandidateTrustBadge(badge)
                        }
                    }
                }

                TorveGhostButton(
                    text = "Return",
                    onClick = onReturn,
                )

                when (visualState) {
                    DesktopDockVisualState.ACTIVE -> {
                        TorveSecondaryButton(text = "Pause", onClick = onPause)
                    }

                    DesktopDockVisualState.PAUSED -> {
                        TorveSecondaryButton(text = "Resume", onClick = onResume)
                        TorveGhostButton(text = "Choose Source", onClick = onChooseSource)
                    }

                    DesktopDockVisualState.FAILED -> {
                        TorveSecondaryButton(text = "Retry", onClick = onRetry)
                        TorveGhostButton(text = "Choose Source", onClick = onChooseSource)
                        // Same dismiss action as the resumable state - without
                        // it, a failed dock had no close affordance and stuck
                        // around until the user navigated away or retried.
                        TorveGhostButton(text = "Dismiss", onClick = onDismissResumable)
                    }

                    DesktopDockVisualState.RESUMABLE -> {
                        TorveSecondaryButton(text = "Resume", onClick = onResume)
                        TorveGhostButton(text = "Dismiss", onClick = onDismissResumable)
                    }

                    else -> Unit
                }
            }
        }
    }
}

/**
 * Provider-type axis for the source-picker filter chips. Mirrors the
 * way users mentally categorise streams (debrid vs torrent vs Usenet
 * vs direct) rather than the addon that produced them.
 */
enum class SourceProviderType(val label: String) {
    DEBRID_CACHED("Debrid (cached)"),
    TORRENT("Torrent"),
    USENET("Usenet / NZB"),
    DIRECT("Direct stream"),
}

private fun classifyCandidate(candidate: DesktopPlaybackSourceCandidate): SourceProviderType {
    val url = candidate.directUrl.orEmpty()

    // NZB / Usenet detection: explicit `/nzb/` segment in Panda's
    // per-user URL, `.nzb` extension, or NZB markers in the rendered
    // title / source label. Catches SceneNZBs, NZBHydra, Easynews,
    // TorBox NZB, etc.
    val looksLikeNzb = url.contains("/nzb/", ignoreCase = true) ||
        url.endsWith(".nzb", ignoreCase = true) ||
        url.contains("/easynews/", ignoreCase = true) ||
        candidate.source.containsUsenetMarker() ||
        candidate.title.containsUsenetMarker()
    if (looksLikeNzb) return SourceProviderType.USENET

    // Torrent / debrid: infoHash/magnet-style rows and configured debrid
    // add-ons such as Torrentio+RD may render as direct URLs after the
    // provider pre-resolves them. Keep those in the torrent/debrid axis
    // instead of calling them generic direct streams.
    val looksLikeTorrentOrDebrid = candidate.infoHash != null ||
        candidate.addonName.containsKnownTorrentProviderMarker() ||
        candidate.source.containsKnownTorrentProviderMarker() ||
        candidate.title.containsKnownTorrentProviderMarker() ||
        (
            candidate.addonBaseUrl?.contains("panda.torve.app", ignoreCase = true) == true &&
                candidate.directUrl != null
            )
    if (looksLikeTorrentOrDebrid) {
        return if (candidate.isCached || candidate.directUrl != null) {
            SourceProviderType.DEBRID_CACHED
        } else {
            SourceProviderType.TORRENT
        }
    }

    // Everything else with a directUrl is a direct stream (hoster URL,
    // HLS/DASH, addon-hosted, etc.).
    return SourceProviderType.DIRECT
}
