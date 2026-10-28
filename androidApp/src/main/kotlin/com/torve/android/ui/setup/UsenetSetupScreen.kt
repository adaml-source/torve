package com.torve.android.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.presentation.panda.PandaSetupViewModel
import org.koin.compose.koinInject

/** Single-purpose source form. Existing account persistence keeps saved secrets and other sources. */
@Composable
fun UsenetSetupScreen(onBack: () -> Unit, viewModel: PandaSetupViewModel = koinInject()) {
    val state by viewModel.state.collectAsState()
    var loading by remember(viewModel) { mutableStateOf(true) }
    var recoveryToken by remember { mutableStateOf("") }
    var recoveryAttempted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.openUsenetSetup().join()
        loading = false
    }
    BackHandler { if (!state.isSaving) onBack() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().testTag("usenet_setup")) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = { if (!state.isSaving) onBack() })
            Text(stringResource(R.string.usenet_setup_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        }
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.isEditMode && state.editRequiresRecovery) {
            Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.usenet_setup_recovery), color = MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(recoveryToken, { recoveryToken = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manage_panda_token_label)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (recoveryAttempted && state.recoveryError != null) {
                    Text(stringResource(R.string.usenet_setup_recovery_failed), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = { recoveryAttempted = true; viewModel.recoverManagementToken(recoveryToken); recoveryToken = "" },
                    enabled = recoveryToken.isNotBlank() && !state.recoveryInProgress) {
                    Text(stringResource(R.string.manage_panda_validate_save))
                }
            }
        } else {
            Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                UsenetConnectionForm(state, viewModel)
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.configLoadFailed) {
                Text(stringResource(R.string.usenet_setup_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.openUsenetSetup() }, enabled = !state.isLoadingConfig) {
                    Text(stringResource(R.string.usenet_setup_retry))
                }
            }
            when {
                state.saveError != null || state.error != null -> Text(stringResource(R.string.usenet_setup_save_failed),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("usenet_setup.error"))
                state.addonInstalled -> Text(stringResource(R.string.usenet_setup_saved),
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("usenet_setup.saved"))
            }
            Button(onClick = { viewModel.saveConfigAndInstall() },
                enabled = !loading && !state.isLoadingConfig && !state.configLoadFailed && !state.isSaving && !state.addonInstalled && !state.editRequiresRecovery && state.selectedProvider != null &&
                    (state.isEditMode || (state.enableUsenet && state.usenetProvider != "none")),
                modifier = Modifier.fillMaxWidth().testTag("usenet_setup.save")) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(if (state.isSaving) R.string.usenet_setup_saving else R.string.usenet_setup_save))
            }
        }
    }
}
