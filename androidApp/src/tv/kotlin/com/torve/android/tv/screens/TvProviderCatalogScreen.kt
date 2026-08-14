package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvHeroBackground
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.TvProviderBrandHeader
import com.torve.android.tv.components.TvRailsPresentationMode
import com.torve.android.tv.components.TvTitleArtworkOrText
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.ui.home.ALL_STREAMING_SERVICES
import com.torve.android.ui.home.StreamingService
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.model.StreamingProviderCandidate
import com.torve.domain.model.resolveStreamingProviderIds
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.seeall.SeeAllViewModel
import java.util.Calendar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@Composable
internal fun TvProviderCatalogScreen(
    providerId: Int,
    providerName: String,
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onSeeAll: ((railKey: String, title: String, mediaType: String) -> Unit)? = null,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val service = remember(providerId, providerName) {
        ALL_STREAMING_SERVICES.firstOrNull { it.tmdbProviderId == providerId }
            ?: StreamingService(providerName, Graphite, providerId)
    }
    val searchRequester = remember(providerId) { FocusRequester() }
    var rails by remember(providerId) { mutableStateOf(emptyList<TvContentRail>()) }
    var focusedItem by remember(providerId) { mutableStateOf<MediaItem?>(null) }
    var heroItem by remember(providerId) { mutableStateOf<MediaItem?>(null) }
    var heroArtworkLookupKey by remember(providerId) { mutableStateOf<String?>(null) }
    var loading by remember(providerId) { mutableStateOf(true) }
    var loadFailed by remember(providerId) { mutableStateOf(false) }
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    BackHandler(onBack = onBack)

    LaunchedEffect(providerId, providerName) {
        loading = true
        loadFailed = false
        val preferredRegion = prefsRepo.getString(SettingsViewModel.KEY_REGION_CODE)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == 2 }
            ?: "US"
        val mediaTypes = listOf("movie", "tv")
        val globalCandidates = coroutineScope {
            mediaTypes.map { mediaType ->
                async {
                    mediaType to runCatching {
                        metadataRepo.getWatchProviderCandidates(mediaType)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().toMap()
        }
        val matchedCandidates = mediaTypes.associateWith { mediaType ->
            val candidates = globalCandidates[mediaType].orEmpty()
            val resolvedIds = resolveStreamingProviderIds(
                requestedName = providerName,
                configuredProviderId = providerId,
                region = preferredRegion,
                availableProviders = candidates,
            ).toSet()
            candidates.filter { it.id in resolvedIds }
        }
        val publishedRegions = matchedCandidates.values
            .flatten()
            .flatMapTo(linkedSetOf()) { it.regions }
            .ifEmpty { linkedSetOf(preferredRegion) }
        val orderedRegions = publishedRegions.sortedWith(
            compareBy<String> { region ->
                when (region) {
                    preferredRegion -> 0
                    "US" -> 1
                    "GB" -> 2
                    "DE" -> 3
                    "FR" -> 4
                    "CA" -> 5
                    "AU" -> 6
                    else -> 10
                }
            }.thenBy { it },
        )

        suspend fun providerIdsFor(mediaType: String, region: String): String {
            val regionalIds = matchedCandidates[mediaType].orEmpty()
                .filter { it.regions.isEmpty() || region in it.regions }
                .map { it.id }
                .filter { it > 0 }
                .distinct()
            if (regionalIds.isNotEmpty()) return regionalIds.joinToString("|")

            val providerNames = runCatching {
                metadataRepo.getWatchProviderNames(mediaType, region)
            }.getOrDefault(emptyMap())
            return resolveStreamingProviderIds(
                requestedName = providerName,
                configuredProviderId = providerId,
                region = region,
                availableProviders = providerNames.map { (id, name) ->
                    StreamingProviderCandidate(id = id, name = name)
                },
            ).filter { it > 0 }.joinToString("|").ifBlank { providerId.toString() }
        }

        val accumulated = TvProviderCatalogBucket.entries.associateWith { mutableListOf<MediaItem>() }
        fun publishRails() {
            val updatedRails = buildTvProviderCatalogRails(
                providerId = providerId,
                providerName = providerName,
                region = "worldwide",
                itemsByBucket = accumulated,
            )
            rails = updatedRails
            val availableKeys = updatedRails.flatMap { it.items }
                .mapTo(hashSetOf()) { it.providerCatalogStableKey() }
            focusedItem = focusedItem
                ?.takeIf { it.providerCatalogStableKey() in availableKeys }
                ?: updatedRails.firstOrNull()?.items?.firstOrNull()
        }
        fun addItems(bucket: TvProviderCatalogBucket, items: List<MediaItem>) {
            accumulated.getValue(bucket).addAll(items)
        }

        val primaryRegion = orderedRegions.firstOrNull() ?: preferredRegion
        val plan = tvProviderCatalogQueryPlan(currentYear)
        val primaryResults = coroutineScope {
            plan.map { query ->
                async {
                    query.bucket to runCatching {
                        metadataRepo.discover(
                            type = query.mediaType,
                            page = 1,
                            sortBy = query.sortBy,
                            minRating = query.minRating,
                            year = query.startYear,
                            yearTo = query.endYear,
                            withWatchProviders = providerIdsFor(query.mediaType, primaryRegion),
                            watchRegion = primaryRegion,
                        )
                    }
                }
            }.awaitAll()
        }
        primaryResults.forEach { (bucket, result) ->
            addItems(bucket, result.getOrElse { PagedResult(emptyList(), 1, 1, 0) }.items)
        }
        publishRails()
        loadFailed = rails.isEmpty() && primaryResults.all { it.second.isFailure }
        loading = false

        orderedRegions.drop(1).chunked(TV_PROVIDER_REGION_BATCH_SIZE).forEach { regionBatch ->
            val regionalResults = coroutineScope {
                regionBatch.flatMap { region ->
                    mediaTypes.map { mediaType ->
                        async {
                            Triple(
                                region,
                                mediaType,
                                runCatching {
                                    metadataRepo.discover(
                                        type = mediaType,
                                        page = 1,
                                        sortBy = "popularity.desc",
                                        withWatchProviders = providerIdsFor(mediaType, region),
                                        watchRegion = region,
                                    )
                                },
                            )
                        }
                    }
                }.awaitAll()
            }
            regionalResults.forEach { (_, mediaType, result) ->
                val items = result.getOrNull()?.items.orEmpty()
                val popularBucket = if (mediaType == "movie") {
                    TvProviderCatalogBucket.POPULAR_MOVIES
                } else {
                    TvProviderCatalogBucket.POPULAR_SERIES
                }
                val recentBucket = if (mediaType == "movie") {
                    TvProviderCatalogBucket.RECENT_MOVIES
                } else {
                    TvProviderCatalogBucket.RECENT_SERIES
                }
                val topBucket = if (mediaType == "movie") {
                    TvProviderCatalogBucket.TOP_RATED_MOVIES
                } else {
                    TvProviderCatalogBucket.TOP_RATED_SERIES
                }
                addItems(popularBucket, items)
                addItems(recentBucket, items.filter { (it.year ?: 0) >= currentYear - 1 })
                addItems(topBucket, items.filter { (it.rating ?: 0.0) >= 7.0 })
            }
            publishRails()
        }
    }

    LaunchedEffect(focusedItem?.providerCatalogStableKey()) {
        val item = focusedItem
        heroItem = item
        val itemKey = item?.providerCatalogStableKey()
        val tmdbId = item?.tmdbId
        if (itemKey == null || tmdbId == null || !item.logoUrl.isNullOrBlank()) {
            heroArtworkLookupKey = null
            return@LaunchedEffect
        }
        heroArtworkLookupKey = itemKey
        try {
            delay(TV_PROVIDER_HERO_DETAIL_DELAY_MS)
            val type = if (item.type == MediaType.SERIES) "tv" else "movie"
            val detail = runCatching { metadataRepo.getDetail(type, tmdbId) }.getOrNull()
            if (focusedItem?.providerCatalogStableKey() == itemKey && detail != null) {
                heroItem = detail
            }
        } finally {
            if (heroArtworkLookupKey == itemKey) {
                heroArtworkLookupKey = null
            }
        }
    }

    val allItems = remember(rails) {
        rails.asSequence()
            .flatMap { (it.seeAllItems ?: it.items).asSequence() }
            .distinctBy { it.providerCatalogStableKey() }
            .toList()
    }
    val browseAll: () -> Unit = {
        if (allItems.isNotEmpty() && onSeeAll != null) {
            val key = "streaming_catalog_${providerId}_worldwide_all"
            SeeAllViewModel.pendingItems[key] = "Search & filter $providerName" to allItems
            onSeeAll(key, "Search & filter $providerName", "movie")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvHeroBackground(
            featuredItem = heroItem ?: focusedItem,
            extendedReadability = true,
        )
        TvMediaRails(
            rails = rails,
            railFocusRequester = railFocusRequester,
            onMediaClick = onMediaClick,
            onFirstContentRequester = onFirstContentRequester,
            onContentFocused = onContentFocused,
            screenId = "provider_catalog_${providerId}_worldwide",
            loading = loading,
            emptyMessage = when {
                loadFailed -> "Could not refresh $providerName right now."
                else -> "No $providerName titles are available right now."
            },
            onMediaFocused = { focusedItem = it },
            onSeeAll = onSeeAll?.let { callback ->
                { key, title ->
                    val row = rails.firstOrNull { it.key == key }
                    val mediaType = row?.seeAllItems.orEmpty().ifEmpty { row?.items.orEmpty() }
                        .let { items -> if (items.isNotEmpty() && items.all { it.type == MediaType.SERIES }) "tv" else "movie" }
                    callback(key, title, mediaType)
                }
            },
            heroOverlay = {
                TvProviderCatalogHero(
                    service = service,
                    providerName = providerName,
                    focusedItem = heroItem ?: focusedItem,
                    artworkLookupPending = heroArtworkLookupKey == focusedItem?.providerCatalogStableKey(),
                    searchRequester = searchRequester,
                    railFocusRequester = railFocusRequester,
                    onSearchFocused = onContentFocused,
                    onBrowseAll = browseAll,
                )
            },
            heroOverlayFocusRequester = searchRequester.takeIf { onSeeAll != null && allItems.isNotEmpty() },
            focusExclusive = true,
            browseLayout = TvBrowseLayout.POSTER_ONLY,
            registerFocusHandle = registerFocusHandle,
            sourceAwareRatings = true,
            showSeeAllCards = onSeeAll != null,
            presentationMode = TvRailsPresentationMode.CatalogHero,
        )
    }
}

@Composable
private fun TvProviderCatalogHero(
    service: StreamingService,
    providerName: String,
    focusedItem: MediaItem?,
    artworkLookupPending: Boolean,
    searchRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onSearchFocused: (FocusRequester) -> Unit,
    onBrowseAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TV_PAGE_CONTENT_GUTTER, top = 10.dp, end = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TvProviderBrandHeader(
                service = service,
                modifier = Modifier
                    .width(if (service.tmdbProviderId == NETFLIX_TMDB_PROVIDER_ID) 187.dp else 234.dp)
                    .height(if (service.tmdbProviderId == NETFLIX_TMDB_PROVIDER_ID) 50.dp else 62.dp),
            )
            TvProviderSearchButton(
                label = "Search & filter $providerName",
                modifier = Modifier
                    .focusRequester(searchRequester)
                    .focusProperties {
                        left = railFocusRequester
                        down = railFocusRequester
                    },
                onFocused = { onSearchFocused(searchRequester) },
                onClick = onBrowseAll,
            )
        }
        focusedItem?.let { item ->
            TvTitleArtworkOrText(
                item = item,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 29.sp),
                maxTextLines = 2,
                artworkLookupPending = artworkLookupPending,
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .height(56.dp),
            )
            val metadata = buildList {
                item.year?.let { add(it.toString()) }
                item.rating?.takeIf { it > 0.0 }?.let { add("★ %.1f".format(it)) }
                add(if (item.type == MediaType.MOVIE) "Movie" else "Series")
                item.genres.take(2).mapTo(this) { it.name }
            }.joinToString("  ·  ")
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodyMedium,
                color = Snow.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.70f),
            )
            item.overview?.trim()?.takeIf { it.isNotEmpty() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Snow.copy(alpha = 0.82f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.76f),
                )
            }
        } ?: Text(
            text = "$providerName movies and series",
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TvProviderSearchButton(
    label: String,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .height(36.dp)
            .widthIn(min = 200.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(shape)
            .background(if (focused) Graphite.copy(alpha = 0.96f) else Charcoal.copy(alpha = 0.72f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AmberLight else Snow.copy(alpha = 0.14f),
                shape = shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = if (focused) Amber else Snow)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Snow,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private fun MediaItem.providerCatalogStableKey(): String = "${type}:${tmdbId ?: id}"

private const val TV_PROVIDER_REGION_BATCH_SIZE = 4
private const val TV_PROVIDER_HERO_DETAIL_DELAY_MS = 180L
private const val NETFLIX_TMDB_PROVIDER_ID = 8
