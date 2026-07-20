package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.BuildConfig
import com.torve.android.tv.NotificationType
import com.torve.android.tv.TvNotificationQueue
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.presentation.beta.BetaProgramCopy
import com.torve.presentation.beta.BetaProgramUiState
import com.torve.presentation.beta.BetaProgramViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import org.koin.compose.koinInject

@Composable
internal fun TvBetaProgramScreen(
    onBack: () -> Unit,
    viewModel: BetaProgramViewModel = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val hasExistingPremiumAccess = subscriptionState.hasEntitlement || subscriptionState.isPro
    val discordInviteUrl = resolveTvDiscordInviteUrl(state.discordInviteUrl)
    val clipboard = LocalClipboardManager.current
    val backRequester = remember { FocusRequester() }
    val primaryRequester = remember { FocusRequester() }
    val refreshRequester = remember { FocusRequester() }
    var restoreFocusAfterBusy by remember { mutableStateOf<BetaFocusAfterBusy?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        viewModel.onOpenBetaProgram()
        subscriptionViewModel.refreshAccess()
        withFrameNanos { }
        runCatching { backRequester.requestFocus() }
    }
    LaunchedEffect(state.copySuccess) {
        if (state.copySuccess) {
            TvNotificationQueue.post("Code copied.", NotificationType.SUCCESS)
            viewModel.consumeCopySuccess()
        }
    }
    LaunchedEffect(state.isGeneratingCode, state.isRefreshing, state.isLoading, restoreFocusAfterBusy) {
        val target = restoreFocusAfterBusy ?: return@LaunchedEffect
        if (!state.isGeneratingCode && !state.isRefreshing && !state.isLoading) {
            withFrameNanos { }
            runCatching {
                when (target) {
                    BetaFocusAfterBusy.Primary -> primaryRequester
                    BetaFocusAfterBusy.Refresh -> refreshRequester
                }.requestFocus()
            }
            restoreFocusAfterBusy = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Amber.copy(alpha = 0.18f), Obsidian, Color.Black),
                    radius = 1400f,
                ),
            )
            .padding(horizontal = 72.dp, vertical = 44.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .focusRequester(backRequester)
                        .focusProperties { down = primaryRequester },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Back")
                }
                BetaPill("Beta Program")
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Torve Beta Program",
                    color = Snow,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = BetaProgramCopy.DEADLINE,
                    color = Silver,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                BetaTvCard(
                    modifier = Modifier.weight(1.15f),
                    title = state.primaryBadge,
                    body = tvBodyFor(state),
                )
                BetaTvCard(
                    modifier = Modifier.weight(0.85f),
                    title = "Discord flow",
                    body = tvDiscordFlowBody(discordInviteUrl),
                )
            }

            state.generatedCode?.takeIf { it.isNotBlank() }?.let { code ->
                BetaCodePanel(
                    code = code,
                    expiresAt = state.generatedCodeExpiresAt,
                    discordInviteUrl = discordInviteUrl,
                )
            }

            if (hasExistingPremiumAccess) {
                BetaTvCard(
                    title = "Full access already enabled",
                    body = "${BetaProgramCopy.PREMIUM_TESTER_APPLICATION} ${BetaProgramCopy.FREE_PREMIUM_NON_PREMIUM_ONLY}",
                )
            }

            BetaTvCard(
                title = "Safety",
                body = BetaProgramCopy.SAFETY,
            )

            state.errorMessage?.let {
                BetaTvCard(
                    title = "Could not update beta status",
                    body = it,
                    tone = BetaCardTone.Error,
                )
            }

            if (state.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Amber)
                    Text("Loading beta status...", color = Silver)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Button(
                    onClick = {
                        when {
                            state.showVerifyEmail -> viewModel.onVerifyEmail()
                            state.showGenerateCode -> {
                                restoreFocusAfterBusy = BetaFocusAfterBusy.Primary
                                viewModel.onGenerateCode()
                            }
                            state.showCopyCode && state.generatedCode != null -> {
                                clipboard.setText(AnnotatedString(state.generatedCode.orEmpty()))
                                viewModel.onCopyCode()
                            }
                            else -> {
                                restoreFocusAfterBusy = BetaFocusAfterBusy.Primary
                                viewModel.onRefreshStatus()
                            }
                        }
                    },
                    enabled = state.isSignedIn &&
                        !state.isGeneratingCode &&
                        !state.isRefreshing &&
                        !state.isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(primaryRequester),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                ) {
                    val icon = when {
                        state.showCopyCode -> Icons.Filled.ContentCopy
                        else -> Icons.Filled.Refresh
                    }
                    Icon(icon, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isGeneratingCode) "Generating..." else state.primaryActionLabel)
                }
                OutlinedButton(
                    onClick = {
                        restoreFocusAfterBusy = BetaFocusAfterBusy.Refresh
                        viewModel.onRefreshStatus()
                    },
                    enabled = !state.isRefreshing && !state.isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(refreshRequester),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isRefreshing) "Refreshing..." else "Refresh Status")
                }
            }
        }
    }
}

@Composable
private fun BetaCodePanel(
    code: String,
    expiresAt: String?,
    discordInviteUrl: String?,
) {
    BetaTvCard(
        title = "Discord link code",
        body = "Use a phone or desktop for Discord. Go to #beta-info, press Apply for Beta, and paste this code.",
    ) {
        Text(
            text = code.chunked(4).joinToString(" "),
            color = Snow,
            fontSize = 35.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        expiresAt?.let {
            Text("Expires at $it", color = Silver, fontSize = 11.sp)
        }
        discordInviteUrl?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
            Text("Invite: $url", color = Amber, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun BetaTvCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: BetaCardTone = BetaCardTone.Default,
    extraContent: @Composable (() -> Unit)? = null,
) {
    val border = if (tone == BetaCardTone.Error) Color(0xFFFF6B6B) else Amber.copy(alpha = 0.22f)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(22.dp)),
        color = Charcoal.copy(alpha = 0.92f),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                color = Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                color = Snow,
                fontSize = 17.sp,
                lineHeight = 22.sp,
            )
            extraContent?.invoke()
        }
    }
}

@Composable
private fun BetaPill(text: String) {
    Surface(
        color = Amber.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.42f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Amber,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private enum class BetaCardTone {
    Default,
    Error,
}

private enum class BetaFocusAfterBusy {
    Primary,
    Refresh,
}

private fun tvBodyFor(state: BetaProgramUiState): String {
    if (!state.isSignedIn) return "Sign in to apply for the Torve Beta Program."
    if (state.isEmailVerificationRequired) {
        return "Verify your email on mobile or desktop before applying for beta."
    }
    return state.body
}

private fun tvDiscordFlowBody(discordInviteUrl: String?): String {
    val invite = discordInviteUrl?.trim()?.takeIf { it.isNotBlank() }
    return buildString {
        append("Use Discord on your phone or desktop. Go to #beta-info, press Apply for Beta, and paste this code.")
        if (invite != null) {
            append("\nInvite: ")
            append(invite)
        }
    }
}

private fun resolveTvDiscordInviteUrl(backendUrl: String?): String? =
    backendUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: BuildConfig.TORVE_DISCORD_INVITE_URL.trim().takeIf { it.isNotBlank() }
