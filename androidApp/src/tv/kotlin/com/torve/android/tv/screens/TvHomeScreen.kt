package com.torve.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvCardStyle
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.dedupeAcrossRails
import com.torve.android.tv.components.rememberTvFocusMemory
import com.torve.android.tv.toMediaItemOrNull
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.CustomSection
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.home.HomeUiState
import com.torve.presentation.home.HomeViewModel
import org.koin.compose.koinInject

private sealed interface TvHomeRenderItem {
    val order: Int
}

private data class BuiltInHomeItem(
    val config: HomeSectionConfig,
) : TvHomeRenderItem {
    override val order: Int = config.order
}

private data class CustomHomeItem(
    val section: CustomSection,
) : TvHomeRenderItem {
    override val order: Int = section.order
}

private data class AddonShelfHomeItem(
    val shelf: CatalogShelf,
    override val order: Int,
) : TvHomeRenderItem

@Composable
fun TvHomeScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
    browseLayout: TvBrowseLayout = TvBrowseLayout.INFO_PANEL,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
) {
    val homeViewModel: HomeViewModel = koinInject()
    val state by homeViewModel.state.collectAsState()
    val sectionConfigs by homeViewModel.sectionConfigs.collectAsState()
    val customSections by homeViewModel.customSections.collectAsState()
    val homeLayoutOrder by homeViewModel.homeLayoutOrder.collectAsState()
    val focusMemory = rememberTvFocusMemory()
    val emptyMessage = state.error ?: stringResource(R.string.tv_no_data)

    val rails = remember(
        state,
        sectionConfigs,
        customSections,
        homeLayoutOrder,
    ) {
        buildTvHomeRails(
            state = state,
            sectionConfigs = sectionConfigs,
            customSections = customSections,
            homeLayoutOrder = homeLayoutOrder,
        )
    }

    TvMediaRails(
        rails = rails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        screenId = "home",
        loading = state.isLoading,
        emptyMessage = emptyMessage,
        focusMemory = focusMemory,
        onMediaFocused = onMediaFocused,
        onSeeAll = onSeeAll,
        heroOverlay = heroOverlay,
        shouldAutoFocus = shouldAutoFocus,
        browseLayout = browseLayout,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
    )
}

private fun buildTvHomeRails(
    state: HomeUiState,
    sectionConfigs: List<HomeSectionConfig>,
    customSections: List<CustomSection>,
    homeLayoutOrder: List<String>,
): List<TvContentRail> {
    val orderIndex = homeLayoutOrder.withIndex().associate { it.value to it.index }
    val addonShelfVisibility = state.addonShelfVisibility

    fun itemKey(item: TvHomeRenderItem): String = when (item) {
        is BuiltInHomeItem -> "section:${item.config.section.name}"
        is CustomHomeItem -> "custom:${item.section.id}"
        is AddonShelfHomeItem -> "addon:${item.shelf.id}"
    }

    val renderItems = buildList<TvHomeRenderItem> {
        sectionConfigs.filter { it.enabled }.forEach { add(BuiltInHomeItem(it)) }
        customSections.filter { it.enabled }.forEach { add(CustomHomeItem(it)) }
        state.addonShelves.forEachIndexed { index, shelf ->
            if (addonShelfVisibility[shelf.id] != false) {
                add(
                    AddonShelfHomeItem(
                        shelf = shelf,
                        order = orderIndex["addon:${shelf.id}"] ?: (10_000 + 100 + index),
                    ),
                )
            }
        }
    }.sortedWith(
        compareBy<TvHomeRenderItem> { item ->
            orderIndex[itemKey(item)] ?: (10_000 + item.order)
        }.thenBy { it.order },
    )

    val rails = mutableListOf<TvContentRail>()
    renderItems.forEach { item ->
        when (item) {
            is BuiltInHomeItem -> {
                rails += buildBuiltInRails(item.config, state)
            }
            is CustomHomeItem -> {
                val items = state.customShelves[item.section.id].orEmpty().take(24)
                if (items.isNotEmpty()) {
                    rails += TvContentRail(
                        key = "custom:${item.section.id}",
                        title = item.section.title,
                        items = items,
                    )
                }
            }
            is AddonShelfHomeItem -> {
                val items = item.shelf.items.take(24)
                if (items.isNotEmpty()) {
                    rails += TvContentRail(
                        key = "addon:${item.shelf.id}",
                        title = item.shelf.title,
                        items = items,
                    )
                }
            }
        }
    }

    return rails.dedupeAcrossRails()
}

