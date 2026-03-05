package com.streamvault.android.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.domain.sync.AccountSyncManager
import com.streamvault.domain.sync.IdentityState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun PairingClaimScreen(
    incomingCode: String?,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    accountSyncManager: AccountSyncManager = koinInject(),
    analytics: SyncAnalytics = NoOpSyncAnalytics,
) {
    val scope = rememberCoroutineScope()
    val status by accountSyncManager.status.collectAsState()
    var codeField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue((incomingCode ?: "").take(6).uppercase()))
    }
    var pendingClaimCode by rememberSaveable {
        mutableStateOf(
            incomingCode
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.length == 6 },
        )
    }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEnableSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        accountSyncManager.refreshStatus()
    }

    suspend fun claimCode(code: String) {
        val normalized = code.trim().uppercase()
        if (normalized.length != 6) {
            error = "Enter a valid 6 character code"
            return
        }

        val claimed = accountSyncManager.claimPairingCode(normalized)
        if (claimed) {
            message = "Device paired successfully"
            error = null
            analytics.track("pairing_claim_success")
        } else {
            message = null
            error = "Code not recognized"
            analytics.track("pairing_claim_failed")
        }
    }

    LaunchedEffect(status.identity, pendingClaimCode) {
        val pending = pendingClaimCode ?: return@LaunchedEffect
        if (status.identity == IdentityState.SIGNED_IN) {
            claimCode(pending)
            pendingClaimCode = null
        }
    }

    LaunchedEffect(incomingCode, status.identity, pendingClaimCode) {
        val incoming = incomingCode
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == 6 } ?: return@LaunchedEffect
        if (status.identity == IdentityState.LOCAL && pendingClaimCode == incoming) {
            showEnableSheet = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackButton(onClick = onBack)

        Text(
            text = "Claim TV pairing",
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Enter the code from your TV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Silver,
                )

                OutlinedTextField(
                    value = codeField,
                    onValueChange = {
                        val sanitized = it.text.uppercase().filter { c -> c.isLetterOrDigit() }.take(6)
                        codeField = it.copy(text = sanitized)
                        error = null
                    },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        scope.launch {
                            val code = codeField.text.trim().uppercase()
                            if (status.identity == IdentityState.SIGNED_IN) {
                                accountSyncManager.enableSync()
                                claimCode(code)
                            } else {
                                pendingClaimCode = code
                                showEnableSheet = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Claim pairing")
                }

                if (status.identity != IdentityState.SIGNED_IN) {
                    Text(
                        text = "Sign in is required to claim this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                    )
                }

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Snow,
                        textAlign = TextAlign.Start,
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showEnableSheet) {
        EnableSyncSheet(
            onDismiss = { showEnableSheet = false },
            onContinue = {
                showEnableSheet = false
                scope.launch { accountSyncManager.enableSync() }
                onOpenLogin()
            },
            isTvFlow = false,
            analytics = analytics,
        )
    }
}
