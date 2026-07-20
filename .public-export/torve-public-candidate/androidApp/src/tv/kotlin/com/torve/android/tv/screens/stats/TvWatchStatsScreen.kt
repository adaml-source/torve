package com.torve.android.tv.screens.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.premium.rememberEffectivePremiumAccessTier
import com.torve.android.tv.premium.AccessTier
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberGlow
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.MediaType
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchStatsAdvancedGroup
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.WatchStatsRatingGroup
import com.torve.domain.stats.WatchStatsSourceBreakdown
import com.torve.domain.stats.WatchStatsSummary
import com.torve.presentation.stats.WatchStatsContentScope
import com.torve.presentation.stats.WatchStatsFilterState
import com.torve.presentation.stats.WatchStatsHeroInsightModel
import com.torve.presentation.stats.WatchStatsInsightMode
import com.torve.presentation.stats.WatchStatsTvCardModel
import com.torve.presentation.stats.WatchStatsTvDashboardModel
import com.torve.presentation.stats.WatchStatsTvFilter
import com.torve.presentation.stats.WatchStatsTvSection
import com.torve.presentation.stats.WatchStatsTvUiModel
import com.torve.presentation.stats.WatchStatsUiState
import com.torve.presentation.stats.WatchStatsUiText
import com.torve.presentation.stats.WatchStatsViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun TvWatchStatsScreen(
    railFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    entryFocusRequester: FocusRequester,
    viewModel: WatchStatsViewModel = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val accessTier = rememberEffectivePremiumAccessTier(
        subscriptionTier = subscriptionState.subscription?.tier,
        subscriptionIsPro = subscriptionState.isPro,
    )
    val isPremium = accessTier != AccessTier.FREE
    val filterEntryRequester = remember { FocusRequester() }
    val mainStageRequester = remember { FocusRequester() }
    var selection by remember { mutableStateOf(TvWatchStatsFilterSelection()) }
    var selectedContentScope by remember { mutableStateOf(WatchStatsContentScope.ALL) }
    var selectedInsightMode by remember { mutableStateOf(WatchStatsInsightMode.OVERVIEW) }
    val selectedInsightKey = state.selectedInsightKey

    BackHandler {
        if (selectedInsightKey != null) {
            viewModel.selectInsight(null)
        } else {
            onBack()
        }
    }

    LaunchedEffect(entryFocusRequester) {
        onFirstContentRequester(entryFocusRequester)
        repeat(6) {
            withFrameNanos { }
            delay(40)
            runCatching { entryFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(selection) {
        viewModel.updateFilters(selection.toFilterState())
    }

    val hasSimklData = state.summary.sourceBreakdown.any {
        it.source == WatchSessionSource.SIMKL && it.sessionCount > 0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF050606),
                        Color(0xFF090A0A),
                        Color(0xFF050505),
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AmberGlow.copy(alpha = 0.14f),
                            Amber.copy(alpha = 0.055f),
                            Color.Transparent,
                        ),
                        radius = 1650f,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 64.dp, top = 36.dp, end = 64.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            TvWatchStatsDashboard(
                state = state,
                entryFocusRequester = entryFocusRequester,
                railFocusRequester = railFocusRequester,
                filterEntryRequester = filterEntryRequester,
                firstMetricRequester = mainStageRequester,
                isPremium = isPremium,
                hasSimklData = hasSimklData,
                selection = selection,
                selectedContentScope = selectedContentScope,
                selectedInsightMode = selectedInsightMode,
                selectedInsightKey = selectedInsightKey,
                onContentScopeSelected = { scope ->
                    selectedContentScope = scope
                    selection = selection.forContentScope(scope)
                    viewModel.selectInsight(null)
                },
                onInsightModeSelected = { mode ->
                    selectedInsightMode = mode
                    viewModel.selectInsight(null)
                },
                onSelectionChange = { selection = it },
                onSelectInsight = viewModel::selectInsight,
                onReload = viewModel::load,
                onBack = onBack,
                onOpenDetails = onOpenDetails,
                onUpgrade = { onRequestLifetimeUnlock(TvEntitledFeature.ADVANCED_RECOMMENDATIONS) },
                onFocused = onContentFocused,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun TvWatchStatsTimeRange.next(): TvWatchStatsTimeRange {
    val entries = TvWatchStatsTimeRange.entries
    return entries[(entries.indexOf(this) + 1) % entries.size]
}

@Composable
private fun TvWatchStatsDashboard(
    state: WatchStatsUiState,
    entryFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    filterEntryRequester: FocusRequester,
    firstMetricRequester: FocusRequester,
    isPremium: Boolean,
    hasSimklData: Boolean,
    selection: TvWatchStatsFilterSelection,
    selectedContentScope: WatchStatsContentScope,
    selectedInsightMode: WatchStatsInsightMode,
    selectedInsightKey: String?,
    onContentScopeSelected: (WatchStatsContentScope) -> Unit,
    onInsightModeSelected: (WatchStatsInsightMode) -> Unit,
    onSelectionChange: (TvWatchStatsFilterSelection) -> Unit,
    onSelectInsight: (String?) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
    onUpgrade: () -> Unit,
    onFocused: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dashboardModel = WatchStatsTvUiModel.dashboardModel(
        contentScope = selectedContentScope,
        insightMode = selectedInsightMode,
        summary = state.summary,
        isPremium = isPremium,
    )
    val metadataUnavailable = selectedInsightMode in setOf(
        WatchStatsInsightMode.RATINGS,
        WatchStatsInsightMode.GENRES,
        WatchStatsInsightMode.YEARS,
    ) && dashboardModel.isUnavailable
    val modeSessions = state.recentActivity
    val rangeEntryRequester = remember("range_all_time") { FocusRequester() }
    val metricCompletedRequester = remember("metric_completed") { FocusRequester() }
    val metricPartialRequester = remember("metric_partial") { FocusRequester() }
    val metricSourceRequester = remember("metric_source") { FocusRequester() }
    val chartTimeRequester = remember("chart_time_over_time") { FocusRequester() }
    val chartSourceRequester = remember("chart_source_breakdown") { FocusRequester() }
    val topMoviesRequester = remember("list_top_movies") { FocusRequester() }
    val topShowsRequester = remember("list_top_shows") { FocusRequester() }
    val yearRequester = remember("chart_year_distribution") { FocusRequester() }
    val ratingRequester = remember("chart_rating_distribution") { FocusRequester() }
    val recentRequester = remember("activity_first") { FocusRequester() }
    val bottomInsightRequester = remember("bottom_insights") { FocusRequester() }
    val bodyScrollState = rememberScrollState()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TvWatchStatsDashboardHeader(
            isPremium = isPremium,
            selectedTimeRange = selection.timeRange,
            entryFocusRequester = entryFocusRequester,
            railFocusRequester = railFocusRequester,
            timeRangeRequester = rangeEntryRequester,
            down = filterEntryRequester,
            onBack = onBack,
            onTimeRangeClick = {
                onSelectionChange(selection.copy(timeRange = selection.timeRange.next()))
            },
            onFocused = { onFocused(entryFocusRequester) },
        )

        TvWatchStatsDashboardFilters(
            summary = state.summary,
            isPremium = isPremium,
            hasSimklData = hasSimklData,
            selectedContentScope = selectedContentScope,
            selectedInsightMode = selectedInsightMode,
            selection = selection,
            filterEntryRequester = filterEntryRequester,
            rangeEntryRequester = rangeEntryRequester,
            firstMetricRequester = firstMetricRequester,
            onContentScopeSelected = onContentScopeSelected,
            onInsightModeSelected = onInsightModeSelected,
            onSelectionChange = onSelectionChange,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(bodyScrollState)
                .padding(bottom = 17.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvWatchStatsDashboardModeHeading(dashboardModel)

            when {
                state.isLoading -> TvStatsLoadingState(Modifier.height(360.dp).fillMaxWidth())
                state.error != null -> TvStatsErrorState(
                    mainStageRequester = firstMetricRequester,
                    sectionEntryRequester = filterEntryRequester,
                    drilldownRequester = chartTimeRequester,
                    onFocused = onFocused,
                    onRetry = onReload,
                    modifier = Modifier.height(360.dp).fillMaxWidth(),
                )
                state.isEmpty -> TvStatsEmptyState(
                    mainStageRequester = firstMetricRequester,
                    sectionEntryRequester = filterEntryRequester,
                    drilldownRequester = chartTimeRequester,
                    onFocused = onFocused,
                    modifier = Modifier.height(360.dp).fillMaxWidth(),
                )
                metadataUnavailable -> TvWatchStatsModeUnavailableCard(
                    title = dashboardModel.unavailableTitle ?: "Metadata unavailable",
                    body = dashboardModel.unavailableBody ?: "No local metadata is available for this selection yet.",
                    locked = false,
                    requester = firstMetricRequester,
                    left = filterEntryRequester,
                    up = filterEntryRequester,
                    onUpgrade = onUpgrade,
                    onFocused = { onFocused(firstMetricRequester) },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
                else -> {
                    val bottomUpRequester = when {
                        selectedInsightMode == WatchStatsInsightMode.ACTIVITY -> recentRequester
                        selectedInsightMode == WatchStatsInsightMode.OVERVIEW &&
                            selectedContentScope == WatchStatsContentScope.ALL -> chartTimeRequester
                        else -> topMoviesRequester
                    }
                    val heroInsight = dashboardModel.heroInsight
                    if (heroInsight != null) {
                        TvWatchStatsOverviewHeroRow(
                            hero = heroInsight,
                            cards = dashboardModel.metricCards.drop(1).take(3),
                            firstMetricRequester = firstMetricRequester,
                            completedRequester = metricCompletedRequester,
                            partialRequester = metricPartialRequester,
                            sourceRequester = metricSourceRequester,
                            up = filterEntryRequester,
                            down = chartTimeRequester,
                            selectedInsightKey = selectedInsightKey,
                            onSelectInsight = onSelectInsight,
                            onFocused = onFocused,
                        )
                    } else {
                        TvWatchStatsMetricCardsRow(
                            cards = dashboardModel.metricCards,
                            firstMetricRequester = firstMetricRequester,
                            completedRequester = metricCompletedRequester,
                            partialRequester = metricPartialRequester,
                            sourceRequester = metricSourceRequester,
                            up = filterEntryRequester,
                            down = if (selectedInsightMode == WatchStatsInsightMode.ACTIVITY) recentRequester else chartTimeRequester,
                            selectedInsightKey = selectedInsightKey,
                            onSelectInsight = onSelectInsight,
                            onFocused = onFocused,
                        )
                    }

                    if (selectedInsightMode == WatchStatsInsightMode.OVERVIEW &&
                        selectedContentScope == WatchStatsContentScope.ALL
                    ) {
                        TvWatchStatsOverviewModulesRow(
                            summary = state.summary,
                            sessions = modeSessions,
                            chartSourceRequester = chartTimeRequester,
                            recentPreviewRequester = chartSourceRequester,
                            left = filterEntryRequester,
                            upLeft = firstMetricRequester,
                            upRight = metricSourceRequester,
                            downLeft = bottomInsightRequester,
                            downRight = bottomInsightRequester,
                            onFocused = onFocused,
                            onOpenDetails = onOpenDetails,
                        )
                    }

                    if (selectedInsightMode != WatchStatsInsightMode.ACTIVITY &&
                        !(selectedInsightMode == WatchStatsInsightMode.OVERVIEW &&
                            selectedContentScope == WatchStatsContentScope.ALL)
                    ) {
                        TvWatchStatsModeAnalyticsRow(
                            model = dashboardModel,
                            summary = state.summary,
                            sessions = modeSessions,
                            isPremium = isPremium,
                            chartTimeRequester = chartTimeRequester,
                            chartSourceRequester = chartSourceRequester,
                            left = filterEntryRequester,
                            upLeft = firstMetricRequester,
                            upRight = metricSourceRequester,
                            downLeft = topMoviesRequester,
                            downRight = topShowsRequester,
                            onUpgrade = onUpgrade,
                            onFocused = onFocused,
                        )
                        TvWatchStatsModeListsRow(
                            model = dashboardModel,
                            summary = state.summary,
                            sessions = modeSessions,
                            topMoviesRequester = topMoviesRequester,
                            topShowsRequester = topShowsRequester,
                            left = filterEntryRequester,
                            upLeft = chartTimeRequester,
                            upRight = chartSourceRequester,
                            downLeft = bottomInsightRequester,
                            downRight = bottomInsightRequester,
                            onUpgrade = onUpgrade,
                            onFocused = onFocused,
                            onOpenDetails = onOpenDetails,
                        )
                    }

                    if (selectedInsightMode == WatchStatsInsightMode.ACTIVITY) {
                        TvWatchStatsRecentActivityTable(
                            sessions = modeSessions.take(12),
                            firstRequester = recentRequester,
                            up = firstMetricRequester,
                            down = bottomInsightRequester,
                            left = filterEntryRequester,
                            onFocused = onFocused,
                            onOpenDetails = onOpenDetails,
                        )
                    }

                    TvWatchStatsBottomInsightStrip(
                        summary = state.summary,
                        isPremium = isPremium,
                        requester = bottomInsightRequester,
                        up = bottomUpRequester,
                        left = filterEntryRequester,
                        onFocused = { onFocused(bottomInsightRequester) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvWatchStatsDashboardFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF090A0A).copy(alpha = 0.96f),
                        Color(0xFF050606).copy(alpha = 0.98f),
                    ),
                ),
                shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.055f), shape)
            .padding(20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(AmberGlow.copy(alpha = 0.07f), Color.Transparent),
                        radius = 540f,
                    ),
                ),
        )
        content()
    }
}

@Composable
private fun TvWatchStatsDashboardHeader(
    isPremium: Boolean,
    selectedTimeRange: TvWatchStatsTimeRange,
    entryFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    timeRangeRequester: FocusRequester,
    down: FocusRequester,
    onBack: () -> Unit,
    onTimeRangeClick: () -> Unit,
    onFocused: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvStatsPill(
            label = "Back",
            selected = false,
            focusRequester = entryFocusRequester,
            left = railFocusRequester,
            down = down,
            onFocused = onFocused,
            onClick = onBack,
            modifier = Modifier.width(72.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Watch Stats",
                style = MaterialTheme.typography.headlineMedium,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Playback, imports, and manual watches. Sources are labeled.",
                style = MaterialTheme.typography.bodyMedium,
                color = Silver,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TvStatsPill(
            label = selectedTimeRange.label,
            selected = false,
            focusRequester = timeRangeRequester,
            down = down,
            onClick = onTimeRangeClick,
            modifier = Modifier.width(112.dp),
        )
        TvStatsSourceChip(if (isPremium) "Premium" else "Basic")
    }
}

@Composable
private fun TvWatchStatsDashboardFilters(
    summary: WatchStatsSummary,
    isPremium: Boolean,
    hasSimklData: Boolean,
    selectedContentScope: WatchStatsContentScope,
    selectedInsightMode: WatchStatsInsightMode,
    selection: TvWatchStatsFilterSelection,
    filterEntryRequester: FocusRequester,
    rangeEntryRequester: FocusRequester,
    firstMetricRequester: FocusRequester,
    onContentScopeSelected: (WatchStatsContentScope) -> Unit,
    onInsightModeSelected: (WatchStatsInsightMode) -> Unit,
    onSelectionChange: (TvWatchStatsFilterSelection) -> Unit,
    onUpgrade: () -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    val contentFilters = listOf(
        WatchStatsTvFilter.ALL,
        WatchStatsTvFilter.MOVIES,
        WatchStatsTvFilter.SHOWS,
    )
    val insightFilters = listOf(
        WatchStatsTvFilter.OVERVIEW,
        WatchStatsTvFilter.SOURCE,
        WatchStatsTvFilter.RATINGS,
        WatchStatsTvFilter.GENRES,
        WatchStatsTvFilter.YEARS,
        WatchStatsTvFilter.ACTIVITY,
    )
    val filters = contentFilters + insightFilters
    val requesters = filters.mapIndexed { index, filter ->
        if (index == 0) filterEntryRequester else remember(filter.id) { FocusRequester() }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filters.forEachIndexed { index, filter ->
            if (filter == WatchStatsTvFilter.OVERVIEW) {
                Text(
                    text = "View",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash,
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                    maxLines = 1,
                )
            } else if (filter == WatchStatsTvFilter.ALL) {
                Text(
                    text = "Content",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash,
                    modifier = Modifier.padding(end = 2.dp),
                    maxLines = 1,
                )
            }
            val requester = requesters[index]
            val selected = when (filter) {
                WatchStatsTvFilter.ALL -> selectedContentScope == WatchStatsContentScope.ALL
                WatchStatsTvFilter.MOVIES -> selectedContentScope == WatchStatsContentScope.MOVIES
                WatchStatsTvFilter.SHOWS -> selectedContentScope == WatchStatsContentScope.SHOWS
                WatchStatsTvFilter.OVERVIEW -> selectedInsightMode == WatchStatsInsightMode.OVERVIEW
                WatchStatsTvFilter.SOURCE -> selectedInsightMode == WatchStatsInsightMode.SOURCE
                WatchStatsTvFilter.RATINGS -> selectedInsightMode == WatchStatsInsightMode.RATINGS
                WatchStatsTvFilter.GENRES -> selectedInsightMode == WatchStatsInsightMode.GENRES
                WatchStatsTvFilter.YEARS -> selectedInsightMode == WatchStatsInsightMode.YEARS
                WatchStatsTvFilter.ACTIVITY -> selectedInsightMode == WatchStatsInsightMode.ACTIVITY
            }

            TvStatsPill(
                label = filter.label,
                selected = selected,
                focusRequester = requester,
                left = requesters.getOrNull(index - 1),
                right = requesters.getOrNull(index + 1),
                up = rangeEntryRequester,
                down = firstMetricRequester,
                onFocused = { onFocused(requester) },
                onClick = {
                    when (filter) {
                        WatchStatsTvFilter.ALL -> onContentScopeSelected(WatchStatsContentScope.ALL)
                        WatchStatsTvFilter.MOVIES -> onContentScopeSelected(WatchStatsContentScope.MOVIES)
                        WatchStatsTvFilter.SHOWS -> onContentScopeSelected(WatchStatsContentScope.SHOWS)
                        WatchStatsTvFilter.OVERVIEW -> onInsightModeSelected(WatchStatsInsightMode.OVERVIEW)
                        WatchStatsTvFilter.SOURCE -> onInsightModeSelected(WatchStatsInsightMode.SOURCE)
                        WatchStatsTvFilter.RATINGS -> onInsightModeSelected(WatchStatsInsightMode.RATINGS)
                        WatchStatsTvFilter.GENRES -> onInsightModeSelected(WatchStatsInsightMode.GENRES)
                        WatchStatsTvFilter.YEARS -> onInsightModeSelected(WatchStatsInsightMode.YEARS)
                        WatchStatsTvFilter.ACTIVITY -> onInsightModeSelected(WatchStatsInsightMode.ACTIVITY)
                    }
                },
            )
        }
    }
}

@Composable
private fun TvWatchStatsDashboardModeHeading(model: WatchStatsTvDashboardModel) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        TvStatsSectionLabel(model.title)
        Text(
            text = model.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Silver,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvWatchStatsOverviewHeroRow(
    hero: WatchStatsHeroInsightModel,
    cards: List<WatchStatsTvCardModel>,
    firstMetricRequester: FocusRequester,
    completedRequester: FocusRequester,
    partialRequester: FocusRequester,
    sourceRequester: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    selectedInsightKey: String?,
    onSelectInsight: (String?) -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvWatchStatsHeroInsightCard(
            hero = hero,
            requester = firstMetricRequester,
            up = up,
            down = down,
            right = completedRequester,
            onFocused = { onFocused(firstMetricRequester) },
            onClick = { onSelectInsight(hero.id) },
            modifier = Modifier.weight(1.35f).height(168.dp),
        )
        Column(
            modifier = Modifier.weight(1.65f).height(168.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val requesters = listOf(completedRequester, partialRequester, sourceRequester)
            cards.take(3).forEachIndexed { index, card ->
                TvWatchStatsCompactMetricRow(
                    card = card,
                    requester = requesters[index],
                    selectedInsightKey = selectedInsightKey,
                    left = firstMetricRequester,
                    up = if (index == 0) up else requesters[index - 1],
                    down = if (index == cards.take(3).lastIndex) down else requesters[index + 1],
                    onFocused = { onFocused(requesters[index]) },
                    onSelectInsight = onSelectInsight,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TvWatchStatsHeroInsightCard(
    hero: WatchStatsHeroInsightModel,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    TvStatsFocusCard(
        title = hero.title,
        subtitle = hero.body,
        compact = true,
        focusRequester = requester,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Amber.copy(alpha = 0.32f), Color(0xFF0C0905).copy(alpha = 0.94f)),
                            radius = 120f,
                        ),
                        RoundedCornerShape(999.dp),
                    )
                    .border(1.dp, Amber.copy(alpha = 0.28f), RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hero.value,
                    color = Snow,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = hero.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hero.chips.take(3).forEach { chip ->
                        TvStatsSourceChip(chip)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvWatchStatsCompactMetricRow(
    card: WatchStatsTvCardModel,
    requester: FocusRequester,
    selectedInsightKey: String?,
    onFocused: () -> Unit,
    onSelectInsight: (String?) -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    TvStatsFocusCard(
        title = card.title,
        selected = selectedInsightKey == card.insightKey,
        showHeader = false,
        compact = true,
        focusRequester = requester,
        left = left,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = { onSelectInsight(card.insightKey) },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.labelMedium,
                color = AmberLight,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(86.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = card.value,
                style = MaterialTheme.typography.titleMedium,
                color = Snow,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(118.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = card.supportingLines.firstOrNull() ?: card.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Silver,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TvStatsSourceChip(card.footer)
        }
    }
}

@Composable
private fun TvWatchStatsMetricCardsRow(
    cards: List<WatchStatsTvCardModel>,
    firstMetricRequester: FocusRequester,
    completedRequester: FocusRequester,
    partialRequester: FocusRequester,
    sourceRequester: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    selectedInsightKey: String?,
    onSelectInsight: (String?) -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    val requesters = listOf(firstMetricRequester, completedRequester, partialRequester, sourceRequester)
    val safeCards = cards.take(4)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        safeCards.forEachIndexed { index, card ->
            val requester = requesters[index]
            TvWatchStatsDashboardMetricCard(
                id = card.id,
                label = card.title,
                value = card.value,
                lines = card.supportingLines.ifEmpty { listOf(card.subtitle) },
                chip = card.footer,
                icon = statsMetricIconFor(card),
                insightKey = card.insightKey,
                requester = requester,
                selectedInsightKey = selectedInsightKey,
                up = up,
                down = down,
                left = requesters.getOrNull(index - 1),
                right = requesters.getOrNull(index + 1),
                onFocused = { onFocused(requester) },
                onSelectInsight = onSelectInsight,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TvWatchStatsDashboardMetricCard(
    id: String,
    label: String,
    value: String,
    lines: List<String>,
    chip: String,
    icon: StatsMetricIcon,
    insightKey: String,
    requester: FocusRequester,
    selectedInsightKey: String?,
    onFocused: () -> Unit,
    onSelectInsight: (String?) -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    TvStatsFocusCard(
        title = label,
        selected = selectedInsightKey == insightKey,
        compact = true,
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = { onSelectInsight(insightKey) },
        modifier = modifier.height(126.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStatsMetricIcon(icon = icon, modifier = Modifier.size(38.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                lines.take(2).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = Silver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                TvStatsSourceChip(chip)
            }
        }
    }
}

private enum class StatsMetricIcon {
    CLOCK,
    CHECK,
    PLAY,
    SOURCE,
    IMPORT,
    MEASURED,
    UNKNOWN,
    ACTIVITY,
    PREMIUM,
}

@Composable
private fun TvStatsMetricIcon(
    icon: StatsMetricIcon,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                Brush.radialGradient(
                    colors = listOf(Amber.copy(alpha = 0.26f), Color(0xFF0F0D08).copy(alpha = 0.96f)),
                    radius = 58f,
                ),
                RoundedCornerShape(999.dp),
            )
            .border(1.dp, Amber.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            StatsMetricIcon.UNKNOWN -> Text(
                text = "?",
                color = AmberLight,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            else -> Canvas(modifier = Modifier.fillMaxSize().padding(9.dp)) {
                val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round)
                val tint = AmberLight
                when (icon) {
                    StatsMetricIcon.CLOCK -> {
                        drawCircle(tint, radius = size.minDimension * 0.42f, style = stroke)
                        drawLine(tint, Offset(size.width * 0.5f, size.height * 0.5f), Offset(size.width * 0.5f, size.height * 0.24f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.5f, size.height * 0.5f), Offset(size.width * 0.7f, size.height * 0.58f), stroke.width, StrokeCap.Round)
                    }
                    StatsMetricIcon.CHECK -> {
                        drawLine(tint, Offset(size.width * 0.18f, size.height * 0.54f), Offset(size.width * 0.42f, size.height * 0.76f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.42f, size.height * 0.76f), Offset(size.width * 0.84f, size.height * 0.25f), stroke.width, StrokeCap.Round)
                    }
                    StatsMetricIcon.PLAY, StatsMetricIcon.MEASURED -> {
                        val path = Path().apply {
                            moveTo(size.width * 0.3f, size.height * 0.2f)
                            lineTo(size.width * 0.78f, size.height * 0.5f)
                            lineTo(size.width * 0.3f, size.height * 0.8f)
                            close()
                        }
                        drawPath(path, tint)
                    }
                    StatsMetricIcon.SOURCE -> {
                        val left = Offset(size.width * 0.24f, size.height * 0.32f)
                        val right = Offset(size.width * 0.76f, size.height * 0.32f)
                        val bottom = Offset(size.width * 0.5f, size.height * 0.76f)
                        drawLine(tint, left, right, stroke.width * 0.78f, StrokeCap.Round)
                        drawLine(tint, left, bottom, stroke.width * 0.78f, StrokeCap.Round)
                        drawLine(tint, right, bottom, stroke.width * 0.78f, StrokeCap.Round)
                        drawCircle(tint, radius = size.minDimension * 0.12f, center = left)
                        drawCircle(tint, radius = size.minDimension * 0.12f, center = right)
                        drawCircle(tint, radius = size.minDimension * 0.12f, center = bottom)
                    }
                    StatsMetricIcon.IMPORT -> {
                        drawLine(tint, Offset(size.width * 0.5f, size.height * 0.16f), Offset(size.width * 0.5f, size.height * 0.62f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.28f, size.height * 0.42f), Offset(size.width * 0.5f, size.height * 0.64f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.72f, size.height * 0.42f), Offset(size.width * 0.5f, size.height * 0.64f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.25f, size.height * 0.84f), Offset(size.width * 0.75f, size.height * 0.84f), stroke.width, StrokeCap.Round)
                    }
                    StatsMetricIcon.ACTIVITY -> {
                        drawLine(tint, Offset(size.width * 0.24f, size.height * 0.82f), Offset(size.width * 0.24f, size.height * 0.5f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.5f, size.height * 0.82f), Offset(size.width * 0.5f, size.height * 0.22f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.76f, size.height * 0.82f), Offset(size.width * 0.76f, size.height * 0.38f), stroke.width, StrokeCap.Round)
                    }
                    StatsMetricIcon.PREMIUM -> {
                        val path = Path().apply {
                            moveTo(size.width * 0.5f, size.height * 0.14f)
                            lineTo(size.width * 0.8f, size.height * 0.34f)
                            lineTo(size.width * 0.68f, size.height * 0.82f)
                            lineTo(size.width * 0.32f, size.height * 0.82f)
                            lineTo(size.width * 0.2f, size.height * 0.34f)
                            close()
                        }
                        drawPath(path, tint, style = stroke)
                    }
                    StatsMetricIcon.UNKNOWN -> Unit
                }
            }
        }
    }
}

private fun statsMetricIconFor(card: WatchStatsTvCardModel): StatsMetricIcon = when {
    card.id.contains("time", ignoreCase = true) -> StatsMetricIcon.CLOCK
    card.id.contains("completed", ignoreCase = true) -> StatsMetricIcon.CHECK
    card.id.contains("partial", ignoreCase = true) || card.id.contains("started", ignoreCase = true) -> StatsMetricIcon.PLAY
    card.id.contains("imported", ignoreCase = true) -> StatsMetricIcon.IMPORT
    card.id.contains("measured", ignoreCase = true) -> StatsMetricIcon.MEASURED
    card.id.contains("unknown", ignoreCase = true) -> StatsMetricIcon.UNKNOWN
    card.id.contains("source", ignoreCase = true) -> StatsMetricIcon.SOURCE
    card.id.contains("activity", ignoreCase = true) || card.id.contains("row", ignoreCase = true) -> StatsMetricIcon.ACTIVITY
    else -> StatsMetricIcon.SOURCE
}

private data class DashboardBreakdownRow(
    val label: String,
    val value: String,
    val count: Int,
)

private data class DashboardTitleRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val value: String,
    val posterUrl: String? = null,
    val session: WatchSession? = null,
)

@Composable
private fun TvWatchStatsOverviewModulesRow(
    summary: WatchStatsSummary,
    sessions: List<WatchSession>,
    chartSourceRequester: FocusRequester,
    recentPreviewRequester: FocusRequester,
    left: FocusRequester,
    upLeft: FocusRequester,
    upRight: FocusRequester,
    downLeft: FocusRequester?,
    downRight: FocusRequester?,
    onFocused: (FocusRequester) -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvWatchStatsDonutCard(
            title = "Source split",
            subtitle = "Only real recorded sources",
            rows = sourceBreakdownRows(summary),
            unavailableCopy = "Source stats appear after Torve records playback, imports, or manual watches.",
            requester = chartSourceRequester,
            left = left,
            right = recentPreviewRequester,
            up = upLeft,
            down = downLeft,
            onFocused = { onFocused(chartSourceRequester) },
            modifier = Modifier.weight(1f).height(228.dp),
        )
        TvWatchStatsTopTitlesCard(
            title = "Recent activity preview",
            rows = dashboardRecentTitleRows(sessions),
            emptyCopy = "Recent rows appear as you watch or import history.",
            requester = recentPreviewRequester,
            left = chartSourceRequester,
            up = upRight,
            down = downRight,
            onFocused = { onFocused(recentPreviewRequester) },
            onOpenDetails = onOpenDetails,
            modifier = Modifier.weight(1f).height(228.dp),
        )
    }
}

@Composable
private fun TvWatchStatsModeAnalyticsRow(
    model: WatchStatsTvDashboardModel,
    summary: WatchStatsSummary,
    sessions: List<WatchSession>,
    isPremium: Boolean,
    chartTimeRequester: FocusRequester,
    chartSourceRequester: FocusRequester,
    left: FocusRequester,
    upLeft: FocusRequester,
    upRight: FocusRequester,
    downLeft: FocusRequester,
    downRight: FocusRequester,
    onUpgrade: () -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvWatchStatsPrimaryModeCard(
            model = model,
            summary = summary,
            sessions = sessions,
            isPremium = isPremium,
            requester = chartTimeRequester,
            left = left,
            right = chartSourceRequester,
            up = upLeft,
            down = downLeft,
            onUpgrade = onUpgrade,
            onFocused = { onFocused(chartTimeRequester) },
            modifier = Modifier.weight(1.18f).height(228.dp),
        )
        TvWatchStatsSecondaryModeCard(
            model = model,
            summary = summary,
            isPremium = isPremium,
            requester = chartSourceRequester,
            left = chartTimeRequester,
            up = upRight,
            down = downRight,
            onUpgrade = onUpgrade,
            onFocused = { onFocused(chartSourceRequester) },
            modifier = Modifier.weight(0.82f).height(228.dp),
        )
    }
}

@Composable
private fun TvWatchStatsModeListsRow(
    model: WatchStatsTvDashboardModel,
    summary: WatchStatsSummary,
    sessions: List<WatchSession>,
    topMoviesRequester: FocusRequester,
    topShowsRequester: FocusRequester,
    left: FocusRequester,
    upLeft: FocusRequester,
    upRight: FocusRequester,
    downLeft: FocusRequester?,
    downRight: FocusRequester?,
    onUpgrade: () -> Unit,
    onFocused: (FocusRequester) -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (model.insightMode) {
            WatchStatsInsightMode.OVERVIEW -> when (model.contentScope) {
                WatchStatsContentScope.MOVIES -> {
                    TvWatchStatsTopTitlesCard(
                        title = "Top movies",
                        rows = dashboardTopMovieRows(sessions),
                        emptyCopy = "Not enough watched movies yet",
                        requester = topMoviesRequester,
                        left = left,
                        right = topShowsRequester,
                        up = upLeft,
                        down = downLeft,
                        onFocused = { onFocused(topMoviesRequester) },
                        onOpenDetails = onOpenDetails,
                        modifier = Modifier.weight(1f).height(248.dp),
                    )
                    TvWatchStatsTopTitlesCard(
                        title = "Recently watched movies",
                        rows = dashboardRecentTitleRows(sessions),
                        emptyCopy = "No recent movie activity in this view",
                        requester = topShowsRequester,
                        left = topMoviesRequester,
                        up = upRight,
                        down = downRight,
                        onFocused = { onFocused(topShowsRequester) },
                        onOpenDetails = onOpenDetails,
                        modifier = Modifier.weight(1f).height(248.dp),
                    )
                }
                WatchStatsContentScope.SHOWS -> {
                    TvWatchStatsTopTitlesCard(
                        title = "Top shows",
                        rows = dashboardTopShowRows(sessions),
                        emptyCopy = "Not enough watched episodes yet",
                        requester = topMoviesRequester,
                        left = left,
                        right = topShowsRequester,
                        up = upLeft,
                        down = downLeft,
                        onFocused = { onFocused(topMoviesRequester) },
                        onOpenDetails = onOpenDetails,
                        modifier = Modifier.weight(1f).height(248.dp),
                    )
                    TvWatchStatsTopTitlesCard(
                        title = "Recently watched episodes",
                        rows = dashboardRecentTitleRows(sessions),
                        emptyCopy = "No recent episode activity in this view",
                        requester = topShowsRequester,
                        left = topMoviesRequester,
                        up = upRight,
                        down = downRight,
                        onFocused = { onFocused(topShowsRequester) },
                        onOpenDetails = onOpenDetails,
                        modifier = Modifier.weight(1f).height(248.dp),
                    )
                }
                WatchStatsContentScope.ALL -> {
                    TvWatchStatsTopTitlesCard(
                        title = "Top movies",
                        rows = dashboardTopMovieRows(sessions),
                        emptyCopy = "Not enough watched movies yet",
                        requester = topMoviesRequester,
                        left = left,
                        right = topShowsRequester,
                        up = upLeft,
                        down = downLeft,
                        onFocused = { onFocused(topMoviesRequester) },
                        onOpenDetails = onOpenDetails,
                        modifier = Modifier.weight(1f).height(248.dp),
                    )
                    TvWatchStatsTopTitlesCard(
                        title = "Top shows",
                        rows = dashboardTopShowRows(sessions),
                        emptyCopy = "Not enough watched episodes yet",
                        requester = topShowsRequester,
                        left = topMoviesRequester,
                        up = upRight,
                        down = downRight,
                        onFocused = { onFocused(topShowsRequester) },
                        onOpenDetails = onOpenDetails,
                        modifier = Modifier.weight(1f).height(248.dp),
                    )
                }
            }
            WatchStatsInsightMode.ACTIVITY -> {
                TvWatchStatsTopTitlesCard(
                    title = "Recent activity preview",
                    rows = dashboardRecentTitleRows(sessions),
                    emptyCopy = "No recent activity in this view",
                    requester = topMoviesRequester,
                    left = left,
                    right = topShowsRequester,
                    up = upLeft,
                    down = downLeft,
                    onFocused = { onFocused(topMoviesRequester) },
                    onOpenDetails = onOpenDetails,
                    modifier = Modifier.weight(1f).height(248.dp),
                )
                TvWatchStatsTopTitlesCard(
                    title = "Latest counted rows",
                    rows = dashboardRecentTitleRows(sessions),
                    emptyCopy = "No counted rows in this view",
                    requester = topShowsRequester,
                    left = topMoviesRequester,
                    up = upRight,
                    down = downRight,
                    onFocused = { onFocused(topShowsRequester) },
                    onOpenDetails = onOpenDetails,
                    modifier = Modifier.weight(1f).height(248.dp),
                )
            }
            WatchStatsInsightMode.GENRES -> {
                TvWatchStatsAdvancedModeCard(
                    model = model,
                    rows = model.insightMode.advancedRows(summary),
                    title = model.secondaryTitle,
                    requester = topMoviesRequester,
                    left = left,
                    right = topShowsRequester,
                    up = upLeft,
                    down = downLeft,
                    onUpgrade = onUpgrade,
                    onFocused = { onFocused(topMoviesRequester) },
                    modifier = Modifier.weight(1f).height(248.dp),
                )
                TvWatchStatsTopTitlesCard(
                    title = model.topListTitle,
                    rows = dashboardRowsFromGroups(model.insightMode.advancedRows(summary)),
                    emptyCopy = model.unavailableBody ?: "No local metadata rows available.",
                    requester = topShowsRequester,
                    left = topMoviesRequester,
                    up = upRight,
                    down = downRight,
                    onFocused = { onFocused(topShowsRequester) },
                    onOpenDetails = onOpenDetails,
                    modifier = Modifier.weight(1f).height(248.dp),
                )
            }
            WatchStatsInsightMode.YEARS -> {
                TvWatchStatsAdvancedModeCard(
                    model = model,
                    rows = summary.advanced.yearGroups,
                    title = model.primaryTitle,
                    requester = topMoviesRequester,
                    left = left,
                    right = topShowsRequester,
                    up = upLeft,
                    down = downLeft,
                    onUpgrade = onUpgrade,
                    onFocused = { onFocused(topMoviesRequester) },
                    modifier = Modifier.weight(1f).height(248.dp),
                )
                TvWatchStatsAdvancedModeCard(
                    model = model,
                    rows = summary.advanced.decadeGroups,
                    title = "Decades",
                    requester = topShowsRequester,
                    left = topMoviesRequester,
                    up = upRight,
                    down = downRight,
                    onUpgrade = onUpgrade,
                    onFocused = { onFocused(topShowsRequester) },
                    modifier = Modifier.weight(1f).height(248.dp),
                )
            }
            WatchStatsInsightMode.RATINGS -> {
                TvWatchStatsRatingDistributionCard(
                    model = model,
                    summary = summary,
                    requester = topMoviesRequester,
                    left = left,
                    right = topShowsRequester,
                    up = upLeft,
                    down = downLeft,
                    onUpgrade = onUpgrade,
                    onFocused = { onFocused(topMoviesRequester) },
                    modifier = Modifier.weight(1f).height(248.dp),
                )
                TvWatchStatsTopTitlesCard(
                    title = "Rated watched titles",
                    rows = dashboardRowsFromRatingGroups(summary.advanced.ratingDistribution),
                    emptyCopy = model.unavailableBody ?: "No watched titles have local ratings yet.",
                    requester = topShowsRequester,
                    left = topMoviesRequester,
                    up = upRight,
                    down = downRight,
                    onFocused = { onFocused(topShowsRequester) },
                    onOpenDetails = onOpenDetails,
                    modifier = Modifier.weight(1f).height(248.dp),
                )
            }
            WatchStatsInsightMode.SOURCE -> {
                TvWatchStatsBreakdownCard(
                    title = "Source detail",
                    subtitle = "Watch time and sessions by source",
                    rows = sourceBreakdownRows(summary),
                    emptyTitle = "No source sessions yet",
                    requester = topMoviesRequester,
                    left = left,
                    right = topShowsRequester,
                    up = upLeft,
                    down = downLeft,
                    onFocused = { onFocused(topMoviesRequester) },
                    modifier = Modifier.weight(1f).height(248.dp),
                )
                TvWatchStatsTopTitlesCard(
                    title = "Recent source rows",
                    rows = dashboardRecentTitleRows(sessions),
                    emptyCopy = "Recent rows appear as source-labeled activity is recorded.",
                    requester = topShowsRequester,
                    left = topMoviesRequester,
                    up = upRight,
                    down = downRight,
                    onFocused = { onFocused(topShowsRequester) },
                    onOpenDetails = onOpenDetails,
                    modifier = Modifier.weight(1f).height(248.dp),
                )
            }
        }
    }
}

@Composable
private fun TvWatchStatsPrimaryModeCard(
    model: WatchStatsTvDashboardModel,
    summary: WatchStatsSummary,
    sessions: List<WatchSession>,
    isPremium: Boolean,
    requester: FocusRequester,
    left: FocusRequester,
    right: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    onUpgrade: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (model.insightMode) {
        WatchStatsInsightMode.SOURCE -> TvWatchStatsDonutCard(
            title = "Source split",
            subtitle = sourceModeSubtitle(summary),
            rows = sourceBreakdownRows(summary),
            unavailableCopy = "No source sessions yet",
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onFocused = onFocused,
            modifier = modifier,
        )
        WatchStatsInsightMode.GENRES -> TvWatchStatsAdvancedModeCard(
            model = model,
            rows = model.insightMode.advancedRows(summary),
            title = model.primaryTitle,
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
            useDonut = true,
        )
        WatchStatsInsightMode.YEARS -> TvWatchStatsAdvancedModeCard(
            model = model,
            rows = summary.advanced.yearGroups,
            title = "Release year distribution",
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
        )
        WatchStatsInsightMode.RATINGS -> TvWatchStatsRatingDistributionCard(
            model = model,
            summary = summary,
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
        )
        WatchStatsInsightMode.ACTIVITY,
        WatchStatsInsightMode.OVERVIEW -> TvWatchStatsTimelineCard(
            title = recentRuntimeTitle(sessions),
            sessions = sessions,
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onFocused = onFocused,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvWatchStatsSecondaryModeCard(
    model: WatchStatsTvDashboardModel,
    summary: WatchStatsSummary,
    isPremium: Boolean,
    requester: FocusRequester,
    left: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    onUpgrade: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (model.insightMode) {
        WatchStatsInsightMode.SOURCE -> TvWatchStatsDonutCard(
            title = "Runtime confidence",
            subtitle = "Measured, estimated, unknown",
            rows = confidenceBreakdownRows(summary),
            unavailableCopy = "No runtime confidence yet",
            requester = requester,
            left = left,
            up = up,
            down = down,
            onFocused = onFocused,
            modifier = modifier,
        )
        WatchStatsInsightMode.GENRES -> TvWatchStatsAdvancedModeCard(
            model = model,
            rows = summary.advanced.genreGroups,
            title = "Top genres",
            requester = requester,
            left = left,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
        )
        WatchStatsInsightMode.YEARS -> TvWatchStatsAdvancedModeCard(
            model = model,
            rows = summary.advanced.decadeGroups,
            title = "Decade distribution",
            requester = requester,
            left = left,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
        )
        WatchStatsInsightMode.RATINGS -> {
            if (model.isLocked || model.isUnavailable) {
                TvWatchStatsModeUnavailableCard(
                    title = model.lockedTitle ?: model.unavailableTitle ?: "Ratings unavailable",
                    body = model.lockedBody ?: model.unavailableBody ?: "No local rating metadata is available.",
                    locked = model.isLocked,
                    requester = requester,
                    left = left,
                    up = up,
                    down = down,
                    onUpgrade = onUpgrade,
                    onFocused = onFocused,
                    modifier = modifier,
                )
            } else {
                TvWatchStatsBreakdownCard(
                    title = "Rating bands",
                    subtitle = "Missing ratings are ignored",
                    rows = ratingBreakdownRows(summary),
                    emptyTitle = model.unavailableBody ?: "No local ratings yet",
                    requester = requester,
                    left = left,
                    up = up,
                    down = down,
                    onFocused = onFocused,
                    modifier = modifier,
                )
            }
        }
        WatchStatsInsightMode.ACTIVITY -> TvWatchStatsDonutCard(
            title = "Runtime confidence",
            subtitle = "Activity runtime labels",
            rows = confidenceBreakdownRows(summary),
            unavailableCopy = "No runtime confidence yet",
            requester = requester,
            left = left,
            up = up,
            down = down,
            onFocused = onFocused,
            modifier = modifier,
        )
        WatchStatsInsightMode.OVERVIEW -> TvWatchStatsBreakdownCard(
            title = "Source breakdown",
            subtitle = "Only real recorded sources",
            rows = sourceBreakdownRows(summary),
            emptyTitle = "No source sessions yet",
            insight = if (summary.sourceBreakdown.count { it.sessionCount > 0 } <= 2) {
                "Imported sessions are estimated unless runtime is known locally."
            } else {
                null
            },
            requester = requester,
            left = left,
            up = up,
            down = down,
            onFocused = onFocused,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvWatchStatsTimelineCard(
    title: String,
    sessions: List<WatchSession>,
    requester: FocusRequester,
    left: FocusRequester,
    right: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = sessions
        .filter { it.countedWatchMs > 0L }
        .take(8)
        .map {
            DashboardBreakdownRow(
                label = formatWatchDate(it.startedAt),
                value = WatchStatsUiText.formatDuration(it.countedWatchMs),
                count = (it.countedWatchMs / 60_000L).toInt().coerceAtLeast(1),
            )
    }
    TvStatsFocusCard(
        title = title,
        subtitle = "Recent counted sessions",
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = {},
        modifier = modifier,
    ) {
        if (rows.isEmpty()) {
            TvWatchStatsUnavailableText("Timeline appears after sessions have counted runtime.")
        } else {
            TvWatchStatsBarRows(rows)
        }
    }
}

@Composable
private fun TvWatchStatsBreakdownCard(
    title: String,
    subtitle: String,
    rows: List<DashboardBreakdownRow>,
    emptyTitle: String,
    insight: String? = null,
    requester: FocusRequester,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvStatsFocusCard(
        title = title,
        subtitle = subtitle,
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = {},
        modifier = modifier,
    ) {
        if (rows.isEmpty()) {
            TvWatchStatsUnavailableText(emptyTitle)
        } else {
            TvWatchStatsBarRows(rows)
            insight?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvWatchStatsAdvancedBreakdownCard(
    title: String,
    unavailableCopy: String,
    locked: Boolean,
    rows: List<DashboardBreakdownRow>,
    requester: FocusRequester,
    onUpgrade: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    TvStatsFocusCard(
        title = title,
        subtitle = if (locked) "Unlock deeper watch insights" else "Local metadata only",
        locked = locked,
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = { if (locked) onUpgrade() },
        modifier = modifier,
    ) {
        when {
            locked -> TvWatchStatsUnavailableText("Advanced metadata breakdowns are available when enough watch data exists. No sample data is shown.")
            rows.isEmpty() -> TvWatchStatsUnavailableText(unavailableCopy)
            else -> TvWatchStatsBarRows(rows)
        }
    }
}

@Composable
private fun TvWatchStatsAdvancedModeCard(
    model: WatchStatsTvDashboardModel,
    rows: List<WatchStatsAdvancedGroup>,
    title: String,
    requester: FocusRequester,
    onUpgrade: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    useDonut: Boolean = false,
) {
    val breakdownRows = rows.take(6).map {
        DashboardBreakdownRow(
            label = it.label,
            value = WatchStatsUiText.formatDuration(it.attributedWatchMs),
            count = it.sessionCount.coerceAtLeast(it.titleCount).coerceAtLeast(1),
        )
    }
    when {
        model.isLocked -> TvWatchStatsModeUnavailableCard(
            title = model.lockedTitle ?: "Unlock deeper watch insights",
            body = model.lockedBody ?: "Advanced metadata insights require Premium.",
            locked = true,
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
        )
        model.isUnavailable || breakdownRows.isEmpty() -> TvWatchStatsModeUnavailableCard(
            title = model.unavailableTitle ?: "$title unavailable",
            body = model.unavailableBody ?: "No local metadata is available for this watch history yet.",
            locked = false,
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
        )
        useDonut -> TvWatchStatsDonutCard(
            title = title,
            subtitle = "Attributed watch time",
            rows = breakdownRows,
            unavailableCopy = model.unavailableBody ?: "No local metadata available.",
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onFocused = onFocused,
            modifier = modifier,
        )
        else -> TvWatchStatsBreakdownCard(
            title = title,
            subtitle = "Attributed watch time",
            rows = breakdownRows,
            emptyTitle = model.unavailableBody ?: "No local metadata available.",
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onFocused = onFocused,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvWatchStatsRatingDistributionCard(
    model: WatchStatsTvDashboardModel,
    summary: WatchStatsSummary,
    requester: FocusRequester,
    onUpgrade: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    when {
        model.isLocked -> TvWatchStatsModeUnavailableCard(
            title = model.lockedTitle ?: "Unlock deeper watch insights",
            body = model.lockedBody ?: "Rating distribution is available to everyone.",
            locked = true,
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
        )
        model.isUnavailable -> TvWatchStatsModeUnavailableCard(
            title = model.unavailableTitle ?: "Ratings unavailable",
            body = model.unavailableBody ?: "No local rating metadata is available for this watch history yet.",
            locked = false,
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onUpgrade = onUpgrade,
            onFocused = onFocused,
            modifier = modifier,
        )
        else -> TvWatchStatsBreakdownCard(
            title = "Rating distribution",
            subtitle = "Missing ratings are ignored",
            rows = ratingBreakdownRows(summary),
            emptyTitle = model.unavailableBody ?: "No local ratings yet",
            requester = requester,
            left = left,
            right = right,
            up = up,
            down = down,
            onFocused = onFocused,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvWatchStatsModeUnavailableCard(
    title: String,
    body: String,
    locked: Boolean,
    requester: FocusRequester,
    onUpgrade: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    TvStatsFocusCard(
        title = title,
        subtitle = if (locked) "More watch data needed" else "Local metadata only",
        locked = locked,
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = { if (locked) onUpgrade() },
        modifier = modifier,
    ) {
        TvWatchStatsUnavailableText(body)
        if (!locked) {
            Text(
                text = "No network lookup is triggered on page open.",
                style = MaterialTheme.typography.labelSmall,
                color = Ash,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvWatchStatsDonutCard(
    title: String,
    subtitle: String,
    rows: List<DashboardBreakdownRow>,
    unavailableCopy: String,
    requester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    TvStatsFocusCard(
        title = title,
        subtitle = subtitle,
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = {},
        modifier = modifier,
    ) {
        if (rows.isEmpty()) {
            TvWatchStatsUnavailableText(unavailableCopy)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvWatchStatsDonutChart(
                    rows = rows,
                    modifier = Modifier.size(116.dp),
                )
                TvWatchStatsLegendRows(
                    rows = rows,
                    modifier = Modifier.weight(1f),
                )
            }
            if (rows.size == 1) {
                Text(
                    text = "All counted activity currently comes from ${rows.first().label}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvWatchStatsDonutChart(
    rows: List<DashboardBreakdownRow>,
    modifier: Modifier = Modifier,
) {
    val total = rows.sumOf { it.count.coerceAtLeast(0) }.coerceAtLeast(1)
    Canvas(modifier = modifier) {
        val strokeWidth = 18.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = Color.White.copy(alpha = 0.08f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
        )
        var start = -90f
        rows.forEachIndexed { index, row ->
            val sweep = if (rows.size == 1) {
                360f
            } else {
                360f * row.count.coerceAtLeast(0).toFloat() / total.toFloat()
            }
            drawArc(
                color = donutSegmentColor(index).copy(alpha = 0.92f),
                startAngle = start,
                sweepAngle = sweep.coerceAtLeast(1.5f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
            start += sweep
        }
    }
}

private fun donutSegmentColor(index: Int): Color =
    listOf(
        AmberLight,
        Color(0xFF38E3BE),
        Color(0xFF5CA8FF),
        Color(0xFFFF8A3D),
        Color(0xFFE85D75),
        Color(0xFFA9B2BC),
    )[index % 6]

@Composable
private fun TvWatchStatsLegendRows(
    rows: List<DashboardBreakdownRow>,
    modifier: Modifier = Modifier,
) {
    val total = rows.sumOf { it.count.coerceAtLeast(0) }.coerceAtLeast(1)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        rows.take(6).forEachIndexed { index, row ->
            val percent = ((row.count.toFloat() / total.toFloat()) * 100f).toInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(donutSegmentColor(index), RoundedCornerShape(999.dp)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Snow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.labelSmall,
                        color = Silver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberLight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TvWatchStatsTopTitlesCard(
    title: String,
    rows: List<DashboardTitleRow>,
    emptyCopy: String,
    requester: FocusRequester,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    onFocused: () -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    var listActive by remember(rows.map { it.id }) { mutableStateOf(false) }
    var enterSignal by remember(rows.map { it.id }) { mutableStateOf(0) }
    val listScrollState = rememberScrollState()
    val visibleRows = rows.take(12)
    val rowIds = visibleRows.map { it.id }
    val internalRequesters = remember(rowIds) { rowIds.map { FocusRequester() } }
    val rowRequesters = internalRequesters

    LaunchedEffect(enterSignal, listActive, rowIds) {
        if (enterSignal > 0 && listActive && rowRequesters.isNotEmpty()) {
            withFrameNanos { }
            runCatching { rowRequesters.first().requestFocus() }
        }
    }

    TvStatsFocusCard(
        title = title,
        subtitle = if (visibleRows.isEmpty()) "Based on local watch sessions" else "Press OK to browse rows",
        meta = if (visibleRows.isEmpty()) null else "${visibleRows.size} rows",
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = {
            listActive = false
            onFocused()
        },
        onClick = {
            if (visibleRows.isNotEmpty()) {
                listActive = true
                enterSignal += 1
            }
        },
        modifier = modifier,
    ) {
        if (visibleRows.isEmpty()) {
            TvWatchStatsUnavailableText(emptyCopy)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 172.dp)
                    .verticalScroll(listScrollState),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                visibleRows.forEachIndexed { index, row ->
                    TvWatchStatsFocusableTitleRow(
                        row = row,
                        requester = rowRequesters[index],
                        enabled = listActive,
                        left = left,
                        right = right,
                        up = if (index == 0) requester else rowRequesters[index - 1],
                        down = if (index == visibleRows.lastIndex) down else rowRequesters[index + 1],
                        onFocused = onFocused,
                        onOpenDetails = onOpenDetails,
                    )
                }
            }
            if (rows.size < 5 && !listActive) {
                Text(
                    text = "More titles appear as you watch",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TvWatchStatsFocusableTitleRow(
    row: DashboardTitleRow,
    requester: FocusRequester,
    enabled: Boolean,
    left: FocusRequester?,
    right: FocusRequester?,
    up: FocusRequester?,
    down: FocusRequester?,
    onFocused: () -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    TvStatsFocusCard(
        title = row.title,
        showHeader = false,
        compact = true,
        enabled = enabled && row.session != null,
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = {
            onFocused()
            coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
        },
        onClick = { row.session?.let(onOpenDetails) },
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .bringIntoViewRequester(bringIntoViewRequester),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStatsMiniPoster(
                title = row.title,
                posterUrl = row.posterUrl,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Snow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = row.value,
                style = MaterialTheme.typography.labelSmall,
                color = AmberLight,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvWatchStatsBarRows(rows: List<DashboardBreakdownRow>) {
    val maxCount = rows.maxOfOrNull { it.count.coerceAtLeast(1) } ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.take(6).forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(999.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(row.count.toFloat() / maxCount.toFloat())
                            .height(4.dp)
                            .background(Amber.copy(alpha = 0.56f), RoundedCornerShape(999.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvStatsMiniPoster(
    title: String,
    posterUrl: String?,
) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF111212))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val poster = posterUrl?.takeIf { it.isNotBlank() }
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = title.firstOrNull()?.uppercaseChar()?.toString() ?: "T",
                color = AmberLight,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TvWatchStatsUnavailableText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Silver,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TvWatchStatsRecentActivityTable(
    sessions: List<WatchSession>,
    firstRequester: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    left: FocusRequester,
    onFocused: (FocusRequester) -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
) {
    val visibleSessions = sessions.take(12)
    val rowIds = visibleSessions.map { it.id }
    val internalRequesters = remember(rowIds) { rowIds.map { FocusRequester() } }
    val rowRequesters = visibleSessions.indices.map { index ->
        if (index == 0) firstRequester else internalRequesters[index]
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStatsSectionLabel("Recent activity")
            Text(
                text = "Title",
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Date",
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
                modifier = Modifier.width(58.dp),
            )
            Text(
                text = "Runtime",
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
                modifier = Modifier.width(66.dp),
            )
            Text(
                text = "Source / status",
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
                modifier = Modifier.width(300.dp),
            )
        }
        if (visibleSessions.isEmpty()) {
            TvStatsFocusCard(
                title = "No recent activity in this view",
                subtitle = "Try a different time range or start watching to build your stats.",
                focusRequester = firstRequester,
                left = left,
                up = up,
                down = down,
                onFocused = { onFocused(firstRequester) },
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )
        } else {
            visibleSessions.forEachIndexed { index, session ->
                TvWatchStatsDashboardActivityRow(
                    session = session,
                    requester = rowRequesters[index],
                    left = left,
                    up = if (index == 0) up else rowRequesters[index - 1],
                    down = if (index == visibleSessions.lastIndex) down else rowRequesters[index + 1],
                    onFocused = { onFocused(rowRequesters[index]) },
                    onClick = { onOpenDetails(session) },
                )
            }
        }
    }
}

@Composable
private fun TvWatchStatsDashboardActivityRow(
    session: WatchSession,
    requester: FocusRequester,
    left: FocusRequester,
    up: FocusRequester?,
    down: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val runtimeText = dashboardSessionRuntimeValue(session)
    TvStatsFocusCard(
        title = session.title,
        showHeader = false,
        compact = true,
        focusRequester = requester,
        left = left,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(74.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStatsPoster(session, width = 42.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = session.watchStatsSecondaryTitleReadable(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatWatchDate(session.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = Ash,
                maxLines = 1,
                modifier = Modifier.width(58.dp),
            )
            Text(
                text = runtimeText,
                style = MaterialTheme.typography.labelSmall,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.width(66.dp),
            )
            Row(
                modifier = Modifier.width(300.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvStatsSourceChip(WatchStatsUiText.sourceLabel(session.source))
                TvStatsSourceChip(WatchStatsUiText.statusLabel(session.status))
                runtimeConfidenceChipLabel(session)?.let { TvStatsSourceChip(it) }
            }
        }
    }
}

@Composable
private fun TvWatchStatsBottomInsightStrip(
    summary: WatchStatsSummary,
    isPremium: Boolean,
    requester: FocusRequester,
    up: FocusRequester,
    left: FocusRequester,
    onFocused: () -> Unit,
) {
    val completedTotal = summary.completedMovies + summary.completedEpisodes
    val items = listOf(
        BottomInsightItem(StatsMetricIcon.CHECK, "Completed", "${pluralCount(completedTotal, "title")} at the 85% threshold"),
        BottomInsightItem(StatsMetricIcon.CLOCK, "Counted time", "${WatchStatsUiText.formatDuration(summary.totalWatchMs)} from known runtimes"),
        BottomInsightItem(StatsMetricIcon.SOURCE, "Sources", "Torve, Trakt, Manual, and Migrated stay labeled"),
        BottomInsightItem(
            icon = if (isPremium) StatsMetricIcon.ACTIVITY else StatsMetricIcon.PREMIUM,
            title = if (isPremium) "Advanced" else "Premium",
            copy = if (isPremium) "Local metadata enables richer insights" else "Advanced cards stay hidden without fake previews",
        ),
    )
    TvStatsFocusCard(
        title = "Stats summary",
        showHeader = false,
        compact = true,
        focusRequester = requester,
        left = left,
        up = up,
        onFocused = onFocused,
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvStatsMetricIcon(icon = item.icon, modifier = Modifier.size(34.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberLight,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.copy,
                            style = MaterialTheme.typography.labelSmall,
                            color = Silver,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private data class BottomInsightItem(
    val icon: StatsMetricIcon,
    val title: String,
    val copy: String,
)

@Composable
private fun TvWatchStatsHeader(
    isPremium: Boolean,
    entryFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    onBack: () -> Unit,
    onFocused: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvStatsPill(
            label = "Back",
            selected = false,
            focusRequester = entryFocusRequester,
            left = railFocusRequester,
            down = sectionEntryRequester,
            onFocused = onFocused,
            onClick = onBack,
            modifier = Modifier.width(88.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Watch Stats",
                style = MaterialTheme.typography.headlineMedium,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Playback, imports, and manual watches. Sources are labeled.",
                style = MaterialTheme.typography.bodyMedium,
                color = Silver,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TvStatsSourceChip(if (isPremium) "Premium" else "Basic")
    }
}

@Composable
private fun TvWatchStatsSectionTabs(
    selectedSection: WatchStatsTvSection,
    sectionEntryRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    mainStageRequester: FocusRequester,
    onSectionSelected: (WatchStatsTvSection) -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WatchStatsTvSection.entries.forEachIndexed { index, section ->
            val requester = if (index == 0) {
                sectionEntryRequester
            } else {
                remember(section.id) { FocusRequester() }
            }
            TvStatsPill(
                label = section.label,
                selected = selectedSection == section,
                focusRequester = requester,
                left = if (index == 0) railFocusRequester else null,
                right = if (index == WatchStatsTvSection.entries.lastIndex) mainStageRequester else null,
                down = mainStageRequester,
                onFocused = { onFocused(requester) },
                onClick = { onSectionSelected(section) },
            )
        }
    }
}

@Composable
private fun TvWatchStatsContextFilters(
    section: WatchStatsTvSection,
    selection: TvWatchStatsFilterSelection,
    summary: WatchStatsSummary,
    isPremium: Boolean,
    hasSimklData: Boolean,
    sectionEntryRequester: FocusRequester,
    mainStageRequester: FocusRequester,
    onSelectionChange: (TvWatchStatsFilterSelection) -> Unit,
    onUpgrade: () -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (section) {
            WatchStatsTvSection.OVERVIEW,
            WatchStatsTvSection.TIME -> TvWatchStatsTimeRange.entries.forEach { range ->
                val requester = remember("range_${range.name}") { FocusRequester() }
                TvStatsPill(
                    label = range.label,
                    selected = selection.timeRange == range,
                    focusRequester = requester,
                    left = sectionEntryRequester,
                    right = mainStageRequester,
                    down = mainStageRequester,
                    onFocused = { onFocused(requester) },
                    onClick = { onSelectionChange(selection.copy(timeRange = range)) },
                )
            }
            WatchStatsTvSection.COMPLETED -> {
                TvWatchStatsContentFilter.entries.forEach { content ->
                    val requester = remember("content_${content.name}") { FocusRequester() }
                    TvStatsPill(
                        label = content.label,
                        selected = selection.content == content,
                        focusRequester = requester,
                        left = sectionEntryRequester,
                        right = mainStageRequester,
                        down = mainStageRequester,
                        onFocused = { onFocused(requester) },
                        onClick = { onSelectionChange(selection.copy(content = content)) },
                    )
                }
                TvWatchStatsStatusFilter.entries.forEach { status ->
                    val requester = remember("status_${status.name}") { FocusRequester() }
                    TvStatsPill(
                        label = status.label,
                        selected = selection.status == status,
                        focusRequester = requester,
                        left = sectionEntryRequester,
                        right = mainStageRequester,
                        down = mainStageRequester,
                        onFocused = { onFocused(requester) },
                        onClick = { onSelectionChange(selection.copy(status = status)) },
                    )
                }
            }
            WatchStatsTvSection.SOURCES,
            WatchStatsTvSection.ACTIVITY -> TvWatchStatsSourceFilter.entries
                .filter { it != TvWatchStatsSourceFilter.SIMKL || hasSimklData }
                .forEach { source ->
                    val requester = remember("source_${source.name}") { FocusRequester() }
                    TvStatsPill(
                        label = source.label,
                        selected = selection.source == source,
                        focusRequester = requester,
                        left = sectionEntryRequester,
                        right = mainStageRequester,
                        down = mainStageRequester,
                        onFocused = { onFocused(requester) },
                        onClick = { onSelectionChange(selection.copy(source = source)) },
                    )
                }
            WatchStatsTvSection.ADVANCED -> {
                if (!isPremium) {
                    val requester = remember("advanced_locked_filter") { FocusRequester() }
                    TvStatsPill(
                        label = "Unlock advanced",
                        selected = false,
                        focusRequester = requester,
                        left = sectionEntryRequester,
                        right = mainStageRequester,
                        down = mainStageRequester,
                        onFocused = { onFocused(requester) },
                        onClick = onUpgrade,
                    )
                } else if (!summary.advanced.availability.hasAnyMetadata) {
                    val requester = remember("advanced_unavailable_filter") { FocusRequester() }
                    TvStatsPill(
                        label = "Metadata unavailable",
                        selected = false,
                        focusRequester = requester,
                        left = sectionEntryRequester,
                        right = mainStageRequester,
                        down = mainStageRequester,
                        onFocused = { onFocused(requester) },
                        onClick = {},
                    )
                } else {
                    if (selection.hasAdvancedSelection()) {
                        val requester = remember("advanced_clear_filter") { FocusRequester() }
                        TvStatsPill(
                            label = "Clear",
                            selected = false,
                            focusRequester = requester,
                            left = sectionEntryRequester,
                            right = mainStageRequester,
                            down = mainStageRequester,
                            onFocused = { onFocused(requester) },
                            onClick = { onSelectionChange(selection.clearAdvanced()) },
                        )
                    }
                    summary.advanced.genreGroups.take(3).forEach { group ->
                        val requester = remember("genre_${group.key}") { FocusRequester() }
                        TvStatsPill(
                            label = group.label,
                            selected = selection.genre == group.key,
                            focusRequester = requester,
                            left = sectionEntryRequester,
                            right = mainStageRequester,
                            down = mainStageRequester,
                            onFocused = { onFocused(requester) },
                            onClick = { onSelectionChange(selection.clearAdvanced().copy(genre = group.key)) },
                        )
                    }
                    summary.advanced.yearGroups.take(3).forEach { group ->
                        val requester = remember("year_${group.key}") { FocusRequester() }
                        TvStatsPill(
                            label = group.label,
                            selected = selection.year?.toString() == group.key,
                            focusRequester = requester,
                            left = sectionEntryRequester,
                            right = mainStageRequester,
                            down = mainStageRequester,
                            onFocused = { onFocused(requester) },
                            onClick = {
                                onSelectionChange(selection.clearAdvanced().copy(year = group.key.toIntOrNull()))
                            },
                        )
                    }
                    summary.advanced.ratingDistribution.take(3).forEach { group ->
                        val requester = remember("rating_${group.band.name}") { FocusRequester() }
                        TvStatsPill(
                            label = group.band.label,
                            selected = selection.ratingBand == group.band,
                            focusRequester = requester,
                            left = sectionEntryRequester,
                            right = mainStageRequester,
                            down = mainStageRequester,
                            onFocused = { onFocused(requester) },
                            onClick = { onSelectionChange(selection.clearAdvanced().copy(ratingBand = group.band)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvWatchStatsMainStage(
    state: WatchStatsUiState,
    section: WatchStatsTvSection,
    isPremium: Boolean,
    mainStageRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    selectedInsightKey: String?,
    onSelectInsight: (String?) -> Unit,
    onReload: () -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
    onFocused: (FocusRequester) -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> TvStatsLoadingState(modifier)
        state.error != null -> TvStatsErrorState(
            mainStageRequester = mainStageRequester,
            sectionEntryRequester = sectionEntryRequester,
            drilldownRequester = drilldownRequester,
            onFocused = onFocused,
            onRetry = onReload,
            modifier = modifier,
        )
        state.isEmpty -> TvStatsEmptyState(
            mainStageRequester = mainStageRequester,
            sectionEntryRequester = sectionEntryRequester,
            drilldownRequester = drilldownRequester,
            onFocused = onFocused,
            modifier = modifier,
        )
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (section) {
                    WatchStatsTvSection.OVERVIEW -> item(key = "overview") {
                        TvWatchStatsOverviewSection(
                            state = state,
                            isPremium = isPremium,
                            mainStageRequester = mainStageRequester,
                            sectionEntryRequester = sectionEntryRequester,
                            drilldownRequester = drilldownRequester,
                            selectedInsightKey = selectedInsightKey,
                            onSelectInsight = onSelectInsight,
                            onOpenDetails = onOpenDetails,
                            onFocused = onFocused,
                        )
                    }
                    WatchStatsTvSection.TIME -> item(key = "time") {
                        TvWatchStatsTimeSection(
                            summary = state.summary,
                            mainStageRequester = mainStageRequester,
                            sectionEntryRequester = sectionEntryRequester,
                            drilldownRequester = drilldownRequester,
                            selectedInsightKey = selectedInsightKey,
                            onSelectInsight = onSelectInsight,
                            onFocused = onFocused,
                        )
                    }
                    WatchStatsTvSection.COMPLETED -> item(key = "completed") {
                        TvWatchStatsCompletedSection(
                            summary = state.summary,
                            mainStageRequester = mainStageRequester,
                            sectionEntryRequester = sectionEntryRequester,
                            drilldownRequester = drilldownRequester,
                            selectedInsightKey = selectedInsightKey,
                            onSelectInsight = onSelectInsight,
                            onFocused = onFocused,
                        )
                    }
                    WatchStatsTvSection.SOURCES -> item(key = "sources") {
                        TvWatchStatsSourcesSection(
                            summary = state.summary,
                            mainStageRequester = mainStageRequester,
                            sectionEntryRequester = sectionEntryRequester,
                            drilldownRequester = drilldownRequester,
                            selectedInsightKey = selectedInsightKey,
                            onSelectInsight = onSelectInsight,
                            onFocused = onFocused,
                        )
                    }
                    WatchStatsTvSection.ACTIVITY -> {
                        item(key = "activity_header") {
                            TvStatsSectionLabel("Recent activity")
                        }
                        if (state.recentActivity.isEmpty()) {
                            item(key = "activity_empty") {
                                TvStatsFocusCard(
                                    title = "No activity in this view",
                                    subtitle = "Try another time range, source, or status filter.",
                                    focusRequester = mainStageRequester,
                                    left = sectionEntryRequester,
                                    onFocused = { onFocused(mainStageRequester) },
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        items(
                            items = state.recentActivity.take(18),
                            key = { "activity_${it.id}" },
                        ) { session ->
                            val requester = if (session == state.recentActivity.firstOrNull()) {
                                mainStageRequester
                            } else {
                                remember("activity_${session.id}") { FocusRequester() }
                            }
                            TvWatchStatsActivityRow(
                                session = session,
                                requester = requester,
                                sectionEntryRequester = sectionEntryRequester,
                                drilldownRequester = drilldownRequester,
                                hasDrilldown = selectedInsightKey != null,
                                onFocused = { onFocused(requester) },
                                onClick = { onOpenDetails(session) },
                            )
                        }
                    }
                    WatchStatsTvSection.ADVANCED -> item(key = "advanced") {
                        TvWatchStatsAdvancedSection(
                            summary = state.summary,
                            isPremium = isPremium,
                            mainStageRequester = mainStageRequester,
                            sectionEntryRequester = sectionEntryRequester,
                            drilldownRequester = drilldownRequester,
                            selectedInsightKey = selectedInsightKey,
                            onSelectInsight = onSelectInsight,
                            onUpgrade = onUpgrade,
                            onFocused = onFocused,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvWatchStatsOverviewSection(
    state: WatchStatsUiState,
    isPremium: Boolean,
    mainStageRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    selectedInsightKey: String?,
    onSelectInsight: (String?) -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    val cards = WatchStatsTvUiModel.overviewCards(state.summary, isPremium)
    val completedRequester = remember("stat_completed") { FocusRequester() }
    val sourcesRequester = remember("stat_sources") { FocusRequester() }
    val panelRequester = drilldownRequester
    val activityRequester = remember("overview_activity_first") { FocusRequester() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(
                modifier = Modifier.weight(0.61f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvWatchStatsHeroInsightCard(
                    card = cards[0],
                    summary = state.summary,
                    requester = mainStageRequester,
                    selectedInsightKey = selectedInsightKey,
                    left = sectionEntryRequester,
                    right = panelRequester,
                    down = completedRequester,
                    onFocused = { onFocused(mainStageRequester) },
                    onClick = { onSelectInsight(cards[0].insightKey) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(218.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvWatchStatsInsightCard(
                        card = cards[1],
                        requester = completedRequester,
                        left = sectionEntryRequester,
                        right = sourcesRequester,
                        up = mainStageRequester,
                        down = activityRequester,
                        selectedInsightKey = selectedInsightKey,
                        onFocused = { onFocused(completedRequester) },
                        onClick = { onSelectInsight(cards[1].insightKey) },
                        modifier = Modifier
                            .weight(1f)
                            .height(132.dp),
                    )
                    TvWatchStatsInsightCard(
                        card = cards[2],
                        requester = sourcesRequester,
                        left = completedRequester,
                        right = panelRequester,
                        up = mainStageRequester,
                        down = activityRequester,
                        selectedInsightKey = selectedInsightKey,
                        onFocused = { onFocused(sourcesRequester) },
                        onClick = { onSelectInsight(cards[2].insightKey) },
                        modifier = Modifier
                            .weight(1f)
                            .height(132.dp),
                    )
                }
            }
            TvWatchStatsOverviewInsightPanel(
                selectedKey = selectedInsightKey,
                summary = state.summary,
                requester = panelRequester,
                left = sourcesRequester,
                down = activityRequester,
                onFocused = { onFocused(panelRequester) },
                onClick = {
                    if (selectedInsightKey == null) {
                        onSelectInsight("time")
                    }
                },
                modifier = Modifier
                    .weight(0.39f)
                    .height(362.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStatsSectionLabel("Recent activity")
            Text(
                text = "Latest watched items",
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
            )
        }
        if (state.recentActivity.isEmpty()) {
            TvWatchStatsCompactActivityRow(
                title = "No recent activity in this view",
                subtitle = "Try another time range, source, or status filter.",
                requester = activityRequester,
                left = sectionEntryRequester,
                right = panelRequester,
                onFocused = { onFocused(activityRequester) },
                onClick = {},
            )
        } else {
            state.recentActivity.take(2).forEachIndexed { index, session ->
                val requester = if (index == 0) {
                    activityRequester
                } else {
                    remember("overview_activity_${session.id}") { FocusRequester() }
                }
                TvWatchStatsCompactActivityRow(
                    session = session,
                    requester = requester,
                    sectionEntryRequester = sectionEntryRequester,
                    drilldownRequester = panelRequester,
                    onFocused = { onFocused(requester) },
                    onClick = { onOpenDetails(session) },
                )
            }
        }
    }
}

@Composable
private fun TvWatchStatsHeroInsightCard(
    card: WatchStatsTvCardModel,
    summary: WatchStatsSummary,
    requester: FocusRequester,
    selectedInsightKey: String?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    TvStatsFocusCard(
        title = card.title,
        selected = selectedInsightKey == card.insightKey,
        locked = card.locked,
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(
            text = card.value,
            style = MaterialTheme.typography.displaySmall,
            color = Snow,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = overviewHeroSubtitle(summary),
            style = MaterialTheme.typography.bodyLarge,
            color = Silver,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStatsSourceChip("Measured ${WatchStatsUiText.formatDuration(summary.measuredWatchMs)}")
            TvStatsSourceChip("Estimated ${WatchStatsUiText.formatDuration(summary.estimatedWatchMs)}")
            if (unknownRuntimeSessionCount(summary) > 0 || summary.hasLegacyUnknownRuntime) {
                TvStatsSourceChip("Unknown runtimes excluded")
            }
        }
    }
}

@Composable
private fun TvWatchStatsTimeSection(
    summary: WatchStatsSummary,
    mainStageRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    selectedInsightKey: String?,
    onSelectInsight: (String?) -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    val confidenceRequester = remember("confidence_card") { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvWatchStatsInsightCard(
            card = WatchStatsTvCardModel(
                id = "stat_time",
                title = "Watch time",
                value = WatchStatsUiText.formatDuration(summary.totalWatchMs),
                subtitle = "Measured ${WatchStatsUiText.formatDuration(summary.measuredWatchMs)} / Estimated ${WatchStatsUiText.formatDuration(summary.estimatedWatchMs)}",
                footer = "Unknown runtimes excluded",
                insightKey = "time",
            ),
            requester = mainStageRequester,
            left = sectionEntryRequester,
            right = if (selectedInsightKey != null) drilldownRequester else null,
            down = confidenceRequester,
            selectedInsightKey = selectedInsightKey,
            onFocused = { onFocused(mainStageRequester) },
            onClick = { onSelectInsight("time") },
            modifier = Modifier.fillMaxWidth(),
        )
        TvStatsFocusCard(
            title = "Runtime confidence",
            subtitle = "Measured, estimated, and unknown are kept separate.",
            focusRequester = confidenceRequester,
            selected = selectedInsightKey == "confidence",
            left = sectionEntryRequester,
            right = if (selectedInsightKey != null) drilldownRequester else null,
            up = mainStageRequester,
            onFocused = { onFocused(confidenceRequester) },
            onClick = { onSelectInsight("confidence") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            summary.runtimeConfidenceBreakdown
                .filter { it.sessionCount > 0 }
                .forEach {
                    TvStatsMetricLine(
                        WatchStatsUiText.confidenceLabel(it.confidence),
                        "${it.sessionCount} / ${WatchStatsUiText.formatDuration(it.countedWatchMs)}",
                    )
                }
        }
    }
}

@Composable
private fun TvWatchStatsOverviewInsightPanel(
    selectedKey: String?,
    summary: WatchStatsSummary,
    requester: FocusRequester,
    left: FocusRequester,
    down: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvStatsFocusCard(
        title = overviewInsightTitle(selectedKey),
        subtitle = selectedKey?.let(::drilldownTitle)
            ?: "Source, status, and runtime confidence stay visible.",
        focusRequester = requester,
        left = left,
        down = down,
        onFocused = onFocused,
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(
            text = overviewInsightMessage(summary, selectedKey),
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        when (selectedKey) {
            "completed" -> {
                TvStatsMetricLine("Movies", summary.completedMovies.toString())
                TvStatsMetricLine("Episodes", summary.completedEpisodes.toString())
                TvStatsMetricLine("Partial", summary.partialCount.toString())
                TvStatsMetricLine("Abandoned", summary.abandonedCount.toString())
            }
            "sources" -> {
                summary.sourceBreakdown
                    .filter { it.sessionCount > 0 }
                    .take(4)
                    .forEach {
                        TvStatsMetricLine(
                            WatchStatsUiText.sourceLabel(it.source),
                            "${it.sessionCount} / ${WatchStatsUiText.formatDuration(it.countedWatchMs)}",
                        )
                    }
            }
            else -> {
                TvStatsMetricLine("Total", WatchStatsUiText.formatDuration(summary.totalWatchMs))
                TvStatsMetricLine("Measured", WatchStatsUiText.formatDuration(summary.measuredWatchMs))
                TvStatsMetricLine("Estimated", WatchStatsUiText.formatDuration(summary.estimatedWatchMs))
                val unknownCount = unknownRuntimeSessionCount(summary)
                if (unknownCount > 0) {
                    TvStatsMetricLine("Unknown", "$unknownCount sessions")
                }
            }
        }
        val chipText = if (selectedKey == null) "Focus a card" else "Details update here"
        TvStatsSourceChip(chipText)
    }
}

@Composable
private fun TvWatchStatsCompletedSection(
    summary: WatchStatsSummary,
    mainStageRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    selectedInsightKey: String?,
    onSelectInsight: (String?) -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    val partialRequester = remember("partial_card") { FocusRequester() }
    val abandonedRequester = remember("abandoned_card") { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvWatchStatsInsightCard(
            card = WatchStatsTvCardModel(
                id = "stat_completed",
                title = "Completed",
                value = "${summary.completedMovies} movies / ${summary.completedEpisodes} episodes",
                subtitle = "Manual ${summary.manualCompletedCount} / Imported ${summary.importedCompletedCount}",
                footer = "85% threshold",
                insightKey = "completed",
            ),
            requester = mainStageRequester,
            left = sectionEntryRequester,
            right = if (selectedInsightKey != null) drilldownRequester else null,
            down = partialRequester,
            selectedInsightKey = selectedInsightKey,
            onFocused = { onFocused(mainStageRequester) },
            onClick = { onSelectInsight("completed") },
            modifier = Modifier.fillMaxWidth(),
        )
        TvWatchStatsInsightCard(
            card = WatchStatsTvCardModel(
                id = "stat_partial",
                title = "Partial",
                value = "${summary.partialCount} sessions",
                subtitle = WatchStatsUiText.formatDuration(summary.partialWatchMs),
                footer = "Not counted as completed",
                insightKey = "partial",
            ),
            requester = partialRequester,
            left = sectionEntryRequester,
            right = if (selectedInsightKey != null) drilldownRequester else null,
            up = mainStageRequester,
            down = abandonedRequester,
            selectedInsightKey = selectedInsightKey,
            onFocused = { onFocused(partialRequester) },
            onClick = { onSelectInsight("partial") },
            modifier = Modifier.fillMaxWidth(),
        )
        TvWatchStatsInsightCard(
            card = WatchStatsTvCardModel(
                id = "stat_abandoned",
                title = "Abandoned",
                value = "${summary.abandonedCount} sessions",
                subtitle = "${summary.startedCount} started",
                footer = "Short sessions separated",
                insightKey = "partial",
            ),
            requester = abandonedRequester,
            left = sectionEntryRequester,
            right = if (selectedInsightKey != null) drilldownRequester else null,
            up = partialRequester,
            selectedInsightKey = selectedInsightKey,
            onFocused = { onFocused(abandonedRequester) },
            onClick = { onSelectInsight("partial") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TvWatchStatsSourcesSection(
    summary: WatchStatsSummary,
    mainStageRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    selectedInsightKey: String?,
    onSelectInsight: (String?) -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val rows = summary.sourceBreakdown.filter { it.sessionCount > 0 }
        if (rows.isEmpty()) {
            TvStatsFocusCard(
                title = "No sources yet",
                subtitle = "Torve, Trakt, Manual, SIMKL, and migrated rows appear only when real sessions exist.",
                focusRequester = mainStageRequester,
                left = sectionEntryRequester,
                onFocused = { onFocused(mainStageRequester) },
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            rows.forEachIndexed { index, source ->
                val requester = if (index == 0) mainStageRequester else remember("source_${source.source.name}") { FocusRequester() }
                TvWatchStatsSourceCard(
                    source = source,
                    requester = requester,
                    left = sectionEntryRequester,
                    right = if (selectedInsightKey != null) drilldownRequester else null,
                    selected = selectedInsightKey == "sources",
                    onFocused = { onFocused(requester) },
                    onClick = { onSelectInsight("sources") },
                )
            }
        }
    }
}

@Composable
private fun TvWatchStatsAdvancedSection(
    summary: WatchStatsSummary,
    isPremium: Boolean,
    mainStageRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    selectedInsightKey: String?,
    onSelectInsight: (String?) -> Unit,
    onUpgrade: () -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    val ratingsRequester = remember("advanced_ratings") { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            !isPremium -> {
                TvStatsFocusCard(
                    title = "Unlock deeper watch insights",
                    subtitle = "See advanced trends by genre, rating, year, studio, and source.",
                    meta = "Go Premium",
                    locked = true,
                    focusRequester = mainStageRequester,
                    left = sectionEntryRequester,
                    onFocused = { onFocused(mainStageRequester) },
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            !summary.advanced.availability.hasAnyMetadata -> {
                TvStatsFocusCard(
                    title = "Metadata unavailable",
                    subtitle = "Advanced cards appear after Torve has local genre, year, or rating metadata.",
                    meta = "No fake preview data",
                    focusRequester = mainStageRequester,
                    left = sectionEntryRequester,
                    onFocused = { onFocused(mainStageRequester) },
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                TvStatsFocusCard(
                    title = "Genre and year",
                    subtitle = "Attributed watch time does not change headline totals.",
                    focusRequester = mainStageRequester,
                    selected = selectedInsightKey == "advanced_genres",
                    left = sectionEntryRequester,
                    right = if (selectedInsightKey != null) drilldownRequester else null,
                    down = ratingsRequester,
                    onFocused = { onFocused(mainStageRequester) },
                    onClick = { onSelectInsight("advanced_genres") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    summary.advanced.genreGroups.take(4).forEach {
                        TvStatsMetricLine(it.label, "${it.titleCount} titles")
                    }
                }
                TvStatsFocusCard(
                    title = "Rating distribution",
                    subtitle = "Missing ratings are hidden instead of treated as zero.",
                    focusRequester = ratingsRequester,
                    selected = selectedInsightKey == "advanced_ratings",
                    left = sectionEntryRequester,
                    right = if (selectedInsightKey != null) drilldownRequester else null,
                    up = mainStageRequester,
                    onFocused = { onFocused(ratingsRequester) },
                    onClick = { onSelectInsight("advanced_ratings") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    summary.advanced.ratingDistribution.take(4).forEach {
                        TvStatsMetricLine(it.band.label, "${it.titleCount} titles")
                    }
                }
            }
        }
    }
}

@Composable
private fun TvWatchStatsInsightCard(
    card: WatchStatsTvCardModel,
    requester: FocusRequester,
    selectedInsightKey: String?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
) {
    TvStatsFocusCard(
        title = card.title,
        selected = selectedInsightKey == card.insightKey,
        locked = card.locked,
        focusRequester = requester,
        left = left,
        right = right,
        up = up,
        down = down,
        onFocused = onFocused,
        onClick = onClick,
        modifier = modifier.heightIn(min = 138.dp),
    ) {
        Text(
            text = card.value,
            style = MaterialTheme.typography.headlineMedium,
            color = Snow,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = card.subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = Silver,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TvStatsSourceChip(card.footer)
    }
}

@Composable
private fun TvWatchStatsSourceCard(
    source: WatchStatsSourceBreakdown,
    requester: FocusRequester,
    left: FocusRequester,
    right: FocusRequester?,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    TvStatsFocusCard(
        title = WatchStatsUiText.sourceLabel(source.source),
        selected = selected,
        focusRequester = requester,
        left = left,
        right = right,
        onFocused = onFocused,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "${source.sessionCount} sessions",
            style = MaterialTheme.typography.headlineMedium,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = WatchStatsUiText.formatDuration(source.countedWatchMs),
            style = MaterialTheme.typography.bodyLarge,
            color = Silver,
        )
    }
}

@Composable
private fun TvWatchStatsActivityRow(
    session: WatchSession,
    requester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    hasDrilldown: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    TvStatsFocusCard(
        title = session.title,
        subtitle = session.watchStatsSecondaryTitleReadable(),
        focusRequester = requester,
        left = sectionEntryRequester,
        right = if (hasDrilldown) drilldownRequester else null,
        onFocused = onFocused,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStatsPoster(session)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "${WatchStatsUiText.statusLabel(session.status)} - ${formatWatchDate(session.startedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Silver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvStatsSourceChip(WatchStatsUiText.sourceLabel(session.source))
                    runtimeConfidenceChipLabel(session)?.let { TvStatsSourceChip(it) }
                    TvStatsSourceChip(runtimeChipLabel(session))
                }
            }
        }
    }
}

@Composable
private fun TvWatchStatsInlineDrilldown(
    selectedKey: String?,
    summary: WatchStatsSummary,
    recentActivity: List<WatchSession>,
    isPremium: Boolean,
    drilldownRequester: FocusRequester,
    mainStageRequester: FocusRequester,
    onFocused: () -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
    onUpgrade: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .width(390.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "drilldown_header") {
            TvStatsFocusCard(
                title = selectedKey?.let(::drilldownTitle) ?: "Details",
                subtitle = "Selected insight details",
                focusRequester = drilldownRequester,
                left = mainStageRequester,
                onFocused = onFocused,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (selectedKey?.startsWith("advanced") == true && !isPremium) {
            item(key = "drilldown_locked") {
                TvStatsFocusCard(
                    title = "Unlock deeper watch insights",
                    subtitle = "See advanced trends by genre, rating, year, studio, and source.",
                    meta = "Go Premium",
                    locked = true,
                    left = mainStageRequester,
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item(key = "drilldown_totals") {
                TvStatsFocusCard(
                    title = "Totals",
                    subtitle = WatchStatsUiText.formatDuration(summary.totalWatchMs),
                    left = mainStageRequester,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TvStatsMetricLine("Movies", summary.completedMovies.toString())
                    TvStatsMetricLine("Episodes", summary.completedEpisodes.toString())
                    TvStatsMetricLine("Partial", summary.partialCount.toString())
                    TvStatsMetricLine("Measured", WatchStatsUiText.formatDuration(summary.measuredWatchMs))
                    TvStatsMetricLine("Estimated", WatchStatsUiText.formatDuration(summary.estimatedWatchMs))
                }
            }
            item(key = "drilldown_sources") {
                TvStatsFocusCard(
                    title = "Sources",
                    left = mainStageRequester,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    summary.sourceBreakdown
                        .filter { it.sessionCount > 0 }
                        .forEach {
                            TvStatsMetricLine(
                                WatchStatsUiText.sourceLabel(it.source),
                                "${it.sessionCount} / ${WatchStatsUiText.formatDuration(it.countedWatchMs)}",
                            )
                        }
                }
            }
            item(key = "drilldown_confidence") {
                TvStatsFocusCard(
                    title = "Runtime",
                    left = mainStageRequester,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    summary.runtimeConfidenceBreakdown
                        .filter { it.sessionCount > 0 }
                        .forEach {
                            TvStatsMetricLine(
                                WatchStatsUiText.confidenceLabel(it.confidence),
                                "${it.sessionCount} / ${WatchStatsUiText.formatDuration(it.countedWatchMs)}",
                            )
                        }
                }
            }
        }

        item(key = "drilldown_recent_label") {
            TvStatsSectionLabel("Recent")
        }
        items(
            items = recentActivity.take(4),
            key = { "drilldown_recent_${it.id}" },
        ) { session ->
            val requester = remember("drilldown_${session.id}") { FocusRequester() }
            TvStatsFocusCard(
                title = session.title,
                subtitle = session.watchStatsSecondaryTitleReadable(),
                meta = "${WatchStatsUiText.sourceLabel(session.source)} - ${WatchStatsUiText.statusLabel(session.status)}",
                focusRequester = requester,
                left = mainStageRequester,
                onFocused = { },
                onClick = { onOpenDetails(session) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TvWatchStatsCompactActivityRow(
    session: WatchSession,
    requester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    TvStatsFocusCard(
        title = session.title,
        subtitle = session.watchStatsSecondaryTitleReadable(),
        focusRequester = requester,
        left = sectionEntryRequester,
        right = drilldownRequester,
        onFocused = onFocused,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvStatsPoster(session, width = 56.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = WatchStatsUiText.statusLabel(session.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatWatchDate(session.startedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ash,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvStatsSourceChip(WatchStatsUiText.sourceLabel(session.source))
                    runtimeConfidenceChipLabel(session)?.let { TvStatsSourceChip(it) }
                    TvStatsSourceChip(runtimeChipLabel(session))
                }
            }
        }
    }
}

@Composable
private fun TvWatchStatsCompactActivityRow(
    title: String,
    subtitle: String,
    requester: FocusRequester,
    left: FocusRequester,
    right: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    TvStatsFocusCard(
        title = title,
        subtitle = subtitle,
        focusRequester = requester,
        left = left,
        right = right,
        onFocused = onFocused,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
    )
}

@Composable
private fun TvStatsPoster(
    session: WatchSession,
    width: androidx.compose.ui.unit.Dp = 64.dp,
) {
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(9.dp))
            .background(Graphite)
            .border(1.dp, Steel.copy(alpha = 0.45f), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val poster = session.posterUrl?.takeIf { it.isNotBlank() }
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = session.title.firstOrNull()?.uppercaseChar()?.toString() ?: "T",
                color = AmberLight,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TvStatsLoadingState(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(Charcoal.copy(alpha = 0.42f), RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Amber,
                modifier = Modifier.size(44.dp),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Loading watch stats",
                color = Snow,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun TvStatsEmptyState(
    mainStageRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    onFocused: (FocusRequester) -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        TvStatsFocusCard(
            title = "Your watch stats will appear here",
            subtitle = "Start watching movies and shows, or connect Trakt/SIMKL, to build your timeline.",
            meta = "Back to browsing",
            focusRequester = mainStageRequester,
            left = sectionEntryRequester,
            right = drilldownRequester,
            onFocused = { onFocused(mainStageRequester) },
            onClick = {},
            modifier = Modifier.fillMaxWidth(0.74f),
        )
    }
}

@Composable
private fun TvStatsErrorState(
    mainStageRequester: FocusRequester,
    sectionEntryRequester: FocusRequester,
    drilldownRequester: FocusRequester,
    onFocused: (FocusRequester) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        TvStatsFocusCard(
            title = "Could not load watch stats",
            subtitle = "Torve could not read your local watch activity right now.",
            meta = "Try again",
            focusRequester = mainStageRequester,
            left = sectionEntryRequester,
            right = drilldownRequester,
            onFocused = { onFocused(mainStageRequester) },
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(0.74f),
        )
    }
}

private fun drilldownTitle(key: String): String = when (key) {
    "time" -> "Watch time"
    "completed" -> "Completed"
    "partial" -> "Partial"
    "sources" -> "Sources"
    "confidence" -> "Runtime labels"
    "advanced_genres" -> "Genre trends"
    "advanced_ratings" -> "Rating bands"
    else -> "Details"
}

private fun TvWatchStatsFilterSelection.forContentScope(
    scope: WatchStatsContentScope,
): TvWatchStatsFilterSelection = copy(
    content = when (scope) {
        WatchStatsContentScope.ALL -> TvWatchStatsContentFilter.ALL
        WatchStatsContentScope.MOVIES -> TvWatchStatsContentFilter.MOVIES
        WatchStatsContentScope.SHOWS -> TvWatchStatsContentFilter.SHOWS
    },
).clearAdvanced()

private fun WatchStatsInsightMode.dashboardAvailable(
    summary: WatchStatsSummary,
    hasSimklData: Boolean,
): Boolean = when (this) {
    WatchStatsInsightMode.OVERVIEW,
    WatchStatsInsightMode.ACTIVITY -> true
    WatchStatsInsightMode.SOURCE -> summary.sourceBreakdown.any { it.sessionCount > 0 } || hasSimklData
    WatchStatsInsightMode.GENRES -> summary.advanced.availability.hasGenres
    WatchStatsInsightMode.YEARS -> summary.advanced.availability.hasYears
    WatchStatsInsightMode.RATINGS -> summary.advanced.availability.hasRatings
}

private fun WatchStatsInsightMode.requiresPremium(): Boolean =
    this == WatchStatsInsightMode.GENRES ||
        this == WatchStatsInsightMode.YEARS ||
        this == WatchStatsInsightMode.RATINGS

private fun WatchStatsInsightMode.advancedRows(summary: WatchStatsSummary): List<WatchStatsAdvancedGroup> =
    when (this) {
        WatchStatsInsightMode.GENRES -> summary.advanced.genreGroups
        WatchStatsInsightMode.YEARS -> summary.advanced.yearGroups
        else -> emptyList()
    }

private fun sourceBreakdownRows(summary: WatchStatsSummary): List<DashboardBreakdownRow> =
    summary.sourceBreakdown
        .filter { it.sessionCount > 0 }
        .map {
            DashboardBreakdownRow(
                label = WatchStatsUiText.sourceLabel(it.source),
                value = "${pluralCount(it.sessionCount, "session")} - ${dashboardRuntimeValue(it.countedWatchMs)}",
                count = it.sessionCount,
            )
        }

private fun confidenceBreakdownRows(summary: WatchStatsSummary): List<DashboardBreakdownRow> =
    summary.runtimeConfidenceBreakdown
        .filter { it.sessionCount > 0 }
        .map {
            DashboardBreakdownRow(
                label = WatchStatsUiText.confidenceLabel(it.confidence),
                value = "${pluralCount(it.sessionCount, "session")} - ${dashboardRuntimeValue(it.countedWatchMs)}",
                count = it.sessionCount,
            )
        }

private fun ratingBreakdownRows(summary: WatchStatsSummary): List<DashboardBreakdownRow> =
    summary.advanced.ratingDistribution
        .map {
            DashboardBreakdownRow(
                label = it.band.label,
                value = WatchStatsUiText.formatDuration(it.attributedWatchMs),
                count = it.titleCount.coerceAtLeast(it.sessionCount).coerceAtLeast(1),
            )
        }

private fun dashboardRowsFromGroups(groups: List<WatchStatsAdvancedGroup>): List<DashboardTitleRow> =
    groups
        .flatMap { group ->
            group.recentActivity.take(2).map { session ->
                DashboardTitleRow(
                    id = "${group.key}_${session.id}",
                    title = session.title,
                    subtitle = "${group.label} - ${WatchStatsUiText.sourceLabel(session.source)}",
                    value = dashboardSessionRuntimeValue(session),
                    posterUrl = session.posterUrl,
                    session = session,
                )
            }
        }
        .distinctBy { it.id }
        .take(5)

private fun dashboardRowsFromRatingGroups(groups: List<WatchStatsRatingGroup>): List<DashboardTitleRow> =
    groups
        .flatMap { group ->
            group.recentActivity.take(2).map { session ->
                DashboardTitleRow(
                    id = "${group.band.name}_${session.id}",
                    title = session.title,
                    subtitle = "${group.band.label} - ${WatchStatsUiText.sourceLabel(session.source)}",
                    value = dashboardSessionRuntimeValue(session),
                    posterUrl = session.posterUrl,
                    session = session,
                )
            }
        }
        .distinctBy { it.id }
        .take(5)

private fun dashboardRecentTitleRows(sessions: List<WatchSession>): List<DashboardTitleRow> =
    sessions
        .take(5)
        .map { session ->
            DashboardTitleRow(
                id = "recent_${session.id}",
                title = session.title,
                subtitle = session.watchStatsSecondaryTitleReadable(),
                value = dashboardSessionRuntimeValue(session),
                posterUrl = session.posterUrl,
                session = session,
            )
        }

private fun sourceModeSubtitle(summary: WatchStatsSummary): String {
    val rows = summary.sourceBreakdown.filter { it.sessionCount > 0 }
    return when (rows.size) {
        0 -> "No source sessions yet"
        1 -> "All counted activity currently comes from ${WatchStatsUiText.sourceLabel(rows.first().source)}"
        else -> "Recorded sessions by source"
    }
}

private fun recentRuntimeTitle(sessions: List<WatchSession>): String =
    when (sessions.count { it.countedWatchMs > 0L }) {
        0 -> "No counted runtime yet"
        1 -> "Recent counted sessions"
        else -> "Watch time trend"
    }

private fun dashboardDominantSource(summary: WatchStatsSummary): String =
    summary.sourceBreakdown
        .filter { it.sessionCount > 0 }
        .maxByOrNull { it.countedWatchMs }
        ?.source
        ?.let(WatchStatsUiText::sourceLabel)
        ?: "No source"

private fun dashboardConfidenceLines(summary: WatchStatsSummary): List<String> {
    val measured = summary.runtimeConfidenceBreakdown
        .firstOrNull { it.confidence == RuntimeConfidence.MEASURED }
        ?.sessionCount
        ?: 0
    val estimated = summary.runtimeConfidenceBreakdown
        .firstOrNull { it.confidence == RuntimeConfidence.ESTIMATED }
        ?.sessionCount
        ?: 0
    val unknown = summary.runtimeConfidenceBreakdown
        .firstOrNull { it.confidence == RuntimeConfidence.UNKNOWN }
        ?.sessionCount
        ?: 0
    return listOf(
        pluralCount(measured, "measured"),
        pluralCount(estimated, "estimated"),
        pluralCount(unknown, "unknown runtime"),
    )
}

private fun pluralCount(count: Int, singular: String): String =
    when {
        singular == "partial" -> "$count partial"
        count == 1 || singular.endsWith("ed") -> "$count $singular"
        singular == "unknown runtime" -> "$count unknown runtimes"
        else -> "$count ${singular}s"
    }

private fun dashboardTopMovieRows(sessions: List<WatchSession>): List<DashboardTitleRow> =
    sessions
        .filter { it.mediaType == MediaType.MOVIE }
        .groupBy { it.mediaId }
        .map { (id, rows) ->
            val first = rows.maxByOrNull { it.startedAt } ?: rows.first()
            DashboardTitleRow(
                id = "movie_$id",
                title = first.title,
                subtitle = "${rows.size} sessions",
                value = dashboardRuntimeValue(rows.sumOf { it.countedWatchMs }),
                posterUrl = first.posterUrl,
                session = first,
            )
        }
        .sortedByDescending { row ->
            sessions.filter { "movie_${it.mediaId}" == row.id }.sumOf { it.countedWatchMs }
        }
        .take(5)

private fun dashboardTopShowRows(sessions: List<WatchSession>): List<DashboardTitleRow> =
    sessions
        .filter { it.mediaType == MediaType.SERIES }
        .groupBy { it.showId ?: it.showTitle ?: it.mediaId }
        .map { (id, rows) ->
            val first = rows.maxByOrNull { it.startedAt } ?: rows.first()
            val countedMs = rows.sumOf { it.countedWatchMs }
            DashboardTitleRow(
                id = "show_$id",
                title = first.showTitle?.takeIf { it.isNotBlank() } ?: first.title,
                subtitle = "${rows.size} episodes/sessions",
                value = if (countedMs > 0L) {
                    WatchStatsUiText.formatDuration(countedMs)
                } else {
                    pluralCount(rows.size, "episode")
                },
                posterUrl = first.posterUrl,
                session = first,
            )
        }
        .sortedByDescending { row ->
            sessions.filter {
                it.mediaType == MediaType.SERIES &&
                    "show_${it.showId ?: it.showTitle ?: it.mediaId}" == row.id
            }.sumOf { it.countedWatchMs }
        }
        .take(5)

private fun dashboardRuntimeValue(ms: Long): String =
    if (ms <= 0L) "Unknown" else WatchStatsUiText.formatDuration(ms)

private fun dashboardSessionRuntimeValue(session: WatchSession): String {
    val countedMs = session.countedWatchMs
    val durationMs = session.durationMs?.takeIf { it > 0L }
    return when {
        countedMs > 0L -> WatchStatsUiText.formatDuration(countedMs)
        durationMs != null -> WatchStatsUiText.formatDuration(durationMs)
        session.mediaType == MediaType.SERIES -> "Episode"
        else -> "Unknown"
    }
}

private fun runtimeConfidenceChipLabel(session: WatchSession): String? =
    when {
        session.runtimeConfidence == RuntimeConfidence.UNKNOWN && session.mediaType == MediaType.SERIES -> null
        else -> WatchStatsUiText.confidenceLabel(session.runtimeConfidence)
    }

private fun runtimeChipLabel(session: WatchSession): String {
    val countedMs = session.countedWatchMs
    val durationMs = session.durationMs?.takeIf { it > 0L }
    return when {
        countedMs > 0L -> "Counted ${WatchStatsUiText.formatDuration(countedMs)}"
        durationMs != null -> "Runtime ${WatchStatsUiText.formatDuration(durationMs)}"
        session.mediaType == MediaType.SERIES -> "Episode runtime"
        else -> "Runtime Unknown"
    }
}

private fun overviewHeroSubtitle(summary: WatchStatsSummary): String = when {
    summary.measuredWatchMs > 0L && summary.estimatedWatchMs > 0L ->
        "Measured and estimated watch time stay separate."
    summary.measuredWatchMs > 0L ->
        "Measured from Torve playback."
    summary.estimatedWatchMs > 0L ->
        "Estimated from imported or manual history."
    else ->
        "Watch in Torve or import history to build this total."
}

private fun overviewInsightTitle(selectedKey: String?): String = when (selectedKey) {
    "time" -> "Watch time detail"
    "completed" -> "Completion detail"
    "sources" -> "Source detail"
    "confidence" -> "Runtime detail"
    else -> "Insight details"
}

private fun overviewInsightMessage(
    summary: WatchStatsSummary,
    selectedKey: String?,
): String {
    val unknownCount = unknownRuntimeSessionCount(summary)
    return when (selectedKey) {
        "time", null -> when {
            summary.measuredWatchMs == 0L && summary.estimatedWatchMs > 0L ->
                "Most watch time is estimated from imports. New Torve playback will appear as measured."
            unknownCount > 0 ->
                "Some imported items have unknown runtime and are excluded from total watch time."
            summary.partialCount > 0 ->
                "Partial sessions are tracked separately and do not count as completed."
            summary.sourceSessionCount(WatchSessionSource.TORVE_PLAYER) == 0 ->
                "Torve playback appears here after watching in Torve."
            else ->
                "Your totals separate measured, estimated, and unknown runtime so watch time stays honest."
        }
        "completed" ->
            "Completed counts use the 85% threshold. Partial and abandoned sessions stay separate."
        "sources" -> when {
            summary.sourceSessionCount(WatchSessionSource.TRAKT) > 0 ->
                "Trakt imports are labeled as estimated unless runtime is known locally."
            summary.sourceSessionCount(WatchSessionSource.TORVE_PLAYER) == 0 ->
                "Torve playback appears here after watching in Torve."
            else ->
                "Each source keeps its own label so imports, manual marks, and Torve playback stay clear."
        }
        "confidence" ->
            "Measured, estimated, and unknown runtimes remain separate. Unknown runtimes never inflate totals."
        else ->
            "Focus a card to inspect source, status, and runtime confidence."
    }
}

private fun unknownRuntimeSessionCount(summary: WatchStatsSummary): Int =
    summary.runtimeConfidenceBreakdown
        .filter { it.confidence == RuntimeConfidence.UNKNOWN }
        .sumOf { it.sessionCount }

private fun WatchStatsSummary.sourceSessionCount(source: WatchSessionSource): Int =
    sourceBreakdown.firstOrNull { it.source == source }?.sessionCount ?: 0

private fun WatchSession.watchStatsSecondaryTitleReadable(): String {
    val episode = seasonNumber?.let { season ->
        episodeNumber?.let { episode -> "S${season}E${episode}" }
    }
    val safeShowTitle = showTitle?.takeIf { it.isNotBlank() }
    return when {
        safeShowTitle != null && episode != null -> "$safeShowTitle - $episode"
        safeShowTitle != null -> safeShowTitle
        episode != null -> episode
        else -> WatchStatsUiText.statusLabel(status)
    }
}

private fun formatWatchDate(epochMs: Long): String =
    runCatching {
        DateTimeFormatter.ofPattern("MMM d")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
    }.getOrElse { "Recent" }

private fun TvWatchStatsFilterSelection.toFilterState(): WatchStatsFilterState {
    return WatchStatsFilterState(
        startMs = timeRange.startMs(),
        mediaTypes = when (content) {
            TvWatchStatsContentFilter.ALL -> emptySet()
            TvWatchStatsContentFilter.MOVIES -> setOf(MediaType.MOVIE)
            TvWatchStatsContentFilter.SHOWS -> setOf(MediaType.SERIES)
        },
        statuses = when (status) {
            TvWatchStatsStatusFilter.ALL -> emptySet()
            TvWatchStatsStatusFilter.COMPLETED -> setOf(
                WatchSessionStatus.COMPLETED,
                WatchSessionStatus.MANUAL_COMPLETED,
                WatchSessionStatus.IMPORTED_COMPLETED,
            )
            TvWatchStatsStatusFilter.PARTIAL -> setOf(WatchSessionStatus.PARTIAL)
            TvWatchStatsStatusFilter.MANUAL -> setOf(WatchSessionStatus.MANUAL_COMPLETED)
            TvWatchStatsStatusFilter.IMPORTED -> setOf(WatchSessionStatus.IMPORTED_COMPLETED)
        },
        sources = when (source) {
            TvWatchStatsSourceFilter.ALL -> emptySet()
            TvWatchStatsSourceFilter.TORVE -> setOf(WatchSessionSource.TORVE_PLAYER)
            TvWatchStatsSourceFilter.TRAKT -> setOf(WatchSessionSource.TRAKT)
            TvWatchStatsSourceFilter.SIMKL -> setOf(WatchSessionSource.SIMKL)
            TvWatchStatsSourceFilter.MANUAL -> setOf(WatchSessionSource.MANUAL)
        },
        genres = genre?.let { setOf(it) }.orEmpty(),
        years = year?.let { setOf(it) }.orEmpty(),
        decades = decade?.let { setOf(it) }.orEmpty(),
        ratingBands = ratingBand?.let { setOf(it) }.orEmpty(),
        actors = actor?.let { setOf(it) }.orEmpty(),
        directors = director?.let { setOf(it) }.orEmpty(),
        studios = studio?.let { setOf(it) }.orEmpty(),
        networks = network?.let { setOf(it) }.orEmpty(),
        providers = provider?.let { setOf(it) }.orEmpty(),
        certifications = certification?.let { setOf(it) }.orEmpty(),
    )
}

private fun TvWatchStatsTimeRange.startMs(): Long? {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return when (this) {
        TvWatchStatsTimeRange.ALL_TIME -> null
        TvWatchStatsTimeRange.THIS_YEAR -> today
            .withDayOfYear(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        TvWatchStatsTimeRange.THIS_MONTH -> today
            .withDayOfMonth(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        TvWatchStatsTimeRange.LAST_30_DAYS -> today
            .minusDays(30)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        TvWatchStatsTimeRange.LAST_7_DAYS -> today
            .minusDays(7)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }
}

private fun TvWatchStatsFilterSelection.hasAdvancedSelection(): Boolean =
    genre != null ||
        year != null ||
        decade != null ||
        ratingBand != null ||
        actor != null ||
        director != null ||
        studio != null ||
        network != null ||
        provider != null ||
        certification != null

private fun TvWatchStatsFilterSelection.clearAdvanced(): TvWatchStatsFilterSelection =
    copy(
        genre = null,
        year = null,
        decade = null,
        ratingBand = null,
        actor = null,
        director = null,
        studio = null,
        network = null,
        provider = null,
        certification = null,
    )