private fun buildBuiltInRails(
    config: HomeSectionConfig,
    state: HomeUiState,
): List<TvContentRail> {
    val title = config.customTitle ?: config.section.defaultTitle
    return when (config.section) {
        HomeSection.SEARCH_BAR,
        HomeSection.HERO,
        HomeSection.STREAMING_SERVICES,
        HomeSection.ACTORS,
        HomeSection.DIRECTORS,
        HomeSection.ADDON_SHELVES -> {
            emptyList()
        }

        HomeSection.CONTINUE_WATCHING -> {
            val items = state.continueWatching
                .mapNotNull { it.toMediaItemOrNull() }
                .filter { it.tmdbId != null }
                .take(20)
            if (items.isEmpty()) emptyList()
            else {
                listOf(
                    TvContentRail(
                        key = "continue_watching",
                        title = title,
                        items = items,
                        cardStyle = TvCardStyle.BACKDROP,
                        progressByMediaId = state.continueWatching
                            .filter { it.progressPercent > 0f }
                            .associate { it.mediaId to it.progressPercent },
                    ),
                )
            }
        }

        HomeSection.WATCHLIST -> {
            val items = state.watchlistItems.take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "watchlist", title = title, items = items))
        }

        HomeSection.WATCHLIST_MOVIES -> {
            val items = state.watchlistItems
                .filter { it.type == MediaType.MOVIE }
                .take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "watchlist_movies", title = title, items = items))
        }

        HomeSection.WATCHLIST_TV -> {
            val items = state.watchlistItems
                .filter { it.type == MediaType.SERIES }
                .take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "watchlist_tv", title = title, items = items))
        }

        HomeSection.TRENDING_MOVIES,
        HomeSection.TRENDING_TV,
        HomeSection.POPULAR_MOVIES,
        HomeSection.NOW_PLAYING,
        HomeSection.NEW_RELEASES,
        HomeSection.TOP_RATED -> {
            val shelf = state.shelves.firstOrNull { it.id == config.section.shelfId }
            val items = shelf?.items?.take(24).orEmpty()
            if (items.isEmpty()) emptyList()
            else {
                listOf(
                    TvContentRail(
                        key = config.section.shelfId ?: config.section.name.lowercase(),
                        title = title,
                        items = items,
                    ),
                )
            }
        }

        HomeSection.RECOMMENDED -> {
            val items = state.recommendedItems.map { it.item }.take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "recommended", title = title, items = items))
        }

        HomeSection.RECENTLY_WATCHED -> {
            val items = state.recentlyWatched.take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "recently_watched", title = title, items = items))
        }

        HomeSection.HIDDEN_GEMS -> {
            val shelf = state.hiddenGemsShelf
            val items = shelf?.items?.take(24).orEmpty()
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = shelf?.id ?: "hidden_gems", title = title, items = items))
        }

        HomeSection.BECAUSE_YOU_WATCHED -> {
            state.becauseYouWatched.mapNotNull { shelf ->
                shelf.items.take(24)
                    .takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        TvContentRail(
                            key = shelf.id,
                            title = shelf.title,
                            items = items,
                        )
                    }
            }
        }

        HomeSection.MDBLIST_SHELVES -> {
            state.mdbListShelves.mapNotNull { shelf ->
                shelf.items.take(24)
                    .takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        TvContentRail(
                            key = "mdblist:${shelf.id}",
                            title = shelf.title,
                            items = items,
                        )
                    }
            }
        }
    }
}
