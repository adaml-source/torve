package com.torve.android.tv.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.tv.premium.TvPremiumAccess
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel

@Composable
fun TvLifetimeUnlockDialog(
    feature: TvEntitledFeature,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val unlockRequester = remember(feature) { FocusRequester() }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(feature) {
        kotlinx.coroutines.delay(24)
        runCatching { unlockRequester.requestFocus() }
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Obsidian.copy(alpha = 0.9f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(720.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Charcoal.copy(alpha = 0.98f))
                    .border(2.dp, Steel.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Amber.copy(alpha = 0.2f))
                            .padding(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Amber,
                        )
                    }
                    Text(
                        text = TvPremiumAccess.UNLOCK_WITH_LIFETIME_LABEL,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Text(
                    text = TvPremiumAccess.titleFor(feature),
                    style = MaterialTheme.typography.titleLarge,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = TvPremiumAccess.unlockSummaryFor(feature),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Silver,
                )
                Text(
                    text = TvPremiumAccess.LIFETIME_REQUIRED_LABEL,
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    fontWeight = FontWeight.Medium,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvPremiumAccess.lifetimeBenefits.forEach { benefit ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "-",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Amber,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = benefit,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Silver,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvUnlockDialogButton(
                        title = TvPremiumAccess.UNLOCK_WITH_LIFETIME_LABEL,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(unlockRequester),
                        onClick = onUnlock,
                    )
                    TvUnlockDialogButton(
                        title = "Not now",
                        modifier = Modifier.weight(1f),
                        secondary = true,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvUnlockDialogButton(
    title: String,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.04f else 1f, label = "unlockDialogButtonScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> Amber
            secondary -> Steel.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        label = "unlockDialogButtonBorder",
    )
    val backgroundColor = when {
        !secondary && focused -> Color(0xFFCC9A23)
        !secondary -> Amber
        focused -> Graphite
        else -> Color(0xFF2B2F36)
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (secondary) Snow else Obsidian,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
