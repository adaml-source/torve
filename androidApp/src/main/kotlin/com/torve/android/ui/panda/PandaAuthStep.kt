package com.torve.android.ui.panda

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel

@Composable
fun PandaAuthStep(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
) {
    val provider = state.selectedProvider ?: return
    val context = LocalContext.current
    val supportsOAuth = "oauth" in provider.authMethods

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.panda_setup_auth_title, provider.name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(16.dp))

        // Connected state
        if (state.authConnected) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Amber.copy(alpha = 0.15f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = Amber, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.panda_setup_auth_connected),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Amber,
                        )
                        if (state.existingCredentialDetected) {
                            Text(
                                "Using existing ${provider.name} credentials",
                                style = MaterialTheme.typography.bodySmall,
                                color = Silver,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FocusRingOutlinedButton(
                        text = stringResource(R.string.storage_reauth_action),
                        onClick = { viewModel.reconnectSelectedDebrid() },
                        modifier = Modifier.weight(1f),
                    )
                    FocusRingOutlinedButton(
                        text = stringResource(R.string.common_disconnect),
                        onClick = { viewModel.disconnectSelectedDebrid() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            return
        }

        // Auth method toggle (only if provider supports both)
        if (supportsOAuth) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.authMethod == "oauth",
                    onClick = { viewModel.setAuthMethod("oauth") },
                    label = { Text(stringResource(R.string.panda_setup_auth_oauth)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber.copy(alpha = 0.2f),
                        selectedLabelColor = Amber,
                    ),
                )
                FilterChip(
                    selected = state.authMethod == "apikey",
                    onClick = { viewModel.setAuthMethod("apikey") },
                    label = { Text(stringResource(R.string.panda_setup_auth_apikey)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber.copy(alpha = 0.2f),
                        selectedLabelColor = Amber,
                    ),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (state.authMethod == "oauth" && supportsOAuth) {
            OAuthSection(state, viewModel)
        } else {
            ApiKeySection(state, viewModel)
        }

        // Error
        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(error, color = Ruby, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FocusRingOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.border(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) Amber else Steel.copy(alpha = 0.45f),
            shape = RoundedCornerShape(12.dp),
        ),
        shape = RoundedCornerShape(12.dp),
        interactionSource = interactionSource,
    ) {
        Text(text, color = if (isFocused) Amber else Snow, fontWeight = FontWeight.SemiBold)
    }
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
        if (deviceCode == null && !state.authLoading && !state.authConnected) {
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

            // Code with copy button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { clipboard.setText(AnnotatedString(deviceCode.userCode)) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    deviceCode.userCode,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Amber,
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = Silver,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(deviceCode.userCode))
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deviceCode.verificationUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
            ) {
                Text(
                    stringResource(R.string.panda_setup_auth_open_browser),
                    color = Gunmetal,
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
        OutlinedButton(
            onClick = { viewModel.startOAuth() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.panda_setup_retry), color = Amber)
        }
    }
}

@Composable
private fun ApiKeySection(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
) {
    Column {
        OutlinedTextField(
            value = state.apiKeyInput,
            onValueChange = { viewModel.setApiKeyInput(it) },
            placeholder = { Text(stringResource(R.string.panda_setup_auth_enter_key)) },
            modifier = Modifier.fillMaxWidth(),
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
        Button(
            onClick = { viewModel.validateApiKey() },
            enabled = state.apiKeyInput.isNotBlank() && !state.authLoading,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Amber),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (state.authLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Gunmetal,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.panda_setup_auth_validating),
                    color = Gunmetal,
                )
            } else {
                Text(
                    stringResource(R.string.panda_setup_auth_validate),
                    color = Gunmetal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
