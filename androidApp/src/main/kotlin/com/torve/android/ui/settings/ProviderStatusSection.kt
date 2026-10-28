package com.torve.android.ui.settings

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.providerhealth.ProviderHealthFailure
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
    onRefresh: (ProviderHealthEntry) -> Unit = {},
    onDiagnose: (ProviderHealthEntry) -> Unit,
    coordinator: ProviderHealthCoordinator = koinInject(),
    modifier: Modifier = Modifier,
) {
    val entries by coordinator.entries.collectAsState()
    if (entries.isEmpty()) return

    ProviderStatusList(
        views = ProviderStatusMapper.mapAll(entries),
        onConfigure = onConfigure,
        onRefresh = {
            coordinator.runCheck(it.providerKey) ?: coordinator.runAll()
            onRefresh(it)
        },
        onDiagnose = onDiagnose,
        modifier = modifier,
    )
}

/** Optional, unused providers do not count as broken connections. */
@Composable
internal fun ProviderStatusList(
    views: List<ProviderStatusView>,
    onConfigure: (ProviderHealthEntry) -> Unit,
    onRefresh: (ProviderHealthEntry) -> Unit,
    onDiagnose: (ProviderHealthEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = views.filter { it.entry.status != ProviderHealthStatus.UNCONFIGURED }
        .sortedWith(compareBy({ it.kind.sortPriority }, { it.entry.label }))
    val unused = views.filter { it.entry.status == ProviderHealthStatus.UNCONFIGURED }.sortedBy { it.entry.label }
    var showUnused by remember { mutableStateOf(false) }
    val connected = active.count { it.kind == ProviderStatusKind.CONNECTED }
    val issues = active.count { it.kind != ProviderStatusKind.CONNECTED && it.kind != ProviderStatusKind.CHECKING }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (active.isEmpty()) stringResource(R.string.repair_no_connections)
                else stringResource(R.string.repair_connection_summary, connected, issues),
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textSecondary,
        )
        active.forEach { view ->
            ProviderStatusCard(
                view = view,
                onConfigure = { onConfigure(view.entry) },
                onRefresh = { onRefresh(view.entry) },
                onDiagnose = { onDiagnose(view.entry) },
            )
        }
        if (unused.isNotEmpty()) {
            TextButton(onClick = { showUnused = !showUnused },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("repair_unused_toggle")) {
                Text(stringResource(R.string.repair_available_connections, unused.size), Modifier.weight(1f))
                Icon(if (showUnused) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (showUnused) unused.forEach { view ->
                ProviderStatusCard(view, { onConfigure(view.entry) }, { onRefresh(view.entry) }, { onDiagnose(view.entry) })
            }
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
    val settingsDescription = stringResource(R.string.repair_connection_settings, view.entry.label)
    val action = {
        when (view.primaryActionKind) {
            ProviderActionKind.CONFIGURE, ProviderActionKind.REENTER -> onConfigure()
            ProviderActionKind.REFRESH -> onRefresh()
            ProviderActionKind.DIAGNOSE -> onDiagnose()
            ProviderActionKind.NONE -> Unit
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("repair_${view.entry.providerKey}"),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = settingsDescription, onClick = onConfigure)
                .semantics { contentDescription = settingsDescription },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(view.kind)
                Column(modifier = Modifier.padding(start = 0.dp).fillMaxWidth().weight(1f)) {
                    Text(
                        text = view.entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = providerHeadline(view),
                        style = MaterialTheme.typography.bodySmall,
                        color = view.kind.headlineColor(),
                    )
                }
                if (view.kind == ProviderStatusKind.CONNECTED) {
                    TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.repair_check_again))
                    }
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                    modifier = Modifier.size(20.dp), tint = Torve.colors.textTertiary)
            }
            providerDetail(view)?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
            }
            view.primaryActionLabel?.takeIf { view.kind != ProviderStatusKind.CONNECTED }?.let {
                Spacer(Modifier.size(2.dp))
                OutlinedButton(
                    onClick = action,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(providerActionLabel(view))
                }
            }
        }
    }
}

@Composable
private fun providerDetail(view: ProviderStatusView): String? {
    if (view.entry.category !in setOf(ProviderHealthCategory.REQUEST_MANAGER, ProviderHealthCategory.PLEX_JELLYFIN,
        ProviderHealthCategory.TRAKT, ProviderHealthCategory.SIMKL)) return view.detail
    val resource = when (view.entry.failure) {
        ProviderHealthFailure.AUTH_REJECTED -> R.string.flow_repair_auth
        ProviderHealthFailure.UNREACHABLE -> R.string.flow_repair_network
        else -> return view.detail
    }
    return stringResource(resource, view.entry.label)
}

