package com.torve.desktop.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.data.auth.AuthClient
import com.torve.desktop.auth.DesktopAuthController
import com.torve.desktop.auth.DesktopAuthUiState
import kotlinx.coroutines.launch

/**
 * Desktop equivalent of Android's [VerifyEmailGateScreen].
 *
 * Rendered by the shell when the user is signed in but has
 * `isVerified == false`. The user must click the link in the email
 * we sent them; once `AuthClient.checkVerificationStatus()` flips
 * `isVerified` to true (either via the SSE listener or the manual
 * "I've confirmed" button), the admission controller advances to
 * Onboarding / Main automatically.
 *
 * Mirrors the Android contract: Resend button, manual "I've
 * confirmed" check, and a sign-out escape hatch in case the user
 * realised they typo'd their email.
 */
@Composable
fun DesktopVerifyEmailScreen(
    authState: DesktopAuthUiState,
    authClient: AuthClient,
    authController: DesktopAuthController,
) {
    val scope = rememberCoroutineScope()
    val user by authClient.authUserFlow.collectAsState()
    val email = user?.email.orEmpty().ifBlank { authState.email }

    // AuthClient.startVerificationEvents() is already invoked
    // automatically when the auth state transitions to
    // "signed in but unverified" (see AuthClient.kt:540). Belt-and-
    // suspenders here: if for any reason the SSE listener is idle
    // when we land, kick it back on.
    LaunchedEffect(user?.isVerified) {
        if (user?.isVerified == false) {
            authClient.startVerificationEvents()
        }
    }

    var resendBusy by remember { mutableStateOf(false) }
    var resendMessage by remember { mutableStateOf<String?>(null) }
    var checkBusy by remember { mutableStateOf(false) }
    var checkMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Confirm your email",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (email.isBlank()) {
                    "Confirm your email before continuing."
                } else {
                    "We sent a confirmation link to $email. Click it, " +
                        "then either return here and press \"I've " +
                        "confirmed\" or wait — Torve will detect it " +
                        "automatically."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !checkBusy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        checkBusy = true
                        checkMessage = null
                        val verified = runCatching { authClient.checkVerificationStatus() }
                            .getOrElse { false }
                        checkBusy = false
                        if (!verified) {
                            checkMessage =
                                "Still not confirmed. Open the link in the email and try again."
                        }
                        // When verified == true, AuthClient updates
                        // authUserFlow, which propagates through
                        // DesktopAuthController -> admission controller,
                        // which transitions us out of this screen.
                    }
                },
            ) {
                Text(if (checkBusy) "Checking…" else "I've confirmed my email")
            }
            checkMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                enabled = email.isNotBlank() && !resendBusy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        resendBusy = true
                        resendMessage = null
                        val result = runCatching { authClient.resendVerification(email) }
                            .getOrElse { com.torve.data.auth.AuthResult(success = false, error = it.message) }
                        resendBusy = false
                        resendMessage = if (result.success) {
                            "Sent. Check your inbox (and spam folder)."
                        } else {
                            result.error ?: "Could not send the verification email. Try again later."
                        }
                    }
                },
            ) {
                Text(if (resendBusy) "Sending…" else "Send the email again")
            }
            resendMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Why this matters: a verified email is required for " +
                    "password resets, account export / deletion, and " +
                    "account security. Without it, your " +
                    "account can't be recovered if anything goes wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            TextButton(
                onClick = { authController.signOut() },
            ) {
                Text("Use a different account")
            }
        }
    }
}
