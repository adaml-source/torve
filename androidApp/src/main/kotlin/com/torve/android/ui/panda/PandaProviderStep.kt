package com.torve.android.ui.panda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.panda.PandaSetupMode
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel

@Composable
fun PandaSetupTypeStep(
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
            stringResource(R.string.panda_setup_type_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.panda_setup_type_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Snow,
        )
        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SetupTypeCard(
                title = stringResource(R.string.panda_setup_type_debrid_title),
                subtitle = stringResource(R.string.panda_setup_type_debrid_subtitle),
                badge = "D",
                selected = state.setupMode == PandaSetupMode.DEBRID,
                entryFocusRequester = entryFocusRequester,
                onClick = { viewModel.selectSetupMode(PandaSetupMode.DEBRID) },
            )
            SetupTypeCard(
                title = stringResource(R.string.panda_setup_type_usenet_title),
                subtitle = stringResource(R.string.panda_setup_type_usenet_subtitle),
                badge = "U",
                selected = state.setupMode == PandaSetupMode.USENET_ONLY,
                onClick = { viewModel.selectSetupMode(PandaSetupMode.USENET_ONLY) },
            )
        }
    }
}

@Composable
private fun SetupTypeCard(
    title: String,
    subtitle: String,
    badge: String,
    selected: Boolean,
    entryFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor: Color = when {
        isFocused -> Amber
        selected -> Amber.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Amber.copy(alpha = 0.15f) else Gunmetal)
            .border(if (isFocused) 3.dp else 2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Amber.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Snow,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Snow,
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Amber),
            )
        }
    }
}

@Composable
fun PandaProviderStep(
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
            stringResource(R.string.panda_setup_provider_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.panda_setup_provider_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
        )
        Spacer(Modifier.height(20.dp))

        if (state.providersLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber)
            }
        } else if (state.error != null && state.providers.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error ?: "", color = Ruby, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { viewModel.retryLoadProviders() }) {
                    Text(stringResource(R.string.panda_setup_retry), color = Amber)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.providers.filter { it.id != "none" }.forEachIndexed { index, provider ->
                    val isSelected = state.selectedProvider?.id == provider.id
                    // Track focus via the InteractionSource so the
                    // card draws an Amber border whenever the D-pad
                    // cursor lands on it. The previous design only
                    // shaded the BG when SELECTED — but selection is
                    // a click outcome, not a focus indicator. On TV
                    // the user couldn't tell which card their dpad
                    // had highlighted.
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()
                    val borderColor: Color = when {
                        isFocused -> Amber
                        isSelected -> Amber.copy(alpha = 0.6f)
                        else -> Color.Transparent
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (index == 0 && entryFocusRequester != null) {
                                    Modifier.focusRequester(entryFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Amber.copy(alpha = 0.15f) else Gunmetal)
                            .border(if (isFocused) 3.dp else 2.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { viewModel.selectProvider(provider) },
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (provider.logoUrl != null) {
                            AsyncImage(
                                model = provider.logoUrl,
                                contentDescription = provider.name,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Amber.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    provider.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Amber,
                                )
                            }
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Snow,
                            )
                            val subtitle = provider.authMethods.joinToString(" / ") { method ->
                                when (method) {
                                    "oauth" -> "Browser sign-in"
                                    "apikey" -> "API key"
                                    else -> method
                                }
                            }
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Steel,
                                )
                            }
                        }

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Amber),
                            )
                        }
                    }
                }
            }
        }
    }
}
