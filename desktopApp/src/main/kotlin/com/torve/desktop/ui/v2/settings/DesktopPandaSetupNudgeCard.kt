package com.torve.desktop.ui.v2.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.panda.PandaConfigStateStore
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

private const val PREF_KEY_PANDA_NUDGE_DISMISSED = "panda_setup_nudge_dismissed"

/**
 * Desktop counterpart to the mobile PandaSetupNudgeCard. Visibility
 * predicates - all must be true:
 *  - [eligible] = true. Panda completion needs the addon catalog, which
 *    is premium-gated, so nudging users who can't actually finish the
 *    flow is misleading. Callers usually compute this as
 *    `signedIn AND emailVerified AND accessTier != FREE`.
 *  - Panda not already configured.
 *  - User hasn't clicked the dismiss button (persisted via the shared
 *    [PreferencesRepository] under [PREF_KEY_PANDA_NUDGE_DISMISSED]).
 *
 * Lives at the top of every Settings category right under the page header
 * so it's noticeable on first open without dominating the screen.
 */
@Composable
fun DesktopPandaSetupNudgeCard(
    onSetupClick: () -> Unit,
    eligible: Boolean,
) {
    val pandaConfigStore: PandaConfigStateStore = remember {
        KoinPlatform.getKoin().get<PandaConfigStateStore>()
    }
    val prefsRepo: PreferencesRepository = remember {
        KoinPlatform.getKoin().get<PreferencesRepository>()
    }
    val pandaState by pandaConfigStore.state.collectAsState()
    val scope = rememberCoroutineScope()

    var dismissed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        dismissed = prefsRepo.getString(PREF_KEY_PANDA_NUDGE_DISMISSED) == "true"
    }

    if (!eligible || pandaState.isEditMode || dismissed != false) return

    val colors = TorveDesktopThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.elevatedSurface)
            .border(1.dp, colors.accent, RoundedCornerShape(10.dp))
            .padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.RocketLaunch,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Set up Panda",
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Easiest way to enable Torve's core source discovery - a guided wizard wires debrid + Usenet for you.",
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(10.dp))
            TorvePrimaryButton(text = "Set up Panda", onClick = onSetupClick)
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
                tint = colors.textSecondary,
            )
        }
    }
}
