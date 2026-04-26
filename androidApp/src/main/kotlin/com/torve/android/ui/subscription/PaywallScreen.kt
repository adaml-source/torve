package com.torve.android.ui.subscription

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.android.billing.BillingManager
import com.torve.android.premium.PremiumAccess
import com.torve.android.premium.PremiumFeature
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.SubscriptionTier
import com.torve.presentation.subscription.PurchaseStatusMessage
import com.torve.presentation.subscription.PurchaseStatusTone
import com.torve.presentation.subscription.PurchaseVerificationState
import com.torve.presentation.subscription.SubscriptionUiState
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.subscription.accessPresentation
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    onDeviceLimitReached: () -> Unit = {},
    onManageDevices: () -> Unit = {},
    onLogin: () -> Unit = {},
    lockedFeature: PremiumFeature? = null,
    viewModel: SubscriptionViewModel = koinInject(),
    billingManager: BillingManager = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val purchaseResult by billingManager.purchaseResult.collectAsState()
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()

    val isAmazonBuild = BuildConfig.FLAVOR.contains("amazon", ignoreCase = true)
    val isTvBuild = BuildConfig.FLAVOR.contains("tv", ignoreCase = true)
    val bypassDeviceGateForMobileDebug = BuildConfig.DEBUG && !isTvBuild
    val platform = when {
        isAmazonBuild && isTvBuild -> "amazon_fire_tv"
        isAmazonBuild -> "amazon_appstore_mobile"
        isTvBuild -> "google_play_tv"
        else -> "google_play_mobile"
    }
    val purchaseStoreLabel = if (isAmazonBuild) "Amazon Appstore" else "Google Play"

    LaunchedEffect(Unit) {
        viewModel.refreshAccess()
        // Defensive: ensure billing is initialized when the paywall opens.
        // BillingManager.initialize() is idempotent and will re-arm the
        // connection if the app-wide init failed or the service disconnected.
        billingManager.initialize()
    }

    LaunchedEffect(purchaseResult) {
        when (val result = purchaseResult) {
            is BillingManager.PurchaseResult.Success -> {
                when (result.store) {
                    BillingManager.Store.AMAZON_APPSTORE -> {
                        viewModel.verifyAmazonPurchase(
                            receiptId = result.purchaseToken,
                            amazonUserId = result.amazonUserId.orEmpty(),
                            productId = result.productId.ifBlank { "com.torve.pro.lifetime" },
                            platform = if (isTvBuild) "amazon_fire_tv" else "amazon_appstore_mobile",
                        )
                    }

                    BillingManager.Store.GOOGLE_PLAY -> {
                        viewModel.verifyGooglePurchase(
                            productId = result.productId.ifBlank { "com.torve.pro.lifetime" },
                            purchaseToken = result.purchaseToken,
                            platform = platform,
                        )
                    }
                }
                billingManager.clearPurchaseResult()
            }

            is BillingManager.PurchaseResult.Pending -> {
                viewModel.markPurchasePending(result.message)
                billingManager.clearPurchaseResult()
            }

            is BillingManager.PurchaseResult.AlreadyOwned -> {
                if (isAmazonBuild) {
                    viewModel.restoreAmazonPurchases(platform = if (isTvBuild) "amazon_fire_tv" else "amazon_appstore_mobile")
                } else {
                    viewModel.restoreStorePurchases(
                        store = "google_play",
                        platform = platform,
                        storeLabel = purchaseStoreLabel,
                    )
                }
                billingManager.clearPurchaseResult()
            }

            is BillingManager.PurchaseResult.Cancelled -> {
                viewModel.setPurchaseError(activity?.getString(R.string.paywall_purchase_cancelled) ?: "Purchase cancelled.")
                billingManager.clearPurchaseResult()
            }

            is BillingManager.PurchaseResult.Error -> {
                viewModel.setPurchaseError(result.message)
                billingManager.clearPurchaseResult()
            }

            null -> Unit
        }
    }

    NeedsVerificationToastEffect(
        message = state.verificationEmailMessage,
        onConsumed = viewModel::consumeVerificationEmailMessage,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.paywall_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
        )

        PaywallContent(
            state = state,
            lockedFeature = lockedFeature,
            purchaseStoreLabel = purchaseStoreLabel,
            billingManager = billingManager,
            onRestore = {
                viewModel.requireAccountForRestore(purchaseStoreLabel) {
                    if (isAmazonBuild) {
                        viewModel.restoreAmazonPurchases(platform = if (isTvBuild) "amazon_fire_tv" else "amazon_appstore_mobile")
                    } else {
                        // Client-driven Google Play restore. Suspend-query
                        // BillingClient for every active SUBS+INAPP token,
                        // map to the shared DTO, hand off to the VM which
                        // verifies each then hits /me/purchases/restore +
                        // /me/access-state. Without the per-token verify
                        // the backend wouldn't know about purchases made
                        // on a different device that were never recorded
                        // server-side.
                        scope.launch {
                            val activePlay = runCatching { billingManager.queryActivePurchases() }
                                .getOrDefault(emptyList())
                                .map { active ->
                                    com.torve.presentation.subscription.GooglePlayActivePurchase(
                                        productId = active.productId,
                                        purchaseToken = active.purchaseToken,
                                        isAcknowledged = active.isAcknowledged,
                                    )
                                }
                            viewModel.restoreGooglePlayPurchases(
                                activePlayPurchases = activePlay,
                                platform = platform,
                                storeLabel = purchaseStoreLabel,
                            )
                        }
                    }
                }
            },
            onManageDevices = onManageDevices,
            onSendVerificationEmail = viewModel::sendVerificationEmail,
            onRefreshAccess = viewModel::refreshAccess,
            onRetryVerification = viewModel::retryPendingAmazonVerification,
            onPurchase = { productType ->
                viewModel.requireAccountForPurchase(purchaseStoreLabel) {
                    activity?.let { billingManager.launchPurchase(it, productType) }
                }
            },
            onRetryBilling = { billingManager.initialize() },
            onLogin = onLogin,
        )
    }
}