@Composable
private fun providerHeadline(view: ProviderStatusView): String = stringResource(when (view.kind) {
    ProviderStatusKind.CONNECTED -> R.string.repair_connected
    ProviderStatusKind.CONFIGURED_NOT_VERIFIED -> if (view.entry.failure == ProviderHealthFailure.SETUP_INCOMPLETE) R.string.repair_setup_incomplete else R.string.repair_unverified
    ProviderStatusKind.NEEDS_CREDENTIALS -> R.string.repair_not_connected
    ProviderStatusKind.LAST_CHECK_FAILED -> when (view.entry.failure) {
        ProviderHealthFailure.AUTH_REJECTED -> R.string.repair_auth_rejected
        ProviderHealthFailure.UNREACHABLE -> R.string.repair_unreachable
        else -> R.string.repair_check_failed
    }
    ProviderStatusKind.CHECKING -> R.string.repair_checking
})

@Composable
private fun providerActionLabel(view: ProviderStatusView): String = stringResource(when (view.primaryActionKind) {
    ProviderActionKind.REENTER -> R.string.repair_update_credentials
    ProviderActionKind.CONFIGURE -> when (view.entry.failure) {
        ProviderHealthFailure.SETUP_INCOMPLETE -> R.string.repair_finish_setup
        ProviderHealthFailure.MIXED -> R.string.repair_review_connections
        else -> R.string.repair_connect
    }
    ProviderActionKind.REFRESH -> if (view.kind == ProviderStatusKind.LAST_CHECK_FAILED || view.entry.failure == ProviderHealthFailure.UNREACHABLE) R.string.repair_try_again else R.string.repair_check_again
    ProviderActionKind.DIAGNOSE -> R.string.repair_diagnostics
    ProviderActionKind.NONE -> R.string.common_close
})

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

/**
 * Compact summary card for the main Settings list. Shows overall health
 * at a glance — one dot, one headline, one button. The full per-provider
 * breakdown lives on its own Status & Repair screen.
 */
@Composable
fun ProviderStatusSummaryCard(
    onViewAll: () -> Unit,
    coordinator: ProviderHealthCoordinator = koinInject(),
    modifier: Modifier = Modifier,
) {
    val entries by coordinator.entries.collectAsState()
    if (entries.isEmpty()) return

    val views = ProviderStatusMapper.mapAll(entries)

    val debridConnected = views.any {
        it.entry.category == ProviderHealthCategory.DEBRID && it.kind == ProviderStatusKind.CONNECTED
    }
    val usenetReady = views.any {
        it.entry.category == ProviderHealthCategory.USENET_INDEXER && it.kind == ProviderStatusKind.CONNECTED
    } && views.any {
        it.entry.category == ProviderHealthCategory.DOWNLOAD_CLIENT && it.kind == ProviderStatusKind.CONNECTED
    }
    val iptvConnected = views.any {
        it.entry.category == ProviderHealthCategory.IPTV && it.kind == ProviderStatusKind.CONNECTED
    }
    val overallKind = when {
        debridConnected || usenetReady -> ProviderStatusKind.CONNECTED
        iptvConnected -> ProviderStatusKind.CONFIGURED_NOT_VERIFIED
        else -> views.minByOrNull { it.kind.sortPriority }?.kind ?: ProviderStatusKind.NEEDS_CREDENTIALS
    }
    val headline = when {
        debridConnected || usenetReady -> "Watching enabled"
        iptvConnected -> "Live TV only — no debrid or Usenet"
        else -> {
            val issueCount = views.count {
                it.entry.status != ProviderHealthStatus.UNCONFIGURED &&
                    it.kind != ProviderStatusKind.CONNECTED && it.kind != ProviderStatusKind.CHECKING
            }
            if (issueCount == 0) stringResource(R.string.repair_no_connections)
            else stringResource(R.string.repair_connection_summary, views.count { it.kind == ProviderStatusKind.CONNECTED }, issueCount)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(overallKind)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localizedUiText("Status & Repair"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = overallKind.headlineColor(),
                    )
                }
            }
            OutlinedButton(
                onClick = onViewAll,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(localizedUiText("View all"))
            }
        }
    }
}
