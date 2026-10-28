package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.torve.android.ui.catalog.CatalogScreen
import com.torve.android.ui.components.PosterCard
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.catalog.CatalogCategory
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@Composable
fun PhoneDiscoveryScreen(viewModel: CatalogViewModel, mediaType: String, isActive: Boolean,
    onMediaClick: (MediaItem) -> Unit, settings: SettingsViewModel = koinInject(),
    home: HomeViewModel = koinInject()) {
    val preferences by settings.state.collectAsState()
    val state by viewModel.state.collectAsState()
    var browseAll by rememberSaveable(mediaType) { mutableStateOf(false) }
    if (!preferences.tmdbExtensionsLoaded) { CircularProgressIndicator(); return }
    if (!preferences.tmdbCatalogueEnabled) {
        ExtensionCatalogueHome(mediaType, onMediaClick)
        return
    }
    if (browseAll) {
        BackHandler { browseAll = false }
        CatalogScreen(viewModel, mediaType, onMediaClick, onBack = { browseAll = false }, isActive = isActive)
        return
    }
    LaunchedEffect(isActive, preferences.tmdbCatalogueEnabled) { if (isActive) viewModel.refresh() }
    val genres = if (mediaType == "movie") listOf(28 to "Action",12 to "Adventure",16 to "Animation",35 to "Comedy",80 to "Crime",18 to "Drama",27 to "Horror",10749 to "Romance",878 to "Science Fiction",53 to "Thriller")
        else listOf(10759 to "Action & Adventure",16 to "Animation",35 to "Comedy",80 to "Crime",99 to "Documentary",18 to "Drama",10751 to "Family",9648 to "Mystery",10765 to "Sci-Fi & Fantasy")
    LazyColumn(Modifier.fillMaxSize().testTag("discovery.rails"), contentPadding = PaddingValues(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy((preferences.railSpacingDp ?: 24).dp)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedButton(onClick = { viewModel.selectGenre(null); browseAll = true }) { Text(localizedUiText("Browse all")) } }
                items(genres) { (id, title) -> FilterChip(false, { viewModel.selectGenre(id); browseAll = true }, label = { Text(title) }) }
            }
        }
        if (state.isLoading && !state.shelvesLoaded) item { CircularProgressIndicator(Modifier.padding(20.dp)) }
        if (state.error != null && state.trendingItems.isEmpty()) item {
            Column(Modifier.padding(20.dp)) {
                Text(localizedUiText("Couldn’t load this catalogue."))
                TextButton(onClick = viewModel::refresh) { Text(localizedUiText("Try again")) }
            }
        }
        val continuing = state.continueWatching.filter { it.mediaType == (if (mediaType == "movie") MediaType.MOVIE else MediaType.SERIES) }
            .map { MediaItem(id = it.mediaId, type = it.mediaType, title = it.title, posterUrl = it.posterUrl, backdropUrl = it.backdropUrl) }
        val rows = listOf(Triple("Continue Watching", continuing, CatalogCategory.IN_PROGRESS),
            Triple("Trending", state.trendingItems, CatalogCategory.TRENDING),
            Triple("Popular", state.popularItems, CatalogCategory.POPULAR),
            Triple("Top Rated", state.topRatedItems, CatalogCategory.TOP_RATED))
        rows.forEach { (title, titles, category) ->
            if (titles.isNotEmpty()) item(key = title) {
                DiscoveryRail(title, titles, onMediaClick) { viewModel.selectGenre(null); viewModel.selectCategory(category); browseAll = true }
            }
        }
    }
}

@Composable
fun ExtensionCatalogueHome(mediaType: String, onMediaClick: (MediaItem) -> Unit, isTv: Boolean = false,
    entryFocus: FocusRequester? = null, railFocus: FocusRequester? = null,
    requestEntryFocus: Boolean = false, onEntryFocused: (FocusRequester) -> Unit = {},
    onFirstContentRequester: (FocusRequester) -> Unit = {},
    home: HomeViewModel = koinInject(), settings: SettingsViewModel = koinInject()) {
    val state by home.state.collectAsState()
    val preferences by settings.state.collectAsState()
    var manage by rememberSaveable { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(manage, browsing, requestEntryFocus) {
        if (!manage && browsing == null && entryFocus != null) {
            onFirstContentRequester(entryFocus)
            if (requestEntryFocus) { kotlinx.coroutines.delay(150); runCatching { entryFocus.requestFocus() } }
        }
    }
    if (manage) { ExtensionsScreen(onBack = { manage = false }, isTv = isTv); return }
    browsing?.let { shelf ->
        ExtensionBrowserScreen(shelf, mediaType = mediaType, isTv = isTv,
            requestEntryFocus = requestEntryFocus, onEntryFocused = onEntryFocused,
            onFirstContentRequester = onFirstContentRequester, railFocusRequester = railFocus,
            onBack = { browsing = null }, onMedia = onMediaClick, onManage = { manage = true })
        return
    }
    LaunchedEffect(preferences.personalDataUserId, preferences.tmdbCatalogueEnabled) { home.refresh() }
    val rows = state.addonShelves.mapNotNull { shelf ->
        val items = shelf.items.filter { mediaType == "all" || it.type == (if (mediaType == "movie") MediaType.MOVIE else MediaType.SERIES) }
        shelf.copy(items = items).takeIf { items.isNotEmpty() }
    }
    val entryModifier = Modifier.onFocusChanged { if (it.isFocused && entryFocus != null) onEntryFocused(entryFocus) }.then(if (entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
        .then(if (railFocus != null) Modifier.focusProperties { left = railFocus } else Modifier)
    LazyColumn(Modifier.fillMaxSize().testTag("extensions.catalogues"), contentPadding = PaddingValues(vertical = 24.dp, horizontal = if (isTv) 40.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy((preferences.railSpacingDp ?: 24).dp + 12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isTv || mediaType == "all") Text(localizedUiText(if (mediaType == "movie") "Movies" else if (mediaType == "tv") "TV Shows" else "Your Home"), style = MaterialTheme.typography.headlineMedium)
                Text(localizedUiText("Browse your installed catalogues or choose the optional TMDB catalogue in Extensions."))
                Button(onClick = { manage = true }, modifier = entryModifier.testTag("extensions.manage")) { Text(localizedUiText("Manage extensions")) }
            }
        }
        if (state.isLoading && rows.isEmpty()) item { CircularProgressIndicator() }
        rows.forEach { shelf -> item(key = shelf.id) { DiscoveryRail(shelf.title, shelf.items, onMediaClick, { browsing = shelf.id }) } }
        if (!state.isLoading && rows.isEmpty()) item { Text(localizedUiText("No catalogues to show yet. Add an extension or enable TMDB browsing to get started.")) }
        item { TextButton(onClick = home::refresh) { Text(localizedUiText("Refresh catalogues")) } }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable private fun DiscoveryRail(title: String, items: List<MediaItem>, onMediaClick: (MediaItem) -> Unit, onSeeAll: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (onSeeAll != null) TextButton(onClick = onSeeAll) { Text(localizedUiText("See All")) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.type.name + ":" + it.id }) { item -> PosterCard(item, { onMediaClick(item) }) }
        }
    }
}
