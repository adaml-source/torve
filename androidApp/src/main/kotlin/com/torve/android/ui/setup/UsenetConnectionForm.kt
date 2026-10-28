package com.torve.android.ui.setup

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.torve.android.ui.components.mobileGlassSurface
import com.torve.android.ui.panda.labelForNzbIndexer
import com.torve.android.ui.panda.labelForDownloadClient
import com.torve.android.R
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsenetConnectionForm(
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
            stringResource(R.string.usenet_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (state.serverHasSecrets.isNotEmpty()) {
            Text(stringResource(R.string.usenet_setup_saved_secrets), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))

        UsenetChoiceCard(
            title = stringResource(R.string.panda_setup_usenet_enable), selected = state.enableUsenet,
            onClick = { viewModel.setEnableUsenet(!state.enableUsenet) },
            modifier = Modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        )

        if (!state.enableUsenet) return

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        Spacer(Modifier.height(16.dp))

        // Provider selection
        Text(
            stringResource(R.string.panda_setup_usenet_provider),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))

        // Easynews card
        ProviderCard(
            name = stringResource(R.string.panda_setup_usenet_easynews),
            description = stringResource(R.string.panda_setup_usenet_easynews_desc),
            selected = state.usenetProvider == "easynews",
            onClick = { viewModel.setUsenetProvider("easynews") },
        )
        Spacer(Modifier.height(8.dp))

        // Generic NNTP card
        ProviderCard(
            name = stringResource(R.string.panda_setup_usenet_generic),
            description = stringResource(R.string.panda_setup_usenet_generic_desc),
            selected = state.usenetProvider == "generic",
            onClick = { viewModel.setUsenetProvider("generic") },
        )

        if (state.usenetProvider == "none") return

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        Spacer(Modifier.height(16.dp))

        // Credentials
        if (state.usenetProvider == "easynews") {
            EasynewsCredentials(state, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))
            NzbIndexerSection(state, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))
            DownloadClientSection(state, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))
            BandwidthSaverSwitch(state, viewModel)
        } else {
            GenericNntpCredentials(state, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))
            NzbIndexerSection(state, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))
            DownloadClientSection(state, viewModel)
        }
    }
}

@Composable
private fun BandwidthSaverSwitch(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    val cloudClients = setOf("premiumize", "torbox", "alldebrid")
    val hasIndexer = state.nzbIndexers.any { it.type != "none" && it.apiKey.isNotBlank() }
    val hasCloudClient = state.downloadClient in cloudClients
    val canEnable = state.enableUsenet && hasIndexer && hasCloudClient

    UsenetChoiceCard(
        title = stringResource(R.string.usenet_setup_bandwidth),
        description = if (canEnable) stringResource(R.string.usenet_setup_bandwidth_desc)
            else stringResource(R.string.usenet_setup_bandwidth_disabled),
        selected = canEnable && state.easynewsPreferNzb,
        enabled = canEnable,
        onClick = { viewModel.setBandwidthSaver(!state.easynewsPreferNzb) },
    )
}

@Composable
private fun ProviderCard(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    UsenetChoiceCard(name, description, selected, onClick)
}

@Composable
private fun EasynewsCredentials(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    FieldInput(
        label = stringResource(R.string.panda_setup_usenet_username),
        value = state.usenetUsername,
        onValueChange = { viewModel.setUsenetUsername(it) },
    )
    Spacer(Modifier.height(10.dp))
    FieldInput(
        label = stringResource(R.string.panda_setup_usenet_password),
        value = state.usenetPassword,
        onValueChange = { viewModel.setUsenetPassword(it) },
        isPassword = true,
    )
}

