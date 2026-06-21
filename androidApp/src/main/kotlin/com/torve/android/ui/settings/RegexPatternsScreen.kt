package com.torve.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.RegexPattern
import com.torve.domain.streams.StreamRulePatternValidator
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

private val PRESETS = listOf(
    "No Cam/TS" to "(?i)(cam|hdcam|telesync|telecine|ts|tc)",
    "1080p+ Only" to "(?i)(?!.*(1080p|2160p|4k)).*",
    "No 3D" to "(?i)(3d|sbs|half.?ou)",
    "English Only" to "(?i)(french|german|spanish|italian|portuguese|hindi|arabic|russian|turkish|chinese|japanese|korean)",
    "No HDR" to "(?i)(hdr|hdr10|hdr10\\+|dolby.?vision|dv)",
    "No HEVC/x265" to "(?i)(hevc|x265|h\\.?265)",
    "No Ads/Promo" to "(?i)(sample|trailer|promo|ads|teaser)",
    "4K Only" to "(?i)(?!.*(2160p|4k|uhd)).*",
    "No Dubbed" to "(?i)(dubbed|dual.?audio|multi.?audio|dub)",
    "No HC Subs" to "(?i)(hc|hardcoded|hardsub)",
)

@Composable
fun RegexPatternsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val jsonText = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return@rememberLauncherForActivityResult
            val result = viewModel.importRegexPatternsJson(jsonText)
            val message = if (result.disabledOnImport > 0) {
                context.getString(
                    R.string.regex_imported_disabled,
                    result.items.size,
                    result.disabledOnImport,
                )
            } else {
                context.getString(R.string.regex_imported, result.items.size)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.regex_import_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val jsonText = pendingExportJson ?: viewModel.exportRegexPatternsJson()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonText.toByteArray())
            }
            pendingExportJson = null
            Toast.makeText(context, context.getString(R.string.regex_exported), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.regex_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

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
                stringResource(R.string.regex_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.addRegexPattern() }) {
                Icon(Icons.Default.Add, stringResource(R.string.common_add), tint = Amber)
            }
        }

        Text(
            stringResource(R.string.regex_filter_desc),
            style = MaterialTheme.typography.bodySmall,
            color = Silver,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            stringResource(R.string.regex_help_text),
            style = MaterialTheme.typography.bodySmall,
            color = Steel,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.regex_import_json), color = Amber)
            }
            OutlinedButton(
                onClick = {
                    pendingExportJson = viewModel.exportRegexPatternsJson()
                    exportLauncher.launch("torve_regex_patterns.json")
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.regex_export_json), color = Amber)
            }
        }

        Text(
            stringResource(R.string.regex_quick_add),
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
                        if (exists) {
                            viewModel.removeRegexPatternByValue(pattern)
                        } else {
                            viewModel.addRegexPattern(label, pattern)
                            Toast.makeText(context, context.getString(R.string.regex_added, label), Toast.LENGTH_SHORT).show()
                        }
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
    val patternError = StreamRulePatternValidator.regexErrorMessage(editPattern)
    val canEnablePattern = StreamRulePatternValidator.canEnable(editPattern)

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
                placeholder = { Text(stringResource(R.string.regex_label_hint), style = MaterialTheme.typography.bodySmall) },
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
                checked = pattern.enabled && canEnablePattern,
                onCheckedChange = { onToggle() },
                enabled = canEnablePattern,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Amber,
                    checkedTrackColor = AmberSubtle,
                    uncheckedThumbColor = Steel,
                    uncheckedTrackColor = Gunmetal,
                ),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, stringResource(R.string.common_delete), tint = Ruby, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = editPattern,
            onValueChange = {
                editPattern = it
                onUpdate(pattern.copy(pattern = it))
            },
            placeholder = { Text(stringResource(R.string.regex_pattern_hint), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = patternError != null,
            supportingText = patternError?.let { message ->
                { Text(message, color = Ruby, style = MaterialTheme.typography.labelSmall) }
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Snow),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Amber,
                unfocusedBorderColor = Steel.copy(alpha = 0.3f),
                errorBorderColor = Ruby,
                errorCursorColor = Ruby,
                cursorColor = Amber,
            ),
        )
    }
}
