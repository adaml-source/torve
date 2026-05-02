package com.torve.desktop.ui.v2.seeall

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.DesktopRatingPills
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.seeall.SeeAllSortMode
import com.torve.presentation.seeall.SeeAllViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val sortOptions = listOf(
    SeeAllSortMode.DEFAULT to "Default",
    SeeAllSortMode.A_Z to "Title A-Z",
    SeeAllSortMode.Z_A to "Title Z-A",
    SeeAllSortMode.YEAR_DESC to "Newest",
    SeeAllSortMode.YEAR_ASC to "Oldest",
    SeeAllSortMode.IMDB_DESC to "Highest rated (IMDb)",
    SeeAllSortMode.TMDB_DESC to "Highest rated (TMDB)",
)

private enum class YearBucket(val label: String, val range: IntRange?) {
    ANY("Any year", null),
    Y2020S("2020s", 2020..2099),
    Y2010S("2010s", 2010..2019),
    Y2000S("2000s", 2000..2009),
    OLDER("Pre-2000", 0..1999),
}

private enum class RatingBucket(val label: String, val min: Double) {
    ANY("Any rating", 0.0),
    R6("6+", 6.0),
    R7("7+", 7.0),
    R8("8+", 8.0),
    R9("9+", 9.0),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun V2SeeAllPage(
    request: SeeAllRequest,
    viewModel: SeeAllViewModel,
    metadataRepository: MetadataRepository,
    onBack: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val state by viewModel.state.collectAsState()

    LaunchedEffect(request.sectionId) {
        if (request.sectionId.startsWith("shelf:")) {
            val key = request.sectionId.removePrefix("shelf:")
            SeeAllViewModel.pendingItems[key] = request.title to request.fallbackItems
        }
        viewModel.loadSection(request.sectionId)
        // Desktop default: sort by IMDb rating (falls back to TMDB if IMDb absent).
        viewModel.setSortMode(SeeAllSortMode.IMDB_DESC)
    }

    val displayed = state.displayedItems
    var selectedIndex by remember(request.sectionId) { mutableStateOf(0) }
    val safeIndex = selectedIndex.coerceIn(0, (displayed.size - 1).coerceAtLeast(0))
    val baseSelected = displayed.getOrNull(safeIndex)

    // Cache TMDB-fetched detail per item id. The list item's ratings (enricher updates them
    // asynchronously) is the source of truth; we merge detail's cast/director/genres/logo on top.
    val detailCache = remember(request.sectionId) { mutableStateMapOf<String, MediaItem>() }
    LaunchedEffect(baseSelected?.id, baseSelected?.tmdbId) {
        val base = baseSelected ?: return@LaunchedEffect
        if (detailCache.containsKey(base.id)) return@LaunchedEffect
        val tmdbId = base.tmdbId ?: return@LaunchedEffect
        val type = if (base.type == MediaType.SERIES) "tv" else "movie"
        val detail = runCatching { withContext(Dispatchers.IO) { metadataRepository.getDetail(type, tmdbId) } }.getOrNull()
        if (detail != null) detailCache[base.id] = detail
    }
    val enrichedSelected: MediaItem? = baseSelected?.let { base ->
        val detail = detailCache[base.id]
        if (detail == null) base
        else base.copy(
            cast = base.cast.ifEmpty { detail.cast },
            director = base.director?.takeIf { it.isNotBlank() } ?: detail.director,
            directorId = base.directorId ?: detail.directorId,
            directorProfileUrl = base.directorProfileUrl ?: detail.directorProfileUrl,
            genres = base.genres.ifEmpty { detail.genres },
            overview = base.overview?.takeIf { it.isNotBlank() } ?: detail.overview,
            logoUrl = base.logoUrl ?: detail.logoUrl,
            backdropUrl = base.backdropUrl ?: detail.backdropUrl,
            runtime = base.runtime ?: detail.runtime,
            tagline = base.tagline ?: detail.tagline,
            // ratings intentionally kept from base so live enrichment updates are visible.
        )
    }

    var yearBucket by remember { mutableStateOf(YearBucket.ANY) }
    var ratingBucket by remember { mutableStateOf(RatingBucket.ANY) }
    val locallyFiltered = remember(displayed, yearBucket, ratingBucket) {
        displayed
            .filter { yearBucket.range?.let { r -> it.year != null && it.year in r } ?: true }
            .filter { (it.rating ?: 0.0) >= ratingBucket.min }
    }

    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= (locallyFiltered.size - 8)
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.isLoading) {
        if (nearEnd && state.hasMore && !state.isLoading && locallyFiltered.size >= 10) {
            viewModel.loadMore()
        }
    }

