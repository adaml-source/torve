package com.torve.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRole
import com.torve.domain.integrations.AutomationServiceType
import com.torve.presentation.integrations.AutomationSettingsViewModel
import org.koin.compose.koinInject

@Composable
fun AutomationConnectionsScreen(
    onBack: () -> Unit,
    viewModel: AutomationSettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var pendingRemoval by remember { mutableStateOf<AutomationInstance?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Snow)
            }
            Column(Modifier.weight(1f)) {
                Text("Automation connections", color = Snow, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("API keys are encrypted on this device", color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }

        state.instances.forEach { instance ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Gunmetal),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(instance.name, color = Snow, fontWeight = FontWeight.SemiBold)
                        Text("${serviceLabel(instance.serviceType)} · ${instance.serverUrl}", color = Silver, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { viewModel.edit(instance) }) { Text("Edit", color = Amber) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pendingRemoval = instance }) { Text("Remove", color = Ruby) }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Gunmetal.copy(alpha = 0.86f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (state.editingId == null) "Add connection" else "Edit connection", color = Snow, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AutomationServiceType.entries.forEach { type ->
                        if (state.serviceType == type) {
                            Button(onClick = { viewModel.selectService(type) }, colors = adminConnectionButtonColors()) { Text(serviceLabel(type)) }
                        } else {
                            OutlinedButton(onClick = { viewModel.selectService(type) }) { Text(serviceLabel(type), color = Snow) }
                        }
                    }
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Connection name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::updateServerUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server URL, including http(s) and port") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (state.serviceType == AutomationServiceType.TDARR) "Optional API key" else "API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (state.serviceType == AutomationServiceType.SONARR || state.serviceType == AutomationServiceType.RADARR) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutomationInstanceRole.entries.forEach { role ->
                            val label = if (role == AutomationInstanceRole.UHD) "4K" else "Standard"
                            if (state.role == role) {
                                Button(onClick = { viewModel.selectRole(role) }, colors = adminConnectionButtonColors()) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { viewModel.selectRole(role) }) { Text(label, color = Snow) }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.setDefault(!state.isDefault) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.isDefault, onCheckedChange = viewModel::setDefault)
                        Text("Default for this quality role", color = Snow)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::saveAndTest,
                        enabled = !state.isBusy,
                        colors = adminConnectionButtonColors(),
                    ) {
                        if (state.isBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Obsidian, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save and test")
                    }
                    if (state.editingId != null) {
                        OutlinedButton(onClick = viewModel::cancelEdit) { Text("Cancel", color = Snow) }
                    }
                }
                state.message?.let { Text(it, color = Emerald) }
                state.error?.let { Text(it, color = Ruby) }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    pendingRemoval?.let { instance ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${instance.name}?") },
            text = { Text("The connection metadata and encrypted API key will be deleted from this device.") },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    viewModel.remove(instance)
                }) { Text("Remove", color = Ruby) }
            },
        )
    }
}

@Composable
private fun adminConnectionButtonColors() = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian)

private fun serviceLabel(type: AutomationServiceType): String =
    type.name.lowercase().replaceFirstChar { it.uppercase() }
