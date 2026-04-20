package com.torve.android.ui.panda

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PandaUsenetStep(
    state: PandaSetupUiState,
    viewModel: PandaSetupViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.panda_setup_usenet_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.panda_setup_usenet_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
        )
        Spacer(Modifier.height(20.dp))

        // Enable toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Gunmetal)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.panda_setup_usenet_enable),
                style = MaterialTheme.typography.bodyLarge,
                color = Snow,
            )
            Switch(
                checked = state.enableUsenet,
                onCheckedChange = { viewModel.setEnableUsenet(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Amber,
                    checkedTrackColor = Amber.copy(alpha = 0.3f),
                ),
            )
        }

        if (!state.enableUsenet) return

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Steel.copy(alpha = 0.15f))
        Spacer(Modifier.height(16.dp))

        // Provider selection
        Text(
            stringResource(R.string.panda_setup_usenet_provider),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Amber,
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
        HorizontalDivider(color = Steel.copy(alpha = 0.15f))
        Spacer(Modifier.height(16.dp))

        // Credentials
        if (state.usenetProvider == "easynews") {
            EasynewsCredentials(state, viewModel)
        } else {
            GenericNntpCredentials(state, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Steel.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))
            NzbIndexerSection(state, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Steel.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))
            DownloadClientSection(state, viewModel)
        }
    }
}

@Composable
private fun ProviderCard(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Amber.copy(alpha = 0.15f) else Gunmetal)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Snow)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Silver)
        }
    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Gunmetal)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.panda_setup_usenet_ssl), style = MaterialTheme.typography.bodyMedium, color = Snow)
        Switch(
            checked = state.usenetSSL,
            onCheckedChange = { viewModel.setUsenetSSL(it) },
            colors = SwitchDefaults.colors(checkedThumbColor = Amber, checkedTrackColor = Amber.copy(alpha = 0.3f)),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NzbIndexerSection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    Text(
        stringResource(R.string.panda_setup_usenet_indexer),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Amber,
    )
    Spacer(Modifier.height(8.dp))

    UsenetDropdown(
        value = state.nzbIndexer,
        options = state.schema.nzbIndexers.map { id -> id to labelForNzbIndexer(id) },
        onSelect = { viewModel.setNzbIndexer(it) },
    )

    if (state.nzbIndexer != "none") {
        Spacer(Modifier.height(10.dp))
        if (state.nzbIndexer == "custom") {
            FieldInput(
                label = stringResource(R.string.panda_setup_usenet_indexer_url),
                value = state.nzbIndexerUrl,
                onValueChange = { viewModel.setNzbIndexerUrl(it) },
            )
            Spacer(Modifier.height(10.dp))
        }
        FieldInput(
            label = stringResource(R.string.panda_setup_usenet_indexer_key),
            value = state.nzbIndexerApiKey,
            onValueChange = { viewModel.setNzbIndexerApiKey(it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadClientSection(state: PandaSetupUiState, viewModel: PandaSetupViewModel) {
    Text(
        stringResource(R.string.panda_setup_usenet_client),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Amber,
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber,
            unfocusedBorderColor = Steel,
            cursorColor = Amber,
            focusedTextColor = Snow,
            unfocusedTextColor = Snow,
            focusedLabelColor = Amber,
            unfocusedLabelColor = Silver,
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
    val displayValue = options.find { it.first == value }?.second ?: value

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
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
