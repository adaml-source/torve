package com.torve.android.ui.channels

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.torve.android.ui.components.mobileGlassSurface
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.presentation.channels.ChannelsSubTab

private val tabs = listOf(
    ChannelsSubTab.LIVE to "LIVE",
    ChannelsSubTab.MOVIES to "VOD",
    ChannelsSubTab.FAVOURITES to "SAVED",
    ChannelsSubTab.GUIDE to "GUIDE",
)

@Composable
fun ChannelsSubTabBar(
    selectedTab: ChannelsSubTab,
    onTabSelected: (ChannelsSubTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { (tab, label) ->
            val selected = tab == selectedTab
            val textColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(200),
                label = "tab_text",
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .selectable(
                        selected = selected, role = Role.Tab,
                        onClick = { onTabSelected(tab) },
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .mobileGlassSurface(RoundedCornerShape(17.dp), selected = selected, quiet = !selected),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = localizedUiText(label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor,
                    )
                }

                // Active indicator dot
                Spacer(Modifier.height(2.dp))
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(Amber),
                    )
                } else {
                    Spacer(Modifier.height(3.dp))
                }
            }
        }
    }
}
