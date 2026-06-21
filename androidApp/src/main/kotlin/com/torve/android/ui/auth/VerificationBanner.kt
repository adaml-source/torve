package com.torve.android.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.data.auth.AuthClient
import kotlinx.coroutines.launch

@Composable
fun VerificationBanner(
    email: String,
    authClient: AuthClient,
    onVerified: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<BannerState>(BannerState.Idle) }

    // SSE is the primary push mechanism (started by AuthClient on unverified login).
    // Polling is kept as a fallback at a longer interval in case SSE disconnects.
    // Both paths go through checkVerificationStatus() → authUserFlow, so duplicate
    // detection is inherently safe — the flow only emits distinct values.
    LaunchedEffect(Unit) {
        // Ensure SSE is running (idempotent — no-ops if already active)
        authClient.startVerificationEvents()
        // Fallback poll every 15s (SSE provides near-instant push, this catches edge cases)
        while (true) {
            delay(15_000)
            val verified = authClient.checkVerificationStatus()
            if (verified) {
                onVerified()
                break
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (state) {
                    is BannerState.Sent -> stringResource(R.string.verify_email_sent)
                    is BannerState.Checking -> stringResource(R.string.verify_checking)
                    is BannerState.RateLimited -> stringResource(R.string.verify_wait_before_resend)
                    is BannerState.Error -> stringResource(R.string.verify_email_not_verified)
                    else -> stringResource(R.string.verify_email_not_verified)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            when (state) {
                is BannerState.Sending, is BannerState.Checking -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                is BannerState.Sent -> {
                    TextButton(onClick = {
                        scope.launch {
                            state = BannerState.Checking
                            val verified = authClient.checkVerificationStatus()
                            if (verified) {
                                onVerified()
                            } else {
                                state = BannerState.Idle
                            }
                        }
                    }) {
                        Text(stringResource(R.string.verify_ive_verified))
                    }
                }
                is BannerState.RateLimited -> {}
                else -> {
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                state = BannerState.Checking
                                val verified = authClient.checkVerificationStatus()
                                if (verified) {
                                    onVerified()
                                } else {
                                    state = BannerState.Idle
                                }
                            }
                        }) {
                            Text(stringResource(R.string.verify_check))
                        }
                        TextButton(onClick = {
                            scope.launch {
                                state = BannerState.Sending
                                val result = authClient.resendVerification(email)
                                state = when {
                                    result.success -> BannerState.Sent
                                    result.error?.contains("wait") == true -> BannerState.RateLimited
                                    else -> BannerState.Error(result.error ?: "Failed")
                                }
                            }
                        }) {
                            Text(stringResource(R.string.verify_resend))
                        }
                    }
                }
            }
        }
    }
}

private sealed interface BannerState {
    data object Idle : BannerState
    data object Sending : BannerState
    data object Checking : BannerState
    data object Sent : BannerState
    data object RateLimited : BannerState
    data class Error(val message: String) : BannerState
}
