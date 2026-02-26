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
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
                    SetupStep.TERMS -> TermsStep(state, viewModel)
                    SetupStep.DEBRID -> DebridStep(state, viewModel)
                    SetupStep.TRAKT -> TraktStep(state, viewModel)
                    SetupStep.QUALITY -> QualityStep(state, viewModel)
                    SetupStep.CHANNELS -> ChannelsStep(state, viewModel)
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
                SetupStep.TERMS -> {
                    Button(
                        onClick = { viewModel.nextStep() },
                        enabled = state.termsAccepted,
                    ) {
                        Text("I Agree")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
                SetupStep.DEBRID, SetupStep.TRAKT, SetupStep.CHANNELS -> {
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
            text = "Torve",
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
            "Cloud service integration for optimized playback",
            "Live channels with M3U playlist support",
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

@Composable
private fun TermsStep(state: SetupUiState, viewModel: SetupWizardViewModel) {
    Column {
        Text(
            "Terms of Use",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Text(
                text = "Torve is a media organizer and player. You are responsible for ensuring " +
                    "you have the right to access any content through third-party sources you " +
                    "configure. Torve does not host, provide, or control any media content.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Text(
                text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setTermsAccepted(!state.termsAccepted) }
                .padding(vertical = 8.dp),
        ) {
            Checkbox(
                checked = state.termsAccepted,
                onCheckedChange = { viewModel.setTermsAccepted(it) },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "I understand and accept these terms",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebridStep(state: SetupUiState, viewModel: SetupWizardViewModel) {
    Column {
        Text("Cloud Service", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect your premium cloud storage service for optimized streaming and faster playback.",
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

        when {
            state.traktConnected -> {
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
                        Column {
                            Text("Connected to Trakt!", fontWeight = FontWeight.Medium)
                            state.traktUsername?.let { username ->
                                Text(
                                    "Logged in as $username",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            state.traktDeviceCode != null -> {
                val code = state.traktDeviceCode!!
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Go to:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            code.verificationUrl,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Enter this code:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            code.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Waiting for authorization...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            else -> {
                FilledTonalButton(
                    onClick = { viewModel.startTraktAuth() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.traktLoading,
                ) {
                    if (state.traktLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Connect with Trakt.tv")
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "You'll be asked to visit trakt.tv/activate and enter a code to authorize.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.traktError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
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
                    "Only show cached streams for instant playback",
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
private fun ChannelsStep(state: SetupUiState, viewModel: SetupWizardViewModel) {
    val isXtream = state.channelPlaylistType == "xtream"

    Column {
        Text("Channels", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add an M3U playlist or Xtream Codes account to watch live TV channels. You can add more later in the Channels tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // Type selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !isXtream,
                onClick = { viewModel.setChannelPlaylistType("m3u") },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("M3U") }
            SegmentedButton(
                selected = isXtream,
                onClick = { viewModel.setChannelPlaylistType("xtream") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Xtream Codes") }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.channelPlaylistName,
            onValueChange = { viewModel.setChannelPlaylistName(it) },
            label = { Text("Playlist Name") },
            placeholder = { Text("e.g. My Channels") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))

        if (isXtream) {
            OutlinedTextField(
                value = state.channelXtreamServer,
                onValueChange = { viewModel.setChannelXtreamServer(it) },
                label = { Text("Server URL") },
                placeholder = { Text("http://example.com:8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.channelXtreamUsername,
                onValueChange = { viewModel.setChannelXtreamUsername(it) },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.channelXtreamPassword,
                onValueChange = { viewModel.setChannelXtreamPassword(it) },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        } else {
            OutlinedTextField(
                value = state.channelPlaylistUrl,
                onValueChange = { viewModel.setChannelPlaylistUrl(it) },
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
            "Torve is ready. You can adjust settings anytime from the Settings tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
