package com.torve.android.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.StreamGroup
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@Composable
fun StreamGroupsScreen(
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
                stringResource(R.string.stream_groups_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.addStreamGroup() }) {
                Icon(Icons.Default.Add, stringResource(R.string.common_add), tint = Amber)
            }
        }

        Text(
            stringResource(R.string.stream_groups_desc),
            style = MaterialTheme.typography.bodySmall,
            color = Silver,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = { viewModel.resetStreamGroups() }) {
                Text(stringResource(R.string.ratings_reset_defaults), color = Amber)
            }
        }

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
            itemsIndexed(state.streamGroups) { index, group ->
                StreamGroupRow(
                    group = group,
                    onUpdate = { viewModel.updateStreamGroup(index, it) },
                    onDelete = { viewModel.removeStreamGroup(index) },
                    onToggle = { viewModel.toggleStreamGroup(index) },
                )
            }
        }
    }
}

@Composable
private fun StreamGroupRow(
    group: StreamGroup,
    onUpdate: (StreamGroup) -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    var editName by remember(group) { mutableStateOf(group.name) }
    var editPattern by remember(group) { mutableStateOf(group.matchPattern) }
    var editPriority by remember(group) { mutableStateOf(group.priority.toString()) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = editName,
                onValueChange = {
                    editName = it
                    onUpdate(group.copy(name = it))
                },
                placeholder = { Text(stringResource(R.string.stream_groups_name_hint), style = MaterialTheme.typography.bodySmall) },
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
            OutlinedTextField(
                value = editPriority,
                onValueChange = {
                    editPriority = it.filter { c -> c.isDigit() }
                    onUpdate(group.copy(priority = editPriority.toIntOrNull() ?: 99))
                },
                placeholder = { Text("#", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.width(56.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Snow),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amber,
                    unfocusedBorderColor = Steel.copy(alpha = 0.3f),
                    cursorColor = Amber,
                ),
            )
            Switch(
                checked = group.enabled,
                onCheckedChange = { onToggle() },
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
                onUpdate(group.copy(matchPattern = it))
            },
            placeholder = { Text(stringResource(R.string.stream_groups_regex_hint), style = MaterialTheme.typography.bodySmall) },
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
