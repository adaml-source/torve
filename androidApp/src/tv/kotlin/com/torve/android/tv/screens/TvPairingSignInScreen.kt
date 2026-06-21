package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.android.session.PostSignInRefresh
import com.torve.android.ui.transfer.AndroidTransferQrRenderer
import com.torve.android.sync.SyncCoordinator
import com.torve.presentation.pairing.TvPairingSignInViewModel
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.device.DeviceGovernanceViewModel
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
    syncCoordinator: SyncCoordinator = koinInject(),
    deviceGovernanceViewModel: DeviceGovernanceViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cancelRequester = remember { FocusRequester() }
    fun exitSignIn() {
        viewModel.cancel()
        onBack()
    }

    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(Unit) { runCatching { cancelRequester.requestFocus() } }
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }
    BackHandler(onBack = ::exitSignIn)

    LaunchedEffect(state) {
        if (state is TvPairingSignInViewModel.State.SignedIn) {
            // Same post-sign-in bootstrap the email-login path runs —
            // device registration, settings + integrations restore,
            // playlist sync, etc.
            runCatching { accountSessionCoordinator.bootstrapAfterSignIn() }
            deviceGovernanceViewModel.fetchAccessState()
            PostSignInRefresh.enqueueAfterAccountRestore(context, accountSessionCoordinator)
            // Refresh SyncCoordinator so isAuthenticated updates immediately
            // without requiring an app restart.
            syncCoordinator.refreshDevices()
            onSignedIn()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .padding(48.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::exitSignIn) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = Snow,
                )
            }
            OutlinedButton(
                onClick = ::exitSignIn,
                modifier = Modifier.focusRequester(cancelRequester),
            ) {
                Text(stringResource(R.string.common_cancel), color = Snow)
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.tv_pairing_sign_in_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tv_pairing_sign_in_desc),
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
                        Text(stringResource(R.string.tv_pairing_preparing_code), color = Snow)
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
                            text = stringResource(R.string.tv_pairing_code_expired_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tv_pairing_code_expired_desc),
                            color = Torve.colors.textSecondary,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { scope.launch { viewModel.restart() } },
                        ) {
                            Text(stringResource(R.string.tv_pairing_generate_new_code))
                        }
                    }
                }
                is TvPairingSignInViewModel.State.SignedIn -> {
                    // Transient — onSignedIn() callback above will navigate
                    // away. Show a confirmation glimpse until then.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(
                                R.string.tv_pairing_welcome_format,
                                s.displayName?.let { ", $it" }.orEmpty(),
                            ),
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
                            stringResource(R.string.tv_pairing_error_title),
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
                            Text(stringResource(R.string.tv_pairing_try_again), color = Amber)
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
                    contentDescription = stringResource(R.string.tv_pairing_qr_content_description),
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
                Text(stringResource(R.string.tv_pairing_qr_unavailable), color = Snow)
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
            text = stringResource(R.string.tv_pairing_waiting_phone),
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textTertiary,
        )
    }
}
