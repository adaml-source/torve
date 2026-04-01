package com.torve.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun IntegrationsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val secretStore: IntegrationSecretStore = koinInject()
    val authClient: com.torve.data.auth.AuthClient = koinInject()
    val authUser by authClient.authUserFlow.collectAsState()
    val accountSessionCoordinator: com.torve.presentation.session.AccountSessionCoordinator = koinInject()
    val scope = rememberCoroutineScope()
    val defaultStorageMode = if (authUser != null) com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT
        else com.torve.domain.integrations.IntegrationStorageMode.DEVICE_ONLY
    var omdbStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }
    var jellyfinStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }
    var plexStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }

    LaunchedEffect(Unit) {
        secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY)?.let { stored ->
            viewModel.setJellyfinApiKey(stored)
        }
        viewModel.loadJellyfinProfiles()
        secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN)?.let { stored ->
            viewModel.setPlexAccessToken(stored)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackButton(onClick = onBack)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.integrations_title),
            style = MaterialTheme.typography.titleLarge,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        com.torve.android.ui.components.StorageModeExplainer()
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.integrations_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = Silver,
        )

        Spacer(Modifier.height(16.dp))

        // ── OMDB ──
        IntegrationCard(
            title = "OMDB",
            description = stringResource(R.string.integrations_omdb_desc),
        ) {
            IntegrationTextField(
                label = stringResource(R.string.settings_api_key),
                value = state.omdbApiKey,
                onValueChange = { viewModel.updateOmdbApiKeyInput(it) },
                isSensitive = true,
            )
            Spacer(Modifier.height(4.dp))
            com.torve.android.ui.components.StorageModeSelector(
                selected = omdbStorageMode,
                onModeSelected = { omdbStorageMode = it },
                isSignedIn = authUser != null,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.saveAndValidateOmdbApiKey()
                        if (omdbStorageMode == com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT) {
                            scope.launch {
                                accountSessionCoordinator.saveIntegrationToBackend(
                                    integrationType = "OMDB_API_KEY",
                                    credentials = mapOf("api_key" to state.omdbApiKey),
                                    displayIdentifier = "OMDB",
                                )
                            }
                        }
                    },
                    enabled = !state.omdbValidating && state.omdbApiKey.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                ) {
                    if (state.omdbValidating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Obsidian, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.omdbValidating) stringResource(R.string.integrations_testing) else stringResource(R.string.integrations_test_connection))
                }
                state.omdbValidationResult?.let { result ->
                    when (result) {
                        "valid" -> Text(stringResource(R.string.integrations_status_connected), color = Emerald, style = MaterialTheme.typography.bodySmall)
                        "invalid" -> Text(stringResource(R.string.integrations_key_invalid), color = Ruby, style = MaterialTheme.typography.bodySmall)
                        else -> Text(result, color = Ruby, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.integrations_omdb_hint),
                color = Silver.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Trakt ──
        IntegrationCard(
            title = "Trakt",
            description = stringResource(R.string.integrations_trakt_desc),
        ) {
            Text(
                if (state.traktConnected) stringResource(R.string.integrations_status_connected) else stringResource(R.string.integrations_status_not_connected),
                color = if (state.traktConnected) Snow else Silver,
                style = MaterialTheme.typography.bodySmall,
            )
            state.traktLastSyncTime?.let { lastSync ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.integrations_last_sync, java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(lastSync))),
                    color = Silver,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.traktDeviceCode?.let { code ->
                Spacer(Modifier.height(8.dp))
                DeviceCodeSection(
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (!state.traktConnected) {
                    Button(
                        onClick = { viewModel.startTraktDeviceAuth() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.traktLoading) stringResource(R.string.integrations_connecting) else stringResource(R.string.common_connect)) }
                } else {
                    Button(
                        onClick = { viewModel.syncTraktNow() },
                        enabled = !state.traktSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) {
                        if (state.traktSyncing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Obsidian,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.integrations_syncing))
                        } else if (state.traktSyncSuccess) {
                            Text(stringResource(R.string.integrations_sync_complete))
                        } else {
                            Text(stringResource(R.string.integrations_sync_now))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { viewModel.disconnectTrakt() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver),
                    ) { Text(stringResource(R.string.common_disconnect)) }
                }
            }
            state.traktError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── SIMKL ──
        IntegrationCard(
            title = "SIMKL",
            description = stringResource(R.string.integrations_simkl_desc),
        ) {
            Text(
                if (state.simklConnected) stringResource(R.string.integrations_status_connected) + (state.simklUser?.username?.let { " ($it)" } ?: "") else stringResource(R.string.integrations_status_not_connected),
                color = if (state.simklConnected) Snow else Silver,
                style = MaterialTheme.typography.bodySmall,
            )
            state.simklDeviceCode?.let { code ->
                Spacer(Modifier.height(8.dp))
                DeviceCodeSection(
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (!state.simklConnected) {
                    Button(
                        onClick = { viewModel.startSimklDeviceAuth() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.simklLoading) stringResource(R.string.integrations_connecting) else stringResource(R.string.common_connect)) }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.disconnectSimkl() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver),
                    ) { Text(stringResource(R.string.common_disconnect)) }
                }
            }
            state.simklError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Jellyfin ──
        IntegrationCard(
            title = "Jellyfin",
            description = stringResource(R.string.integrations_jellyfin_desc),
        ) {
            IntegrationTextField(
                label = stringResource(R.string.integrations_server_url),
                value = state.jellyfinServerUrl,
                onValueChange = viewModel::setJellyfinServerUrl,
            )
            Spacer(Modifier.height(8.dp))
            IntegrationTextField(
                label = stringResource(R.string.settings_api_key),
                value = state.jellyfinApiKey,
                onValueChange = { viewModel.updateJellyfinApiKeyInput(it) },
                isSensitive = true,
            )
            Spacer(Modifier.height(4.dp))
            com.torve.android.ui.components.StorageModeSelector(
                selected = jellyfinStorageMode,
                onModeSelected = { jellyfinStorageMode = it },
                isSignedIn = authUser != null,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    viewModel.saveAndTestJellyfinConnection()
                    if (jellyfinStorageMode == com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT) {
                        scope.launch {
                            accountSessionCoordinator.saveIntegrationToBackend(
                                integrationType = "JELLYFIN_API_KEY",
                                credentials = mapOf("api_key" to state.jellyfinApiKey),
                                displayIdentifier = "Jellyfin",
                                config = mapOf("server_url" to state.jellyfinServerUrl),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text(stringResource(R.string.integrations_test_connection))
            }
            state.jellyfinStatusMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Silver, style = MaterialTheme.typography.bodySmall)
            }

            // Profile selector (visible after successful connection)
            if (state.jellyfinProfiles.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.integrations_user_profile), color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                state.jellyfinProfiles.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectJellyfinProfile(profile.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.selectedJellyfinUserId == profile.id,
                            onClick = { viewModel.selectJellyfinProfile(profile.id) },
                            colors = RadioButtonDefaults.colors(selectedColor = Amber, unselectedColor = Silver),
                        )
                        Text(
                            text = profile.name + if (profile.isAdmin) " (" + stringResource(R.string.integrations_admin) + ")" else "",
                            color = Snow,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Plex ──
        IntegrationCard(
            title = "Plex",
            description = stringResource(R.string.integrations_plex_desc),
        ) {
            IntegrationTextField(
                label = stringResource(R.string.integrations_plex_url_hint),
                value = state.plexServerUrl,
                onValueChange = viewModel::setPlexServerUrl,
            )
            Spacer(Modifier.height(8.dp))
            IntegrationTextField(
                label = stringResource(R.string.integrations_access_token),
                value = state.plexAccessToken,
                onValueChange = { viewModel.updatePlexAccessTokenInput(it) },
                isSensitive = true,
            )
            Spacer(Modifier.height(4.dp))
            com.torve.android.ui.components.StorageModeSelector(
                selected = plexStorageMode,
                onModeSelected = { plexStorageMode = it },
                isSignedIn = authUser != null,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.integrations_plex_token_hint),
                color = Silver.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.saveAndConnectPlex()
                    if (plexStorageMode == com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT) {
                        scope.launch {
                            accountSessionCoordinator.saveIntegrationToBackend(
                                integrationType = "PLEX_ACCESS_TOKEN",
                                credentials = mapOf("access_token" to state.plexAccessToken),
                                displayIdentifier = "Plex",
                                config = mapOf("server_url" to state.plexServerUrl),
                            )
                        }
                    }
                },
                enabled = state.plexAccessToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
            ) {
                Text(stringResource(R.string.integrations_save_connect))
            }
            Spacer(Modifier.height(8.dp))

            if (state.plexConnected) {
                Text(
                    stringResource(R.string.integrations_status_connected),
                    color = Snow,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
            }

            state.plexError?.let {
                Text(it, color = Silver, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }

            Row {
                if (!state.plexConnected) {
                    Button(
                        onClick = { viewModel.testPlexConnection() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.plexLoading) stringResource(R.string.integrations_testing) else stringResource(R.string.common_connect)) }
                } else {
                    Button(
                        onClick = { viewModel.testPlexConnection() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(stringResource(R.string.integrations_test_connection)) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { viewModel.disconnectPlex() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver),
                    ) { Text(stringResource(R.string.common_disconnect)) }
                }
            }
        }
    }
}

/** Reusable device-code auth section: clickable URL, copyable code, open-in-browser button. */
@Composable
private fun DeviceCodeSection(
    userCode: String,
    verificationUrl: String,
) {
    val context = LocalContext.current

    // Clickable verification URL
    Text(
        text = verificationUrl,
        color = Amber,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)))
        },
    )

    Spacer(Modifier.height(8.dp))

    // Code with copy button
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Gunmetal, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(stringResource(R.string.integrations_your_code), style = MaterialTheme.typography.bodySmall, color = Silver)
            Text(
                text = userCode,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
                letterSpacing = MaterialTheme.typography.titleLarge.letterSpacing,
            )
        }
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Device Code", userCode))
                Toast.makeText(context, context.getString(R.string.integrations_code_copied), Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.diagnostics_copy),
                tint = Amber,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // Open in browser button
    Button(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)))
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
    ) {
        Icon(
            Icons.Default.OpenInBrowser,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.integrations_open_browser))
    }
}

@Composable
private fun IntegrationCard(
    title: String,
    description: String,
    content: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Snow, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = Silver, style = MaterialTheme.typography.bodySmall)
            if (content != null) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun IntegrationTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSensitive: Boolean = false,
) {
    var revealed by remember { mutableStateOf(false) }
    val peekTransformation = com.torve.android.ui.components.rememberPeekPasswordTransformation(value)
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow),
            cursorBrush = SolidColor(Amber),
            visualTransformation = if (isSensitive && !revealed) peekTransformation else VisualTransformation.None,
            keyboardOptions = if (isSensitive) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Gunmetal, RoundedCornerShape(8.dp))
                        .padding(start = 12.dp, end = if (isSensitive) 4.dp else 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isBlank()) {
                            Text(label, color = Torve.colors.textHint, style = MaterialTheme.typography.bodySmall)
                        }
                        inner()
                    }
                    if (isSensitive && value.isNotEmpty()) {
                        IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (revealed) "Hide" else "Show",
                                tint = Silver,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}
