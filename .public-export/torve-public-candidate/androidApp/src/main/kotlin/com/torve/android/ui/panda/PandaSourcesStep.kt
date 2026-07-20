package com.torve.android.ui.panda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel

@Composable
fun PandaSourcesStep(
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
            stringResource(R.string.panda_setup_sources_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.panda_setup_sources_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
        )
        Spacer(Modifier.height(20.dp))

        // Group by category
        val grouped = state.sourceProviders.groupBy { it.category }
        val categoryOrder = listOf("movies", "series", "general", "anime", "regional")

        var firstFocusableAttached = false
        categoryOrder.forEach { category ->
            val providers = grouped[category] ?: return@forEach
            val categoryLabel = when (category) {
                "movies" -> "Movies"
                "series" -> "Series"
                "general" -> "General"
                "anime" -> "Anime"
                "regional" -> "Regional"
                else -> category
            }

            Text(
                categoryLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ash,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                providers.forEach { source ->
                    val checked = source.id in state.enabledSources
                    val attachEntryFocus = !firstFocusableAttached && entryFocusRequester != null
                    if (attachEntryFocus) firstFocusableAttached = true
                    val interactionSource = remember(source.id) { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (attachEntryFocus) {
                                    Modifier.focusRequester(entryFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (checked) Amber.copy(alpha = 0.08f) else Gunmetal)
                            .border(
                                width = if (isFocused) 2.dp else 1.dp,
                                color = if (isFocused) Amber else Steel.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) { viewModel.toggleSource(source.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { viewModel.toggleSource(source.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Amber,
                                uncheckedColor = Steel,
                            ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                source.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Snow,
                            )
                            Text(
                                source.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Silver,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
