package com.torve.desktop.ui.v2.search

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torve.data.ai.AiProvider
import com.torve.data.ai.KeywordSearchResult
import com.torve.data.ai.KeywordSearchService
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbMappers
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2Atmosphere
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.discovery.DiscoveryControls
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownKey
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownOption
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownUiModel
import com.torve.desktop.ui.v2.discovery.MoodFilterUiModel
import com.torve.desktop.ui.v2.discovery.mixedDiscoveryFilterConfig
import com.torve.domain.discovery.applyMoodFilter
import com.torve.domain.discovery.nextSelectedMoodId
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.search.SearchUiState
import kotlinx.coroutines.launch

/**
 * Combined catalogue + AI search page.
 *
 * The AI mode is an inline toggle on the same search bar - no extra
 * window, no overlay. Pressing the toggle:
 *   * with a configured AI provider → switches the search to natural-
 *     language mode. The TMDB results pane is replaced with AI-resolved
 *     results until toggled off.
 *   * without a configured AI provider → refuses to switch, surfaces
 *     a banner with a button to open the provider settings.
 *
 * A small badge next to the toggle shows which AI provider is connected
 * (e.g. "Claude", "ChatGPT") so the user always knows what's about to
 * answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun V2SearchPage(
    searchState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onApplyFilter: (String?) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    /** Currently configured AI provider from Settings. */
    aiProvider: AiProvider,
    /** Active API key for that provider - blank means "not set up". */
    aiApiKey: String,
    /**
     * Caller jumps the user to the AI provider section in Settings. Wired
     * by V2App; null disables the "Open Settings" affordance on the
     * not-configured banner.
     */
    onOpenAiProviderSettings: (() -> Unit)? = null,
    /** Lazy lookup so we don't pay Koin cost when AI mode is never enabled. */
    keywordSearch: () -> KeywordSearchService,
    tmdb: () -> TmdbApiClient,
) {
    val colors = TorveDesktopThemeTokens.colors
    val scope = rememberCoroutineScope()

    val providerConfigured = aiApiKey.isNotBlank()
    var aiMode by remember { mutableStateOf(false) }
    var aiQuery by remember { mutableStateOf("") }
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiResult by remember { mutableStateOf<KeywordSearchResult?>(null) }
    var aiItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var notConfiguredNotice by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(true) }
    var selectedMoodId by remember { mutableStateOf("all") }
    val searchMoodChips = remember {
        listOf(
            MoodFilterUiModel("all", "All moods"),
            MoodFilterUiModel("dark", "Dark"),
            MoodFilterUiModel("funny", "Funny"),
            MoodFilterUiModel("cinematic", "Cinematic"),
            MoodFilterUiModel("acclaimed", "Critically acclaimed"),
            MoodFilterUiModel("hidden", "Hidden gems"),
        )
    }

    // If the user disconnects the provider mid-session, force AI mode
    // off so the search bar doesn't keep accepting AI prompts that
    // would 401.
    LaunchedEffect(providerConfigured) {
        if (!providerConfigured) {
            aiMode = false
            aiResult = null
            aiItems = emptyList()
        }
    }

    suspend fun resolveSpecific(items: List<com.torve.data.ai.SpecificItem>): List<MediaItem> =
        items.mapNotNull { sp ->
            runCatching {
                if (sp.mediaType == "tv") {
                    TmdbMappers.tvToMediaItem(tmdb().getTvDetail(sp.tmdbId))
                } else {
                    TmdbMappers.movieToMediaItem(tmdb().getMovieDetail(sp.tmdbId))
                }
            }.getOrNull()
        }

    suspend fun runDiscover(r: KeywordSearchResult): List<MediaItem> = runCatching {
        if (r.mediaType == "tv") {
            tmdb().discoverTv(
                page = 1,
                sortBy = r.sortBy,
                withGenres = r.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                minRating = r.minRating,
                year = r.yearFrom,
                yearTo = r.yearTo,
            ).results.map(TmdbMappers::tvToMediaItem)
        } else {
            tmdb().discoverMovies(
                page = 1,
                sortBy = r.sortBy,
                withGenres = r.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                minRating = r.minRating,
                year = r.yearFrom,
                yearTo = r.yearTo,
            ).results.map(TmdbMappers::movieToMediaItem)
        }
    }.getOrDefault(emptyList())

    fun runAiSearch(prompt: String) {
        if (prompt.isBlank() || !providerConfigured) return
        scope.launch {
            aiLoading = true
            aiError = null
            try {
                val r = keywordSearch().searchWithAi(aiProvider, aiApiKey, prompt)
                aiResult = r
                aiItems = if (r.specificItems.isNotEmpty()) {
                    resolveSpecific(r.specificItems)
                } else {
                    runDiscover(r)
                }
            } catch (t: Throwable) {
                aiError = t.message ?: "AI search failed."
                aiItems = emptyList()
            } finally {
                aiLoading = false
            }
        }
    }

    val scrollState = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        val atmosphereBackdrop = if (aiMode) {
            aiItems.firstOrNull()?.backdropUrl
        } else {
            searchState.results.firstOrNull()?.backdropUrl
        }
        V2Atmosphere(atmosphereBackdrop, Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val contentTypeValue = when (searchState.filter.mediaType) {
                "movie" -> "Movies"
                "tv" -> "TV Shows"
                else -> "All"
            }
            DiscoveryControls(
                config = mixedDiscoveryFilterConfig(
                    aiAvailable = true,
                    geminiReadyAvailable = aiProvider == AiProvider.GEMINI && providerConfigured,
                    showYear = false,
                ),
                query = if (aiMode) aiQuery else searchState.query,
                onQueryChange = if (aiMode) ({ value -> aiQuery = value }) else onQueryChange,
                isGeminiReady = aiProvider == AiProvider.GEMINI && providerConfigured,
                isAiSearchEnabled = providerConfigured,
                isAiSearchActive = aiMode,
                aiLoading = aiLoading,
                onSearchSubmit = if (aiMode) ({ runAiSearch(aiQuery) }) else null,
                onAiSearchClick = {
                    if (aiMode) {
                        aiMode = false
                        notConfiguredNotice = false
                    } else if (providerConfigured) {
                        aiMode = true
                        notConfiguredNotice = false
                    } else {
                        notConfiguredNotice = true
                    }
                },
                filtersExpanded = filtersExpanded,
                onFiltersClick = { filtersExpanded = !filtersExpanded },
                canReset = searchState.filter.mediaType != null ||
                    searchState.query.isNotBlank() ||
                    aiQuery.isNotBlank() ||
                    aiMode ||
                    selectedMoodId != "all",
                onResetClick = {
                    onApplyFilter(null)
                    onQueryChange("")
                    aiQuery = ""
                    aiMode = false
                    selectedMoodId = "all"
                    aiResult = null
                    aiItems = emptyList()
                    notConfiguredNotice = false
                },
                dropdowns = listOf(
                    DiscoveryDropdownUiModel(
                        key = DiscoveryDropdownKey.ContentType,
                        label = "Type",
                        value = contentTypeValue,
                        options = listOf(
                            DiscoveryDropdownOption("All") { onApplyFilter(null) },
                            DiscoveryDropdownOption("Movies") { onApplyFilter("movie") },
                            DiscoveryDropdownOption("TV Shows") { onApplyFilter("tv") },
                        ),
                    ),
                ),
                moods = searchMoodChips,
                selectedMoodIds = setOf(selectedMoodId),
                onMoodClick = { mood ->
                    selectedMoodId = nextSelectedMoodId(selectedMoodId, mood.id)
                },
            )
            // ── Search bar with inline AI toggle + provider status ──
            if (notConfiguredNotice && !providerConfigured) {
                TorveBanner(
                    title = ds("AI search needs a provider"),
                    description = ds("Set up an AI provider (Claude, ChatGPT, Gemini, Perplexity, or DeepSeek) in Settings → Integrations → AI Provider before turning AI search on."),
                    tone = TorveBannerTone.Warning,
                )
                onOpenAiProviderSettings?.let { open ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TorveGhostButton(
                            text = ds("Open AI provider settings"),
                            onClick = {
                                notConfiguredNotice = false
                                open()
                            },
                        )
                        TorveGhostButton(
                            text = ds("Dismiss"),
                            onClick = { notConfiguredNotice = false },
                        )
                    }
                }
            }

            // ── AI mode panes ─────────────────────────────────────
            if (aiMode) {
                if (aiLoading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }
                aiError?.let { err ->
                    TorveBanner(
                        title = ds("AI search failed"),
                        description = err,
                        tone = TorveBannerTone.Error,
                    )
                }
                aiResult?.let { r ->
                    if (r.specificItems.isEmpty() && aiItems.isEmpty() && !aiLoading) {
                        val bannerTitle = r.title.takeIf { it.isNotBlank() } ?: "No matches"
                        TorveBanner(
                            title = bannerTitle,
                            description = ds("Try rephrasing - the AI couldn't surface a confident match."),
                            tone = TorveBannerTone.Info,
                        )
                    }
                }
                val filteredAiItems = remember(aiItems, selectedMoodId, searchState.filter.mediaType) {
                    aiItems
                        .filter { item ->
                            when (searchState.filter.mediaType) {
                                "movie" -> item.type == MediaType.MOVIE
                                "tv" -> item.type == MediaType.SERIES
                                else -> true
                            }
                        }
                        .applyMoodFilter(selectedMoodId)
                }
                if (filteredAiItems.isNotEmpty()) {
                    Text(
                        text = "${filteredAiItems.size} AI results",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        filteredAiItems.forEach { item ->
                            V2PosterCard(
                                title = item.title,
                                imageUrl = item.posterUrl,
                                modifier = Modifier.width(155.dp),
                                year = item.year?.toString(),
                                rating = item.rating?.let { String.format("%.1f", it) },
                                backdropUrl = item.backdropUrl,
                                overview = item.overview,
                                onClick = { onOpenDetail(item) },
                            )
                        }
                    }
                }
                return@Column
            }

            // ── Catalogue mode (original behavior) ────────────────
            if (searchState.isSearching) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
            searchState.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
            }
            val results = remember(searchState.results, selectedMoodId) {
                searchState.results.applyMoodFilter(selectedMoodId)
            }
            if (results.isNotEmpty()) {
                Text(
                    text = "${results.size} results",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    results.forEach { item ->
                        V2PosterCard(
                            title = item.title,
                            imageUrl = item.posterUrl,
                            modifier = Modifier.width(155.dp),
                            year = item.year?.toString(),
                            rating = item.rating?.let { String.format("%.1f", it) },
                            backdropUrl = item.backdropUrl,
                            overview = item.overview,
                            onClick = { onOpenDetail(item) },
                        )
                    }
                }
            } else if (!searchState.isSearching && searchState.query.isNotBlank()) {
                Spacer(Modifier.height(32.dp))
                com.torve.desktop.ui.components.TorvePlaceholderState(
                    title = ds("No results"),
                    description = "Nothing matched \"${searchState.query}\". Try a different spelling, or browse Discover below.",
                    emoji = "🔎",
                )
            }
            val discoverResults = remember(searchState.discoverResults, selectedMoodId) {
                searchState.discoverResults.applyMoodFilter(selectedMoodId)
            }
            if (discoverResults.isNotEmpty() && searchState.query.isBlank()) {
                Text(
                    text = ds("Discover"),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    discoverResults.forEach { item ->
                        V2PosterCard(
                            title = item.title,
                            imageUrl = item.posterUrl,
                            modifier = Modifier.width(155.dp),
                            year = item.year?.toString(),
                            rating = item.rating?.let { String.format("%.1f", it) },
                            backdropUrl = item.backdropUrl,
                            overview = item.overview,
                            onClick = { onOpenDetail(item) },
                        )
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 4.dp),
        )
    }
}
