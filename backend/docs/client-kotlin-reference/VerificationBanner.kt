package com.torve.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Non-blocking verification banner for unverified users.
 *
 * Shows at the top of any screen when user.isVerified == false.
 * Offers a "Resend" action. Handles rate limiting gracefully.
 *
 * Usage:
 *   VerificationBanner(
 *       isVerified = user.isVerified,
 *       onResend = { viewModel.resendVerification() },
 *       resendState = viewModel.resendState,
 *   )
 */
enum class ResendState {
    Idle,
    Loading,
    Sent,
    RateLimited,
    Error,
}

@Composable
fun VerificationBanner(
    isVerified: Boolean,
    onResend: () -> Unit,
    resendState: ResendState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = !isVerified) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Please verify your email.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )

            when (resendState) {
                ResendState.Idle -> {
                    Text(
                        text = "Resend",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onResend() },
                    )
                }
                ResendState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                ResendState.Sent -> {
                    Text(
                        text = "Sent!",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                ResendState.RateLimited -> {
                    Text(
                        text = "Try again later",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ResendState.Error -> {
                    Text(
                        text = "Failed. Tap to retry.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { onResend() },
                    )
                }
            }
        }
    }
}
