package com.streamvault.android.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.StreamQuality
import com.streamvault.presentation.setup.SetupStep
import com.streamvault.presentation.setup.SetupUiState
import com.streamvault.presentation.setup.SetupWizardViewModel

@Composable
fun SetupWizardScreen(
    viewModel: SetupWizardViewModel,
    onComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    val stepIndex = SetupStep.entries.indexOf(state.currentStep)
    val totalSteps = SetupStep.entries.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Progress indicator
        if (state.currentStep != SetupStep.WELCOME && state.currentStep != SetupStep.DONE) {
            LinearProgressIndicator(
                progress = { (stepIndex.toFloat()) / (totalSteps - 1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Step $stepIndex of ${totalSteps - 2}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }

        // Step content
        AnimatedContent(
            targetState = state.currentStep,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            },
            label = "step",
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep()
                    SetupStep.DEBRID -> DebridStep(state, viewModel)
                    SetupStep.TRAKT -> TraktStep(state, viewModel)
                    SetupStep.QUALITY -> QualityStep(state, viewModel)
                    SetupStep.IPTV -> IptvStep(state, viewModel)
                    SetupStep.DONE -> DoneStep()
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.currentStep != SetupStep.WELCOME && state.currentStep != SetupStep.DONE) {
                OutlinedButton(onClick = { viewModel.previousStep() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Back")
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            when (state.currentStep) {
                SetupStep.WELCOME -> {
                    Button(onClick = { viewModel.nextStep() }) {
                        Text("Get Started")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
                SetupStep.DONE -> {
                    Button(onClick = {
                        viewModel.completeSetup()
                        onComplete()
                    }) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Start Streaming")
                    }
                }
                SetupStep.DEBRID, SetupStep.TRAKT, SetupStep.IPTV -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.skipStep() }) {
                            Text("Skip")
                        }
                        Button(onClick = { viewModel.nextStep() }) {
                            Text("Next")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
                SetupStep.QUALITY -> {
                    Button(onClick = { viewModel.nextStep() }) {
                        Text("Next")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "StreamVault",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your all-in-one streaming companion",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        val features = listOf(
            "Stream movies & TV shows from multiple sources",
            "Debrid service integration for fast, cached streams",
            "Live TV with IPTV/M3U playlist support",
            "Download content for offline viewing",
            "Track your watchlist with Trakt.tv",
        )
        features.forEach { feature ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Check,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(feature, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Let's set things up in a few quick steps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebridStep(state: SetupUiState, viewModel: SetupWizardViewModel) {
    Column {
        Text("Debrid Service", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "A debrid service provides fast, cached downloads. Connect one to get the best streaming experience.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // Provider selector
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = state.debridProvider.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DebridServiceType.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.label) },
                        onClick = {
                            viewModel.setDebridProvider(provider)
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.debridApiKey,
            onValueChange = { viewModel.setDebridApiKey(it) },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        if (state.debridConnected) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Connected successfully!", fontWeight = FontWeight.Medium)
                }
            }
        } else {
            FilledTonalButton(
                onClick = { viewModel.connectDebrid() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.debridApiKey.isNotBlank() && !state.debridLoading,
            ) {
                if (state.debridLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Verify & Connect")
            }
        }

        state.debridError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TraktStep(state: SetupUiState, viewModel: SetupWizardViewModel) {
    Column {
        Text("Trakt.tv", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect Trakt to sync your watchlist, track progress, and get personalized recommendations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.traktClientId,
            onValueChange = { viewModel.setTraktClientId(it) },
            label = { Text("Client ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.traktClientSecret,
            onValueChange = { viewModel.setTraktClientSecret(it) },
            label = { Text("Client Secret") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "You can get these from trakt.tv/oauth/applications",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityStep(state: SetupUiState, viewModel: SetupWizardViewModel) {
    Column {
        Text("Quality Preferences", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Set your preferred stream quality. You can change this later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = state.maxQuality.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Max Quality") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                StreamQuality.selectable.forEach { quality ->
                    DropdownMenuItem(
                        text = { Text(quality.label) },
                        onClick = {
                            viewModel.setMaxQuality(quality)
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Cached Only", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Only show debrid-cached streams for instant playback",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.cachedOnly,
                onCheckedChange = { viewModel.setCachedOnly(it) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IptvStep(state: SetupUiState, viewModel: SetupWizardViewModel) {
    val isXtream = state.iptvPlaylistType == "xtream"

    Column {
        Text("IPTV / Live TV", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add an M3U playlist or Xtream Codes account to watch live TV channels. You can add more later in the IPTV tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // Type selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !isXtream,
                onClick = { viewModel.setIptvPlaylistType("m3u") },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("M3U") }
            SegmentedButton(
                selected = isXtream,
                onClick = { viewModel.setIptvPlaylistType("xtream") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Xtream Codes") }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.iptvPlaylistName,
            onValueChange = { viewModel.setIptvPlaylistName(it) },
            label = { Text("Playlist Name") },
            placeholder = { Text("e.g. My IPTV") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))

        if (isXtream) {
            OutlinedTextField(
                value = state.iptvXtreamServer,
                onValueChange = { viewModel.setIptvXtreamServer(it) },
                label = { Text("Server URL") },
                placeholder = { Text("http://example.com:8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.iptvXtreamUsername,
                onValueChange = { viewModel.setIptvXtreamUsername(it) },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.iptvXtreamPassword,
                onValueChange = { viewModel.setIptvXtreamPassword(it) },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        } else {
            OutlinedTextField(
                value = state.iptvPlaylistUrl,
                onValueChange = { viewModel.setIptvPlaylistUrl(it) },
                label = { Text("M3U Playlist URL") },
                placeholder = { Text("https://example.com/playlist.m3u") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun DoneStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Check,
            null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "You're All Set!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "StreamVault is ready. You can adjust settings anytime from the Settings tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
