package com.torve.android.ui.setup

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
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
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.domain.model.StreamQuality
import com.torve.presentation.setup.SetupStep
import com.torve.presentation.setup.SetupUiState
import com.torve.presentation.setup.SetupWizardViewModel

@Composable
fun SetupWizardScreen(
    viewModel: SetupWizardViewModel,
    onComplete: () -> Unit,
    onExit: () -> Unit = {},
    onPandaSetupClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    val stepIndex = SetupStep.entries.indexOf(state.currentStep)
    val totalSteps = SetupStep.entries.size

    // Wrap the wizard body in a Surface(background) so default text
    // colors resolve to colorScheme.onBackground (light on dark theme)
    // instead of falling back to black. Without this, every Text() that
    // doesn't pass an explicit `color =` renders in the default content
    // color of "no Surface", which is black — invisible against the
    // NavHost's transparent backdrop. This is why the welcome
    // bullet labels and the terms-accept checkbox label rendered as
    // empty rows on Pixel/dark devices.
    //
    // Insets pad the Close-setup button (status bar) and Next button
    // (gesture bar) on edge-to-edge displays.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onExit) {
                Text("Close setup")
            }
        }

        // Progress indicator
        if (state.currentStep != SetupStep.WELCOME && state.currentStep != SetupStep.DONE) {
            LinearProgressIndicator(
                progress = { (stepIndex.toFloat()) / (totalSteps - 1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setup_step_of, stepIndex, totalSteps - 2),
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
                    SetupStep.DEBRID -> DebridStep(state, viewModel, onPandaSetupClick)
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
                    Text(stringResource(R.string.common_back))
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            when (state.currentStep) {
                SetupStep.WELCOME -> {
                    Button(onClick = { viewModel.nextStep() }) {
                        Text(stringResource(R.string.setup_get_started))
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
                        Text(stringResource(R.string.setup_start_streaming))
                    }
                }
                SetupStep.TERMS -> {
                    Button(
                        onClick = { viewModel.nextStep() },
                        enabled = state.termsAccepted,
                    ) {
                        Text(stringResource(R.string.setup_i_agree))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
                SetupStep.DEBRID, SetupStep.TRAKT, SetupStep.CHANNELS -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.skipStep() }) {
                            Text(stringResource(R.string.common_skip))
                        }
                        Button(onClick = { viewModel.nextStep() }) {
                            Text(stringResource(R.string.common_next))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
                SetupStep.QUALITY -> {
                    Button(onClick = { viewModel.nextStep() }) {
                        Text(stringResource(R.string.common_next))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    } // end Surface
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_welcome_subtitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        val features = listOf(
            stringResource(R.string.setup_feature_stream),
            stringResource(R.string.setup_feature_cloud),
            stringResource(R.string.setup_feature_channels),
            stringResource(R.string.setup_feature_download),
            stringResource(R.string.setup_feature_trakt),
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
            stringResource(R.string.setup_lets_go),
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
            stringResource(R.string.setup_terms_title),
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
                text = stringResource(R.string.setup_terms_text),
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
                text = stringResource(R.string.setup_tmdb_text),
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
                stringResource(R.string.setup_accept_terms),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DebridStep(state: SetupUiState, viewModel: SetupWizardViewModel, onPandaSetupClick: () -> Unit = {}) {
    Column {
        Text(stringResource(R.string.settings_cloud_service), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_cloud_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                // Pin content color so the title + description below render
                // against the amber background with proper contrast. Without
                // this, descendants pick up the screen's onBackground (white)
                // which works for the title but the inner Text that uses
                // onSurfaceVariant lands as a muted gray that's nearly
                // invisible on amber.
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.setup_panda_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.setup_panda_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onPandaSetupClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.setup_panda_open_setup))
                }
            }
        }

        state.debridConnected.takeIf { it }?.let {
            Spacer(Modifier.height(16.dp))
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
                    Text(stringResource(R.string.setup_connected_success), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun TraktStep(state: SetupUiState, viewModel: SetupWizardViewModel) {
    Column {
        Text(stringResource(R.string.settings_trakt), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_trakt_desc),
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
                            Text(stringResource(R.string.setup_trakt_connected), fontWeight = FontWeight.Medium)
                            state.traktUsername?.let { username ->
                                Text(
                                    stringResource(R.string.setup_logged_in_as, username),
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
                            stringResource(R.string.setup_go_to),
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
                            stringResource(R.string.setup_enter_code),
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
                                stringResource(R.string.setup_waiting_auth),
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
                    Text(stringResource(R.string.setup_connect_trakt))
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.setup_trakt_hint),
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
        Text(stringResource(R.string.setup_quality_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_quality_desc),
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
                label = { Text(stringResource(R.string.settings_max_quality)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
                Text(stringResource(R.string.settings_cached_only), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.settings_cached_only_desc),
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
        Text(stringResource(R.string.setup_channels_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_channels_desc),
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
            ) { Text(stringResource(R.string.setup_m3u)) }
            SegmentedButton(
                selected = isXtream,
                onClick = { viewModel.setChannelPlaylistType("xtream") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.setup_provider_login)) }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.channelPlaylistName,
            onValueChange = { viewModel.setChannelPlaylistName(it) },
            label = { Text(stringResource(R.string.channels_playlist_name)) },
            placeholder = { Text(stringResource(R.string.setup_playlist_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))

        if (isXtream) {
            OutlinedTextField(
                value = state.channelXtreamServer,
                onValueChange = { viewModel.setChannelXtreamServer(it) },
                label = { Text(stringResource(R.string.channels_server_url)) },
                placeholder = { Text(stringResource(R.string.setup_server_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.channelXtreamUsername,
                onValueChange = { viewModel.setChannelXtreamUsername(it) },
                label = { Text(stringResource(R.string.channels_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.channelXtreamPassword,
                onValueChange = { viewModel.setChannelXtreamPassword(it) },
                label = { Text(stringResource(R.string.channels_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        } else {
            OutlinedTextField(
                value = state.channelPlaylistUrl,
                onValueChange = { viewModel.setChannelPlaylistUrl(it) },
                label = { Text(stringResource(R.string.channels_m3u_url)) },
                placeholder = { Text(stringResource(R.string.setup_m3u_hint)) },
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
            stringResource(R.string.setup_done_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_done_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
