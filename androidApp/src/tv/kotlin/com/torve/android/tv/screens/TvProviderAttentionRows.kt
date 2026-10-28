package com.torve.android.tv.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import com.torve.android.tv.focus.TvSettingsFocusStateMachine
import com.torve.android.tv.focus.TvSettingsFocusTarget
import com.torve.android.tv.focus.rememberRegisteredTvSettingsFocusRequester
import com.torve.android.tv.focus.tvSettingsRowNavigation
import com.torve.domain.providerhealth.ProviderHealthEntry

/** Keep the attention section's callbacks out of the large settings-list closure. */
internal class TvProviderAttentionUi(
    val entries: List<ProviderHealthEntry>,
    val categories: Set<TvSettingsCategory>,
    val requesters: Map<String, FocusRequester>,
    val controller: TvSettingsFocusStateMachine,
    val railRequester: FocusRequester,
    val categoryRequester: FocusRequester,
    val automationRequester: FocusRequester,
    val automationTarget: TvSettingsFocusTarget,
    val openSettings: (ProviderHealthEntry) -> Unit,
    val focusRow: (TvSettingsFocusTarget, FocusRequester) -> Unit,
    val onFocused: (TvSettingsFocusTarget, FocusRequester) -> Unit,
)

internal fun LazyListScope.tvProviderAttentionRows(ui: TvProviderAttentionUi) {
    ui.entries.forEachIndexed { index, entry ->
        val itemId = providerAttentionItemId(entry)
        item(key = itemId) {
            val target = TvSettingsFocusTarget(itemId, TvSettingsCategory.CONNECTIONS, index + 1, "navigation")
            val requester = rememberRegisteredTvSettingsFocusRequester(
                controller = ui.controller,
                target = target,
                externalRequester = ui.requesters.getValue(itemId),
                isDefaultEntry = index == 0,
            )
            TvSettingCard(
                title = com.torve.android.tv.components.tvSettingsLabel("${entry.label} needs attention"),
                subtitle = com.torve.android.tv.components.tvSettingsLabel(entry.message ?: entry.nextAction ?: "Review this connection's settings."),
                modifier = Modifier.fillMaxWidth().tvSettingsRowNavigation(
                    onUp = if (index == 0) null else ({
                        val previous = ui.entries[index - 1]
                        ui.focusRow(
                            TvSettingsFocusTarget(providerAttentionItemId(previous), TvSettingsCategory.CONNECTIONS, index, "navigation"),
                            ui.requesters.getValue(providerAttentionItemId(previous)),
                        )
                    }),
                    onDown = {
                        if (index == ui.entries.lastIndex) {
                            ui.focusRow(ui.automationTarget, ui.automationRequester)
                        } else {
                            val next = ui.entries[index + 1]
                            ui.focusRow(
                                TvSettingsFocusTarget(providerAttentionItemId(next), TvSettingsCategory.CONNECTIONS, index + 2, "navigation"),
                                ui.requesters.getValue(providerAttentionItemId(next)),
                            )
                        }
                    },
                ).focusProperties {
                    left = ui.railRequester
                    if (index == 0) up = ui.categoryRequester
                },
                focusRequester = requester,
                onFocused = { ui.onFocused(target, requester) },
                onClick = { ui.openSettings(entry) },
                rowType = TvSettingRowType.NAVIGATION,
                focusedHint = entry.nextAction ?: "Press OK to open this connection's settings.",
                needsAttention = true,
            )
        }
    }
}
