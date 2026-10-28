package com.torve.android.ui.settings

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.torve.android.ui.components.MobileGlassOutlinedButton

@Composable
internal fun MobileProfileActions(onProfiles: () -> Unit, onFamily: () -> Unit,
    onSync: (() -> Unit)?, syncing: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountMenuAction(localizedUiText("Manage profiles"), "account.profiles", onProfiles)
        AccountMenuAction(localizedUiText("Family controls"), "account.family", onFamily)
        if (onSync != null) AccountMenuAction(localizedUiText(if (syncing) "Syncing account…" else "Sync account"), "account.sync", onSync, !syncing)
    }
}

@Composable
internal fun MobileDeviceActions(onPairings: () -> Unit, onDevices: () -> Unit, onTvSignIn: () -> Unit,
    onSend: () -> Unit, onReceive: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountMenuAction(localizedUiText("Manage pairings"), "devices.pairings", onPairings)
        AccountMenuAction(localizedUiText("Manage devices"), "devices.manage", onDevices)
        AccountMenuAction(localizedUiText("Sign in a TV with this phone"), "devices.signin", onTvSignIn)
        AccountMenuAction(localizedUiText("Send connections"), "devices.send", onSend)
        AccountMenuAction(localizedUiText("Receive connections"), "devices.receive", onReceive)
    }
}

@Composable
private fun AccountMenuAction(label: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    MobileGlassOutlinedButton(onClick, Modifier.fillMaxWidth().height(48.dp).testTag(tag), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
        Text(localizedUiText(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
