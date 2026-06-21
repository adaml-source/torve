package com.torve.android.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.presentation.setup.ReadyToWatchSummary
import com.torve.presentation.setup.SetupIntent
import com.torve.presentation.setup.SetupIntentState
import com.torve.presentation.setup.SetupIntentStatus
import com.torve.presentation.setup.SetupWizardCoordinator

/**
 * Credential-first hub for Android. Renders four intent cards
 * (Debrid / IPTV / Plex+Jellyfin / Usenet) with a per-intent
 * status pill, plus a "Ready to watch" summary banner.
 *
 * Each "Set up" button routes to an existing surface — Debrid and IPTV
 * deep-link into the legacy guided wizard's matching step (so users
 * still get the existing credential-entry forms), Plex/Jellyfin opens
 * Settings → Library, Usenet opens Panda setup. The hub itself doesn't
 * host credential forms; its job is to track per-intent progress and
 * surface the aggregate Ready-to-watch verdict.
 */
@Composable
fun SetupIntentHubScreen(
    coordinator: SetupWizardCoordinator,
    onOpenDebridSetup: () -> Unit,
    onOpenIptvSetup: () -> Unit,
    onOpenPlexJellyfinSetup: () -> Unit,
    onOpenUsenetSetup: () -> Unit,
    onUseGuidedWizard: () -> Unit,
    onContinueToApp: () -> Unit,
    onExit: (() -> Unit)? = null,
    onSkipToApp: (() -> Unit)? = null,
) {
    LaunchedEffect(coordinator) {
        coordinator.load()
        // Auto-validate every intent once on entry. Without this the
        // user has to tap "Validate" on each row before the badges
        // (and the summary card) reflect their actual configured
        // state — a fresh hub open showed everything as "Not started"
        // even when Panda + IPTV were fully wired and working.
        // Validators are cheap (in-process state inspection, no
        // network), so running them all is fine.
        for (intent in SetupIntent.entries) {
            coordinator.validate(intent)
        }
    }

    val state by coordinator.state.collectAsState()
    val summary by coordinator.summary.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Pad for the status bar (top) and gesture nav bar (bottom)
            // BEFORE the scroll/inner padding — without this, the
            // top-right "Close setup" TextButton paints under the
            // notification area on edge-to-edge displays (Pixel 8 Pro,
            // Galaxy S24, etc.) and either falls behind the system UI or
            // shrinks past its 48dp tap target. Same fix at the bottom
            // for the gesture pill.
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onExit != null) {
            TextButton(
                onClick = onExit,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Close setup")
            }
        }
        Text(
            text = "Set up Torve for watching.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Start with one source. Panda covers Debrid and Usenet; IPTV is separate for live TV; Plex/Jellyfin connects your existing library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ReadyToWatchSummaryCard(summary)

        SetupIntent.entries.forEach { intent ->
            SetupIntentRow(
                intent = intent,
                state = state[intent] ?: SetupIntentState(intent = intent),
                // The summary card derives its readiness by merging the
                // raw per-intent state with the live provider-health
                // rows (so a GREEN ProviderHealth check can flip an
                // unvalidated intent into READY). The row badge has to
                // use the same resolved status or the summary and the
                // row contradict each other.
                resolvedStatus = summary.resolvedStatusFor(intent),
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

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The guided wizard is an alternative entry into setup —
            // only meaningful before the user has wired any source.
            // Once paths are green, "Continue" and the wizard both
            // ultimately exit to the app, so showing both side-by-side
            // reads as two ways to do the same thing. Hide it then.
            if (!summary.canStartWatching) {
                TextButton(onClick = onUseGuidedWizard) {
                    Text("Use guided wizard instead")
                }
            } else {
                Spacer(Modifier.width(0.dp))
            }
            Button(
                onClick = onContinueToApp,
                enabled = summary.canStartWatching,
            ) {
                // Use a verb that signals "you're done with setup, this
                // takes you out". "Continue" was ambiguous — readers
                // expected it to advance setup, not exit.
                Text(if (summary.canStartWatching) "Start watching" else "Set up at least one path")
            }
        }
        if (!summary.canStartWatching && onSkipToApp != null) {
            TextButton(
                onClick = onSkipToApp,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Explore without sources")
            }
        }
    }
}

@Composable
private fun ReadyToWatchSummaryCard(summary: ReadyToWatchSummary) {
    val container = when {
        summary.canStartWatching && summary.attentionCount == 0 ->
            MaterialTheme.colorScheme.primaryContainer
        summary.canStartWatching ->
            MaterialTheme.colorScheme.tertiaryContainer
        summary.invalid.isNotEmpty() ->
            MaterialTheme.colorScheme.errorContainer
        else ->
            MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = when {
                    summary.canStartWatching && summary.attentionCount == 0 ->
                        "Ready to watch — ${summary.ready.size} path${if (summary.ready.size == 1) "" else "s"} green."
                    summary.canStartWatching ->
                        "Ready to watch — ${summary.ready.size} ready, ${summary.attentionCount} need attention."
                    summary.invalid.isNotEmpty() ->
                        "Setup incomplete — ${summary.invalid.size} path${if (summary.invalid.size == 1) "" else "s"} need fixing."
                    else -> "Pick a path to start."
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val parts = buildList {
                if (summary.ready.isNotEmpty()) add("Ready: " + summary.ready.joinToString(", ") { it.shortName() })
                if (summary.warnings.isNotEmpty()) add("Warnings: " + summary.warnings.joinToString(", ") { it.shortName() })
                if (summary.invalid.isNotEmpty()) add("Fix: " + summary.invalid.joinToString(", ") { it.shortName() })
            }
            Text(
                text = parts.joinToString(" • ").ifBlank { "No paths configured yet." },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SetupIntentRow(
    intent: SetupIntent,
    state: SetupIntentState,
    resolvedStatus: SetupIntentStatus,
    onSetUp: () -> Unit,
    onValidate: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = intent.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = intent.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(resolvedStatus)
            }
            state.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSetUp) {
                    Text(if (resolvedStatus == SetupIntentStatus.READY) "Edit" else "Set up")
                }
                OutlinedButton(
                    onClick = onValidate,
                    enabled = state.status != SetupIntentStatus.VALIDATING,
                ) {
                    Text("Validate")
                }
                if (resolvedStatus != SetupIntentStatus.NOT_STARTED) {
                    TextButton(onClick = onReset) { Text("Reset") }
                }
                if (state.status == SetupIntentStatus.VALIDATING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: SetupIntentStatus) {
    val (label, container, content) = when (status) {
        SetupIntentStatus.READY -> Triple(
            "Ready",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        SetupIntentStatus.NEEDS_ATTENTION -> Triple(
            "Attention",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        SetupIntentStatus.INVALID -> Triple(
            "Fix",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        SetupIntentStatus.VALIDATING -> Triple(
            "Checking",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SetupIntentStatus.IN_PROGRESS -> Triple(
            "In progress",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SetupIntentStatus.NOT_STARTED -> Triple(
            "Not started",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = container,
            labelColor = content,
        ),
        border = null,
    )
}

private fun SetupIntent.shortName(): String = when (this) {
    SetupIntent.DEBRID -> "Debrid"
    SetupIntent.IPTV -> "IPTV"
    SetupIntent.PLEX_JELLYFIN -> "Plex/Jellyfin"
    SetupIntent.USENET -> "Usenet"
}

@Suppress("unused")
private val _placeholder: Color = Color.Unspecified
