package com.torve.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.theme.Torve
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.addon.AddonViewModel.Companion.normalizeManifestUrl

@Composable
fun AddonManagerSection(
    viewModel: AddonViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.addon_content_sources),
            style = MaterialTheme.typography.titleMedium,
            color = Amber,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Installed addons
                if (state.addons.isEmpty() && !state.isLoading) {
                    Text(
                        stringResource(R.string.addon_no_sources),
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textSecondary,
                    )
                }

                state.addons.forEachIndexed { index, addon ->
                    val flags = state.policyFlagsByUrl[normalizeManifestUrl(addon.manifestUrl)]
                    val isRestricted = flags?.installable == false
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = addon.manifest.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isRestricted) Torve.colors.textSecondary else Torve.colors.textPrimary,
                            )
                            Text(
                                text = if (isRestricted) stringResource(R.string.addon_restricted_label)
                                    else addon.manifest.description.ifBlank {
                                        addon.manifestUrl.removePrefix("https://").removePrefix("http://")
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = Torve.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (!isRestricted) {
                            Switch(
                                checked = addon.isEnabled,
                                onCheckedChange = { viewModel.toggleAddon(addon.manifestUrl, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Amber,
                                    checkedTrackColor = Amber.copy(alpha = 0.3f),
                                    uncheckedThumbColor = Silver,
                                    uncheckedTrackColor = Gunmetal,
                                ),
                            )
                        }
                        IconButton(
                            onClick = { viewModel.removeAddon(addon.manifestUrl) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.common_remove),
                                tint = Ruby,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (index < state.addons.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = Steel.copy(alpha = 0.3f),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Install addon from URL
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.addon_extension_url), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                        Spacer(Modifier.height(4.dp))
                        BasicTextField(
                            value = state.installUrl,
                            onValueChange = { viewModel.setInstallUrl(it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow),
                            singleLine = true,
                            cursorBrush = SolidColor(Amber),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Gunmetal, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    if (state.installUrl.isEmpty()) {
                                        Text(
                                            "https://example.com/manifest.json",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Torve.colors.textHint,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }
                    Button(
                        onClick = { viewModel.installAddon() },
                        enabled = state.installUrl.isNotBlank() && !state.isInstalling,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        if (state.isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Obsidian,
                            )
                        } else {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.common_install))
                        }
                    }
                }

                state.installError?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ruby,
                    )
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ruby,
                    )
                }
            }
        }
    }
}
