package com.torve.android.ui.panda

import com.torve.android.ui.community.parityLabel as localizedUiText

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.data.panda.PandaProvider
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel

@Composable
fun PandaAuthStep(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
    entryFocusRequester: FocusRequester? = null,
) {
    val provider = state.selectedProvider

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.panda_connections_title),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Snow)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.panda_setup_auth_inline_hint),
            color = Silver,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        PandaButton(
            onClick = { viewModel.selectSetupMode(com.torve.presentation.panda.PandaSetupMode.USENET_ONLY) },
            modifier = Modifier.fillMaxWidth().then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        ) { Text(stringResource(R.string.panda_setup_type_usenet_title)) }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.providers.filter { it.id != "none" }.forEach { item ->
                val selected = item.id == provider?.id
                val providerCardRequester = remember(item.id) { FocusRequester() }
                PandaChoiceCard(
                    title = item.name,
                    description = state.debridStatusLabel(item.id),
                    selected = selected,
                    onClick = { viewModel.selectProvider(item) },
                    modifier = Modifier.focusRequester(providerCardRequester),
                )
                if (selected) {
                    SelectedProviderAuthControls(state, viewModel, item,
                        providerCardRequester = providerCardRequester)
                }
            }
        }
    }

}

@Composable
private fun SelectedProviderAuthControls(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
    provider: PandaProvider,
    entryFocusRequester: FocusRequester? = null,
    providerCardRequester: FocusRequester,
) {
    val supportsOAuth = "oauth" in provider.authMethods
    val providerConnected = state.hasDebridCredential(provider.id)
    val providerVerified = state.isDebridVerified(provider.id)
    val providerEnabled = state.isDebridEnabled(provider.id)
    val reauthenticateRequester = remember(provider.id, ProviderActionId.REAUTHENTICATE) { FocusRequester() }
    val disconnectRequester = remember(provider.id, ProviderActionId.DISCONNECT) { FocusRequester() }
    val enabledToggleRequester = remember(provider.id, ProviderActionId.ENABLED_TOGGLE) { FocusRequester() }
    val effectiveReauthenticateRequester = entryFocusRequester ?: reauthenticateRequester
    var pendingAction by remember(provider.id) { mutableStateOf<ProviderActionId?>(null) }

    LaunchedEffect(providerConnected, providerEnabled, pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect
        withFrameNanos { }
        val target = when {
            action == ProviderActionId.ENABLED_TOGGLE && providerConnected -> enabledToggleRequester
            action == ProviderActionId.REAUTHENTICATE && providerConnected -> effectiveReauthenticateRequester
            else -> providerCardRequester
        }
        runCatching { target.requestFocus() }
        pendingAction = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Gunmetal.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (providerConnected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Amber.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (providerVerified) Icons.Default.Check else Icons.Default.VpnKey,
                    null, tint = Amber, modifier = Modifier.size(18.dp))
                Text(
                    state.debridStatusLabel(provider.id),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                    modifier = Modifier.weight(1f),
                )
                if (state.existingCredentialDetected) {
                    Text(
                        localizedUiText("Existing credentials"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                    )
                }
            }
            PandaProviderAction(
                text = localizedUiText(if (state.authLoading) "Checking connection…" else "Check connection"),
                onClick = { viewModel.validateApiKey() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.authLoading && state.apiKeyInput.isNotBlank(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PandaProviderAction(
                    text = stringResource(R.string.storage_reauth_action),
                    onClick = {
                        pendingAction = ProviderActionId.REAUTHENTICATE
                        viewModel.reconnectSelectedDebrid()
                    },
                    modifier = Modifier.weight(1f),
                    entryFocusRequester = effectiveReauthenticateRequester,
                )
                PandaProviderAction(
                    text = stringResource(R.string.common_disconnect),
                    onClick = {
                        pendingAction = ProviderActionId.DISCONNECT
                        viewModel.disconnectSelectedDebrid()
                    },
                    modifier = Modifier.weight(1f),
                    entryFocusRequester = disconnectRequester,
                )
                PandaProviderAction(
                    text = localizedUiText(if (providerEnabled) "Disable for use" else "Enable for use"),
                    onClick = {
                        pendingAction = ProviderActionId.ENABLED_TOGGLE
                        viewModel.setDebridProviderEnabled(provider.id, !providerEnabled)
                    },
                    modifier = Modifier.weight(1.2f),
                    entryFocusRequester = enabledToggleRequester,
                )
            }
        }

        if (!providerConnected) {
            // Auth method toggle (only if provider supports both)
            if (supportsOAuth) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PandaAuthMethodChip(
                        selected = state.authMethod == "oauth",
                        onClick = { viewModel.setAuthMethod("oauth") },
                        label = stringResource(R.string.panda_setup_auth_oauth),
                        entryFocusRequester = entryFocusRequester,
                    )
                    PandaAuthMethodChip(
                        selected = state.authMethod == "apikey",
                        onClick = { viewModel.setAuthMethod("apikey") },
                        label = stringResource(R.string.panda_setup_auth_apikey),
                    )
                }
            }

            if (state.authMethod == "oauth" && supportsOAuth) {
                OAuthSection(state, viewModel)
            } else {
                ApiKeySection(
                    state = state,
                    viewModel = viewModel,
                    entryFocusRequester = if (supportsOAuth) null else entryFocusRequester,
                )
            }
        }

        state.error?.let { error ->
            Text(localizedUiText(error), color = Ruby, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PandaProviderAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    entryFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    PandaButton(onClick, modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier), enabled = enabled) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2)
    }
}