    LaunchedEffect(safeIndex) {
        val info = gridState.layoutInfo.visibleItemsInfo
        val firstVisible = info.firstOrNull()?.index ?: 0
        val lastVisible = info.lastOrNull()?.index ?: 0
        if (safeIndex < firstVisible || safeIndex > lastVisible) {
            runCatching { gridState.animateScrollToItem(safeIndex) }
        }
    }

    var columns by remember { mutableStateOf(6) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(request.sectionId) { runCatching { focusRequester.requestFocus() } }

    var sortMenuExpanded by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.shellBackground)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val size = locallyFiltered.size
                if (size == 0) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { selectedIndex = (safeIndex - 1).coerceAtLeast(0); true }
                    Key.DirectionRight -> { selectedIndex = (safeIndex + 1).coerceAtMost(size - 1); true }
                    Key.DirectionUp -> { selectedIndex = (safeIndex - columns).coerceAtLeast(0); true }
                    Key.DirectionDown -> { selectedIndex = (safeIndex + columns).coerceAtMost(size - 1); true }
                    Key.Enter, Key.NumPadEnter -> {
                        locallyFiltered.getOrNull(safeIndex)?.let { onOpenDetail(it) }
                        true
                    }
                    Key.Escape -> { onBack(); true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header - leave space on the right so sort button doesn't collide with user badge.
            Row(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 200.dp, top = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
                    color = colors.fieldSurface.copy(alpha = 0.45f),
                    shape = CircleShape,
                ) {
                    Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.textPrimary)
                    }
                }
                Text(
                    state.title.ifBlank { request.title },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${locallyFiltered.size}${if (state.hasMore) "+" else ""} items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                Spacer(Modifier.weight(1f))
                Box {
                    val label = sortOptions.firstOrNull { it.first == state.sortMode }?.second ?: "Default"
                    Surface(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { sortMenuExpanded = true },
                        ),
                        color = colors.fieldSurface.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Sort, null, tint = colors.textSecondary)
                            Text("Sort: $label", style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
                        }
                    }
                    TorveDropdownScaffold(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                        items = sortOptions.map { (mode, lbl) ->
                            lbl to { sortMenuExpanded = false; viewModel.setSortMode(mode) }
                        },
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 28.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YearBucket.entries.forEach { yf ->
                        TorveFilterChip(yf.label, yearBucket == yf, onClick = { yearBucket = yf })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RatingBucket.entries.forEach { rf ->
                        TorveFilterChip(rf.label, ratingBucket == rf, onClick = { ratingBucket = rf })
                    }
                }
                val availableGenres = state.availableGenres
                if (availableGenres.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TorveFilterChip(
                            "All genres",
                            state.filterGenreIds.isEmpty(),
                            onClick = { viewModel.clearFilters() },
                        )
                        availableGenres.take(20).forEach { (id, name) ->
                            TorveFilterChip(name, id in state.filterGenreIds, onClick = { viewModel.toggleGenre(id) })
                        }
                    }
                }
            }

            Row(Modifier.fillMaxSize()) {
                // Left: info panel with hero banner
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(420.dp)
                        .padding(start = 72.dp, end = 18.dp, bottom = 24.dp),
                ) {
                    InfoPanel(
                        item = enrichedSelected,
                        onOpenDetail = onOpenDetail,
                        onOpenPerson = onOpenPerson,
                    )
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (locallyFiltered.isEmpty() && !state.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No items match your filters.", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                        }
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val minCell = 160
                            val availablePx = constraints.maxWidth
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val minCellPx = with(density) { minCell.dp.toPx() }.toInt().coerceAtLeast(1)
                            val computedCols = (availablePx / minCellPx).coerceAtLeast(1)
                            LaunchedEffect(computedCols) { columns = computedCols }

                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = minCell.dp),
                                modifier = Modifier.fillMaxSize().padding(end = 24.dp),
                                contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                items(locallyFiltered, key = { it.id + ":" + it.type.name }) { item ->
                                    val idx = locallyFiltered.indexOf(item)
                                    val isSelected = idx == safeIndex
                                    Box(
                                        Modifier.then(
                                            if (isSelected) Modifier.background(colors.accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)).padding(3.dp)
                                            else Modifier,
                                        ),
                                    ) {
                                        V2PosterCard(
                                            title = item.title,
                                            imageUrl = item.posterUrl,
                                            modifier = Modifier.width(160.dp),
                                            year = item.year?.toString(),
                                            rating = item.rating?.let { String.format("%.1f", it) },
                                            ratings = item.ratings,
                                            backdropUrl = item.backdropUrl,
                                            overview = item.overview,
                                            onClick = { selectedIndex = idx },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.isLoading && locallyFiltered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent)
                        }
                    } else if (state.isLoading) {
                        Box(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp).align(Alignment.BottomCenter),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(
    item: MediaItem?,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select an item", color = colors.textMuted)
        }
        return
    }

    val backdrop = rememberCachedBitmap(item.backdropUrl ?: item.posterUrl)
    val logo = rememberCachedBitmap(item.logoUrl)
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.cardSurface.copy(alpha = 0.6f))
            .verticalScroll(scroll),
    ) {
        // Hero banner
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Brush.linearGradient(listOf(colors.fieldSurface, colors.shellBackground))),
        ) {
            Crossfade(backdrop, label = "sa_hero") { bmp ->
                if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1.0f to colors.cardSurface.copy(alpha = 0.95f),
                        ),
                    ),
            )
            // Logo if present, otherwise title
            Box(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Crossfade(logo, label = "sa_logo") { art ->
                    if (art != null) {
                        Image(
                            bitmap = art,
                            contentDescription = item.title,
                            modifier = Modifier.height(56.dp).fillMaxWidth(0.85f),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                        )
                    } else {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (logo != null) {
                // When logo is shown in the hero, still show title as secondary so it's readable.
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val metaParts = listOfNotNull(
                item.year?.toString(),
                item.runtime?.takeIf { it > 0 }?.let { "${it}m" },
                if (item.type == MediaType.SERIES) "TV Show" else "Movie",
            )
            if (metaParts.isNotEmpty()) {
                Text(
                    metaParts.joinToString("  \u00B7  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }

            if (item.ratings != null) {
                DesktopRatingPills(ratings = item.ratings)
            }

            if (item.genres.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.genres.forEach { g ->
                        Surface(color = colors.fieldSurface.copy(alpha = 0.55f), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                g.name,
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }

            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!item.director.isNullOrBlank() && item.directorId != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Director", style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
                    PersonTile(
                        name = item.director!!,
                        subtitle = null,
                        profileUrl = item.directorProfileUrl,
                        onClick = { onOpenPerson(item.directorId!!) },
                    )
                }
            }

            if (item.cast.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cast", style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.cast.take(5).forEach { member ->
                            PersonTile(
                                name = member.name,
                                subtitle = member.character?.takeIf { it.isNotBlank() },
                                profileUrl = member.profileUrl,
                                onClick = { onOpenPerson(member.id) },
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onOpenDetail(item) },
                ),
                color = colors.accent,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = "Open details",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0A0B14),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PersonTile(
    name: String,
    subtitle: String?,
    profileUrl: String?,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val bmp = rememberCachedBitmap(profileUrl)
    Row(
        Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.fieldSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Filled.Person, null, tint = colors.textMuted, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
