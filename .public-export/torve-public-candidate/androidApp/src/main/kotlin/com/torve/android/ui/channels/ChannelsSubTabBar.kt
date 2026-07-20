package com.torve.android.ui.channels

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
    ChannelsSubTab.FAVOURITES to "FAVOURITES",
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
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { (tab, label) ->
            val selected = tab == selectedTab
            val bgColor by animateColorAsState(
                if (selected) Amber else Gunmetal,
                animationSpec = tween(200),
                label = "tab_bg",
            )
            val textColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.background else Torve.colors.textTertiary,
                animationSpec = tween(200),
                label = "tab_text",
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        onClick = { onTabSelected(tab) },
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor,
                    )
                }

                // Active indicator dot
                Spacer(Modifier.height(4.dp))
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(Amber),
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
