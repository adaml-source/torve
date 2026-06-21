package com.torve.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.mediaItemLazyKey
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.MediaItem
import com.torve.domain.model.resolveCardStyle
import com.torve.presentation.detail.PersonViewModel
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    personId: Int,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    viewModel: PersonViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )

    LaunchedEffect(personId) { viewModel.loadPerson(personId) }

    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.torve.android.ui.components.BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                state.personName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(48.dp))
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = com.torve.android.error.resolveErrorKey(androidx.compose.ui.platform.LocalContext.current, state.error) ?: stringResource(R.string.error_unknown),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            else -> {
                // Person info header
                if (state.profileUrl != null || state.biography.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        state.profileUrl?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = state.personName,
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (state.knownFor.isNotBlank()) {
                            Text(
                                text = state.knownFor,
                                style = MaterialTheme.typography.bodySmall,
                                color = Torve.colors.textTertiary,
                            )
                        }
                        if (state.biography.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            var bioExpanded by remember { mutableStateOf(false) }
                            Text(
                                text = state.biography,
                                style = MaterialTheme.typography.bodySmall,
                                color = Torve.colors.textSecondary,
                                maxLines = if (bioExpanded) Int.MAX_VALUE else 4,
                                overflow = if (bioExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.clickable { bioExpanded = !bioExpanded },
                            )
                        }
                    }
                }

                // Filmography header
                Text(
                    text = stringResource(R.string.person_filmography),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Torve.colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )

                // Filmography grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(state.credits, key = { index, item -> mediaItemLazyKey(item, index) }) { _, item ->
                        PosterCard(
                            item = item,
                            sizeOverride = CardSize.SMALL,
                            onClick = { onMediaClick(item) },
                        )
                    }
                }
            }
        }
    }
    }
}
