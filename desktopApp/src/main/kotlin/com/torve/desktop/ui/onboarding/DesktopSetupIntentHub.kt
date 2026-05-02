package com.torve.desktop.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.admission.DesktopAdmissionSnapshot
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.presentation.setup.ReadyToWatchSummary
import com.torve.presentation.setup.SetupIntent
import com.torve.presentation.setup.SetupIntentState
import com.torve.presentation.setup.SetupIntentStatus
import com.torve.presentation.setup.SetupWizardCoordinator

/**
 * Credential-first setup hub. Replaces the legacy linear wizard as the
 * default desktop onboarding surface. Renders four intent cards
 * (Debrid / IPTV / Plex+Jellyfin / Usenet) with traffic-light status,
 * an aggregated "Ready to watch" banner, and per-intent
 * Set-up / Validate / Reset buttons.
 *
 * The hub never enters credentials itself - each Set-up button routes to
 * the matching detail surface (legacy wizard step for Debrid/IPTV,
 * Settings for Plex/Jellyfin, Panda setup for Usenet) so we don't
 * duplicate per-intent forms. Validate hits the coordinator's validator
 * which delegates to the same clients/state as the provider-health
 * checkers, so the wizard verdict and the health UI agree.
 */
@Composable
fun DesktopSetupIntentHub(
    coordinator: SetupWizardCoordinator,
    admission: DesktopAdmissionSnapshot?,
    onOpenDebridSetup: () -> Unit,
    onOpenIptvSetup: () -> Unit,
    onOpenPlexJellyfinSetup: () -> Unit,
    onOpenUsenetSetup: () -> Unit,
    onUseGuidedWizard: () -> Unit,
    onShowQrReceive: () -> Unit,
    onContinueToDesktop: () -> Unit,
    isCompleting: Boolean = false,
    completionError: String? = null,
    modifier: Modifier = Modifier,
) {
    // Use rememberUpdatedState so the effect always sees the most recent
    // admission when it runs, without re-triggering the effect on each
    // recomposition (which would fight user resets).
    val currentAdmission = rememberUpdatedState(admission)
    var hubReady by remember { mutableStateOf(false) }

    // Load persisted per-intent state from prefs, downgrade any stuck
    // VALIDATING rows to IN_PROGRESS, then seed any admission-detected paths
    // as READY so the hub immediately reflects what the account already has
    // configured — no guided-wizard visit required.
    LaunchedEffect(coordinator) {
        coordinator.load()
        currentAdmission.value?.let { snap ->
            coordinator.applyAdmissionHints(
                hasVodPath = snap.hasVodPlaybackPath,
                hasLivePath = snap.hasLivePlaybackPath,
            )
        }
        hubReady = true
    }

    val state by coordinator.state.collectAsState()
    val summary by coordinator.summary.collectAsState()
    val colors = TorveDesktopThemeTokens.colors
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Pick what you'd like to set up.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Text(
            text = "You can complete one path or all four - the hub remembers progress between launches.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )

        ReadyToWatchBanner(
            summary = summary,
            admission = admission,
            isLoading = !hubReady,
        )

        completionError?.let {
            TorveBanner(
                title = "Setup incomplete",
                description = it,
                tone = TorveBannerTone.Error,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SetupIntent.entries.forEach { intent ->
                SetupIntentCard(
                    intent = intent,
                    intentState = state[intent] ?: SetupIntentState(intent = intent),
                    summary = summary,
                    onSetUp = {
                        when (intent) {
                            SetupIntent.DEBRID -> onOpenDebridSetup()
                            SetupIntent.IPTV -> onOpenIptvSetup()
                            SetupIntent.PLEX_JELLYFIN -> onOpenPlexJellyfinSetup()
                            SetupIntent.USENET -> onOpenUsenetSetup()
                        }
                    },
                    onValidate = { coordinator.validate(intent) },
                    onReset = { coordinator.reset(intent) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveGhostButton(
                    text = "Use guided wizard instead",
                    onClick = onUseGuidedWizard,
                    enabled = !isCompleting,
                )
                TorveGhostButton(
                    text = "Receive from another device",
                    onClick = onShowQrReceive,
                    enabled = !isCompleting,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isCompleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                }
                TorvePrimaryButton(
                    text = if (summary.canStartWatching) "Continue to Torve" else "Set up at least one path",
                    onClick = onContinueToDesktop,
                    enabled = summary.canStartWatching && !isCompleting,
                )
            }
        }
    }
}

@Composable
private fun ReadyToWatchBanner(
    summary: ReadyToWatchSummary,
    admission: DesktopAdmissionSnapshot?,
    isLoading: Boolean,
) {
    if (isLoading) {
        TorveBanner(
            title = "Checking your Torve account...",
            description = "Looking for existing playback paths and account sync.",
            tone = TorveBannerTone.Info,
        )
        return
    }

    val tone = when {
        summary.canStartWatching && summary.attentionCount == 0 -> TorveBannerTone.Success
        summary.canStartWatching -> TorveBannerTone.Info
        summary.invalid.isNotEmpty() -> TorveBannerTone.Error
        else -> TorveBannerTone.Info
    }
    val title = when {
        summary.canStartWatching && summary.attentionCount == 0 ->
            "Ready to watch - ${summary.ready.size} path${if (summary.ready.size == 1) "" else "s"} green."
        summary.canStartWatching ->
            "Ready to watch - ${summary.ready.size} ready, ${summary.attentionCount} need attention."
        summary.invalid.isNotEmpty() ->
            "Setup incomplete - ${summary.invalid.size} path${if (summary.invalid.size == 1) "" else "s"} need fixing."
        else -> "Pick a path to start."
    }
    val descriptionParts = buildList {
        if (summary.ready.isNotEmpty()) add("Ready: " + summary.ready.joinToString(", ") { it.shortName() })
        if (summary.warnings.isNotEmpty()) add("Warnings: " + summary.warnings.joinToString(", ") { it.shortName() })
        if (summary.invalid.isNotEmpty()) add("Fix: " + summary.invalid.joinToString(", ") { it.shortName() })
    }
    // "No paths configured yet." only appears after detection completes AND
    // no paths were found. If admission detects paths that the coordinator
    // didn't absorb (edge case: hints not applied yet), surface a retry
    // prompt instead of the misleading "no paths" message.
    val emptyDescription = if (admission?.hasUsablePlaybackPath == true) {
        "Paths detected — click Validate on a path card to confirm, or Continue if ready."
    } else {
        "No paths configured yet."
    }
    TorveBanner(
        title = title,
        description = descriptionParts.joinToString(" • ").ifBlank { emptyDescription },
        tone = tone,
    )
}

@Composable
private fun SetupIntentCard(
    intent: SetupIntent,
    intentState: SetupIntentState,
    summary: ReadyToWatchSummary,
    onSetUp: () -> Unit,
    onValidate: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    // Use the summary's resolved status so the badge always agrees with
    // the "Ready to watch" banner (health rows can override optimistic state).
    val resolvedStatus = summary.resolvedStatusFor(intent)
    val tone = resolvedStatus.toBadgeTone()
    val statusLabel = resolvedStatus.toUiLabel()
    val message = intentState.message

    TorveSectionCard(
        title = intent.title,
        supportingText = intent.tagline,
        trailing = { TorveBadge(text = statusLabel, tone = tone) },
    ) {
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorvePrimaryButton(
                text = if (resolvedStatus == SetupIntentStatus.READY) "Edit" else "Set up",
                onClick = onSetUp,
            )
            TorveGhostButton(
                text = "Validate",
                onClick = onValidate,
                enabled = resolvedStatus != SetupIntentStatus.VALIDATING,
            )
            if (resolvedStatus != SetupIntentStatus.NOT_STARTED) {
                TorveGhostButton(text = "Reset", onClick = onReset)
            }
            if (resolvedStatus == SetupIntentStatus.VALIDATING) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                )
            }
        }
    }
}

