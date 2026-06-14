package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.diagnostics.AndroidDiagnosticsRecorder
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import com.torve.android.ui.support.buildAndroidFullBugReportPayload
import com.torve.android.ui.support.clearPendingTvBugReport
import com.torve.android.ui.support.copyBugReport
import com.torve.android.ui.support.loadPendingTvBugReport
import com.torve.android.ui.support.savePendingTvBugReport
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.data.auth.AuthClient
import com.torve.data.support.SupportApi
import com.torve.data.support.SupportBugReportPayload
import com.torve.data.support.SupportBugReportSubmitResult
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.transfer.TransferDiagnosticsCollector
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun TvBugReportScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = koinInject(),
    providerHealthCoordinator: ProviderHealthCoordinator = koinInject(),
    transferDiagnosticsCollector: TransferDiagnosticsCollector = koinInject(),
    supportApi: SupportApi = koinInject(),
    authClient: AuthClient = koinInject(),
    subscriptionRepository: SubscriptionRepository = koinInject(),
    addonRepository: AddonRepository = koinInject(),
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settingsState by settingsViewModel.state.collectAsState()
    val providerEntries by providerHealthCoordinator.entries.collectAsState()
    val scope = rememberCoroutineScope()
    val issueTypes = tvBugReportIssueTypes()
    var selectedIssueType by remember { mutableStateOf(issueTypes.first()) }
    var userDescription by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf<SupportBugReportPayload?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitStatus by remember { mutableStateOf<String?>(null) }
    var usingSavedReport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AndroidDiagnosticsRecorder.recordScreen("bug_report_tv")
        loadPendingTvBugReport(context)?.let {
            payload = it
            report = it.report
            selectedIssueType = it.issueType
            userDescription = it.message.orEmpty()
            usingSavedReport = true
            submitStatus = context.getString(R.string.bug_report_saved_for_retry)
        }
    }

    LaunchedEffect(settingsState, providerEntries, selectedIssueType, userDescription, usingSavedReport) {
        if (usingSavedReport) return@LaunchedEffect
        val transferSnapshot = runCatching {
            transferDiagnosticsCollector.collect(probeRelay = false)
        }.getOrNull()
        val nextPayload = buildAndroidFullBugReportPayload(
            context = context,
            settingsState = settingsState,
            providerEntries = providerEntries,
            transferSnapshot = transferSnapshot,
            issueType = selectedIssueType,
            userDescription = userDescription,
            includeDiagnostics = true,
            authClient = authClient,
            subscriptionRepository = subscriptionRepository,
            addonRepository = addonRepository,
        )
        payload = nextPayload
        report = nextPayload.report
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .padding(56.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.bug_report_title),
                color = Snow,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.bug_report_tv_subtitle),
                color = Silver,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.bug_report_privacy_note),
                color = Silver,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.bug_report_issue_type),
                color = Snow,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            issueTypes.chunked(2).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEachIndexed { itemIndex, type ->
                        TvBugReportChoice(
                            title = type,
                            selected = type == selectedIssueType,
                            initialFocus = rowIndex == 0 && itemIndex == 0,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                selectedIssueType = type
                                usingSavedReport = false
                                submitStatus = null
                            },
                        )
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            TvClickToEditOutlinedTextField(
                value = userDescription,
                onValueChange = {
                    userDescription = it.take(1_000)
                    usingSavedReport = false
                    submitStatus = null
                },
                label = { Text(stringResource(R.string.bug_report_description)) },
                placeholder = { Text(stringResource(R.string.bug_report_description_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )
            Spacer(Modifier.height(8.dp))
            if (report == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = Amber)
                    Text(stringResource(R.string.bug_report_generating), color = Silver)
                }
            } else {
                TvBugReportAction(
                    title = if (isSubmitting) {
                        stringResource(R.string.bug_report_sending)
                    } else {
                        stringResource(R.string.bug_report_send_full_diagnostics)
                    },
                    subtitle = stringResource(R.string.bug_report_send_support_tv_desc),
                    enabled = !isSubmitting,
                    onClick = {
                        val snapshot = payload ?: return@TvBugReportAction
                        scope.launch {
                            isSubmitting = true
                            submitStatus = null
                            AndroidDiagnosticsRecorder.recordAction(
                                screen = "bug_report_tv",
                                action = "provider_check",
                                target = "support_bug_report",
                            )
                            submitStatus = when (val result = supportApi.submitBugReport(snapshot)) {
                                is SupportBugReportSubmitResult.Sent -> {
                                    clearPendingTvBugReport(context)
                                    usingSavedReport = false
                                    context.getString(R.string.bug_report_sent, result.reportId)
                                }
                                is SupportBugReportSubmitResult.Error -> {
                                    if (result.retryable) {
                                        savePendingTvBugReport(context, snapshot)
                                        usingSavedReport = true
                                    }
                                    result.message.ifBlank { context.getString(R.string.bug_report_send_failed) }
                                }
                            }
                            isSubmitting = false
                        }
                    },
                )
                TvBugReportAction(
                    title = stringResource(R.string.bug_report_copy_report),
                    subtitle = stringResource(R.string.bug_report_copy_report_desc),
                    onClick = { report?.let { copyBugReport(context, it) } },
                )
                submitStatus?.let {
                    Text(
                        text = it,
                        color = Silver,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun tvBugReportIssueTypes(): List<String> = listOf(
    stringResource(R.string.bug_report_type_crash),
    stringResource(R.string.bug_report_type_lag_freezing),
    stringResource(R.string.bug_report_type_focus_navigation),
    stringResource(R.string.bug_report_type_playback_problem),
    stringResource(R.string.bug_report_type_broken_link),
    stringResource(R.string.bug_report_type_integration_problem),
    stringResource(R.string.bug_report_type_addon_problem),
    stringResource(R.string.bug_report_type_iptv_problem),
    stringResource(R.string.bug_report_type_account_access),
    stringResource(R.string.bug_report_type_other),
)

@Composable
private fun TvBugReportChoice(
    title: String,
    selected: Boolean,
    initialFocus: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(initialFocus) {
        if (initialFocus) {
            kotlinx.coroutines.delay(120L)
            runCatching { requester.requestFocus() }
        }
    }
    Column(
        modifier = modifier
            .background(
                when {
                    focused -> Charcoal.copy(alpha = 0.95f)
                    selected -> Charcoal.copy(alpha = 0.78f)
                    else -> Charcoal.copy(alpha = 0.45f)
                },
                RoundedCornerShape(12.dp),
            )
            .border(
                if (focused || selected) 2.dp else 1.dp,
                if (focused || selected) Amber else Silver.copy(alpha = 0.16f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        Text(
            text = title,
            color = if (focused || selected) Amber else Snow,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TvBugReportAction(
    title: String,
    subtitle: String,
    initialFocus: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(initialFocus) {
        if (initialFocus) {
            kotlinx.coroutines.delay(120L)
            runCatching { requester.requestFocus() }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) Charcoal.copy(alpha = 0.9f) else Charcoal.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) Amber else Silver.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (
                    enabled &&
                    event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        Text(title, color = if (focused && enabled) Amber else Snow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Silver, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    }
}
