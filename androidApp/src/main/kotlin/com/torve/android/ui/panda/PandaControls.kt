package com.torve.android.ui.panda

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow

/** The solid amber fill belongs to remote/keyboard focus, never to selection. */
@Composable
fun PandaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = null,
        interactionSource = interactions,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) Amber else Gunmetal,
            contentColor = if (focused) Obsidian else Snow,
            disabledContainerColor = Gunmetal.copy(alpha = 0.5f),
            disabledContentColor = Silver.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
internal fun PandaChoiceCard(
    title: String,
    description: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val foreground = if (focused) Obsidian else Snow
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Amber else Gunmetal)
            .semantics { this.selected = selected }
            .clickable(interactionSource = interactions, indication = null,
                enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) foreground else Silver, fontWeight = FontWeight.SemiBold)
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(description, color = if (focused) Obsidian else Silver,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (selected) Icon(Icons.Default.Check, null, tint = foreground, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun PandaChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    PandaButton(onClick, modifier.semantics { this.selected = selected }) {
        if (selected) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(localizedUiText(label))
    }
}

@Composable
internal fun PandaDropdown(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    entryFocusRequester: FocusRequester? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val ownRequester = remember { FocusRequester() }
    val requester = entryFocusRequester ?: ownRequester
    Column(modifier) {
        if (label != null) {
            Text(localizedUiText(label), color = Silver, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
        }
        PandaButton({ expanded = true }, Modifier.fillMaxWidth().focusRequester(requester)) {
            Text(options.find { it.first == value }?.second ?: value, Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null)
        }
    }
    if (expanded) {
        val selectedRequester = remember { FocusRequester() }
        val selectedKey = value.takeIf { current -> options.any { it.first == current } } ?: options.firstOrNull()?.first
        AlertDialog(
            onDismissRequest = { expanded = false },
            containerColor = Obsidian,
            title = label?.let { { Text(it, color = Snow) } },
            text = {
                LaunchedEffect(selectedKey) {
                    if (selectedKey != null) { withFrameNanos { }; selectedRequester.requestFocus() }
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (key, display) ->
                        PandaChoiceCard(display, selected = key == value,
                            onClick = { onSelect(key); expanded = false },
                            modifier = if (key == selectedKey) Modifier.focusRequester(selectedRequester) else Modifier)
                    }
                }
            },
            confirmButton = { PandaButton({ expanded = false }) { Text(androidx.compose.ui.res.stringResource(com.torve.android.R.string.common_cancel)) } },
        )
    }
}

@Composable
internal fun pandaTextFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Gunmetal,
    unfocusedContainerColor = Gunmetal,
    focusedTextColor = Snow,
    unfocusedTextColor = Snow,
    focusedLabelColor = Amber,
    unfocusedLabelColor = Silver,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = Amber,
)
