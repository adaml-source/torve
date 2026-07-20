package com.torve.android.ui.beta

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.BuildConfig
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.presentation.beta.BetaProgramCopy
import com.torve.presentation.beta.BetaProgramViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import org.koin.compose.koinInject

@Composable
fun BetaProgramScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    viewModel: BetaProgramViewModel = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val hasExistingPremiumAccess = subscriptionState.hasEntitlement || subscriptionState.isPro
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val discordInviteUrl = resolveDiscordInviteUrl(state.discordInviteUrl)

    LaunchedEffect(Unit) {
        viewModel.onOpenBetaProgram()
        subscriptionViewModel.refreshAccess()
    }
    LaunchedEffect(state.copySuccess) {
        if (state.copySuccess) {
            Toast.makeText(context, "Code copied.", Toast.LENGTH_SHORT).show()
            viewModel.consumeCopySuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onBack,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
        ) {
            Text("Back")
        }
        Text(
            text = "Torve Beta Program",
            style = MaterialTheme.typography.headlineMedium,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = BetaProgramCopy.DETAIL_INTRO,
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textSecondary,
        )

        BetaStatusCard(state)

        if (hasExistingPremiumAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = Charcoal)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = BetaProgramCopy.PREMIUM_TESTER_APPLICATION,
                        color = Snow,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = BetaProgramCopy.FREE_PREMIUM_NON_PREMIUM_ONLY,
                        color = Torve.colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (state.generatedCode != null) {
            BetaCodeCard(
                code = state.generatedCode.orEmpty(),
                expiresAt = state.generatedCodeExpiresAt,
            )
        }

        BetaInfoCard(discordInviteUrl = discordInviteUrl)

        state.errorMessage?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = Charcoal)) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (state.isLoading) {
            CircularProgressIndicator(color = Amber)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    when {
                        !state.isSignedIn -> onSignIn()
                        state.showVerifyEmail -> viewModel.onVerifyEmail()
                        state.showGenerateCode -> viewModel.onGenerateCode()
                        state.showCopyCode && state.generatedCode != null -> {
                            clipboard.setText(AnnotatedString(state.generatedCode.orEmpty()))
                            viewModel.onCopyCode()
                        }
                        else -> viewModel.onRefreshStatus()
                    }
                },
                enabled = !state.isGeneratingCode && !state.isRefreshing && !state.isLoading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
            ) {
                Text(
                    when {
                        state.isGeneratingCode -> "Generating..."
                        else -> state.primaryActionLabel
                    },
                )
            }
            val secondaryActionLabel = when {
                state.secondaryActionLabel == "Learn More" && discordInviteUrl != null -> "Open Discord"
                else -> state.secondaryActionLabel
            }
            secondaryActionLabel?.let { label ->
                OutlinedButton(
                    onClick = {
                        when (label) {
                            "Open Discord" -> openDiscord(context, discordInviteUrl)
                            "Resend Verification Email" -> viewModel.onResendVerificationEmail()
                            else -> Unit
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    Text(label)
                }
            }
        }
        if (discordInviteUrl != null && state.secondaryActionLabel != "Learn More" && state.secondaryActionLabel != "Open Discord") {
            OutlinedButton(
                onClick = { openDiscord(context, discordInviteUrl) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text("Open Discord")
            }
        }
        OutlinedButton(
            onClick = viewModel::onRefreshStatus,
            enabled = !state.isRefreshing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
        ) {
            Text(if (state.isRefreshing) "Refreshing..." else "Refresh Status")
        }
    }
}

@Composable
private fun BetaStatusCard(state: com.torve.presentation.beta.BetaProgramUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.primaryBadge,
                style = MaterialTheme.typography.labelLarge,
                color = Amber,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.body,
                style = MaterialTheme.typography.titleMedium,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = BetaProgramCopy.EMAIL_VERIFICATION,
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textSecondary,
            )
            if (state.betaAccessActive) {
                Text(
                    text = "Free beta access runs no later than July 31, 2026.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun BetaCodeCard(code: String, expiresAt: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Discord link code", color = Amber, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.headlineLarge,
                color = Snow,
                fontWeight = FontWeight.Black,
            )
            expiresAt?.let {
                Text(
                    text = "Expires at $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = BetaProgramCopy.DISCORD_INSTRUCTION,
                style = MaterialTheme.typography.bodyMedium,
                color = Torve.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun BetaInfoCard(discordInviteUrl: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(BetaProgramCopy.DEADLINE, color = Snow, fontWeight = FontWeight.SemiBold)
            Text(BetaProgramCopy.SAFETY, color = Torve.colors.textSecondary)
            discordInviteUrl?.let { invite ->
                Text(
                    text = "Torve Discord: $invite",
                    color = Torve.colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun resolveDiscordInviteUrl(backendUrl: String?): String? =
    backendUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: BuildConfig.TORVE_DISCORD_INVITE_URL.trim().takeIf { it.isNotBlank() }

private fun openDiscord(context: android.content.Context, url: String?) {
    val trimmed = url?.trim().orEmpty()
    if (trimmed.isBlank()) {
        Toast.makeText(context, "Discord invite is not configured.", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val inviteUri = Uri.parse(trimmed)
        val discordIntent = Intent(Intent.ACTION_VIEW, inviteUri).setPackage("com.discord")
        val fallbackIntent = Intent(Intent.ACTION_VIEW, inviteUri)
        if (discordIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(discordIntent)
        } else {
            context.startActivity(fallbackIntent)
        }
    }.onFailure {
        Toast.makeText(context, "Could not open Discord.", Toast.LENGTH_SHORT).show()
    }
}
