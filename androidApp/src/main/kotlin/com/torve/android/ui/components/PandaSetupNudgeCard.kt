package com.torve.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.panda.PandaConfigStateStore
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Persistent (until-dismissed) Settings nudge that points users at the
 * Panda wizard — the fastest path to wiring up debrid + indexer access,
 * which the rest of the app relies on for source discovery.
 *
 * Visibility rules:
 *  - Hidden when Panda is already configured
 *    ([PandaConfigStateStore.current.isEditMode] == true).
 *  - Hidden once the user has tapped the dismiss button (persisted via
 *    [PreferencesRepository] under [PREF_KEY_PANDA_NUDGE_DISMISSED]).
 *  - Not extensively styled — a single one-line subtitle and a CTA. The
 *    intent is "you'll see this on every Settings open until you act or
 *    dismiss," not "an ad".
 *
 * Setting up Panda re-arms the nudge for any user who later disconnects
 * Panda — the predicate is reactive on the live state store.
 */
@Composable
fun PandaSetupNudgeCard(
    onSetupClick: () -> Unit,
    pandaConfigStore: PandaConfigStateStore = koinInject(),
    prefsRepo: PreferencesRepository = koinInject(),
) {
    val pandaState by pandaConfigStore.state.collectAsState()
    val isPandaConfigured = pandaState.isEditMode
    val scope = rememberCoroutineScope()

    var dismissed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        dismissed = prefsRepo.getString(PREF_KEY_PANDA_NUDGE_DISMISSED) == "true"
    }

    if (isPandaConfigured || dismissed != false) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.RocketLaunch,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier
                    .size(22.dp)
                    .padding(top = 2.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Set up Panda",
                    style = MaterialTheme.typography.titleSmall,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Easiest way to enable Torve's core source discovery — a guided wizard wires debrid + Usenet for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onSetupClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                ) {
                    Text("Set up Panda", fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(
                onClick = {
                    dismissed = true
                    scope.launch {
                        prefsRepo.setString(PREF_KEY_PANDA_NUDGE_DISMISSED, "true")
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = Torve.colors.textSecondary,
                )
            }
        }
    }
}

const val PREF_KEY_PANDA_NUDGE_DISMISSED = "panda_setup_nudge_dismissed"
