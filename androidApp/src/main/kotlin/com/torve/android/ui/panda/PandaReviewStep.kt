package com.torve.android.ui.panda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
fun PandaReviewStep(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
    onComplete: () -> Unit,
    entryFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.panda_setup_review_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(20.dp))

        // Summary card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Gunmetal)
                .padding(16.dp),
        ) {
            ReviewRow(
                label = stringResource(R.string.panda_setup_review_provider),
                value = state.selectedProvider?.name ?: "â€”",
            )
            HorizontalDivider(color = Steel.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))
            ReviewRow(
                label = stringResource(R.string.panda_setup_review_auth),
                value = if (state.authConnected) stringResource(R.string.panda_setup_auth_connected) else "â€”",
            )
            HorizontalDivider(color = Steel.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))
            ReviewRow(
                label = stringResource(R.string.panda_setup_quality_max),
                value = state.maxQuality,
            )
            HorizontalDivider(color = Steel.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))
            ReviewRow(
                label = stringResource(R.string.panda_setup_quality_profile),
                value = state.qualityProfile.replace("_", " ").replaceFirstChar { it.uppercase() },
            )
            HorizontalDivider(color = Steel.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))
            ReviewRow(
                label = stringResource(R.string.panda_setup_quality_language),
                value = state.releaseLanguage.replaceFirstChar { it.uppercase() },
            )
            if (state.enableUsenet) {
                HorizontalDivider(color = Steel.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))
                ReviewRow(
                    label = stringResource(R.string.panda_setup_review_usenet),
                    value = when (state.usenetProvider) {
                        "easynews" -> "Easynews"
                        "generic" -> "Generic NNTP"
                        else -> "Disabled"
                    },
                )
                if (state.usenetProvider == "generic" && state.nzbIndexer != "none") {
                    HorizontalDivider(color = Steel.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))
                    ReviewRow(
                        label = stringResource(R.string.panda_setup_review_indexer),
                        value = when (state.nzbIndexer) {
                            "nzbgeek" -> "NZBgeek"
                            "scenenzbs" -> "SceneNZBs"
                            "dognzb" -> "DogNZB"
                            "nzbplanet" -> "NZBPlanet"
                            "custom" -> "Custom"
                            else -> state.nzbIndexer
                        },
                    )
                }
                if (state.usenetProvider == "generic" && state.downloadClient != "none") {
                    HorizontalDivider(color = Steel.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))
                    ReviewRow(
                        label = stringResource(R.string.panda_setup_review_client),
                        value = when (state.downloadClient) {
                            "nzbget" -> "NZBget"
                            "sabnzbd" -> "SABnzbd"
                            else -> state.downloadClient
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (state.addonInstalled) {
            // Success state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Amber.copy(alpha = 0.15f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Check, null, tint = Amber, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.panda_setup_review_success),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                )
            }
            // Management token is returned exactly once by the server. Surface it
            // here as an "advanced" card so power users can capture it without
            // gating the happy path for casuals.
            if (!state.pendingManagementTokenDisplay.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                PandaManagementTokenCard(
                    token = state.pendingManagementTokenDisplay,
                    notice = state.managementTokenNotice,
                    onAcknowledge = { viewModel.acknowledgeManagementTokenDisplay() },
                )
            }
            Spacer(Modifier.height(16.dp))
            val doneInteractionSource = remember { MutableInteractionSource() }
            val doneFocused by doneInteractionSource.collectIsFocusedAsState()
            val doneShape = RoundedCornerShape(12.dp)
            Button(
                onClick = onComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .clip(doneShape)
                    .border(
                        width = if (doneFocused) 2.dp else 1.dp,
                        color = if (doneFocused) Snow else Amber.copy(alpha = 0.35f),
                        shape = doneShape,
                    ),
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
                shape = doneShape,
                interactionSource = doneInteractionSource,
            ) {
                Text(
                    stringResource(R.string.panda_setup_review_done),
                    color = Gunmetal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            // Edit mode without a local management token â€” saving would fail.
            // Route the user to the recovery flow in Manage Panda.
            if (state.isEditMode && state.editRequiresRecovery) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ruby.copy(alpha = 0.12f))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.panda_setup_review_token_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ruby,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            // Save button
            val saveInteractionSource = remember { MutableInteractionSource() }
            val saveFocused by saveInteractionSource.collectIsFocusedAsState()
            val saveShape = RoundedCornerShape(12.dp)
            Button(
                onClick = { viewModel.saveConfigAndInstall() },
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .clip(saveShape)
                    .border(
                        width = if (saveFocused) 2.dp else 1.dp,
                        color = if (saveFocused) Snow else Amber.copy(alpha = 0.35f),
                        shape = saveShape,
                    ),
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
                shape = saveShape,
                interactionSource = saveInteractionSource,
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Gunmetal,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.panda_setup_review_saving),
                        color = Gunmetal,
                    )
                } else {
                    Text(
                        stringResource(
                            if (state.isEditMode) R.string.panda_setup_review_update
                            else R.string.panda_setup_review_save,
                        ),
                        color = Gunmetal,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Error
            state.saveError?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(error, color = Ruby, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Silver)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Snow)
    }
}
