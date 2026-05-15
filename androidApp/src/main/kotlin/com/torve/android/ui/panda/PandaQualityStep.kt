package com.torve.android.ui.panda

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PandaQualityStep(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
    entryFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.panda_setup_quality_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(20.dp))

        // Max quality
        DropdownField(
            label = stringResource(R.string.panda_setup_quality_max),
            value = state.maxQuality,
            options = state.schema.qualityOptions.map { id -> id to labelForQuality(id) },
            onSelect = { viewModel.setMaxQuality(it) },
            entryFocusRequester = entryFocusRequester,
        )
        Spacer(Modifier.height(16.dp))

        // Quality profile
        DropdownField(
            label = stringResource(R.string.panda_setup_quality_profile),
            value = state.qualityProfile,
            options = state.schema.qualityProfiles.map { id -> id to labelForQualityProfile(id) },
            onSelect = { viewModel.setQualityProfile(it) },
        )
        Spacer(Modifier.height(16.dp))

        // Release language — multi-select chips. "any" is exclusive with specific
        // languages; toggling handled in PandaSetupViewModel.toggleLanguage.
        Text(
            stringResource(R.string.panda_setup_quality_language),
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
        )
        Spacer(Modifier.height(6.dp))
        LanguageChips(
            available = state.schema.releaseLanguages,
            selected = state.releaseLanguages,
            onToggle = { code, sel -> viewModel.toggleLanguage(code, sel) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LanguageChips(
    available: List<String>,
    selected: List<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    val selectedSet = selected.toSet()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        available.forEach { code ->
            val isSelected = code in selectedSet
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(code, !isSelected) },
                label = { Text(labelForLanguage(code)) },
                interactionSource = interactionSource,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = if (isFocused) Steel.copy(alpha = 0.28f) else Snow.copy(alpha = 0.06f),
                    labelColor = if (isFocused) Snow else Silver,
                    selectedContainerColor = if (isFocused) Amber.copy(alpha = 0.32f) else Amber.copy(alpha = 0.2f),
                    selectedLabelColor = if (isFocused) Snow else Amber,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    entryFocusRequester: FocusRequester? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = options.find { it.first == value }?.second ?: value

    Text(label, style = MaterialTheme.typography.bodyMedium, color = Silver)
    Spacer(Modifier.height(6.dp))
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Amber,
                unfocusedBorderColor = Steel,
                focusedTextColor = Snow,
                unfocusedTextColor = Snow,
            ),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}
