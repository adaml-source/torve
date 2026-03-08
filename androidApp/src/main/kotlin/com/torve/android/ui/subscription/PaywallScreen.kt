package com.torve.android.ui.subscription

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
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
import android.app.Activity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Snow
import com.torve.presentation.subscription.SubscriptionViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    onDeviceLimitReached: () -> Unit = {},
    viewModel: SubscriptionViewModel = koinInject(),
    billingManager: BillingManager = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val purchaseResult by billingManager.purchaseResult.collectAsState()
    val activity = LocalContext.current as? Activity

    val platform = if (BuildConfig.FLAVOR.contains("tv")) "google_play_tv" else "google_play_mobile"

    // Navigate to Device Limit Reached screen when device cap is hit after purchase
    LaunchedEffect(state.showDeviceLimitReached) {
        if (state.showDeviceLimitReached) {
            viewModel.dismissDeviceLimitReached()
            onDeviceLimitReached()
        }
    }

    LaunchedEffect(purchaseResult) {
        when (val result = purchaseResult) {
            is BillingManager.PurchaseResult.Success -> {
                viewModel.verifyGooglePurchase(
                    productId = "com.torve.pro.lifetime",
                    purchaseToken = result.purchaseToken,
                    platform = platform,
                )
                billingManager.clearPurchaseResult()
            }
            is BillingManager.PurchaseResult.AlreadyOwned -> {
                viewModel.purchase("restored_purchase")
                billingManager.clearPurchaseResult()
            }
            else -> { /* Cancelled or Error — no action */ }
        }
    }

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

        if (state.isPro) {
            LifetimeActiveContent(
                onRestore = { billingManager.queryExistingPurchases() },
            )
        } else {
            val billingState by billingManager.billingState.collectAsState()
            val formattedPrice = billingManager.getFormattedPrice()
            val priceReady = formattedPrice != null
            val priceText = when {
                formattedPrice != null -> formattedPrice
                billingState is BillingManager.BillingState.Ready -> stringResource(R.string.paywall_price)
                billingState is BillingManager.BillingState.Error -> stringResource(R.string.paywall_price)
                else -> stringResource(R.string.paywall_price_loading)
            }
            FreeTierContent(
                state = state,
                formattedPrice = priceText,
                purchaseEnabled = priceReady,
                onPurchase = { activity?.let { billingManager.launchPurchase(it) } },
                onRestore = { billingManager.queryExistingPurchases() },
            )
        }
    }
}

@Composable
private fun LifetimeActiveContent(
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = Color(0xFF22C55E),
            modifier = Modifier.size(64.dp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.paywall_lifetime_active),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.paywall_full_access),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        // All features with check marks
        AllFeaturesChecked()

        Spacer(Modifier.height(32.dp))

        TextButton(onClick = onRestore) {
            Text(stringResource(R.string.paywall_restore))
        }
    }
}

@Composable
private fun AllFeaturesChecked() {
    val features = listOf(
        R.string.paywall_search_browse,
        R.string.paywall_stream_playback,
        R.string.paywall_downloads,
        R.string.paywall_channels,
        R.string.paywall_multi_cloud,
        R.string.paywall_trakt_simkl,
        R.string.paywall_ai_search,
        R.string.paywall_rating_pills,
        R.string.paywall_custom_home,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        features.forEach { featureRes ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(featureRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Snow,
                )
            }
        }
    }
}

@Composable
private fun FreeTierContent(
    state: com.torve.presentation.subscription.SubscriptionUiState,
    formattedPrice: String? = null,
    purchaseEnabled: Boolean = true,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.paywall_free_status),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.paywall_unlock),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.paywall_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        FeatureComparison()

        Spacer(Modifier.height(24.dp))

        // Pricing card
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.paywall_lifetime),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.paywall_one_time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formattedPrice ?: stringResource(R.string.paywall_price),
                    style = if (purchaseEnabled) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (purchaseEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onPurchase,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = purchaseEnabled && !state.isPurchasing,
            shape = RoundedCornerShape(16.dp),
        ) {
            if (state.isPurchasing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = stringResource(R.string.paywall_get_lifetime),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onRestore) {
            Text(stringResource(R.string.paywall_restore))
        }

        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))
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
                Text(stringResource(R.string.paywall_free), style = MaterialTheme.typography.labelMedium, color = Snow, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.paywall_pro), style = MaterialTheme.typography.labelMedium, color = Amber, fontWeight = FontWeight.Bold)
            }
        }
        FeatureRow(stringResource(R.string.paywall_search_browse), free = true, pro = true)
        FeatureRow(stringResource(R.string.paywall_stream_playback), free = false, pro = true)
        FeatureRow(stringResource(R.string.paywall_downloads), free = false, pro = true)
        FeatureRow(stringResource(R.string.paywall_channels), free = false, pro = true)
        FeatureRow(stringResource(R.string.paywall_multi_cloud), free = false, pro = true)
        FeatureRow(stringResource(R.string.paywall_trakt_simkl), free = false, pro = true)
        FeatureRow(stringResource(R.string.paywall_ai_search), free = false, pro = true)
        FeatureRow(stringResource(R.string.paywall_rating_pills), free = false, pro = true)
        FeatureRow(stringResource(R.string.paywall_custom_home), free = false, pro = true)
    }
}

@Composable
private fun FeatureRow(feature: String, free: Boolean, pro: Boolean) {
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
            } else {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
            }
        }
        Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
            if (pro) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
            }
        }
    }
}
