package com.torve.desktop.ui.v2.movies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torve.data.ai.AiProvider
import com.torve.data.metadata.TmdbGenres
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.discovery.BrandFilterUiModel
import com.torve.desktop.ui.v2.discovery.DiscoveryControls
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownKey
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownOption
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownUiModel
import com.torve.desktop.ui.v2.discovery.GenreFilterUiModel
import com.torve.desktop.ui.v2.discovery.MoodFilterUiModel
import com.torve.desktop.ui.v2.discovery.movieDiscoveryFilterConfig
import com.torve.desktop.ui.v2.discovery.tvDiscoveryFilterConfig
import com.torve.domain.discovery.DiscoveryRatingSource
import com.torve.domain.discovery.nextSelectedMoodId
import com.torve.domain.discovery.ratingThresholdLabel
import com.torve.domain.model.MediaItem
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.catalog.RuntimeFilter
import com.torve.presentation.catalog.SortOption

enum class CatalogMediaType { MOVIE, TV }

@Composable
fun V2CatalogFilterBar(
    catalogViewModel: CatalogViewModel,
    mediaType: CatalogMediaType,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    startPadding: Dp = 72.dp,
    modifier: Modifier = Modifier,
    aiProvider: AiProvider = AiProvider.CLAUDE,
    aiProviderConfigured: Boolean = false,
    aiMode: Boolean = false,
    aiQuery: String = "",
    aiLoading: Boolean = false,
    onAiModeChange: (Boolean) -> Unit = {},
    onAiQueryChange: (String) -> Unit = {},
    onRunAiSearch: () -> Unit = {},
    onOpenAiProviderSettings: (() -> Unit)? = null,
    providerLogoUrls: Map<Int, String> = emptyMap(),
    selectedMoodId: String = "all",
    onMoodSelected: (String) -> Unit = {},
) {
    val state by catalogViewModel.state.collectAsState()
    val colors = TorveDesktopThemeTokens.colors
    var filtersVisible by remember { mutableStateOf(false) }
    var aiNotConfiguredNotice by remember { mutableStateOf(false) }
    val currentSort = state.filter.sortBy

    LaunchedEffect(mediaType, state.providerId) {
        if (mediaType == CatalogMediaType.MOVIE && state.providerId != null) {
            catalogViewModel.clearProvider()
        }
    }

    val genres = remember(mediaType) {
        when (mediaType) {
            CatalogMediaType.MOVIE -> TmdbGenres.MOVIE_GENRES
            CatalogMediaType.TV -> TmdbGenres.TV_GENRES
        }
    }
    val activeCount = state.filter.activeCount +
        (if (state.selectedGenreId != null) 1 else 0) +
        (if (mediaType == CatalogMediaType.TV && state.providerId != null) 1 else 0) +
        (if (state.filter.ratingSource != DiscoveryRatingSource.TMDB) 1 else 0) +
        (if (selectedMoodId != "all") 1 else 0)
    val searchActive = searchQuery.isNotBlank() || aiQuery.isNotBlank()

    val yearChoices = remember {
        listOf(
            Triple("Any", null as Int?, null as Int?),
            Triple("2026", 2026, 2026),
            Triple("2025", 2025, 2025),
            Triple("2024", 2024, 2024),
            Triple("2020s", 2020, 2029),
            Triple("2010s", 2010, 2019),
            Triple("2000s", 2000, 2009),
            Triple("1990s", 1990, 1999),
            Triple("1980s", 1980, 1989),
            Triple("Older", 1900, 1979),
        )
    }
    val currentYearLabel = remember(state.filter.year, state.filter.yearTo) {
        val y = state.filter.year
        val yt = state.filter.yearTo
        when {
            y == null && yt == null -> "Any"
            y != null && yt != null && y == yt -> y.toString()
            y != null && yt != null && yt - y == 9 -> "${y}s"
            y != null && yt != null -> "$y-$yt"
            else -> "Custom"
        }
    }
    val currentRuntimeLabel = state.filter.runtimeFilter?.label ?: "Any"
    val currentRatingLabel = ratingThresholdLabel(state.filter.minRating)
    val isGeminiReady = aiProvider == AiProvider.GEMINI && aiProviderConfigured
    val config = when (mediaType) {
        CatalogMediaType.MOVIE -> movieDiscoveryFilterConfig(
            aiAvailable = true,
            geminiReadyAvailable = isGeminiReady,
            showYear = true,
            showRuntime = true,
            showRating = true,
            showLanguage = false,
        )
        CatalogMediaType.TV -> tvDiscoveryFilterConfig(
            providerAvailable = DESKTOP_WATCH_PROVIDERS.isNotEmpty(),
            networkAvailable = false,
            aiAvailable = true,
            geminiReadyAvailable = isGeminiReady,
            showYear = true,
            showStatus = false,
            showRuntime = false,
            showRating = true,
            showLanguage = false,
        )
    }

    val dropdowns = buildList {
        if (mediaType == CatalogMediaType.TV) {
            add(
                DiscoveryDropdownUiModel(
                    key = DiscoveryDropdownKey.Provider,
                    label = "Provider",
                    value = DESKTOP_WATCH_PROVIDERS.firstOrNull { it.id == state.providerId }?.label ?: "All",
                    options = listOf(DiscoveryDropdownOption("All") { catalogViewModel.clearProvider() }) +
                        DESKTOP_WATCH_PROVIDERS.map { provider ->
                            DiscoveryDropdownOption(provider.label) { catalogViewModel.setProvider(provider.id) }
                        },
                ),
            )
        }
        add(
            DiscoveryDropdownUiModel(
                key = DiscoveryDropdownKey.Year,
                label = "Year",
                value = currentYearLabel,
                options = yearChoices.map { (label, from, to) ->
                    DiscoveryDropdownOption(label) {
                        catalogViewModel.applyFilter(state.filter.copy(year = from, yearTo = to))
                    }
                },
            ),
        )
        if (mediaType == CatalogMediaType.MOVIE) {
            add(
                DiscoveryDropdownUiModel(
                    key = DiscoveryDropdownKey.Runtime,
                    label = "Runtime",
                    value = currentRuntimeLabel,
                    options = listOf(
                        DiscoveryDropdownOption("Any") {
                            catalogViewModel.applyFilter(state.filter.copy(runtimeFilter = null))
                        },
                    ) + RuntimeFilter.entries.map { runtime ->
                        DiscoveryDropdownOption(runtime.label) {
                            catalogViewModel.applyFilter(state.filter.copy(runtimeFilter = runtime))
                        }
                    },
                ),
            )
        }
        add(
            DiscoveryDropdownUiModel(
                key = DiscoveryDropdownKey.RatingSource,
                label = "Source",
                value = state.filter.ratingSource.shortLabel,
                options = DiscoveryRatingSource.entries.map { source ->
                    DiscoveryDropdownOption(source.label) {
                        catalogViewModel.applyFilter(state.filter.copy(ratingSource = source))
                    }
                },
            ),
        )
        add(
            DiscoveryDropdownUiModel(
                key = DiscoveryDropdownKey.Rating,
                label = "Rating",
                value = currentRatingLabel,
                options = listOf(
                    DiscoveryDropdownOption("Any") {
                        catalogViewModel.applyFilter(state.filter.copy(minRating = null))
                    },
                    DiscoveryDropdownOption("6+") {
                        catalogViewModel.applyFilter(state.filter.copy(minRating = 6f))
                    },
                    DiscoveryDropdownOption("7+") {
                        catalogViewModel.applyFilter(state.filter.copy(minRating = 7f))
                    },
                    DiscoveryDropdownOption("8+") {
                        catalogViewModel.applyFilter(state.filter.copy(minRating = 8f))
                    },
                    DiscoveryDropdownOption("9+") {
                        catalogViewModel.applyFilter(state.filter.copy(minRating = 9f))
                    },
                ),
            ),
        )
        add(
            DiscoveryDropdownUiModel(
                key = DiscoveryDropdownKey.Sort,
                label = "Sort",
                value = currentSort.label
                    .removePrefix("Most ")
                    .removeSuffix(" First")
                    .replace("Highest ", ""),
                options = SortOption.entries.map { option ->
                    DiscoveryDropdownOption(option.label) {
                        catalogViewModel.applyFilter(state.filter.copy(sortBy = option))
                    }
                },
            ),
        )
    }
    val providerChips = remember(providerLogoUrls) {
        listOf(BrandFilterUiModel("all", "All", null)) +
            DESKTOP_WATCH_PROVIDERS.map { provider ->
                BrandFilterUiModel(
                    id = provider.id.toString(),
                    name = provider.label,
                    logoUrl = providerLogoUrls[provider.id],
                )
            }
    }
    val genreChips = remember(genres) {
        listOf(GenreFilterUiModel("all", "All")) +
            genres.entries.map { (id, name) -> GenreFilterUiModel(id.toString(), name) }
    }
    val moodChips = remember(mediaType) {
        when (mediaType) {
            CatalogMediaType.TV -> listOf(
                MoodFilterUiModel("all", "All moods"),
                MoodFilterUiModel("dark", "Dark"),
                MoodFilterUiModel("funny", "Funny"),
                MoodFilterUiModel("cinematic", "Cinematic"),
                MoodFilterUiModel("fast", "Fast-paced"),
                MoodFilterUiModel("comfort", "Comfort watch"),
                MoodFilterUiModel("acclaimed", "Critically acclaimed"),
                MoodFilterUiModel("hidden", "Hidden gems"),
            )
            CatalogMediaType.MOVIE -> listOf(
                MoodFilterUiModel("all", "All moods"),
                MoodFilterUiModel("blockbuster", "Blockbuster"),
                MoodFilterUiModel("acclaimed", "Critically acclaimed"),
                MoodFilterUiModel("easy", "Easy watch"),
                MoodFilterUiModel("date", "Date night"),
                MoodFilterUiModel("dark", "Dark"),
                MoodFilterUiModel("funny", "Funny"),
                MoodFilterUiModel("mind", "Mind-bending"),
                MoodFilterUiModel("hidden", "Hidden gems"),
                MoodFilterUiModel("family", "Family night"),
            )
        }
    }
    val workerProgress = com.torve.desktop.globalCatalogTopWorker?.progress?.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 24.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        workerProgress?.value?.takeIf { it.running }?.let { p ->
            val pct = if (p.totalGenres > 0) (p.processedGenres * 100 / p.totalGenres) else 0
            Text(
                text = "Pre-caching catalog: ${p.processedGenres}/${p.totalGenres} genres ($pct%) - current: ${p.currentLabel}. Genre clicks will be instant once this completes.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.accent,
            )
        }

        DiscoveryControls(
            config = config,
            query = if (aiMode) aiQuery else searchQuery,
            onQueryChange = if (aiMode) onAiQueryChange else onSearchQueryChange,
            isGeminiReady = isGeminiReady,
            isAiSearchEnabled = aiProviderConfigured,
            isAiSearchActive = aiMode,
            aiLoading = aiLoading,
            onSearchSubmit = if (aiMode) onRunAiSearch else null,
            onAiSearchClick = {
                if (aiMode) {
                    onAiModeChange(false)
                    aiNotConfiguredNotice = false
                } else if (aiProviderConfigured) {
                    onAiModeChange(true)
                    aiNotConfiguredNotice = false
                } else {
                    aiNotConfiguredNotice = true
                }
            },
            filtersExpanded = filtersVisible,
            onFiltersClick = { filtersVisible = !filtersVisible },
            activeFilterCount = activeCount,
            canReset = activeCount > 0 || searchActive || aiMode,
            onResetClick = {
                catalogViewModel.clearFilters()
                catalogViewModel.clearProvider()
                onMoodSelected("all")
                onSearchQueryChange("")
                onAiQueryChange("")
                onAiModeChange(false)
                aiNotConfiguredNotice = false
            },
            dropdowns = dropdowns,
            providers = if (mediaType == CatalogMediaType.TV) providerChips else emptyList(),
            genres = genreChips,
            moods = moodChips,
            selectedProviderIds = setOf(state.providerId?.toString() ?: "all"),
            selectedGenreIds = setOf(state.selectedGenreId?.toString() ?: "all"),
            selectedMoodIds = setOf(selectedMoodId),
            onProviderClick = { provider ->
                if (provider.id == "all") {
                    catalogViewModel.clearProvider()
                } else {
                    provider.id.toIntOrNull()?.let { catalogViewModel.setProvider(it) }
                }
            },
            onGenreClick = { genre ->
                if (genre.id == "all") {
                    catalogViewModel.selectGenre(null)
                } else {
                    catalogViewModel.selectGenre(genre.id.toIntOrNull())
                }
            },
            onMoodClick = { mood ->
                onMoodSelected(nextSelectedMoodId(selectedMoodId, mood.id))
            },
        )

        if (aiNotConfiguredNotice && !aiProviderConfigured) {
            TorveBanner(
                title = ds("AI search needs a provider"),
                description = ds("Set up an AI provider in Settings before turning AI search on."),
                tone = TorveBannerTone.Warning,
            )
            onOpenAiProviderSettings?.let { open ->
                TorveGhostButton(
                    text = ds("Open AI provider settings"),
                    onClick = {
                        aiNotConfiguredNotice = false
                        open()
                    },
                )
            }
        }

        if (activeCount > 0 && !aiMode) {
            Text(
                text = "$activeCount active",
                modifier = Modifier.padding(horizontal = 4.dp),
                color = colors.textSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
@Composable
fun V2CatalogAiResultsGrid(
    items: List<MediaItem>,
    isLoading: Boolean,
    error: String?,
    onOpenDetail: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    if (error != null) {
        TorveBanner(
            title = ds("AI search failed"),
            description = error,
            tone = TorveBannerTone.Error,
            modifier = Modifier.padding(start = 72.dp, end = 24.dp),
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 72.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        }
        if (!isLoading && items.isEmpty() && error == null) {
            item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = ds("Ask for a mood, sport, actor, title, or genre."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        items(items, key = { it.id }) { item ->
            V2PosterCard(
                item.title,
                item.posterUrl,
                Modifier.width(150.dp),
                item.year?.toString(),
                item.rating?.let { r -> String.format("%.1f", r) },
                ratings = item.ratings,
                backdropUrl = item.backdropUrl,
                overview = item.overview,
            ) { onOpenDetail(item) }
        }
    }
}
