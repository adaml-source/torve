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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.torve.android.ui.support.TORVE_SUPPORT_EMAIL
import com.torve.android.ui.support.buildAndroidBugReport
import com.torve.android.ui.support.copyBugReport
import com.torve.android.ui.support.emailBugReport
import com.torve.android.ui.support.shareBugReport
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.transfer.TransferDiagnosticsCollector
import org.koin.compose.koinInject

@Composable
fun TvBugReportScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = koinInject(),
    providerHealthCoordinator: ProviderHealthCoordinator = koinInject(),
    transferDiagnosticsCollector: TransferDiagnosticsCollector = koinInject(),
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settingsState by settingsViewModel.state.collectAsState()
    val providerEntries by providerHealthCoordinator.entries.collectAsState()
    var report by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settingsState, providerEntries) {
        val transferSnapshot = runCatching {
            transferDiagnosticsCollector.collect(probeRelay = false)
        }.getOrNull()
        report = buildAndroidBugReport(
            context = context,
            settingsState = settingsState,
            providerEntries = providerEntries,
            transferSnapshot = transferSnapshot,
            issueType = context.getString(R.string.bug_report_type_tv),
            userDescription = "Report generated from Android TV.",
            pastedLogs = "",
            includeDiagnostics = true,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .padding(56.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.bug_report_title),
                color = Snow,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.bug_report_tv_subtitle, TORVE_SUPPORT_EMAIL),
                color = Silver,
                style = MaterialTheme.typography.titleMedium,
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
                    title = stringResource(R.string.bug_report_send_email),
                    subtitle = stringResource(R.string.bug_report_send_email_desc),
                    initialFocus = true,
                    onClick = { report?.let { emailBugReport(context, it) } },
                )
                TvBugReportAction(
                    title = stringResource(R.string.bug_report_copy_report),
                    subtitle = stringResource(R.string.bug_report_copy_report_desc),
                    onClick = { report?.let { copyBugReport(context, it) } },
                )
                TvBugReportAction(
                    title = stringResource(R.string.bug_report_share),
                    subtitle = stringResource(R.string.bug_report_share_desc),
                    onClick = { report?.let { shareBugReport(context, it) } },
                )
            }
        }
    }
}

@Composable
private fun TvBugReportAction(
    title: String,
    subtitle: String,
    initialFocus: Boolean = false,
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
        Text(title, color = if (focused) Amber else Snow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Silver, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    }
}
