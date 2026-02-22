package com.streamvault.android.ui.subscription

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.SubscriptionTier
import com.streamvault.presentation.subscription.SubscriptionViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text("StreamVault Pro") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (state.isPro) {
            // Already subscribed
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "You're a Pro!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tier: ${state.subscription?.tier?.label ?: "Pro"}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.deactivate() }) {
                        Text("Cancel Subscription", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else {
            // Paywall
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Unlock StreamVault Pro",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Stream, download, and watch IPTV with a Pro subscription",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))

                // Feature comparison
                FeatureComparison()

                Spacer(Modifier.height(24.dp))

                // Pricing cards
                PricingCard(
                    title = "Monthly",
                    price = "\$4.99/mo",
                    description = "Billed monthly, cancel anytime",
                    selected = state.selectedTier == SubscriptionTier.MONTHLY,
                    onClick = { viewModel.selectTier(SubscriptionTier.MONTHLY) },
                )

                Spacer(Modifier.height(12.dp))

                PricingCard(
                    title = "Lifetime",
                    price = "\$29.99",
                    description = "One-time payment, forever access",
                    selected = state.selectedTier == SubscriptionTier.LIFETIME,
                    highlighted = true,
                    onClick = { viewModel.selectTier(SubscriptionTier.LIFETIME) },
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        // In production, this would trigger Google Play billing
                        viewModel.purchase("mock_token_${System.currentTimeMillis()}")
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !state.isPurchasing,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (state.isPurchasing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            text = "Subscribe Now",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                TextButton(onClick = {
                    viewModel.restorePurchase("restore_token")
                }) {
                    Text("Restore Purchase")
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureComparison() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeatureRow("Search & Browse", free = true, pro = true)
        FeatureRow("Stream Playback", free = false, pro = true)
        FeatureRow("Downloads", free = false, pro = true)
        FeatureRow("IPTV / Live TV", free = false, pro = true)
        FeatureRow("Multi-Debrid", free = false, pro = true)
        FeatureRow("Advanced Filters", free = false, pro = true)
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

@Composable
private fun PricingCard(
    title: String,
    price: String,
    description: String,
    selected: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }

    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (highlighted) {
                        Spacer(Modifier.width(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = "BEST VALUE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
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
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