private enum class ProviderActionId {
    REAUTHENTICATE,
    DISCONNECT,
    ENABLED_TOGGLE,
}

@Composable
private fun PandaAuthMethodChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    entryFocusRequester: FocusRequester? = null,
) {
    PandaChip(selected, onClick, label,
        Modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier))
}

@Composable
private fun OAuthSection(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val deviceCode = state.deviceCode

    // Auto-start OAuth when entering this section
    LaunchedEffect(state.selectedProvider?.id) {
        val providerId = state.selectedProvider?.id
        val providerConnected = providerId != null && state.debridApiKeys[providerId]?.isNotBlank() == true
        if (deviceCode == null && !state.authLoading && !providerConnected) {
            viewModel.startOAuth()
        }
    }

    if (state.authLoading && deviceCode == null) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = Amber)
        }
    } else if (deviceCode != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Gunmetal)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.panda_setup_auth_enter_code),
                style = MaterialTheme.typography.bodyMedium,
                color = Silver,
            )
            Spacer(Modifier.height(8.dp))

            PandaButton({ clipboard.setText(AnnotatedString(deviceCode.userCode)) }) {
                Text(deviceCode.userCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ContentCopy, stringResource(R.string.common_copy), Modifier.size(20.dp))
            }

            Spacer(Modifier.height(16.dp))
            PandaButton(
                onClick = {
                    clipboard.setText(AnnotatedString(deviceCode.userCode))
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deviceCode.verificationUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
            ) {
                Text(
                    stringResource(R.string.panda_setup_auth_open_browser),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Silver,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.panda_setup_auth_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = Steel,
                )
            }
        }
    } else {
        // Failed to start — show retry
        PandaButton(
            onClick = { viewModel.startOAuth() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.panda_setup_retry))
        }
    }
}

@Composable
private fun ApiKeySection(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
    entryFocusRequester: FocusRequester? = null,
) {
    Column {
        PandaEditableOutlinedTextField(
            value = state.apiKeyInput,
            onValueChange = { viewModel.setApiKeyInput(it) },
            placeholder = { Text(stringResource(R.string.panda_setup_auth_enter_key)) },
            modifier = Modifier.fillMaxWidth(),
            entryFocusRequester = entryFocusRequester,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Amber,
                unfocusedBorderColor = Steel,
                cursorColor = Amber,
                focusedTextColor = Snow,
                unfocusedTextColor = Snow,
            ),
        )
        Spacer(Modifier.height(12.dp))
        PandaButton(
            onClick = { viewModel.validateApiKey() },
            enabled = state.apiKeyInput.isNotBlank() && !state.authLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.authLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.panda_setup_auth_validating),
                )
            } else {
                Text(
                    stringResource(R.string.panda_setup_auth_validate),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
