package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.torve.android.tv.TV_FILTER_ROW_NESTED_END_GUTTER
import com.torve.android.tv.TV_PAGE_BOTTOM_GUTTER
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.focus.TvSportsFocusStateMachine
import com.torve.android.tv.focus.TvSportsFocusTarget
import com.torve.android.tv.focus.TvSportsTopAction
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import com.torve.android.catalog.SportsBootstrapJson
import com.torve.android.catalog.SportsBootstrapPayload
import com.torve.android.catalog.sportsBootstrapKey
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.torve.data.usenet.NewznabClient
import com.torve.data.usenet.NewznabItem
import com.torve.data.usenet.TorBoxUsenetClient
import com.torve.domain.sports.SportBucket
import com.torve.domain.sports.SportsReleaseDisplay
import com.torve.domain.sports.SportsReleaseDisplayParser
import com.torve.domain.usenet.UsenetIndexerCategoryMap
import com.torve.domain.usenet.UsenetIndexerUrlResolver
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.presentation.panda.PandaConfigStateStore
import com.torve.presentation.usenet.NzbBrowseStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import com.torve.data.auth.AuthClient

private val SPORTS_PRIMARY_BUCKETS = listOf(
    SportBucket.F1,
    SportBucket.MMA,
    SportBucket.BOXING,
    SportBucket.WRESTLING,
    SportBucket.AMERICAN_FOOTBALL,
    SportBucket.BASKETBALL,
    SportBucket.BASEBALL,
    SportBucket.SOCCER,
    SportBucket.HOCKEY,
    SportBucket.TENNIS,
    SportBucket.GOLF,
    SportBucket.CRICKET,
    SportBucket.RUGBY,
)

private const val SPORTS_SCREEN_ID = "tv_sports"
private const val SPORTS_ROW_TOP_ACTIONS = "top_actions"
private const val SPORTS_ROW_CATEGORIES = "categories"
private const val SPORTS_ROW_EVENTS = "events"

private data class SportsResolveUiState(
    val message: String,
    val isWorking: Boolean,
)

/**
 * TV Sports catalog. Cross-platform Newznab + TorBox plumbing now lives
 * in `shared/`, so this screen is just the TV-flavored UI on top of
 * [NewznabClient] / [TorBoxUsenetClient]: D-pad-friendly bucket pills,
 * a vertical list of focusable release rows, and Play / Resolve handed
 * off to the shared player route.
 *
 * The screen reads its indexer + TorBox credentials directly off the
 * live [PandaConfigStateStore]; if the user hasn't completed Panda
 * setup, the page surfaces a one-line CTA to open it instead of an
 * empty grid.
 */
