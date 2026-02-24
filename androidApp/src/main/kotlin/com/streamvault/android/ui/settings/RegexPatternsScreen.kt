package com.streamvault.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Ash
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Ruby
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.domain.model.RegexPattern
import com.streamvault.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

private val PRESETS = listOf(
    "No Cam/TS" to "(?i)(cam|hdcam|telesync|telecine|ts|tc)",
    "1080p+ Only" to "(?i)(?!.*(1080p|2160p|4k)).*",
    "No 3D" to "(?i)(3d|sbs|half.?ou)",
    "English Only" to "(?i)(french|german|spanish|italian|portuguese|hindi|arabic|russian|turkish|chinese|japanese|korean)",
)

@Composable
fun RegexPatternsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                "Regex Patterns",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.addRegexPattern() }) {
                Icon(Icons.Default.Add, "Add", tint = Amber)
            }
        }

        Text(
            "Filter stream results by title. Matching streams will be excluded. Use standard regex syntax.",
            style = MaterialTheme.typography.bodySmall,
            color = Silver,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Text(
            "Quick Add",
            style = MaterialTheme.typography.labelLarge,
            color = Ash,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(PRESETS.size) { i ->
                val (label, pattern) = PRESETS[i]
                val exists = state.regexPatterns.any { it.pattern == pattern }
                FilterChip(
                    selected = exists,
                    onClick = {
                        if (exists) viewModel.removeRegexPatternByValue(pattern)
                        else viewModel.addRegexPattern(label, pattern)
                    },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber,
                        selectedLabelColor = Obsidian,
                        containerColor = Gunmetal,
                        labelColor = Snow,
                    ),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
            itemsIndexed(state.regexPatterns) { index, regexPattern ->
                RegexPatternRow(
                    pattern = regexPattern,
                    onUpdate = { viewModel.updateRegexPattern(index, it) },
                    onDelete = { viewModel.removeRegexPattern(index) },
                    onToggle = { viewModel.toggleRegexPattern(index) },
                )
            }
        }
    }
}

@Composable
private fun RegexPatternRow(
    pattern: RegexPattern,
    onUpdate: (RegexPattern) -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    var editLabel by remember(pattern) { mutableStateOf(pattern.label) }
    var editPattern by remember(pattern) { mutableStateOf(pattern.pattern) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = editLabel,
                onValueChange = {
                    editLabel = it
                    onUpdate(pattern.copy(label = it))
                },
                placeholder = { Text("Label", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Snow),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amber,
                    unfocusedBorderColor = Steel.copy(alpha = 0.3f),
                    cursorColor = Amber,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = pattern.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Amber,
                    checkedTrackColor = AmberSubtle,
                    uncheckedThumbColor = Steel,
                    uncheckedTrackColor = Gunmetal,
                ),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, "Delete", tint = Ruby, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = editPattern,
            onValueChange = {
                editPattern = it
                onUpdate(pattern.copy(pattern = it))
            },
            placeholder = { Text("Regex pattern", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Snow),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Amber,
                unfocusedBorderColor = Steel.copy(alpha = 0.3f),
                cursorColor = Amber,
            ),
        )
    }
}
