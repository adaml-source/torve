package com.torve.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.auth.VerificationBanner
import com.torve.data.auth.AuthClient
import kotlinx.coroutines.launch

@Composable
fun VerifyEmailGateScreen(
    authClient: AuthClient,
    onVerified: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val user by authClient.authUserFlow.collectAsState()
    val email = user?.email.orEmpty()

    LaunchedEffect(user?.isVerified) {
        if (user?.isVerified == true) {
            onVerified()
        } else {
            authClient.startVerificationEvents()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Confirm your email",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (email.isBlank()) {
                "Confirm your email before setting up sources."
            } else {
                "We sent a confirmation link to $email. Confirm it, then continue to setup."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (email.isNotBlank()) {
            VerificationBanner(
                email = email,
                authClient = authClient,
                onVerified = onVerified,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                scope.launch {
                    if (authClient.checkVerificationStatus()) {
                        onVerified()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("I've confirmed my email")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    if (email.isNotBlank()) {
                        authClient.resendVerification(email)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send email again")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Next: choose how you want to watch. Use Panda for Debrid/Usenet, add IPTV for live TV, or connect Plex/Jellyfin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = {
                scope.launch {
                    authClient.logout()
                    onSignedOut()
                }
            },
        ) {
            Text("Use a different account")
        }
    }
}
