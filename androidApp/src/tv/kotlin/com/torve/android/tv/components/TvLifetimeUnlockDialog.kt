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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.stringResource
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.android.billing.BillingManager
import com.torve.android.billing.isStripeFireTvBillingBuild
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.tv.premium.TvPremiumAccess
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import org.koin.compose.koinInject

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvLifetimeUnlockDialog(
    feature: TvEntitledFeature,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!BuildConfig.SUPPORTS_TV_BILLING || isStripeFireTvBillingBuild()) {
        TvExternalPremiumOptionsDialog(
            feature = feature,
            onGoToPremium = onUnlock,
            onDismiss = onDismiss,
        )
        return
    }

    val billingManager: BillingManager = koinInject()
    val billingState by billingManager.billingState.collectAsState()
    val unlockRequester = remember(feature) { FocusRequester() }
    val dismissRequester = remember(feature) { FocusRequester() }
    val monthlyOffer = remember(billingState) {
        billingManager.getOffer(BillingManager.ProductType.MONTHLY)
    }
    val lifetimeOffer = remember(billingState) {
        billingManager.getOffer(BillingManager.ProductType.LIFETIME)
    }
    val billingErrorMessage = (billingState as? BillingManager.BillingState.Error)?.message
    val monthlyPrice = when {
        monthlyOffer?.formattedPrice != null -> monthlyOffer.formattedPrice
        billingState is BillingManager.BillingState.Connecting ||
            billingState is BillingManager.BillingState.Disconnected ->
            stringResource(R.string.paywall_price_loading)
        else -> stringResource(R.string.paywall_monthly_description)
    }
    val lifetimePrice = when {
        lifetimeOffer?.formattedPrice != null -> lifetimeOffer.formattedPrice
        billingState is BillingManager.BillingState.Connecting ||
            billingState is BillingManager.BillingState.Disconnected ->
            stringResource(R.string.paywall_price_loading)
        else -> stringResource(R.string.paywall_lifetime_description)
    }

    BackHandler(onBack = onDismiss)

    val hasAnyOfferForFocus = monthlyOffer != null || lifetimeOffer != null
    LaunchedEffect(feature, hasAnyOfferForFocus) {
        kotlinx.coroutines.delay(24)
        runCatching {
            if (hasAnyOfferForFocus) unlockRequester.requestFocus()
            else dismissRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        billingManager.initialize()
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

                val hasAnyOffer = monthlyOffer != null || lifetimeOffer != null
                if (billingState is BillingManager.BillingState.Connecting ||
                    billingState is BillingManager.BillingState.Disconnected
                ) {
                    Text(
                        text = stringResource(R.string.paywall_price_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                } else if (hasAnyOffer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Graphite.copy(alpha = 0.55f))
                            .border(1.dp, Steel.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (monthlyOffer != null) {
                            TvUnlockPlanRow(
                                label = stringResource(R.string.tv_settings_monthly_access),
                                price = monthlyPrice,
                                details = monthlyOffer.billingDetails,
                            )
                        }
                        if (lifetimeOffer != null) {
                            TvUnlockPlanRow(
                                label = stringResource(R.string.tv_settings_lifetime_access),
                                price = lifetimePrice,
                                details = lifetimeOffer.billingDetails,
                            )
                        }
                    }
                } else {
                    Text(
                        text = billingErrorMessage ?: stringResource(R.string.paywall_price),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (billingErrorMessage != null) MaterialTheme.colorScheme.error else Silver,
                    )
                }

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
                    if (hasAnyOffer) {
                        TvUnlockDialogButton(
                            title = TvPremiumAccess.UNLOCK_WITH_LIFETIME_LABEL,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(unlockRequester)
                                .focusProperties {
                                    left = dismissRequester
                                    right = dismissRequester
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                },
                            onClick = onUnlock,
                        )
                    }
                    TvUnlockDialogButton(
                        title = stringResource(R.string.tv_settings_not_now),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(dismissRequester)
                            .focusProperties {
                                left = if (hasAnyOffer) unlockRequester else FocusRequester.Cancel
                                right = if (hasAnyOffer) unlockRequester else FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                        secondary = true,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvExternalPremiumOptionsDialog(
    feature: TvEntitledFeature,
    onGoToPremium: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissRequester = remember(feature) { FocusRequester() }
    val premiumRequester = remember(feature) { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(feature) {
        kotlinx.coroutines.delay(24)
        runCatching { premiumRequester.requestFocus() }
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
                    .width(640.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Charcoal.copy(alpha = 0.98f))
                    .border(2.dp, Steel.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 28.dp, vertical = 28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Amber.copy(alpha = 0.2f))
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Amber,
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Graphite.copy(alpha = 0.6f))
                        .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tv_unlock_purchase_options_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.tv_unlock_purchase_options_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvUnlockDialogButton(
                        title = stringResource(R.string.common_close),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(dismissRequester)
                            .focusProperties {
                                left = premiumRequester
                                right = premiumRequester
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                        secondary = true,
                        onClick = onDismiss,
                    )
                    TvUnlockDialogButton(
                        title = stringResource(R.string.tv_unlock_go_to_premium),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(premiumRequester)
                            .focusProperties {
                                left = dismissRequester
                                right = dismissRequester
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                        onClick = onGoToPremium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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

@Composable
private fun TvUnlockPlanRow(
    label: String,
    price: String,
    details: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = Snow,
                fontWeight = FontWeight.Medium,
            )
            if (!details.isNullOrBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
            }
        }
        Text(
            text = price,
            style = MaterialTheme.typography.titleMedium,
            color = Amber,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
