package com.torve.android.tv.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.android.ui.transfer.AndroidTransferQrRenderer
import com.torve.presentation.pairing.TvPairingSignInViewModel
import com.torve.presentation.session.AccountSessionCoordinator
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * TV-side QR sign-in screen. Generates a one-time pairing code via the
 * Torve backend and shows it as both a QR (for the user's phone camera)
 * and a 6-character fallback (for users without a phone handy). Polls
 * for status until either claimed (→ persists tokens, runs the same
 * post-sign-in bootstrap as email login) or the code expires.
 */
@Composable
fun TvPairingSignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: TvPairingSignInViewModel = koinInject(),
    accountSessionCoordinator: AccountSessionCoordinator = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }

    LaunchedEffect(state) {
        if (state is TvPairingSignInViewModel.State.SignedIn) {
            // Same post-sign-in bootstrap the email-login path runs —
            // device registration, settings + integrations restore,
            // playlist sync, etc.
            runCatching { accountSessionCoordinator.bootstrapAfterSignIn() }
            onSignedIn()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .padding(48.dp),
    ) {
        IconButton(onClick = {
            viewModel.cancel()
            onBack()
        }) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Snow,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Sign in with your phone",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Scan this QR code with the Torve app on your phone, or open Settings → Devices and enter the code below.",
            style = MaterialTheme.typography.bodyLarge,
            color = Torve.colors.textSecondary,
        )

        Spacer(Modifier.height(32.dp))

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                TvPairingSignInViewModel.State.Idle,
                TvPairingSignInViewModel.State.Generating -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Amber)
                        Spacer(Modifier.height(16.dp))
                        Text("Preparing a sign-in code…", color = Snow)
                    }
                }
                is TvPairingSignInViewModel.State.Active -> {
                    val qr = remember(s.qrPayload) {
                        runCatching { AndroidTransferQrRenderer.render(s.qrPayload) }.getOrNull()
                    }
                    ActiveContent(qr = qr, code = s.code)
                }
                TvPairingSignInViewModel.State.Expired -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Code expired",
                            style = MaterialTheme.typography.titleLarge,
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "The pairing code timed out. Generate a new one and try again.",
                            color = Torve.colors.textSecondary,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { scope.launch { viewModel.restart() } },
                        ) {
                            Text("Generate new code")
                        }
                    }
                }
                is TvPairingSignInViewModel.State.SignedIn -> {
                    // Transient — onSignedIn() callback above will navigate
                    // away. Show a confirmation glimpse until then.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Welcome${s.displayName?.let { ", $it" } ?: ""}!",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Snow,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(s.email, color = Torve.colors.textSecondary)
                    }
                }
                is TvPairingSignInViewModel.State.Error -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Couldn't sign in",
                            style = MaterialTheme.typography.titleLarge,
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = s.message,
                            color = Torve.colors.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { scope.launch { viewModel.restart() } }) {
                            Text("Try again", color = Amber)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveContent(qr: ImageBitmap?, code: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (qr != null) {
            Box(
                modifier = Modifier
                    .size(360.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = qr,
                    contentDescription = "Sign-in QR code",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(360.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Charcoal),
                contentAlignment = Alignment.Center,
            ) {
                Text("QR unavailable", color = Snow)
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Charcoal)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.displaySmall,
                color = Amber,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = "Waiting for your phone…",
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textTertiary,
        )
    }
}
