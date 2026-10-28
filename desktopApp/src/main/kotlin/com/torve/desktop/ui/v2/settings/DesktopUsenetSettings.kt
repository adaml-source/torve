package com.torve.desktop.ui.v2.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.data.usenet.model.UsenetStreamingBackend
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.l10n.ds
import com.torve.presentation.usenet.NzbdavSetupViewModel
import com.torve.presentation.usenet.NzbdavStatus
import com.torve.presentation.usenet.NzbdavTestResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopUsenetSettings() {
    val vm: NzbdavSetupViewModel = remember {
        org.koin.java.KoinJavaComponent.get(NzbdavSetupViewModel::class.java)
    }
    val state by vm.state.collectAsState()
    val busy = state.isTesting || state.isSaving || state.isRemoving
    var advanced by remember { mutableStateOf(false) }
    TorveSectionCard(title = ds("Usenet streaming"), supportingText = ds(
        "Connect InfiniDysk, NzbDavEx, AltMount, Decypharr or NZBDav. Configure NNTP accounts in that server. TorBox is optional.",
    )) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val status = when (state.status) {
                NzbdavStatus.Loading -> "Checking status…"
                NzbdavStatus.NotConfigured -> "Not configured"
                NzbdavStatus.ConnectionFailed -> "Connection failed"
                is NzbdavStatus.Connected -> "Connected"
            }
            Text(ds(status))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsenetStreamingBackend.entries.forEach { backend ->
                    TorveFilterChip(backend.displayName, state.connection.backendType == backend.wireValue,
                        onClick = { if (!busy) vm.updateBackendType(backend.wireValue) })
                }
            }
            TorveTextField(state.baseUrl, vm::updateBaseUrl, ds("Server URL"), Modifier.fillMaxWidth(), enabled = !busy)
            TorveTextField(state.apiKey, vm::updateApiKey, ds("API key"), Modifier.fillMaxWidth(),
                enabled = !busy, visualTransformation = PasswordVisualTransformation())
            TorveTextField(state.connection.webdavUsername, vm::updateWebdavUsername, ds("WebDAV username"),
                Modifier.fillMaxWidth(), enabled = !busy)
            TorveTextField(state.connection.webdavPassword, vm::updateWebdavPassword, ds("WebDAV password"),
                Modifier.fillMaxWidth(), enabled = !busy, visualTransformation = PasswordVisualTransformation())
            TorveGhostButton(text = ds("Advanced connection settings"), onClick = { advanced = !advanced })
            if (advanced) {
                TorveTextField(state.connection.webdavUrl, vm::updateWebdavUrl, ds("WebDAV URL (optional override)"), Modifier.fillMaxWidth())
                TorveTextField(state.connection.category, vm::updateCategory, ds("Download category (optional)"), Modifier.fillMaxWidth())
                TorveTextField(state.connection.apiPath, vm::updateApiPath, ds("API path (optional override)"), Modifier.fillMaxWidth())
                TorveTextField(state.connection.contentPath, vm::updateContentPath, ds("Content path template (optional override)"), Modifier.fillMaxWidth())
            }
            state.lastTestResult?.let { result ->
                Text(ds(when (result) {
                    NzbdavTestResult.Ok -> "Connection test succeeded"
                    NzbdavTestResult.DegradedOk -> "Connection test succeeded with reduced availability"
                    NzbdavTestResult.Failed -> "Connection test failed"
                    NzbdavTestResult.MissingFields -> "Server URL and API key are required"
                    NzbdavTestResult.Saved -> "Saved"
                    NzbdavTestResult.Removed -> "Removed"
                }))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveSecondaryButton(text = ds("Test connection"), onClick = vm::test, enabled = !busy)
                TorvePrimaryButton(text = ds("Save"), onClick = vm::save, enabled = !busy)
                TorveGhostButton(text = ds("Remove"), onClick = vm::remove,
                    enabled = !busy && state.status !is NzbdavStatus.NotConfigured)
            }
        }
    }
}
