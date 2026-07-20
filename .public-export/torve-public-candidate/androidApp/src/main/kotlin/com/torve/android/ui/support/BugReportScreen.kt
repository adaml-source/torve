package com.torve.android.ui.support

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.data.support.SupportApi
import com.torve.data.support.SupportBugReportSubmitResult
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.transfer.TransferDiagnosticsCollector
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = koinInject(),
    providerHealthCoordinator: ProviderHealthCoordinator = koinInject(),
    transferDiagnosticsCollector: TransferDiagnosticsCollector = koinInject(),
    supportApi: SupportApi = koinInject(),
) {
    val settingsState by settingsViewModel.state.collectAsState()
    val providerEntries by providerHealthCoordinator.entries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val issueTypes = listOf(
        stringResource(R.string.bug_report_type_playback),
        stringResource(R.string.bug_report_type_live_tv),
        stringResource(R.string.bug_report_type_vod),
        stringResource(R.string.bug_report_type_account),
        stringResource(R.string.bug_report_type_settings),
        stringResource(R.string.bug_report_type_other),
    )
    var issueType by remember { mutableStateOf(issueTypes.first()) }
    var issueTypeExpanded by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var pastedLogs by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var submitStatus by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    fun buildAndSend(send: (String) -> Unit) {
        scope.launch {
            val transferSnapshot = runCatching {
                transferDiagnosticsCollector.collect(probeRelay = false)
            }.getOrNull()
            val report = buildAndroidBugReport(
                context = context,
                settingsState = settingsState,
                providerEntries = providerEntries,
                transferSnapshot = transferSnapshot,
                issueType = issueType,
                userDescription = description,
                pastedLogs = pastedLogs,
                includeDiagnostics = includeDiagnostics,
            )
            send(report)
        }
    }

    fun buildAndSubmit() {
        buildAndSend { report ->
            scope.launch {
                isSubmitting = true
                submitStatus = null
                submitStatus = when (val result = supportApi.submitBugReport(
                    issueType = issueType,
                    report = report,
                    platform = androidBugReportPlatformLabel(),
                    appVersion = androidBugReportAppVersion(context),
                )) {
                    is SupportBugReportSubmitResult.Sent ->
                        context.getString(R.string.bug_report_sent, result.reportId)
                    is SupportBugReportSubmitResult.Error ->
                        result.message.ifBlank { context.getString(R.string.bug_report_send_failed) }
                }
                isSubmitting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackButton(onClick = onBack)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.bug_report_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.bug_report_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = issueTypeExpanded,
                    onExpandedChange = { issueTypeExpanded = !issueTypeExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        color = Gunmetal,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.bug_report_issue_type),
                                    color = Torve.colors.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(issueType, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Silver)
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = issueTypeExpanded,
                        onDismissRequest = { issueTypeExpanded = false },
                    ) {
                        issueTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    issueType = type
                                    issueTypeExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text(stringResource(R.string.bug_report_description)) },
                    placeholder = { Text(stringResource(R.string.bug_report_description_hint)) },
                )

                OutlinedTextField(
                    value = pastedLogs,
                    onValueChange = { pastedLogs = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    label = { Text(stringResource(R.string.bug_report_logs)) },
                    placeholder = { Text(stringResource(R.string.bug_report_logs_hint)) },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.bug_report_attach_diagnostics), color = Snow)
                        Text(
                            stringResource(R.string.bug_report_attach_diagnostics_desc),
                            color = Torve.colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = includeDiagnostics,
                        onCheckedChange = { includeDiagnostics = it },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { buildAndSubmit() },
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
            ) {
                Text(
                    if (isSubmitting) {
                        stringResource(R.string.bug_report_sending)
                    } else {
                        stringResource(R.string.bug_report_send_support)
                    },
                )
            }
            OutlinedButton(
                onClick = { buildAndSend { copyBugReport(context, it) } },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.common_copy))
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { buildAndSend { shareBugReport(context, it) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.bug_report_share))
        }
        submitStatus?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = Torve.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
