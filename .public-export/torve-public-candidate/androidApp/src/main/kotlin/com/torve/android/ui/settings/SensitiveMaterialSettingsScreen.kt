package com.torve.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.presentation.contentpolicy.SensitiveMaterialSettingsStep
import com.torve.presentation.contentpolicy.SensitiveMaterialSettingsViewModel
import org.koin.compose.koinInject

@Composable
fun SensitiveMaterialSettingsScreen(
    onBack: () -> Unit,
    viewModel: SensitiveMaterialSettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearNotice()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackButton(onClick = onBack)
            Text(
                text = stringResource(R.string.settings_sensitive_material),
                style = MaterialTheme.typography.headlineSmall,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.sensitive_material_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.statusSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textSecondary,
                )
                state.notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (state.step) {
            SensitiveMaterialSettingsStep.OVERVIEW -> SensitiveMaterialOverviewStep(
                enabled = state.policy.adultEnabled,
                canEnable = state.canOfferEnableAction,
                isWorking = state.isWorking,
                onEnable = viewModel::beginEnableFlow,
                onDisable = viewModel::disableSensitive,
            )

            SensitiveMaterialSettingsStep.ENTER_DOB -> SensitiveMaterialDobStep(
                value = state.dobInput,
                isWorking = state.isWorking,
                onValueChange = viewModel::updateDobInput,
                onCancel = viewModel::cancelFlow,
                onSubmit = viewModel::submitDob,
            )

            SensitiveMaterialSettingsStep.CONFIRM_ENABLE -> SensitiveMaterialConfirmStep(
                isWorking = state.isWorking,
                onBack = viewModel::cancelFlow,
                onConfirm = viewModel::confirmEnable,
            )
        }
    }
}

@Composable
private fun SensitiveMaterialOverviewStep(
    enabled: Boolean,
    canEnable: Boolean,
    isWorking: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sensitive_material_overview_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Torve.colors.textSecondary,
            )
            if (enabled) {
                Button(
                    onClick = onDisable,
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.sensitive_material_disable))
                    }
                }
            } else if (canEnable) {
                Button(
                    onClick = onEnable,
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sensitive_material_enable))
                }
            } else {
                Text(
                    text = stringResource(R.string.sensitive_material_locked_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun SensitiveMaterialDobStep(
    value: String,
    isWorking: Boolean,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sensitive_material_dob_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Torve.colors.textSecondary,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sensitive_material_dob_label)) },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = !isWorking,
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = onSubmit,
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_apply))
                }
            }
        }
    }
}

@Composable
private fun SensitiveMaterialConfirmStep(
    isWorking: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sensitive_material_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Torve.colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onBack,
                    enabled = !isWorking,
                ) {
                    Text(stringResource(R.string.common_back))
                }
                Button(
                    onClick = onConfirm,
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.sensitive_material_confirm_enable))
                }
            }
        }
    }
}