@Composable
private fun GenericNntpCredentials(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldInput(
            label = stringResource(R.string.panda_setup_usenet_host),
            value = state.usenetHost,
            onValueChange = { viewModel.setUsenetHost(it) },
            modifier = Modifier.weight(2f),
        )
        FieldInput(
            label = stringResource(R.string.panda_setup_usenet_port),
            value = state.usenetPort.toString(),
            onValueChange = { viewModel.setUsenetPort(it.toIntOrNull() ?: 563) },
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Number,
        )
    }
    Spacer(Modifier.height(10.dp))
    FieldInput(
        label = stringResource(R.string.panda_setup_usenet_username),
        value = state.usenetUsername,
        onValueChange = { viewModel.setUsenetUsername(it) },
    )
    Spacer(Modifier.height(10.dp))
    FieldInput(
        label = stringResource(R.string.panda_setup_usenet_password),
        value = state.usenetPassword,
        onValueChange = { viewModel.setUsenetPassword(it) },
        isPassword = true,
    )
    Spacer(Modifier.height(10.dp))
    UsenetChoiceCard(stringResource(R.string.panda_setup_usenet_ssl), selected = state.usenetSSL,
        onClick = { viewModel.setUsenetSSL(!state.usenetSSL) })

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NzbIndexerSection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    Text(
        stringResource(R.string.panda_setup_usenet_indexer),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))

    state.nzbIndexers.forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        NzbIndexerRowCard(
            row = row,
            indexerOptions = state.schema.nzbIndexers,
            onTypeChange = { newType ->
                viewModel.updateIndexer(index) { it.copy(type = newType) }
            },
            onUrlChange = { newUrl ->
                viewModel.updateIndexer(index) { it.copy(url = newUrl) }
            },
            onKeyChange = { newKey ->
                viewModel.updateIndexer(index) { it.copy(apiKey = newKey) }
            },
            onRemove = { viewModel.removeIndexer(index) },
            canRemove = state.nzbIndexers.size > 1,
        )
    }

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { viewModel.addIndexer() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.panda_setup_usenet_add_indexer))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NzbIndexerRowCard(
    row: com.torve.data.panda.NzbIndexerRow,
    indexerOptions: List<String>,
    onTypeChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                UsenetDropdown(
                    value = row.type,
                    options = indexerOptions.map { id -> id to labelForNzbIndexer(id) },
                    onSelect = onTypeChange,
                )
            }
            if (canRemove) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.usenet_setup_remove_indexer),
                    )
                }
            }
        }

        if (row.type != "none") {
            if (row.type == "custom") {
                Spacer(Modifier.height(10.dp))
                FieldInput(
                    label = stringResource(R.string.panda_setup_usenet_indexer_url),
                    value = row.url,
                    onValueChange = onUrlChange,
                )
            }
            Spacer(Modifier.height(10.dp))
            FieldInput(
                label = stringResource(R.string.panda_setup_usenet_indexer_key),
                value = row.apiKey,
                onValueChange = onKeyChange,
                isPassword = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadClientSection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    Text(
        stringResource(R.string.panda_setup_usenet_client),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))

    UsenetDropdown(
        value = state.downloadClient,
        options = state.schema.downloadClients.map { id -> id to labelForDownloadClient(id) },
        onSelect = { viewModel.setDownloadClient(it) },
    )

    val spec = state.schema.downloadClientFields[state.downloadClient]
    val fields = spec?.fields.orEmpty()
    if (fields.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        fields.forEachIndexed { index, field ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            when (field) {
                "url" -> FieldInput(
                    label = stringResource(R.string.panda_setup_usenet_client_url),
                    value = state.downloadClientUrl,
                    onValueChange = { viewModel.setDownloadClientUrl(it) },
                )
                "username" -> FieldInput(
                    label = stringResource(R.string.panda_setup_usenet_client_user),
                    value = state.downloadClientUsername,
                    onValueChange = { viewModel.setDownloadClientUsername(it) },
                )
                "password" -> FieldInput(
                    label = stringResource(R.string.panda_setup_usenet_client_pass),
                    value = state.downloadClientPassword,
                    onValueChange = { viewModel.setDownloadClientPassword(it) },
                    isPassword = true,
                )
                "apiKey" -> FieldInput(
                    label = stringResource(R.string.panda_setup_usenet_client_key),
                    value = state.downloadClientApiKey,
                    onValueChange = { viewModel.setDownloadClientApiKey(it) },
                    isPassword = true,
                )
            }
        }
    }
}

@Composable
private fun FieldInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    // Per-field reveal toggle. Defaults to masked for password fields so the
    // credential isn't shoulder-surfed in casual use; the user opts in to
    // plaintext when they need to verify what they typed.
    var revealed by remember { mutableStateOf(false) }
    val effectiveKeyboardType = if (isPassword) KeyboardType.Password else keyboardType
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(localizedUiText(label)) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (isPassword && !revealed) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = effectiveKeyboardType),
        trailingIcon = if (isPassword) {
            {
                Button(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (revealed) R.string.common_hide else R.string.usenet_setup_show_password),
                    )
                }
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsenetDropdown(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second ?: value,
            onValueChange = {}, readOnly = true,
            modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(localizedUiText(label)) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}

@Composable
private fun UsenetChoiceCard(
    title: String, description: String = "", selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    Surface(modifier.fillMaxWidth().mobileGlassSurface(quiet = true),
        shape = RoundedCornerShape(16.dp), color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, null, enabled = enabled)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(localizedUiText(title), style = MaterialTheme.typography.titleSmall)
                if (description.isNotBlank()) Text(localizedUiText(description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