private fun SetupIntentStatus.toBadgeTone(): TorveBadgeTone = when (this) {
    SetupIntentStatus.READY -> TorveBadgeTone.Success
    SetupIntentStatus.NEEDS_ATTENTION -> TorveBadgeTone.Warning
    SetupIntentStatus.INVALID -> TorveBadgeTone.Error
    SetupIntentStatus.VALIDATING -> TorveBadgeTone.Accent
    SetupIntentStatus.IN_PROGRESS -> TorveBadgeTone.Accent
    SetupIntentStatus.NOT_STARTED -> TorveBadgeTone.Neutral
}

private fun SetupIntentStatus.toUiLabel(): String = when (this) {
    SetupIntentStatus.READY -> "Ready"
    SetupIntentStatus.NEEDS_ATTENTION -> "Attention"
    SetupIntentStatus.INVALID -> "Fix"
    SetupIntentStatus.VALIDATING -> "Checking..."
    SetupIntentStatus.IN_PROGRESS -> "In progress"
    SetupIntentStatus.NOT_STARTED -> "Not started"
}

private fun SetupIntent.shortName(): String = when (this) {
    SetupIntent.DEBRID -> "Debrid"
    SetupIntent.IPTV -> "IPTV"
    SetupIntent.PLEX_JELLYFIN -> "Plex/Jellyfin"
    SetupIntent.USENET -> "Usenet"
}
