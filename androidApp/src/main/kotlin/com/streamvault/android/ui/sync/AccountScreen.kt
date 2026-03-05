package com.streamvault.android.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.streamvault.android.sync.SyncCoordinator
import org.koin.compose.koinInject

@Composable
fun AccountScreen(
    onOpenDevices: () -> Unit,
    onBack: () -> Unit,
    syncCoordinator: SyncCoordinator = koinInject(),
) {
    val state by syncCoordinator.state.collectAsState()
    var profileName by remember { mutableStateOf(state.profileName.orEmpty()) }
    var pin by remember { mutableStateOf("") }
    var repeatPin by remember { mutableStateOf("") }

    LaunchedEffect(state.profileName, state.isAuthenticated) {
        if (state.isAuthenticated) {
            profileName = state.profileName.orEmpty()
            pin = ""
            repeatPin = ""
        }
    }

    val hasPinInput = pin.isNotBlank() || repeatPin.isNotBlank()
    val pinValid = pin.isEmpty() || pin.matches(Regex("^[0-9]{4,8}$"))
    val pinsMatch = pin == repeatPin
    val canCreateProfile = profileName.trim().isNotBlank() && pinValid && pinsMatch && !state.isLoading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Local Profile",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Installation ID: ${syncCoordinator.installationId()}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Sync mode: Local Wi-Fi",
            style = MaterialTheme.typography.bodySmall,
        )

        if (state.isAuthenticated) {
            Text(
                text = "Profile: ${state.profileName ?: state.userEmail ?: "Unnamed"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (state.hasPin) "PIN protection enabled" else "No PIN set",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenDevices) {
                    Text("Pair TV")
                }
                OutlinedButton(onClick = { syncCoordinator.clearLocalProfile() }) {
                    Text("Remove Profile")
                }
            }
        } else {
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("Profile Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(8) },
                label = { Text("PIN (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = repeatPin,
                onValueChange = { repeatPin = it.filter { ch -> ch.isDigit() }.take(8) },
                label = { Text("Repeat PIN") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Text(
                text = "PIN is optional. If used, choose 4 to 8 digits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasPinInput && !pinValid) {
                Text(
                    text = "PIN must be 4 to 8 digits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (hasPinInput && !pinsMatch) {
                Text(
                    text = "PIN entries do not match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
            Button(
                onClick = {
                    syncCoordinator.createLocalProfile(
                        profileName = profileName.trim(),
                        pin = pin.takeIf { it.isNotBlank() },
                    )
                },
                enabled = canCreateProfile,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Create Local Profile")
            }
            OutlinedButton(
                onClick = onOpenDevices,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Pair TV")
            }
        }

        if (state.isLoading) {
            CircularProgressIndicator()
        }
        state.error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) {
            Text("Back")
        }
    }
}