@Composable
internal fun TvSportsScreen(
    railFocusRequester: FocusRequester,
    onPlayStream: (url: String, title: String, sizeBytes: Long?) -> Unit,
    onOpenPandaSetup: () -> Unit = {},
    onFirstContentRequester: (FocusRequester) -> Unit = {},
    onContentFocused: (FocusRequester) -> Unit = {},
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
    isActive: Boolean = true,
    pandaStore: PandaConfigStateStore = koinInject(),
    newznab: NewznabClient = koinInject(),
    torbox: TorBoxUsenetClient = koinInject(),
    prefsRepo: PreferencesRepository = koinInject(),
    localSettingsRepo: DeviceLocalSettingsRepository = koinInject(),
    secretStore: IntegrationSecretStore = koinInject(),
) {
    val pandaState by pandaStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var localSportsConfig by remember { mutableStateOf<LocalSportsPandaConfig?>(null) }
    LaunchedEffect(Unit) {
        localSportsConfig = withContext(Dispatchers.IO) {
            resolveLocalSportsConfig(secretStore, prefsRepo)
        }
    }

    // Hydrate the PandaConfigStateStore by instantiating the VM once.
    // Without this, a user who's never opened the Panda wizard in
    // this app session sees an empty configStateStore — so Sports
    // can't resolve any indexer URL/key even though their Panda
    // setup IS persisted on the backend. The VM's init pulls the
    // saved config from /panda/configs/me and publishes into the
    // singleton store; the resulting StateFlow update re-triggers
    // the credential resolution below. Discarding the VM reference
    // is fine — it's a factory and gets GC'd as soon as the launch
    // finishes; the store retains the hydrated state.
    // Do not hydrate Panda through its setup ViewModel from Sports. That path
    // can hit the network on screen entry. Sports reads persisted credentials
    // directly and lets the warmup worker refresh result caches.

    // Resolve indexer + TorBox credentials from the live Panda state.
    // Mirrors the desktop V2SportsPage logic but reads directly from
    // the store rather than hand-threaded params.
    val activeIndexer = pandaState.nzbIndexers.firstOrNull {
        it.type != "none" && it.apiKey.isUsableSportsSecret()
    }
    val localSportsIndexer = localSportsConfig?.indexer
    val indexerType = activeIndexer?.type
        ?: localSportsIndexer?.type
        ?: pandaState.nzbIndexer.takeIf { it != "none" }
        ?: ""
    val indexerUrl = if (activeIndexer != null) {
        UsenetIndexerUrlResolver.resolve(activeIndexer.type, activeIndexer.url)
    } else if (localSportsIndexer != null) {
        localSportsIndexer.url
    } else if (pandaState.nzbIndexer != "none") {
        UsenetIndexerUrlResolver.resolve(pandaState.nzbIndexer, "")
    } else ""
    val indexerKey = activeIndexer?.apiKey
        ?: localSportsIndexer?.apiKey
        ?: pandaState.nzbIndexerApiKey.takeIf { it.isUsableSportsSecret() }
        ?: ""
    val torboxKey = pandaState.downloadClientApiKey
        .takeIf {
            pandaState.downloadClient.equals("torbox", ignoreCase = true) &&
                it.isUsableSportsSecret()
        }
        ?: localSportsConfig?.torboxApiKey.orEmpty()
    val configured = indexerUrl.isNotBlank() && indexerKey.isNotBlank()

    val pageKey = "tv_sports"
    val sportsCacheKey = remember(indexerUrl, indexerKey) {
        "tv_sports_cache:${indexerUrl.hashCode()}:${indexerKey.hashCode()}"
    }
    val sportsJson = remember { Json { ignoreUnknownKeys = true } }
    val sportsCacheWriteMutex = remember { Mutex() }
    val pageState by NzbBrowseStateHolder.flow(pageKey).collectAsState()
    val savedOnce = remember { NzbBrowseStateHolder.get(pageKey) }
    var query by remember { mutableStateOf(savedOnce.query) }
    val savedMode = savedOnce.selectedSportBucket ?: SPORTS_FILTER_ALL
    var selectedBucket by remember {
        mutableStateOf(
            savedMode.let { name ->
                SportBucket.entries.firstOrNull { it.name == name }
            },
        )
    }
    var selectedSportsMode by remember {
        mutableStateOf(savedMode)
    }
    val sportsFocusState = remember { TvSportsFocusStateMachine(selectedSportsMode) }
    var resolveStatus by remember { mutableStateOf<Map<String, SportsResolveUiState>>(emptyMap()) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedOnce.scrollIndex,
        initialFirstVisibleItemScrollOffset = savedOnce.scrollOffset,
    )

    fun startFetch(forceAll: Boolean = false, replaceInFlight: Boolean = false) {
        val q = query
        val requestedMode = if (forceAll) SPORTS_FILTER_ALL else selectedSportsMode
        val plan = tvSportsRefreshPlan(requestedMode, q)
        val fetchJobKey = tvSportsRefreshJobKey(pageKey, plan.scopeId)
        if (NzbBrowseStateHolder.isFetching(fetchJobKey) && !replaceInFlight) return
        NzbBrowseStateHolder.startFetch(fetchJobKey) {
            NzbBrowseStateHolder.update(pageKey) {
                it.copy(
                    loading = it.items.isEmpty() && plan.kind == TvSportsRefreshKind.ALL,
                    errorText = if (plan.kind == TvSportsRefreshKind.ALL) null else it.errorText,
                    activeRefreshScopes = it.activeRefreshScopes + plan.scopeId,
                    refreshProgressByScope = it.refreshProgressByScope +
                        (plan.scopeId to "Starting ${sportsScopeLabel(plan.scopeId)} refresh…"),
                )
            }
            try {
                val sportsCat = UsenetIndexerCategoryMap.sportsCategoriesFor(indexerType)
                val items = if (plan.remoteQuery.isNullOrBlank()) {
                    newznab.browseAllPages(
                        indexerUrl,
                        indexerKey,
                        sportsCat,
                        maxItems = plan.maxItems,
                        maxAgeDays = plan.maxAgeDays,
                        onProgress = { fetched, max ->
                            NzbBrowseStateHolder.update(pageKey) {
                                it.copy(
                                    refreshProgressByScope = it.refreshProgressByScope +
                                        (plan.scopeId to "Loading… $fetched / $max"),
                                )
                            }
                        },
                    )
                } else {
                    newznab.searchAllPages(
                        indexerUrl,
                        indexerKey,
                        sportsCat,
                        plan.remoteQuery,
                        maxItems = plan.maxItems,
                        maxAgeDays = plan.maxAgeDays,
                        onProgress = { fetched, max ->
                            NzbBrowseStateHolder.update(pageKey) {
                                it.copy(
                                    refreshProgressByScope = it.refreshProgressByScope +
                                        (plan.scopeId to "Loading… $fetched / $max"),
                                )
                            }
                        },
                    )
                }
                val error = if (items.isEmpty() && configured) {
                    if (plan.remoteQuery.isNullOrBlank()) {
                        "Indexer returned 0 results for ${sportsScopeLabel(plan.scopeId)}."
                    } else {
                        "No matches for \"${plan.remoteQuery}\"."
                    }
                } else null
                var mergedItems: List<NewznabItem> = emptyList()
                NzbBrowseStateHolder.update(pageKey) {
                    mergedItems = mergeTvSportsRefresh(it.items, items, plan)
                    it.copy(
                        loading = false,
                        progress = null,
                        items = mergedItems,
                        errorText = if (mergedItems.isEmpty() && plan.kind == TvSportsRefreshKind.ALL) {
                            error
                        } else {
                            null
                        },
                        query = q,
                        selectedSportBucket = it.selectedSportBucket ?: requestedMode,
                        scrollIndex = listState.firstVisibleItemIndex,
                        scrollOffset = listState.firstVisibleItemScrollOffset,
                        activeRefreshScopes = it.activeRefreshScopes - plan.scopeId,
                        refreshProgressByScope = it.refreshProgressByScope - plan.scopeId,
                        refreshErrorsByScope = if (error == null) {
                            it.refreshErrorsByScope - plan.scopeId
                        } else {
                            it.refreshErrorsByScope + (plan.scopeId to error)
                        },
                    )
                }
                withContext(Dispatchers.IO) {
                    sportsCacheWriteMutex.withLock {
                        val latest = NzbBrowseStateHolder.get(pageKey)
                        val payload = TvSportsCachePayload(
                            savedAtMs = System.currentTimeMillis(),
                            query = latest.query,
                            selectedSportBucket = latest.selectedSportBucket,
                            items = latest.items,
                        )
                        prefsRepo.setString(
                            sportsCacheKey,
                            sportsJson.encodeToString(TvSportsCachePayload.serializer(), payload),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val message = t.message ?: "Indexer call failed."
                NzbBrowseStateHolder.update(pageKey) {
                    it.copy(
                        loading = false,
                        progress = null,
                        items = it.items,
                        errorText = if (it.items.isEmpty() && plan.kind == TvSportsRefreshKind.ALL) {
                            message
                        } else {
                            it.errorText
                        },
                        activeRefreshScopes = it.activeRefreshScopes - plan.scopeId,
                        refreshProgressByScope = it.refreshProgressByScope - plan.scopeId,
                        refreshErrorsByScope = it.refreshErrorsByScope + (plan.scopeId to message),
                    )
                }
            }
        }
    }

    LaunchedEffect(indexerUrl, indexerKey) {
        if (!configured) return@LaunchedEffect
        var restoredFromCache = false
        if (NzbBrowseStateHolder.get(pageKey).items.isEmpty()) {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    val userId = localSettingsRepo.getString(AuthClient.KEY_AUTH_USER_ID)
                    val bootstrap = userId?.let { id ->
                        localSettingsRepo.getString(sportsBootstrapKey(id, indexerUrl, indexerKey))
                            ?.takeIf { it.isNotBlank() }
                            ?.let { SportsBootstrapJson.decodeFromString(SportsBootstrapPayload.serializer(), it) }
                            ?.let { payload ->
                                TvSportsCachePayload(
                                    savedAtMs = payload.savedAtMs,
                                    query = payload.query,
                                    selectedSportBucket = payload.selectedSportBucket,
                                    items = payload.items,
                                )
                            }
                    }
                    bootstrap ?: prefsRepo.getString(sportsCacheKey)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { sportsJson.decodeFromString(TvSportsCachePayload.serializer(), it) }
                }.getOrNull()
            }
            if (restored != null && restored.items.isNotEmpty()) {
                restoredFromCache = true
                query = restored.query
                selectedSportsMode = restored.selectedSportBucket ?: SPORTS_FILTER_ALL
                selectedBucket = selectedSportsMode.let { name ->
                    SportBucket.entries.firstOrNull { it.name == name }
                }
                sportsFocusState.selectCategory(selectedSportsMode)
                NzbBrowseStateHolder.put(
                    pageKey,
                    NzbBrowseStateHolder.State(
                        query = restored.query,
                        items = restored.items,
                        loading = false,
                        selectedSportBucket = restored.selectedSportBucket,
                    ),
                )
            }
        }
        if (!restoredFromCache && NzbBrowseStateHolder.get(pageKey).items.isEmpty()) {
            val current = NzbBrowseStateHolder.get(pageKey)
            if (!current.loading) {
                startFetch(forceAll = true)
            }
        }
    }

    // Classify and parse each item once per load. Recomputed on filter change.
    data class ClassifiedItem(
        val item: NewznabItem,
        val bucket: SportBucket,
        val display: SportsReleaseDisplay,
    )
    val classified: List<ClassifiedItem> = remember(pageState.items) {
        pageState.items.map { item ->
            val bucket = SportBucket.classify(item.title)
            ClassifiedItem(
                item = item,
                bucket = bucket,
                display = SportsReleaseDisplayParser.parse(item.title, bucket),
            )
        }
    }
    val countsByBucket: Map<SportBucket, Int> = remember(classified) {
        classified.groupingBy { it.bucket }.eachCount()
    }
    val chipListState = rememberLazyListState()
    val selectedChipIndex = remember(selectedSportsMode) {
        when (selectedSportsMode) {
            SPORTS_FILTER_ALL -> 0
            SPORTS_FILTER_TODAY -> 1
            SPORTS_FILTER_RECENT -> 2
            else -> {
                val bucketIndex = SPORTS_PRIMARY_BUCKETS.indexOfFirst { it.name == selectedSportsMode }
                if (bucketIndex >= 0) 3 + bucketIndex else 0
            }
        }
    }
    LaunchedEffect(selectedChipIndex) {
        val selectedIsVisible = chipListState.layoutInfo.visibleItemsInfo.any { it.index == selectedChipIndex }
        if (!selectedIsVisible) chipListState.animateScrollToItem(selectedChipIndex)
    }
    val todayTitlePattern = remember {
        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    }
    val todayTitlePatternSpaced = remember(todayTitlePattern) { todayTitlePattern.replace('.', ' ') }
    val visible: List<ClassifiedItem> = remember(classified, selectedBucket, selectedSportsMode, todayTitlePattern, todayTitlePatternSpaced) {
        when (selectedSportsMode) {
            SPORTS_FILTER_TODAY -> classified.filter {
                it.item.title.contains(todayTitlePattern) || it.item.title.contains(todayTitlePatternSpaced)
            }
            SPORTS_FILTER_RECENT -> classified.take(80)
            SPORTS_FILTER_ALL -> classified
            else -> selectedBucket?.let { bucket -> classified.filter { it.bucket == bucket } } ?: classified
        }
    }

    val categoryIds = remember {
        listOf(SPORTS_FILTER_ALL, SPORTS_FILTER_TODAY, SPORTS_FILTER_RECENT) +
            SPORTS_PRIMARY_BUCKETS.map(SportBucket::name)
    }
    val categoryRequesters = remember {
        categoryIds.associateWith { FocusRequester() }
    }
    val firstChipRequester = categoryRequesters.getValue(SPORTS_FILTER_ALL)
    val refreshFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val retryFocusRequester = remember { FocusRequester() }
    val focusRestoreController = rememberTvModalFocusRestoreController(SPORTS_SCREEN_ID)
    val refreshTarget = remember { sportsTopActionTarget(TvSportsTopAction.REFRESH) }
    val searchTarget = remember { sportsTopActionTarget(TvSportsTopAction.SEARCH) }
    val retryTarget = remember { sportsTopActionTarget(TvSportsTopAction.RETRY) }
    rememberRegisteredTvFocusRequester(focusRestoreController, refreshTarget, refreshFocusRequester)
    rememberRegisteredTvFocusRequester(focusRestoreController, searchTarget, searchFocusRequester)
    val visibleEventIds = remember(visible, configured) {
        if (configured) visible.map { it.item.sportsStableId() } else emptyList()
    }
    val visibleEventTargets = remember(visibleEventIds) {
        visibleEventIds.mapIndexed { index, id -> sportsEventTarget(id, index) }.toSet()
    }
    val validFocusTargets = remember(categoryIds, visibleEventTargets) {
        buildSet {
            add(refreshTarget)
            add(searchTarget)
            add(retryTarget)
            categoryIds.forEachIndexed { index, id -> add(sportsCategoryTarget(id, index)) }
            addAll(visibleEventTargets)
        }
    }
    LaunchedEffect(validFocusTargets) {
        focusRestoreController.pruneInactiveTargets(validFocusTargets)
    }
    DisposableEffect(registerFocusHandle, focusRestoreController) {
        registerFocusHandle?.invoke(
            TvScreenFocusHandle(
                captureFocusedOrigin = {
                    focusRestoreController.captureFocusedOrigin(
                        screenId = SPORTS_SCREEN_ID,
                        outerListState = listState,
                    )
                },
                requestRestore = { origin, reason ->
                    focusRestoreController.requestRestore(origin, reason)
                },
                isOriginFocused = focusRestoreController::isOriginFocused,
            ),
        )
        onDispose { registerFocusHandle?.invoke(null) }
    }
    LaunchedEffect(
        focusRestoreController.pendingRestore?.restoreToken,
        visibleEventIds,
        isActive,
    ) {
        focusRestoreController.restorePendingFocus(
            screenId = SPORTS_SCREEN_ID,
            outerListState = listState,
            isScreenActive = { isActive },
        )
    }
    LaunchedEffect(visibleEventIds, focusRestoreController.focusedTarget, selectedSportsMode, isActive) {
        if (!isActive) return@LaunchedEffect
        when (val repair = sportsFocusState.repairAfterEventMutation(visibleEventIds)) {
            is TvSportsFocusTarget.Event -> {
                listState.scrollToItem(repair.index)
                withFrameNanos { }
                runCatching {
                    focusRestoreController.requesterFor(sportsEventTarget(repair.eventId, repair.index)).requestFocus()
                }
            }
            is TvSportsFocusTarget.Category -> {
                withFrameNanos { }
                categoryRequesters[repair.categoryId]?.let { requester ->
                    runCatching { requester.requestFocus() }
                }
            }
            else -> Unit
        }
    }
    val selectedCategoryRequester = categoryRequesters[selectedSportsMode] ?: firstChipRequester
    val rememberedEventIndex = sportsFocusState.state.focusedEventId
        ?.let(visibleEventIds::indexOf)
        ?.takeIf { it >= 0 }
        ?: 0
    val categoryDownRequester = visibleEventIds.getOrNull(rememberedEventIndex)?.let { eventId ->
        focusRestoreController.requesterFor(sportsEventTarget(eventId, rememberedEventIndex))
    }
    val selectedRefreshScope = tvSportsRefreshPlan(selectedSportsMode, query).scopeId
    val selectedScopeRefreshing = selectedRefreshScope in pageState.activeRefreshScopes
    val selectedScopeProgress = pageState.refreshProgressByScope[selectedRefreshScope]
    val selectedScopeError = pageState.refreshErrorsByScope[selectedRefreshScope]
    val availableTopActions = buildSet {
        add(TvSportsTopAction.REFRESH)
        add(TvSportsTopAction.SEARCH)
        if (selectedScopeError != null || pageState.errorText != null) add(TvSportsTopAction.RETRY)
    }
    LaunchedEffect(availableTopActions, isActive) {
        val repair = sportsFocusState.repairAfterTopActionMutation(availableTopActions)
        if (isActive && repair is TvSportsFocusTarget.Category) {
            withFrameNanos { }
            runCatching { selectedCategoryRequester.requestFocus() }
        }
    }
    fun selectSportsCategory(mode: String, bucket: SportBucket?) {
        selectedSportsMode = mode
        selectedBucket = bucket
        sportsFocusState.selectCategory(mode)
        NzbBrowseStateHolder.update(pageKey) { it.copy(selectedSportBucket = mode) }
    }
    LaunchedEffect(Unit) { onFirstContentRequester(firstChipRequester) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF101A26), Color(0xFF070B12), Color(0xFF020408)),
                    radius = 1550f,
                ),
            )
            .padding(
                start = TV_PAGE_CONTENT_GUTTER,
                top = TV_PAGE_TOP_GUTTER,
                end = 0.dp,
                bottom = TV_PAGE_BOTTOM_GUTTER,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Sports",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = Snow,
        )
        Text(
            text = selectedScopeProgress
                ?: selectedScopeError
                ?: "Sports releases detected from release names",
            style = MaterialTheme.typography.bodySmall,
            color = if (selectedScopeProgress != null || selectedScopeError != null) {
                Amber
            } else {
                Torve.colors.textSecondary
            },
        )

        // Search row — submit (IME action / OK on the field) triggers
        // a Newznab `t=search` against the configured sport
        // categories. Empty query falls back to browse on next reload.
        // Clearing the field via the trailing X re-runs the browse so
        // the user sees a fresh list without re-tapping Search.
        val keyboardController = LocalSoftwareKeyboardController.current
        var searchFieldFocused by remember { mutableStateOf(false) }
        var searchExpanded by remember { mutableStateOf(query.isNotBlank()) }
        var searchStartEditingSignal by remember { mutableIntStateOf(0) }
        fun collapseSearchToFilters() {
            keyboardController?.hide()
            searchExpanded = query.isNotBlank()
            runCatching { selectedCategoryRequester.requestFocus() }
            onContentFocused(selectedCategoryRequester)
        }
        // On TV, pressing Back while the search field is focused clears focus back to the rail.
        BackHandler(enabled = searchFieldFocused || searchExpanded) {
            collapseSearchToFilters()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvSportsSearchChip(
                label = if (selectedScopeRefreshing) "Refreshing…" else "Refresh",
                modifier = Modifier
                    .focusRequester(refreshFocusRequester)
                    .focusProperties {
                        left = railFocusRequester
                        down = selectedCategoryRequester
                    },
                onClick = {
                    if (configured && !selectedScopeRefreshing) startFetch()
                },
                onFocused = {
                    sportsFocusState.markTopActionFocused(TvSportsTopAction.REFRESH)
                    focusRestoreController.markFocused(refreshTarget)
                    onContentFocused(refreshFocusRequester)
                },
            )
            if (searchExpanded) {
                com.torve.android.ui.components.TorveSearchField(
                    value = query,
                    onValueChange = { newValue ->
                        val clearing = query.isNotBlank() && newValue.isBlank()
                        query = newValue
                        if (clearing) startFetch(forceAll = true, replaceInFlight = true)
                    },
                    placeholder = if (pageState.loading) "Searching…" else "Search sport releases",
                    onSubmit = { startFetch(forceAll = true, replaceInFlight = true) },
                    showFocusRing = true,
                    editOnClick = true,
                    startEditingSignal = searchStartEditingSignal,
                    onMoveDownFromEdit = { collapseSearchToFilters() },
                    modifier = Modifier
                        .width(300.dp)
                        .height(38.dp)
                        .focusRequester(searchFocusRequester)
                        .focusProperties {
                            left = refreshFocusRequester
                            down = selectedCategoryRequester
                        }
                        .onFocusChanged {
                            searchFieldFocused = it.hasFocus
                            if (it.hasFocus) {
                                sportsFocusState.markTopActionFocused(TvSportsTopAction.SEARCH)
                                focusRestoreController.markFocused(searchTarget)
                                onContentFocused(searchFocusRequester)
                            }
                        },
                )
            } else {
                TvSportsSearchChip(
                    label = if (query.isBlank()) "Search" else "Search: $query",
                    modifier = Modifier
                        .focusRequester(searchFocusRequester)
                        .focusProperties {
                            left = refreshFocusRequester
                            down = selectedCategoryRequester
                        },
                    onClick = {
                        searchExpanded = true
                        searchStartEditingSignal += 1
                    },
                    onFocused = {
                        sportsFocusState.markTopActionFocused(TvSportsTopAction.SEARCH)
                        focusRestoreController.markFocused(searchTarget)
                        onContentFocused(searchFocusRequester)
                    },
                )
            }
        }

        // Bucket filter pills — first focusable row; LEFT off the
        // first pill returns to the nav rail.
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                state = chipListState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = TV_FILTER_ROW_NESTED_END_GUTTER),
                modifier = Modifier.fillMaxWidth(),
            ) {
            item(key = "bucket_all") {
                val target = remember { sportsCategoryTarget(SPORTS_FILTER_ALL, 0) }
                val requester = rememberRegisteredTvFocusRequester(
                    focusRestoreController,
                    target,
                    firstChipRequester,
                )
                TvBucketChip(
                    label = "All · ${classified.size}",
                    selected = selectedSportsMode == SPORTS_FILTER_ALL,
                    onClick = { selectSportsCategory(SPORTS_FILTER_ALL, null) },
                    focusRequester = requester,
                    leftFocusRequester = railFocusRequester,
                    upFocusRequester = refreshFocusRequester,
                    downFocusRequester = categoryDownRequester,
                    onFocused = {
                        sportsFocusState.markCategoryFocused(SPORTS_FILTER_ALL)
                        focusRestoreController.markFocused(target)
                        onContentFocused(requester)
                    },
                )
            }
            item(key = "bucket_today") {
                val target = remember { sportsCategoryTarget(SPORTS_FILTER_TODAY, 1) }
                val requester = rememberRegisteredTvFocusRequester(
                    focusRestoreController,
                    target,
                    categoryRequesters.getValue(SPORTS_FILTER_TODAY),
                )
                val todayCount = classified.count {
                    it.item.title.contains(todayTitlePattern) || it.item.title.contains(todayTitlePatternSpaced)
                }
                TvBucketChip(
                    label = "Today $todayCount",
                    selected = selectedSportsMode == SPORTS_FILTER_TODAY,
                    onClick = { selectSportsCategory(SPORTS_FILTER_TODAY, null) },
                    focusRequester = requester,
                    upFocusRequester = refreshFocusRequester,
                    downFocusRequester = categoryDownRequester,
                    onFocused = {
                        sportsFocusState.markCategoryFocused(SPORTS_FILTER_TODAY)
                        focusRestoreController.markFocused(target)
                        onContentFocused(requester)
                    },
                )
            }
            item(key = "bucket_recent") {
                val target = remember { sportsCategoryTarget(SPORTS_FILTER_RECENT, 2) }
                val requester = rememberRegisteredTvFocusRequester(
                    focusRestoreController,
                    target,
                    categoryRequesters.getValue(SPORTS_FILTER_RECENT),
                )
                TvBucketChip(
                    label = "Recent ${classified.take(80).size}",
                    selected = selectedSportsMode == SPORTS_FILTER_RECENT,
                    onClick = { selectSportsCategory(SPORTS_FILTER_RECENT, null) },
                    focusRequester = requester,
                    upFocusRequester = refreshFocusRequester,
                    downFocusRequester = categoryDownRequester,
                    onFocused = {
                        sportsFocusState.markCategoryFocused(SPORTS_FILTER_RECENT)
                        focusRestoreController.markFocused(target)
                        onContentFocused(requester)
                    },
                )
            }
            items(SPORTS_PRIMARY_BUCKETS, key = { it.name }) { bucket ->
                val categoryIndex = 3 + SPORTS_PRIMARY_BUCKETS.indexOf(bucket)
                val target = remember(bucket) { sportsCategoryTarget(bucket.name, categoryIndex) }
                val requester = rememberRegisteredTvFocusRequester(
                    focusRestoreController,
                    target,
                    categoryRequesters.getValue(bucket.name),
                )
                val count = countsByBucket[bucket] ?: 0
                TvBucketChip(
                    label = "${bucket.label} · $count",
                    selected = selectedSportsMode == bucket.name,
                    onClick = { selectSportsCategory(bucket.name, bucket) },
                    focusRequester = requester,
                    upFocusRequester = refreshFocusRequester,
                    downFocusRequester = categoryDownRequester,
                    onFocused = {
                        sportsFocusState.markCategoryFocused(bucket.name)
                        focusRestoreController.markFocused(target)
                        onContentFocused(requester)
                    },
                )
            }
            }
        }

        when {
            !configured -> NotConfiguredBanner(
                redacted = pandaState.nzbIndexerApiKey.isBlank() &&
                    pandaState.nzbIndexers.any { it.type != "none" },
                onOpenPandaSetup = onOpenPandaSetup,
            )
            pageState.loading && pageState.items.isEmpty() && selectedScopeError == null -> Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Amber) }
            visible.isEmpty() && (selectedScopeError != null || pageState.errorText != null) -> Surface(
                color = Charcoal,
                shape = RoundedCornerShape(8.dp),
            ) {
                val requester = rememberRegisteredTvFocusRequester(
                    focusRestoreController,
                    retryTarget,
                    retryFocusRequester,
                )
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Couldn't load",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Snow,
                    )
                    Text(
                        text = selectedScopeError ?: pageState.errorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textSecondary,
                    )
                    TvSportsSearchChip(
                        label = if (selectedScopeRefreshing) "Retrying…" else "Retry",
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                left = railFocusRequester
                                up = selectedCategoryRequester
                            },
                        onClick = { if (!selectedScopeRefreshing) startFetch() },
                        onFocused = {
                            sportsFocusState.markTopActionFocused(TvSportsTopAction.RETRY)
                            focusRestoreController.markFocused(retryTarget)
                            onContentFocused(requester)
                        },
                    )
                }
            }
            visible.isEmpty() -> SportsEmptyState(
                title = when {
                    selectedBucket != null -> "No ${selectedBucket!!.label} releases found"
                    selectedSportsMode == SPORTS_FILTER_TODAY -> "No sports releases found today"
                    else -> "No sports releases found"
                },
                subtitle = "Try All, Recent, or Search.",
            )
            else -> LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(visible, key = { _, ci -> ci.item.sportsStableId() }) { index, ci ->
                    val rowKey = ci.item.sportsStableId()
                    val target = remember(rowKey, index) { sportsEventTarget(rowKey, index) }
                    val requester = rememberRegisteredTvFocusRequester(
                        focusRestoreController,
                        target,
                    )
                    val resolveState = resolveStatus[rowKey]
                    val torboxConfigured = torboxKey.isNotBlank()
                    TvSportsEventHubCard(
                        item = ci.item,
                        bucket = ci.bucket,
                        display = ci.display,
                        statusText = resolveState?.message,
                        isWorking = resolveState?.isWorking == true,
                        torboxConfigured = torboxConfigured,
                        onPlay = {
                            // IO dispatcher so the blocking NZB upload
                            // doesn't freeze the Compose dispatcher for
                            // the duration of the TorBox round-trip.
                            resolveStatus = resolveStatus +
                                (rowKey to SportsResolveUiState("Starting…", isWorking = true))
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    torbox.resolve(ci.item.nzbUrl, torboxKey) { msg ->
                                        scope.launch {
                                            resolveStatus = resolveStatus +
                                                (rowKey to SportsResolveUiState(msg, isWorking = true))
                                        }
                                    }
                                }
                                res.onSuccess { resolved ->
                                    resolveStatus = resolveStatus - rowKey
                                    onPlayStream(resolved.streamUrl, resolved.fileName, resolved.sizeBytes)
                                }.onFailure { t ->
                                    resolveStatus = resolveStatus + (
                                        rowKey to SportsResolveUiState(
                                            t.message ?: "Unknown error",
                                            isWorking = false,
                                        )
                                    )
                                }
                            }
                        },
                        leftFocusRequester = railFocusRequester,
                        focusRequester = requester,
                        upFocusRequester = if (index == 0) selectedCategoryRequester else null,
                        onFocused = {
                            sportsFocusState.markEventFocused(rowKey, index)
                            focusRestoreController.markFocused(target)
                            onContentFocused(requester)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSportsSearchChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = if (focused) Color(0xFF20283A) else Color(0xB0141824),
        shape = RoundedCornerShape(50),
        modifier = modifier
            .widthIn(min = 104.dp, max = 230.dp)
            .border(
                width = 1.dp,
                color = if (focused) Amber.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(50),
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            text = label,
            color = if (focused) Snow else Torve.colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TvBucketChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        selected -> Amber.copy(alpha = if (focused) 0.30f else 0.18f)
        focused -> Color(0xFF20283A)
        else -> Color(0xB0141824)
    }
    val labelColor = if (selected || focused) Snow else Torve.colors.textSecondary
    val borderColor = if (focused || selected) Amber.copy(alpha = if (focused) 0.9f else 0.45f) else Color.White.copy(alpha = 0.10f)
    Surface(
        color = container,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .scale(if (focused) 1.02f else 1f)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(
                if (leftFocusRequester != null || upFocusRequester != null || downFocusRequester != null) {
                    Modifier.focusProperties {
                        leftFocusRequester?.let { left = it }
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                    }
                } else {
                    Modifier
                },
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TvSportsEventHubCard(
    item: NewznabItem,
    bucket: SportBucket,
    display: SportsReleaseDisplay,
    statusText: String?,
    isWorking: Boolean,
    torboxConfigured: Boolean,
    onPlay: () -> Unit,
    leftFocusRequester: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = tvSportsEventInteraction(torboxConfigured, isWorking)
    val metaLine = listOfNotNull(
        display.leagueLabel,
        display.dateLabel,
        display.qualitySourceLabel,
    ).joinToString(" / ").ifBlank { bucket.label }
    val technicalLine = listOfNotNull(
        item.sizeBytes?.let { humanBytes(it) },
        item.fileCount?.let { "$it files" },
        display.releaseGroup,
    ).joinToString(" / ").ifBlank { item.title.replace('.', ' ') }
    val sportBadge = display.leagueLabel?.takeIf { it.length <= 12 } ?: display.sportLabel
    val statusLabel = when {
        !torboxConfigured -> "Setup"
        isWorking -> "Working"
        statusText != null -> "Retry"
        else -> "Ready"
    }
    val secondaryPills = listOfNotNull(display.qualityLabel, statusLabel)
    Surface(
        color = if (focused) Color(0xFF273346) else Color(0xA80E1420),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth(0.93f)
            .scale(if (focused) 1.014f else 1f)
            .shadow(if (focused) 14.dp else 0.dp, RoundedCornerShape(12.dp), clip = false)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Amber.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.045f),
                shape = RoundedCornerShape(12.dp),
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(
                if (leftFocusRequester != null || upFocusRequester != null) {
                    Modifier.focusProperties {
                        leftFocusRequester?.let { left = it }
                        upFocusRequester?.let { up = it }
                    }
                } else {
                    Modifier
                },
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // Keep one stable focus node while status changes from Ready
                // to Working and back. Duplicate activation is ignored by the
                // caller instead of removing this row from the focus graph.
                enabled = interaction.canFocus,
                onClick = { if (interaction.acceptsActivation) onPlay() },
            ),
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    colors = if (focused) {
                        listOf(Color.White.copy(alpha = 0.055f), Color.Transparent)
                    } else {
                        listOf(Color.White.copy(alpha = 0.018f), Color.Transparent)
                    },
                ),
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = Amber.copy(alpha = if (focused) 0.13f else 0.075f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = sportBadge,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) Amber else Torve.colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(min = 42.dp, max = 78.dp)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = display.title,
                        fontSize = if (focused) 17.sp else 16.sp,
                        fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                        color = Snow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metaLine,
                        fontSize = 11.sp,
                        color = Torve.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = technicalLine,
                        fontSize = 10.sp,
                        color = Torve.colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            fontSize = 10.sp,
                            color = Amber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.widthIn(min = 74.dp, max = 116.dp),
                ) {
                    if (focused) {
                        SportsTinyPill(label = "OK Play", focused = true)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            secondaryPills.forEach { label ->
                                SportsTinyPill(label = label, focused = false)
                            }
                        }
                    } else {
                        display.qualityLabel?.let { quality ->
                            SportsTinyPill(label = quality, focused = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SportsTinyPill(label: String, focused: Boolean) {
    Surface(
        color = if (focused) Amber.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.045f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            color = if (focused) Snow else Torve.colors.textSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun TvSportsEventCard(
    item: NewznabItem,
    bucket: SportBucket,
    display: SportsReleaseDisplay,
    statusText: String?,
    torboxConfigured: Boolean,
    onPlay: () -> Unit,
    leftFocusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val isWorking = statusText != null &&
        !statusText.startsWith("Failed") &&
        !statusText.startsWith("TorBox error")
    val borderColor = if (focused) Amber else Color.Transparent
    val container = if (focused) Graphite else Charcoal
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (focused) 1.02f else 1f)
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (leftFocusRequester != null) Modifier.focusProperties { left = leftFocusRequester }
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = torboxConfigured && !isWorking,
                onClick = onPlay,
            ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = Amber.copy(alpha = 0.18f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Snow,
                )
                Text(
                    text = listOfNotNull(
                        item.sizeBytes?.let { humanBytes(it) },
                        item.fileCount?.let { "$it files" },
                        item.grabs?.let { "$it grabs" },
                        item.pubDate,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Torve.colors.textSecondary,
                )
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                    )
                }
            }
            Text(
                text = if (!torboxConfigured) "TorBox key missing"
                    else if (isWorking) "Working…"
                    else "Ready",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (torboxConfigured && !isWorking) Amber else Torve.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun SportsEmptyState(title: String, subtitle: String) {
    Surface(
        color = Color(0xB0141824),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                color = Snow,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = Torve.colors.textSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun NotConfiguredBanner(redacted: Boolean, onOpenPandaSetup: () -> Unit) {
    Surface(
        color = Charcoal,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (redacted) "Streaming credentials are masked"
                    else "No NZB indexer configured",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Text(
                text = if (redacted)
                    "An indexer is configured but its API key is unavailable on this device. Re-paste it in Settings → Connections → Streaming sources → Usenet."
                else
                    "Set up Usenet via Settings → Connections → Streaming sources → Usenet.",
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textSecondary,
            )
        }
    }
}

private fun sportsTopActionTarget(action: TvSportsTopAction): TvFocusTargetId = TvFocusTargetId(
    screenId = SPORTS_SCREEN_ID,
    rowKey = SPORTS_ROW_TOP_ACTIONS,
    itemKey = action.actionId,
    rowIndex = -1,
    itemIndex = action.ordinal,
    targetType = "sports_action",
)

private fun sportsCategoryTarget(categoryId: String, index: Int): TvFocusTargetId = TvFocusTargetId(
    screenId = SPORTS_SCREEN_ID,
    rowKey = SPORTS_ROW_CATEGORIES,
    itemKey = categoryId,
    rowIndex = 0,
    itemIndex = index,
    targetType = "sports_category",
)

private fun sportsEventTarget(eventId: String, index: Int): TvFocusTargetId = TvFocusTargetId(
    screenId = SPORTS_SCREEN_ID,
    rowKey = SPORTS_ROW_EVENTS,
    itemKey = eventId,
    rowIndex = 1,
    itemIndex = index,
    targetType = "sports_event",
)

private fun sportsScopeLabel(scopeId: String): String = when (scopeId) {
    SPORTS_FILTER_ALL -> "all Sports"
    SPORTS_FILTER_TODAY -> "today's Sports"
    SPORTS_FILTER_RECENT -> "recent Sports"
    else -> SportBucket.entries.firstOrNull { it.name == scopeId }?.label ?: "Sports"
}

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024.0
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    val whole = (v * 100).toInt() / 100.0
    return "$whole ${units[i]}"
}

private data class LocalSportsIndexer(
    val type: String,
    val url: String,
    val apiKey: String,
)

private data class LocalSportsPandaConfig(
    val indexer: LocalSportsIndexer? = null,
    val downloadClient: String = "none",
    val torboxApiKey: String = "",
)

private suspend fun resolveLocalSportsConfig(
    secretStore: IntegrationSecretStore,
    prefsRepo: PreferencesRepository,
): LocalSportsPandaConfig {
    val downloadClient = prefsRepo.getString("panda_download_client") ?: "none"
    val pandaTorboxKey = secretStore
        .get(IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY, "torbox")
        ?.takeIf { it.isUsableSportsSecret() }
        .orEmpty()
    val debridTorboxKey = secretStore
        .get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX)
        ?.takeIf { it.isUsableSportsSecret() }
        .orEmpty()
    return LocalSportsPandaConfig(
        indexer = resolveLocalSportsIndexer(secretStore, prefsRepo),
        downloadClient = downloadClient,
        torboxApiKey = pandaTorboxKey.ifBlank { debridTorboxKey },
    )
}

private suspend fun resolveLocalSportsIndexer(
    secretStore: IntegrationSecretStore,
    prefsRepo: PreferencesRepository,
): LocalSportsIndexer? {
    val fromSecrets = secretStore
        .getSubKeys(IntegrationSecretKey.PANDA_INDEXER_API_KEY)
        .filter { it.contains("|") }
        .mapNotNull { subKey ->
            val apiKey = secretStore.get(IntegrationSecretKey.PANDA_INDEXER_API_KEY, subKey)
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val (type, url) = subKey.split("|", limit = 2)
            LocalSportsIndexer(
                type = type,
                url = UsenetIndexerUrlResolver.resolve(type, url),
                apiKey = apiKey,
            )
        }
        .firstOrNull()
    if (fromSecrets != null) return fromSecrets

    val type = prefsRepo.getString("panda_nzb_indexer")
        ?: prefsRepo.getString("nzb_indexer")
        ?: return null
    val apiKey = prefsRepo.getString("panda_nzb_indexer_api_key")
        ?: prefsRepo.getString("nzb_indexer_api_key")
        ?: return null
    val url = prefsRepo.getString("panda_nzb_indexer_url")
        ?: prefsRepo.getString("nzb_indexer_url")
        ?: ""
    return LocalSportsIndexer(
        type = type,
        url = UsenetIndexerUrlResolver.resolve(type, url),
        apiKey = apiKey,
    )
}

private fun String?.isUsableSportsSecret(): Boolean {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return false
    if (value.equals("[redacted]", ignoreCase = true)) return false
    if (value.equals("redacted", ignoreCase = true)) return false
    if (value.all { it == '*' || it.code == 8226 }) return false
    return true
}

@Serializable
private data class TvSportsCachePayload(
    val savedAtMs: Long,
    val query: String = "",
    val selectedSportBucket: String? = null,
    val items: List<NewznabItem> = emptyList(),
)
