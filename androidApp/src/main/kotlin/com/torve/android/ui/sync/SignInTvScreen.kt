package com.torve.android.ui.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.sync.SyncCoordinator
import com.torve.android.ui.transfer.QrScannerView
import com.torve.android.ui.transfer.cameraPermissionGranted
import com.torve.android.ui.transfer.deviceHasAnyCamera
import com.torve.data.auth.AuthClient
import com.torve.presentation.pairing.TvPairingSignInViewModel
import org.koin.compose.koinInject

/**
 * Phone-side companion to the TV's QR-sign-in screen.
 *
 * The TV displays a QR encoding `torve-signin:<CODE>` plus a 6-char
 * code. The user can either scan with the phone camera (handled here)
 * or type the code manually. Both routes hit
 * `POST /pairing/signin/claim`, after which the TV's poll picks up
 * tokens for this phone's account.
 *
 * Native phone camera apps cannot interpret `torve-signin:` URIs —
 * scanning has to happen inside the Torve app. This screen is the
 * dedicated, discoverable entry point for that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInTvScreen(
    onBack: () -> Unit,
    onDeviceLimitReached: () -> Unit = {},
    syncCoordinator: SyncCoordinator = koinInject(),
    authClient: AuthClient = koinInject(),
) {
    val state by syncCoordinator.state.collectAsState()
    val authUser by authClient.authUserFlow.collectAsState()
    val context = LocalContext.current
    val hasCamera = remember { deviceHasAnyCamera(context) }
    var manualCode by remember { mutableStateOf("") }
    var scannerOpen by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(cameraPermissionGranted(context))
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) scannerOpen = true
    }

    // When the underlying claim succeeds, syncCoordinator updates
    // pairingStatus on its state — bounce back to the previous screen
    // so the user sees their own account context, not this scanner.
    LaunchedEffect(state.pairingStatus) {
        val status = state.pairingStatus
        if (status != null && status.contains("signed in", ignoreCase = true)) {
            onBack()
        }
    }

    LaunchedEffect(authUser?.id, state.isAuthenticated, state.isLoading) {
        if (authUser != null && !state.isAuthenticated && !state.isLoading) {
            syncCoordinator.refreshDevices()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sign_in_tv_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (authUser == null && !state.isAuthenticated) {
                Text(
                    stringResource(R.string.sign_in_tv_need_signed_in),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Text(
                stringResource(R.string.sign_in_tv_instructions),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (hasCamera) {
                if (!scannerOpen) {
                    Button(
                        onClick = {
                            if (permissionGranted) scannerOpen = true
                            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.sign_in_tv_scan_qr))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)),
                    ) {
                        QrScannerView(
                            onQrDetected = { payload ->
                                val code = TvPairingSignInViewModel
                                    .extractCodeFromQrPayload(payload)
                                if (code != null) {
                                    scannerOpen = false
                                    syncCoordinator.claimTvSigninCode(
                                        code = code,
                                        onDeviceLimitReached = onDeviceLimitReached,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    OutlinedButton(
                        onClick = { scannerOpen = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.sign_in_tv_cancel_scan))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.sign_in_tv_no_camera),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sign_in_tv_enter_code_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                label = { Text(stringResource(R.string.sign_in_tv_code_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    syncCoordinator.claimTvSigninCode(
                        code = manualCode,
                        onDeviceLimitReached = onDeviceLimitReached,
                    )
                },
                enabled = manualCode.isNotBlank() && !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sign_in_tv_submit))
            }

            state.error?.let {
                Text(
                    com.torve.android.error.resolveErrorKey(LocalContext.current, it)
                        ?: it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.pairingStatus?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