@Composable
private fun PaywallContent(
    state: SubscriptionUiState,
    lockedFeature: PremiumFeature?,
    purchaseStoreLabel: String,
    billingManager: BillingManager,
    onRestore: () -> Unit,
    onManageDevices: () -> Unit,
    onSendVerificationEmail: () -> Unit,
    onRefreshAccess: () -> Unit,
    onRetryVerification: () -> Unit,
    onPurchase: (BillingManager.ProductType) -> Unit,
    onRetryBilling: () -> Unit,
    onLogin: () -> Unit,
) {
    val billingState by billingManager.billingState.collectAsState()
    val monthlyOffer = remember(billingState) {
        billingManager.getOffer(BillingManager.ProductType.MONTHLY)
    }
    val lifetimeOffer = remember(billingState) {
        billingManager.getOffer(BillingManager.ProductType.LIFETIME)
    }
    val billingErrorMessage = (billingState as? BillingManager.BillingState.Error)?.message
    val billingReady = billingState is BillingManager.BillingState.Ready
    val billingLoading = billingState is BillingManager.BillingState.Connecting ||
        billingState is BillingManager.BillingState.Connected ||
        billingState is BillingManager.BillingState.Disconnected
    val purchaseBlocked = state.purchaseVerificationState == PurchaseVerificationState.PENDING
    val purchaseStrings: com.torve.presentation.subscription.PurchaseStringResolver = koinInject()
    val access = state.accessPresentation(purchaseStrings)

    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = access.accessStatusLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        if (state.needsVerification) {
            NeedsVerificationBanner(
                isSending = state.isSendingVerificationEmail,
                onSendVerificationEmail = onSendVerificationEmail,
                onRefreshAccess = onRefreshAccess,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }

        Text(
            text = stringResource(R.string.paywall_upgrade_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.paywall_upgrade_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        lockedFeature?.let { feature ->
            Spacer(Modifier.height(16.dp))
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Amber,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.premium_requires_lifetime),
                            style = MaterialTheme.typography.labelMedium,
                            color = Amber,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(PremiumAccess.titleResFor(feature)),
                        style = MaterialTheme.typography.titleSmall,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        FeatureComparison()
        Spacer(Modifier.height(24.dp))

        CurrentAccessCard(
            state = state,
            purchaseStoreLabel = purchaseStoreLabel,
        )

        state.purchaseStatus?.let { status ->
            Spacer(Modifier.height(16.dp))
            PurchaseStatusCard(
                status = status,
                onClick = if (status.kind == com.torve.presentation.subscription.PurchaseStatusKind.SIGN_IN_REQUIRED) onLogin else null,
            )
        }

        if (access.shouldShowBuy) {
            Spacer(Modifier.height(20.dp))
            when {
                billingErrorMessage != null && monthlyOffer == null && lifetimeOffer == null -> {
                    Text(
                        text = billingErrorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRetryBilling,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.common_retry))
                    }
                }

                billingLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.paywall_price_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    // Per-product gates so a monthly subscriber can still
                    // upgrade to lifetime. shouldShowBuyMonthly hides the
                    // monthly button once any entitlement is active;
                    // shouldShowBuyLifetime hides the lifetime button only
                    // when the user already owns lifetime.
                    if (monthlyOffer != null && access.shouldShowBuyMonthly) {
                        PricingOptionCard(
                            title = stringResource(R.string.paywall_monthly_title),
                            description = stringResource(R.string.paywall_monthly_description),
                            price = monthlyOffer.formattedPrice ?: stringResource(R.string.paywall_price),
                            billingDetails = monthlyOffer.billingDetails,
                            enabled = !state.isPurchasing && !purchaseBlocked,
                            actionLabel = stringResource(R.string.paywall_choose_monthly),
                            onClick = { onPurchase(BillingManager.ProductType.MONTHLY) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    if (lifetimeOffer != null && access.shouldShowBuyLifetime) {
                        // When the user already has monthly, surface the
                        // lifetime offer as an explicit upgrade so they
                        // know they're not double-paying.
                        val isMonthlyToLifetimeUpgrade = access.hasPremiumEntitlement
                        if (isMonthlyToLifetimeUpgrade) {
                            Text(
                                text = stringResource(R.string.paywall_lifetime_upgrade_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = Amber,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        PricingOptionCard(
                            title = stringResource(R.string.paywall_lifetime_title),
                            description = stringResource(R.string.paywall_lifetime_description),
                            price = lifetimeOffer.formattedPrice ?: stringResource(R.string.paywall_price),
                            billingDetails = lifetimeOffer.billingDetails,
                            enabled = !state.isPurchasing && !purchaseBlocked,
                            actionLabel = if (isMonthlyToLifetimeUpgrade) {
                                stringResource(R.string.paywall_upgrade_to_lifetime)
                            } else {
                                stringResource(R.string.paywall_choose_lifetime)
                            },
                            onClick = { onPurchase(BillingManager.ProductType.LIFETIME) },
                        )
                    }

                    if (monthlyOffer == null && lifetimeOffer == null && billingReady) {
                        Text(
                            text = stringResource(R.string.paywall_price),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onRetryBilling,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (access.shouldShowManageDevices) {
            Button(
                onClick = onManageDevices,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.manage_devices_title))
            }
            if (state.isLoggedIn) {
                TextButton(onClick = onRefreshAccess) {
                    Text(stringResource(R.string.premium_refresh_access))
                }
            }
        } else if (state.isLoggedIn) {
            TextButton(onClick = onRefreshAccess) {
                Text(stringResource(R.string.premium_refresh_access))
            }
        }

        if (state.purchaseStatus?.showRetryVerification == true) {
            TextButton(onClick = onRetryVerification) {
                Text(stringResource(R.string.paywall_retry_verification))
            }
        }

        TextButton(onClick = onRestore) {
            Text(stringResource(R.string.paywall_restore))
        }

        Text(
            text = stringResource(R.string.paywall_marketplace_copy, purchaseStoreLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.paywall_refund_copy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )

        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CurrentAccessCard(
    state: SubscriptionUiState,
    purchaseStoreLabel: String,
) {
    val purchaseStrings: com.torve.presentation.subscription.PurchaseStringResolver = koinInject()
    val access = state.accessPresentation(purchaseStrings)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = access.accessStatusLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = access.accessHelperText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val marketplace = marketplaceLabel(state.subscription?.platform)
            if (marketplace != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.paywall_managed_through, marketplace),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (access.hasPremiumEntitlement) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.paywall_marketplace_copy, purchaseStoreLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PricingOptionCard(
    title: String,
    description: String,
    price: String,
    billingDetails: String,
    enabled: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = price,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = billingDetails,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun PurchaseStatusCard(
    status: PurchaseStatusMessage,
    onClick: (() -> Unit)? = null,
) {
    val accent = when (status.tone) {
        PurchaseStatusTone.INFO -> Amber
        PurchaseStatusTone.SUCCESS -> Color(0xFF22C55E)
        PurchaseStatusTone.ERROR -> MaterialTheme.colorScheme.error
    }
    val baseModifier = Modifier.fillMaxWidth()
    val cardModifier = if (onClick != null) {
        baseModifier.then(Modifier.clickable { onClick() })
    } else {
        baseModifier
    }
    OutlinedCard(
        modifier = cardModifier,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = status.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onClick != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.paywall_sign_in_action_hint),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun FeatureComparison() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.paywall_free),
                    style = MaterialTheme.typography.labelMedium,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(modifier = Modifier.width(80.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.paywall_pro),
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        FeatureRow(stringResource(R.string.paywall_search_browse), free = true, premium = true)
        FeatureRow(stringResource(R.string.paywall_stream_playback), free = false, premium = true)
        FeatureRow(stringResource(R.string.paywall_downloads), free = false, premium = true)
        FeatureRow(stringResource(R.string.paywall_channels), free = false, premium = true)
        FeatureRow(stringResource(R.string.paywall_multi_cloud), free = false, premium = true)
        FeatureRow(stringResource(R.string.paywall_trakt_simkl), free = false, premium = true)
        FeatureRow(stringResource(R.string.paywall_ai_search), free = false, premium = true)
        FeatureRow(stringResource(R.string.paywall_rating_pills), free = false, premium = true)
        FeatureRow(stringResource(R.string.paywall_custom_home), free = false, premium = true)
    }
}

@Composable
private fun FeatureRow(feature: String, free: Boolean, premium: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = feature,
            style = MaterialTheme.typography.bodyMedium,
            color = Snow,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
            if (free) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
            }
        }
        Box(modifier = Modifier.width(80.dp), contentAlignment = Alignment.Center) {
            if (premium) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun marketplaceLabel(value: String?): String? {
    return when (value?.lowercase()) {
        "google_play" -> "Google Play"
        "amazon" -> "Amazon Appstore"
        "apple" -> "Apple App Store"
        else -> null
    }
}
