package com.torve.android.ui.detail

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.device.DeviceFormFactor
import com.torve.android.ui.community.CommunityAction
import com.torve.android.ui.community.ExpandableStory
import com.torve.android.ui.community.PersonalBackground
import com.torve.android.ui.components.*
import com.torve.domain.model.MediaItem
import com.torve.domain.model.resolveCardStyle
import com.torve.domain.model.resolvedWidthDp
import com.torve.presentation.detail.PersonViewModel
import com.torve.presentation.detail.PersonFilmographyPolicy
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PersonScreen(personId: Int, onBack: () -> Unit, onMediaClick: (MediaItem) -> Unit,
    viewModel: PersonViewModel = koinInject(), settingsViewModel: SettingsViewModel = koinInject()) {
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsState()
    val settings by settingsViewModel.state.collectAsState()
    var filter by rememberSaveable(personId, settings.personalDataUserId) { mutableStateOf("roles") }
    var sort by rememberSaveable(personId, settings.personalDataUserId) { mutableStateOf("imdb") }
    val credits = remember(state.credits, filter, sort) { PersonFilmographyPolicy.items(state.credits, filter, sort) }
    val tv = DeviceFormFactor.isTv(LocalContext.current)
    val backEntry = com.torve.android.ui.community.communityEntryFocus()
    val cardStyle = resolveCardStyle(settings.cardStylePresets, null, settings.globalDefaultPresetId)
    LaunchedEffect(personId, settings.personalDataUserId, viewModel) { viewModel.loadPerson(personId) }
    CompositionLocalProvider(LocalRatingPrefs provides settings.ratingPrefs,
        LocalCardStyle provides cardStyle) {
        PersonalBackground {
            LazyVerticalGrid(columns = GridCells.Adaptive(cardStyle.size.resolvedWidthDp().dp.coerceAtLeast(if (tv) 148.dp else 110.dp)),
                contentPadding = PaddingValues(if (tv) 32.dp else 16.dp), modifier = Modifier.fillMaxSize().statusBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CommunityAction(localizedUiText("Back"), onBack, backEntry)
                        Text(state.personName, style = MaterialTheme.typography.headlineSmall)
                    }
                }
                if (state.isLoading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column { Text(localizedUiText("This person's credits could not be loaded.")); CommunityAction(localizedUiText("Retry"), { viewModel.loadPerson(personId) }) }
                }
                if (!state.isLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            state.profileUrl?.let { AsyncImage(it, state.personName, Modifier.size(100.dp)) }
                            Column { Text(state.knownFor); Text(localizedUiText("${state.credits.size} credits")) }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { ExpandableStory(localizedUiText("Biography"), state.biography) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text(localizedUiText("Filmography"), style = MaterialTheme.typography.titleLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("roles" to "Acting roles", "movies" to "Movies", "tv" to "TV shows", "other" to "Appearances & extras").forEach { (key, label) ->
                                    CommunityAction((if (filter == key) "✓ " else "") + label, { filter = key })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("imdb" to "Highest IMDb rating", "popular" to "Popular", "newest" to "Newest").forEach { (key, label) ->
                                    CommunityAction((if (sort == key) "✓ " else "") + label, { sort = key })
                                }
                            }
                        }
                    }
                    items(credits, key = { "${it.type}:${it.id}" }) { media -> PosterCard(media, onClick = { onMediaClick(media) }, fillCell = true) }
                    if (credits.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(localizedUiText("No credits in this category.")) }
                }
            }
        }
    }
}
