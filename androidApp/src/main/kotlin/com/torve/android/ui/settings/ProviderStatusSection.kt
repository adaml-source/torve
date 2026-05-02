package com.torve.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.presentation.providerhealth.ProviderActionKind
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import com.torve.presentation.providerhealth.ProviderStatusKind
import com.torve.presentation.providerhealth.ProviderStatusMapper
import com.torve.presentation.providerhealth.ProviderStatusView
import org.koin.compose.koinInject

/**
 * Settings → Status & Repair (Prompt 16). Replaces the previous
 * "Needs attention"-only panel with a unified per-provider status view.
 *
 * Every card renders state via the shared [ProviderStatusMapper] so a
 * provider can never read "connected" here and "not configured" in
 * another surface — the four status kinds (Connected / Configured but
 * not verified / Needs credentials / Last check failed) are computed
 * from the same source of truth as every other surface that renders
 * provider health.
 *
 * Each card has exactly one CTA, set by the mapper. The UI does not
 * synthesize additional buttons from the message field — that was a
 * source of "two CTAs disagreeing about what to do" before.
 *
 * Cards are read-only beyond that one CTA: this section is the
 * "Status & Repair" surface; actual credential editing lives in
 * "Configure Sources" below.
 */
@Composable
fun ProviderStatusSection(
    onConfigure: (ProviderHealthEntry) -> Unit,
    onRefresh: (ProviderHealthEntry) -> Unit,
    onDiagnose: (ProviderHealthEntry) -> Unit,
    coordinator: ProviderHealthCoordinator = koinInject(),
    modifier: Modifier = Modifier,
) {
    val entries by coordinator.entries.collectAsState()
    if (entries.isEmpty()) return

    val views = ProviderStatusMapper.mapAll(entries)
        .sortedWith(compareBy({ it.kind.sortPriority }, { it.entry.label }))

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Status & Repair",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Snow,
        )
        Text(
            text = "What's connected and what needs your attention. Configuration lives below.",
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textSecondary,
        )
        views.forEach { view ->
            ProviderStatusCard(
                view = view,
                onConfigure = { onConfigure(view.entry) },
                onRefresh = { onRefresh(view.entry) },
                onDiagnose = { onDiagnose(view.entry) },
            )
        }
    }
}

@Composable
private fun ProviderStatusCard(
    view: ProviderStatusView,
    onConfigure: () -> Unit,
    onRefresh: () -> Unit,
    onDiagnose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(view.kind)
                Column(modifier = Modifier.padding(start = 0.dp).fillMaxWidth().weight(1f)) {
                    Text(
                        text = view.entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Snow,
                    )
                    Text(
                        text = view.headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = view.kind.headlineColor(),
                    )
                }
            }
            view.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
            }
            view.primaryActionLabel?.let { label ->
                Spacer(Modifier.size(2.dp))
                OutlinedButton(
                    onClick = {
                        when (view.primaryActionKind) {
                            ProviderActionKind.CONFIGURE,
                            ProviderActionKind.REENTER -> onConfigure()
                            ProviderActionKind.REFRESH -> onRefresh()
                            ProviderActionKind.DIAGNOSE -> onDiagnose()
                            ProviderActionKind.NONE -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(kind: ProviderStatusKind) {
    val color = when (kind) {
        ProviderStatusKind.CONNECTED -> Color(0xFF4CAF50)
        ProviderStatusKind.CONFIGURED_NOT_VERIFIED -> Amber
        ProviderStatusKind.NEEDS_CREDENTIALS -> Torve.colors.textTertiary
        ProviderStatusKind.LAST_CHECK_FAILED -> Ruby
        ProviderStatusKind.CHECKING -> Torve.colors.textSecondary
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Sort: needs-action first (red, yellow), then needs-credentials,
 * then connected at the bottom. CHECKING goes mid-pack. */
private val ProviderStatusKind.sortPriority: Int
    get() = when (this) {
        ProviderStatusKind.LAST_CHECK_FAILED -> 0
        ProviderStatusKind.CONFIGURED_NOT_VERIFIED -> 1
        ProviderStatusKind.NEEDS_CREDENTIALS -> 2
        ProviderStatusKind.CHECKING -> 3
        ProviderStatusKind.CONNECTED -> 4
    }

@Composable
private fun ProviderStatusKind.headlineColor(): Color = when (this) {
    ProviderStatusKind.CONNECTED -> Color(0xFF4CAF50)
    ProviderStatusKind.CONFIGURED_NOT_VERIFIED -> Amber
    ProviderStatusKind.NEEDS_CREDENTIALS -> Torve.colors.textTertiary
    ProviderStatusKind.LAST_CHECK_FAILED -> Ruby
    ProviderStatusKind.CHECKING -> Torve.colors.textSecondary
}
