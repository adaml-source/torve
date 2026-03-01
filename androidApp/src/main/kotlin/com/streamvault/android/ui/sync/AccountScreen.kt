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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Account",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Installation ID: ${syncCoordinator.installationId()}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Realtime: ${state.wsStatus}",
            style = MaterialTheme.typography.bodySmall,
        )

        if (state.isAuthenticated) {
            Text(
                text = "Signed in as ${state.userEmail ?: "unknown"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenDevices) {
                    Text("Devices")
                }
                OutlinedButton(onClick = { syncCoordinator.logout() }) {
                    Text("Logout")
                }
            }
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { syncCoordinator.login(email, password) },
                    enabled = email.isNotBlank() && password.length >= 8 && !state.isLoading,
                ) {
                    Text("Sign In")
                }
                OutlinedButton(
                    onClick = { syncCoordinator.register(email, password) },
                    enabled = email.isNotBlank() && password.length >= 8 && !state.isLoading,
                ) {
                    Text("Register")
                }
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

