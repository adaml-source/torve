package com.torve.android.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.sync.SyncCoordinator
import com.torve.data.account.AccountSettingsRepository
import com.torve.data.auth.AuthClient
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.error.resolveErrorKey
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.subscription.accessPresentation
import org.koin.compose.koinInject

@Composable
fun AccountScreen(
    onOpenDevices: () -> Unit,
    onManageDevices: () -> Unit,
    onBack: () -> Unit,
    authClient: AuthClient = koinInject(),
    syncCoordinator: SyncCoordinator = koinInject(),
    accountSettingsRepository: AccountSettingsRepository = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
) {
    val syncState by syncCoordinator.state.collectAsState()
    val accountSettingsState by accountSettingsRepository.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val purchaseStrings: com.torve.presentation.subscription.PurchaseStringResolver = org.koin.compose.koinInject()
    val subscriptionAccess = subscriptionState.accessPresentation(purchaseStrings)
    val pairedDevices = syncState.devices.filter { it.revokedAt == null }
    val authUserState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.torve.data.auth.AuthUser?>(null) }

    LaunchedEffect(Unit) {
        authUserState.value = authClient.getAuthenticatedUser()
        subscriptionViewModel.refreshAccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.account_title), style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = authUserState.value?.email ?: stringResource(R.string.account_not_signed_in),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.account_this_device, syncCoordinator.installationId()),
                    style = MaterialTheme.typography.bodySmall,
                )
                val context = LocalContext.current
                val syncErrorMessage = accountSettingsState.lastError?.let { resolveErrorKey(context, it) }
                Text(
                    text = if (syncErrorMessage == null) {
                        stringResource(R.string.account_settings_sync_ok)
                    } else {
                        syncErrorMessage
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncErrorMessage == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = subscriptionAccess.accessStatusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subscriptionAccess.accessHelperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (subscriptionAccess.shouldShowManageDevices) {
                    Button(onClick = onManageDevices, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.manage_devices_title))
                    }
                }
                if (subscriptionState.isLoggedIn) {
                    OutlinedButton(
                        onClick = { subscriptionViewModel.refreshAccess() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.premium_refresh_access))
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.account_pairings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (pairedDevices.isEmpty()) {
                        stringResource(R.string.account_no_pairings)
                    } else {
                        if (pairedDevices.size == 1) stringResource(R.string.account_pairings_count_one, pairedDevices.size)
                        else stringResource(R.string.account_pairings_count_other, pairedDevices.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.account_pairing_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenDevices, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_manage_pairings))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.account_devices), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(R.string.account_devices_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onManageDevices, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_manage_signed_in))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.common_back))
        }
    }
}
