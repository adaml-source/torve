package com.torve.android.tv.components

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.torve.android.R
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.android.ui.components.ratingSourceIconRes
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.CastMember
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingPillStyle
import com.torve.domain.model.RatingSource
import com.torve.domain.model.allowsTmdbRatingProvider
import com.torve.domain.model.deriveProvidersToRender
import com.torve.domain.model.hasAnyEnabledDisplayValue
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.hasRichExternalRating
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.domain.repository.MetadataRepository
import com.torve.android.ui.components.getRatingValue
import com.torve.presentation.settings.SettingsViewModel
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** App-lifetime enriched item cache — survives all recomposition/navigation.
 *  SnapshotStateMap so Compose observes reads and recomposes when entries are added. */
private val enrichedItemCache = androidx.compose.runtime.mutableStateMapOf<String, MediaItem>()

internal fun tvBrowsePreviewEnrichedItemKey(item: MediaItem): String =
    "${item.type}:${item.tmdbId ?: item.id}"

internal fun cacheTvBrowsePreviewEnrichedItem(item: MediaItem) {
    val key = tvBrowsePreviewEnrichedItemKey(item)
    val existing = enrichedItemCache[key]
    enrichedItemCache[key] = existing?.mergeWithRicherPreviewItem(item) ?: item
}

private fun MediaItem.mergeWithRicherPreviewItem(other: MediaItem): MediaItem =
    copy(
        tmdbId = tmdbId ?: other.tmdbId,
        imdbId = imdbId ?: other.imdbId,
        overview = overview ?: other.overview,
        genres = genres.ifEmpty { other.genres },
        year = year ?: other.year,
        runtime = runtime ?: other.runtime,
        ratings = ratings.preferRichRatings(other.ratings),
        logoUrl = logoUrl ?: other.logoUrl,
        posterUrl = posterUrl ?: other.posterUrl,
        backdropUrl = backdropUrl ?: other.backdropUrl,
        cast = if (cast.isNotEmpty()) cast else other.cast,
    )

private fun MediaRatings?.preferRichRatings(other: MediaRatings?): MediaRatings? =
    when {
        this.hasRichExternalRating() -> this
        other.hasRichExternalRating() -> other
        this != null -> this
        else -> other
    }

private fun MediaItem.upcomingScheduleDateTime(): String? {
    if (!id.startsWith("trakt-calendar:")) return null
    val raw = releaseDate?.trim()?.takeIf { it.isNotEmpty() }
        ?: id.split(":", limit = 5).getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return runCatching {
        java.time.ZonedDateTime.parse(raw)
            .withZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", java.util.Locale.US))
    }.getOrElse {
        raw.take(16).replace('T', ' ').removeSuffix("Z").takeIf { it.isNotBlank() }
    }
}

/**
 * Left-side contextual info panel for TV browsing.
 * Displays title, metadata, ratings, and synopsis for the currently focused item.
 * Updates via AnimatedContent when the focused item changes.
 */
@Composable
fun TvFocusDetailsPanel(
    focusedItem: MediaItem?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    logoLookupInFlight: Boolean = false,
    logoLookupResolved: Boolean = false,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val ratingsEnricher: com.torve.data.mdblist.RatingsEnricher = koinInject()
    val prefsRepo: com.torve.domain.repository.PreferencesRepository = koinInject()
    val secretStore: com.torve.domain.integrations.IntegrationSecretStore = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    var lastResolvedLogoUrl by remember { mutableStateOf<String?>(null) }
    val requestedItem = focusedItem
    val requestedItemKey = requestedItem?.let { "${it.type}:${it.tmdbId ?: it.id}" }
    var displayedFocusedItem by remember { mutableStateOf(focusedItem) }

    LaunchedEffect(requestedItemKey) {
        if (requestedItem == null) {
            displayedFocusedItem = null
            return@LaunchedEffect
        }
        delay(220L)
        displayedFocusedItem = requestedItem
    }

    LaunchedEffect(displayedFocusedItem?.logoUrl) {
        val resolved = displayedFocusedItem?.logoUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        lastResolvedLogoUrl = resolved
    }

    // Per-focus enrichment: calls enrichSingle which checks SQLite cache first (instant
    // for items enriched by HomeViewModel or rail-level enrichment). For cache misses,
    // does network calls. Results stored in static enrichedItemCache so returning to an
    // item is always instant.
    LaunchedEffect(
        displayedFocusedItem?.let { "${it.type}:${it.tmdbId ?: it.id}" },
        settingsState.ratingPrefs.enabledProviders,
    ) {
        val item = displayedFocusedItem ?: return@LaunchedEffect
        val itemKey = "${item.type}:${item.tmdbId ?: item.id}"
        val tmdbId = item.tmdbId ?: item.id.extractTmdbIdOrNull()
        val cachedItem = enrichedItemCache[itemKey]
        if (
            cachedItem != null &&
            !cachedItem.logoUrl.isNullOrBlank() &&
            (!cachedItem.posterUrl.isNullOrBlank() || !cachedItem.backdropUrl.isNullOrBlank())
        ) return@LaunchedEffect
        // Skip only when the item already has values for the user's selected
        // providers. A raw TMDB score should not block IMDb/RT enrichment.
        val displayRatings = item.ratings.withFallbackTmdbScore(item.rating)
        if (
            !item.overview.isNullOrBlank() &&
            displayRatings?.hasAnyEnabledDisplayValue(settingsState.ratingPrefs) == true
        ) {
            enrichedItemCache[itemKey] = item
            return@LaunchedEffect
        }

        delay(650L)
        try {
            val mediaType = if (item.type == MediaType.MOVIE) "movie" else "tv"
            val detail = if (tmdbId != null) withContext(Dispatchers.IO) {
                runCatching { metadataRepo.getDetail(mediaType, tmdbId) }.getOrNull()
            } else null
            val itemWithDetail = item.copy(
                tmdbId = tmdbId ?: detail?.tmdbId ?: item.tmdbId,
                imdbId = detail?.imdbId ?: item.imdbId,
                overview = item.overview ?: detail?.overview,
                genres = item.genres.ifEmpty { detail?.genres ?: emptyList() },
                year = item.year ?: detail?.year,
                runtime = item.runtime ?: detail?.runtime,
                logoUrl = item.logoUrl ?: detail?.logoUrl,
                posterUrl = item.posterUrl ?: detail?.posterUrl,
                backdropUrl = item.backdropUrl ?: detail?.backdropUrl,
                cast = if (item.cast.isNotEmpty()) item.cast else detail?.cast?.take(8) ?: emptyList(),
            )
            // Enrich ratings via SQLite cache or network
            val apiKey = withContext(Dispatchers.IO) {
                runCatching {
                    secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.MDBLIST_API_KEY)
                        ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                        ?: com.torve.data.mdblist.MdbListApi.DEFAULT_API_KEY
                }.getOrDefault(com.torve.data.mdblist.MdbListApi.DEFAULT_API_KEY)
            }
            val enriched = withContext(Dispatchers.IO) {
                runCatching { ratingsEnricher.enrichSingle(itemWithDetail, apiKey) }.getOrNull()
            }
            val result = enriched ?: itemWithDetail
            enrichedItemCache[itemKey] = result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Cache raw item so we don't retry failed enrichments endlessly
            enrichedItemCache[itemKey] = item
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Obsidian.copy(alpha = 0.95f),
                        Obsidian.copy(alpha = 0.85f),
                        Obsidian.copy(alpha = 0f),
                    ),
                ),
            ),
    ) {
        AnimatedContent(
            targetState = displayedFocusedItem,
            transitionSpec = {
                fadeIn(tween(50)) togetherWith fadeOut(tween(30))
            },
            contentKey = { it?.let { "${it.type}:${it.tmdbId ?: it.id}" } },
            label = "focusPanel",
        ) { item ->
            if (item != null) {
                // Read enriched data directly from the static cache on every render.
                // enrichVersion is read to trigger recomposition when new data arrives.
                val itemKey = "${item.type}:${item.tmdbId ?: item.id}"
                val cached = enrichedItemCache[itemKey]
                val base = if (cached != null) {
                    item.copy(
                        ratings = cached.ratings ?: item.ratings,
                        overview = item.overview ?: cached.overview,
                        genres = item.genres.ifEmpty { cached.genres },
                        year = item.year ?: cached.year,
                        runtime = item.runtime ?: cached.runtime,
                        logoUrl = item.logoUrl ?: cached.logoUrl,
                        posterUrl = item.posterUrl ?: cached.posterUrl,
                        backdropUrl = item.backdropUrl ?: cached.backdropUrl,
                        cast = if (item.cast.isNotEmpty()) item.cast else cached.cast,
                    )
                } else item
                val displayItem = base.let { b ->
                    val latestLogo = item.logoUrl?.takeIf { it.isNotBlank() } ?: b.logoUrl
                    if (latestLogo != b.logoUrl) b.copy(logoUrl = latestLogo) else b
                }
                FocusPanelContent(
                    item = displayItem,
                    progress = progress,
                    logoLookupInFlight = logoLookupInFlight,
                    logoLookupResolved = logoLookupResolved,
                    retainedLogoUrl = lastResolvedLogoUrl,
                    artworkUrls = emptyList(),
                    cast = displayItem.cast.take(8),
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun TvBrowsePreviewPanel(
    focusedItem: MediaItem?,
    modifier: Modifier = Modifier,
    focusablePanel: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val metadataRepo: MetadataRepository = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.015f else 1f,
        label = "browsePreviewScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) Amber.copy(alpha = 0.92f) else Steel.copy(alpha = 0.34f),
        label = "browsePreviewBorder",
    )
    val requestedSourceItem = focusedItem
    val requestedSourceKey = requestedSourceItem?.let { tvBrowsePreviewEnrichedItemKey(it) }
    var debouncedSourceItem by remember { mutableStateOf(focusedItem) }

    LaunchedEffect(requestedSourceKey) {
        if (requestedSourceItem == null) {
            debouncedSourceItem = null
            return@LaunchedEffect
        }
        delay(240L)
        debouncedSourceItem = requestedSourceItem
    }

    val sourceItem = debouncedSourceItem
    val sourceKey = sourceItem?.let { tvBrowsePreviewEnrichedItemKey(it) }
    val cachedItem = sourceKey?.let { enrichedItemCache[it] }
    val item = if (sourceItem != null && cachedItem != null) {
        sourceItem.copy(
            tmdbId = sourceItem.tmdbId ?: cachedItem.tmdbId,
            imdbId = sourceItem.imdbId ?: cachedItem.imdbId,
            overview = sourceItem.overview ?: cachedItem.overview,
            genres = sourceItem.genres.ifEmpty { cachedItem.genres },
            year = sourceItem.year ?: cachedItem.year,
            runtime = sourceItem.runtime ?: cachedItem.runtime,
            ratings = sourceItem.ratings.preferRichRatings(cachedItem.ratings),
            logoUrl = sourceItem.logoUrl ?: cachedItem.logoUrl,
            posterUrl = sourceItem.posterUrl ?: cachedItem.posterUrl,
            backdropUrl = sourceItem.backdropUrl ?: cachedItem.backdropUrl,
        )
    } else {
        sourceItem
    }

    LaunchedEffect(sourceKey) {
        val base = sourceItem ?: return@LaunchedEffect
        val key = sourceKey ?: return@LaunchedEffect
        if (!base.logoUrl.isNullOrBlank()) return@LaunchedEffect
        val tmdbId = base.tmdbId ?: base.id.extractTmdbIdOrNull() ?: return@LaunchedEffect
        val mediaType = if (base.type == MediaType.MOVIE) "movie" else "tv"
        delay(360L)
        val logoUrl = withContext(Dispatchers.IO) {
            runCatching { metadataRepo.getLogoUrl(mediaType, tmdbId) }.getOrNull()
        } ?: return@LaunchedEffect
        val current = enrichedItemCache[key] ?: base
        enrichedItemCache[key] = current.copy(logoUrl = current.logoUrl ?: logoUrl)
    }

    LaunchedEffect(sourceKey) {
        val base = sourceItem ?: return@LaunchedEffect
        val key = sourceKey ?: return@LaunchedEffect
        val cached = enrichedItemCache[key]
        if (
            cached != null &&
            !cached.logoUrl.isNullOrBlank() &&
            !cached.overview.isNullOrBlank() &&
            cached.ratings != null
        ) return@LaunchedEffect
        val tmdbId = base.tmdbId ?: base.id.extractTmdbIdOrNull() ?: return@LaunchedEffect
        val mediaType = if (base.type == MediaType.MOVIE) "movie" else "tv"
        delay(900L)
        val detail = withContext(Dispatchers.IO) {
            runCatching { metadataRepo.getDetail(mediaType, tmdbId) }.getOrNull()
        } ?: return@LaunchedEffect
        val merged = base.copy(
            tmdbId = base.tmdbId ?: detail.tmdbId,
            imdbId = base.imdbId ?: detail.imdbId,
            overview = base.overview ?: detail.overview,
            genres = base.genres.ifEmpty { detail.genres },
            year = base.year ?: detail.year,
            runtime = base.runtime ?: detail.runtime,
            ratings = base.ratings.preferRichRatings(detail.ratings),
            logoUrl = base.logoUrl ?: detail.logoUrl,
            posterUrl = base.posterUrl ?: detail.posterUrl,
            backdropUrl = base.backdropUrl ?: detail.backdropUrl,
        )
        if (base.ratings.hasRichExternalRating() && !detail.ratings.hasRichExternalRating()) {
            Log.d("TvSeeAllRatings", "see_all_preview_preserved_rich_ratings key=$key")
        } else if (!merged.ratings.hasRichExternalRating()) {
            Log.d("TvSeeAllRatings", "see_all_preview_tmdb_fallback_only key=$key")
        }
        cacheTvBrowsePreviewEnrichedItem(merged)
    }

    val imageUrl = item?.backdropUrl?.takeIf { it.isNotBlank() }
        ?: item?.posterUrl?.takeIf { it.isNotBlank() }
    val logoUrl = item?.logoUrl?.takeIf { it.isNotBlank() }
    val overviewScrollState = rememberScrollState()
    val overview = item?.overview?.takeIf { it.isNotBlank() }
    LaunchedEffect(sourceKey, overview) {
        overviewScrollState.scrollTo(0)
        if (overview.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            delay(2_400L)
            val max = overviewScrollState.maxValue
            if (max > 0) {
                overviewScrollState.animateScrollTo(
                    value = max,
                    animationSpec = tween(durationMillis = (max * 42).coerceIn(6_000, 18_000)),
                )
                delay(1_400L)
                overviewScrollState.scrollTo(0)
            }
        }
    }
    val metadata = remember(item) {
        if (item == null) {
            ""
        } else {
            buildList {
                item.year?.let { add(it.toString()) }
                item.runtime?.takeIf { it > 0 }?.let { minutes ->
                    val hours = minutes / 60
                    val mins = minutes % 60
                    add(if (hours > 0) "${hours}h ${mins}m" else "${mins}m")
                }
                item.genres.take(2).mapNotNull { genre ->
                    genre.name.takeIf { name -> name.isNotBlank() }
                }.takeIf { it.isNotEmpty() }
                    ?.let { add(it.joinToString(" / ")) }
                val rating = item.ratings?.imdbScore?.toDouble()
                    ?: item.ratings?.tmdbScore?.toDouble()
                    ?: item.rating
                rating?.takeIf { it > 0.0 }?.let { add("Rating %.1f".format(java.util.Locale.US, it)) }
            }.joinToString("  /  ")
        }
    }

    Box(
        modifier = modifier
            .onFocusChanged {
                focused = focusablePanel && it.isFocused
                if (focusablePanel && it.isFocused) onFocused()
            }
            .scale(scale)
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(Charcoal.copy(alpha = if (focused) 0.82f else 0.64f))
            .focusable(enabled = focusablePanel && item != null),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.62f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.18f),
                            Obsidian.copy(alpha = 0.58f),
                            Obsidian.copy(alpha = 0.94f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.22f),
                            Obsidian.copy(alpha = 0.58f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = item?.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp),
                )
            } else {
                Text(
                    text = item?.title ?: "Focus a title",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (metadata.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item?.let {
                PreviewRatingPills(
                    item = it,
                    ratingPrefs = settingsState.ratingPrefs.tvExternalCardRatingPrefs(),
                )
            }
            overview?.let {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 156.dp)
                        .verticalScroll(overviewScrollState),
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow.copy(alpha = 0.84f),
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRatingPills(
    item: MediaItem,
    ratingPrefs: RatingDisplayPrefs,
) {
    val ratings = item.ratings.withFallbackTmdbScore(item.rating)
    if (ratings == null) return

    Spacer(Modifier.height(12.dp))
    PreferredRatingPills(
        ratings = ratings,
        prefs = ratingPrefs.copy(
            pillStyle = RatingPillStyle.ICON,
            maxRatingsOnCard = ratingPrefs.maxRatingsOnCard.coerceAtLeast(3),
        ),
    )
}

@Composable
private fun FocusPanelContent(
    item: MediaItem,
    progress: Float?,
    logoLookupInFlight: Boolean,
    logoLookupResolved: Boolean,
    retainedLogoUrl: String?,
    artworkUrls: List<String>,
    cast: List<CastMember>,
) {
    val panelArtworkUrls = remember(artworkUrls, item.backdropUrl, item.posterUrl) {
        if (artworkUrls.isNotEmpty()) {
            (
                artworkUrls +
                    listOfNotNull(item.backdropUrl?.takeIf { it.isNotBlank() }) +
                    listOfNotNull(item.posterUrl?.takeIf { it.isNotBlank() })
                ).distinct()
        } else {
            listOfNotNull(
                item.backdropUrl?.takeIf { it.isNotBlank() }
                    ?: item.posterUrl?.takeIf { it.isNotBlank() },
            )
        }
    }
    val fallbackArtworkUrls = remember(item.backdropUrl, item.posterUrl) {
        listOfNotNull(
            item.backdropUrl?.takeIf { it.isNotBlank() },
            item.posterUrl?.takeIf { it.isNotBlank() },
        ).distinct()
    }
    val effectiveArtworkUrls = panelArtworkUrls.ifEmpty { fallbackArtworkUrls }
    var artworkIndex by remember(item.type, item.tmdbId, item.id, effectiveArtworkUrls) { mutableStateOf(0) }
    val activeArtworkUrl = effectiveArtworkUrls.getOrNull(artworkIndex.coerceIn(0, (effectiveArtworkUrls.size - 1).coerceAtLeast(0)))

    LaunchedEffect(effectiveArtworkUrls) {
        artworkIndex = 0
        if (effectiveArtworkUrls.size > 1) {
            while (true) {
                delay(3_800L)
                artworkIndex = (artworkIndex + 1) % effectiveArtworkUrls.size
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!activeArtworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = activeArtworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.72f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.82f),
                            Obsidian.copy(alpha = 0.58f),
                            Obsidian.copy(alpha = 0.30f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.04f),
                            Charcoal.copy(alpha = 0.20f),
                            Obsidian.copy(alpha = 0.70f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
        // 1. Clearlogo / title artwork — or fallback text title
        ClearlogoOrTitle(
            item = item,
            logoLookupInFlight = logoLookupInFlight,
            logoLookupResolved = logoLookupResolved,
            retainedLogoUrl = retainedLogoUrl,
        )

        Spacer(Modifier.height(10.dp))

        // 2. Metadata line — Kodi-style: year • genre • runtime/seasons • status
        MetadataBlock(item)

        // 3. Progress indicator (if applicable)
        if (progress != null && progress > 0f) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Steel),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Amber, RoundedCornerShape(2.dp)),
                    )
                }
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Silver,
                )
            }
        }

        // 4. Ratings row — respects user-configured providers and order
        val settingsViewModel: SettingsViewModel = koinInject()
        val settingsState by settingsViewModel.state.collectAsState()
        val ratingPrefs = settingsState.ratingPrefs
        val fallbackRating = item.rating
        val ratings = item.ratings.withFallbackTmdbScore(item.rating)
        if (ratings != null && ratings.hasAnyEnabledDisplayValue(ratingPrefs)) {
            Spacer(Modifier.height(14.dp))
            RatingsRow(ratings, ratingPrefs)
        } else if (fallbackRating != null && fallbackRating > 0.0) {
            Spacer(Modifier.height(14.dp))
            FallbackRatingRow(fallbackRating)
        } else if (ratings != null && hasAnyRating(ratings)) {
            Spacer(Modifier.height(14.dp))
            RatingsRow(ratings)
        }

        // 5. Synopsis — TV-readable size, expanded with poster removed
        if (!item.overview.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = item.overview!!,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                ),
                color = Snow.copy(alpha = 0.8f),
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (cast.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            CastStrip(cast = cast)
        }
    }
}
}

/**
 * Renders the TMDB clearlogo (transparent title artwork) when available,
 * falling back to a bold text title when no logo asset exists or if loading fails.
 * Fixed-height container prevents layout jumping between items.
 */
@Composable
private fun ClearlogoOrTitle(
    item: MediaItem,
    logoLookupInFlight: Boolean,
    logoLookupResolved: Boolean,
    retainedLogoUrl: String?,
) {
    // Fixed height so the panel does not jump between logo and text titles
    val logoHeight = 56.dp
    val currentLogoUrl = item.logoUrl?.takeIf { it.isNotBlank() }
    var currentLogoFailed by remember(currentLogoUrl) { mutableStateOf(false) }
    var retainedLogoFailed by remember(retainedLogoUrl) { mutableStateOf(false) }

    val displayLogoUrl = when {
        currentLogoUrl != null && !currentLogoFailed -> currentLogoUrl
        logoLookupInFlight && !retainedLogoUrl.isNullOrBlank() && !retainedLogoFailed -> retainedLogoUrl
        else -> null
    }

    if (displayLogoUrl != null) {
        AsyncImage(
            model = displayLogoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    if (displayLogoUrl == currentLogoUrl) {
                        currentLogoFailed = true
                    } else if (displayLogoUrl == retainedLogoUrl) {
                        retainedLogoFailed = true
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(logoHeight),
        )
        return
    }

    val showFallbackText = currentLogoFailed || retainedLogoFailed || logoLookupResolved || !logoLookupInFlight
    if (showFallbackText) {
        FallbackTextTitle(item.title, logoHeight)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(logoHeight),
        )
    }
}

@Composable
private fun FallbackTextTitle(title: String, minHeight: androidx.compose.ui.unit.Dp) {
    // Resolve the font style once outside AnimatedContent's transient scope so
    // Compose doesn't re-resolve the bundled font on every new target state.
    val resolvedStyle = remember {
        androidx.compose.ui.text.TextStyle(
            fontFamily = com.torve.android.ui.theme.JakartaSans,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            lineHeight = 28.sp,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(minHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style = resolvedStyle,
            color = Snow,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Rich metadata block: renders 1-2 lines of Kodi-style contextual info.
 * Line 1: year • genre(s) • runtime or season count
 * Line 2 (series only): status if available
 */
@Composable
private fun MetadataBlock(item: MediaItem) {
    val primaryParts = buildList {
        item.upcomingScheduleDateTime()?.let { add(it) }

        // Year
        if (!item.id.startsWith("trakt-calendar:")) {
            item.year?.let { add(it.toString()) }
        }

        // Genres (up to 2, concise)
        if (item.genres.isNotEmpty()) {
            add(item.genres.take(2).joinToString(" / ") { it.name })
        }

        // Runtime or season count
        when (item.type) {
            MediaType.MOVIE -> {
                item.runtime?.let { rt ->
                    val h = rt / 60
                    val m = rt % 60
                    add(if (h > 0) "${h}h ${m}m" else "${m}m")
                }
            }
            MediaType.SERIES -> {
                val seasonCount = item.seasons.size
                if (seasonCount > 0) {
                    add("$seasonCount Season${if (seasonCount != 1) "s" else ""}")
                }
            }
        }
    }

    if (primaryParts.isNotEmpty()) {
        Text(
            text = primaryParts.joinToString("  \u2022  "),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            color = Silver,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // Status line for series (Ended, Returning Series, etc.)
    if (item.type == MediaType.SERIES) {
        val statusText = item.status?.takeIf { it.isNotBlank() }
        if (statusText != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = Amber.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun CastStrip(cast: List<CastMember>) {
    val castRows = cast.take(6).chunked(3)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.labelLarge,
            color = Silver,
            fontWeight = FontWeight.SemiBold,
        )
        castRows.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { castMember ->
                    CastChip(castMember)
                }
            }
        }
    }
}

@Composable
private fun CastChip(castMember: CastMember) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val profileUrl = castMember.profileUrl
        if (!profileUrl.isNullOrBlank()) {
            AsyncImage(
                model = profileUrl,
                contentDescription = castMember.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Steel.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = castMember.name.take(1),
                    style = MaterialTheme.typography.labelMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = castMember.name,
            style = MaterialTheme.typography.labelSmall,
            color = Snow.copy(alpha = 0.88f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
        )
    }
}

private fun hasAnyRating(r: MediaRatings): Boolean {
    return r.imdbScore != null || r.tmdbScore != null ||
        r.rottenTomatoesScore != null || r.rtAudienceScore != null ||
        r.metacriticScore != null || r.letterboxdScore != null ||
        r.traktScore != null || r.mdblistScore != null || r.malScore != null
}

@Composable
private fun RatingsRow(
    ratings: MediaRatings,
    prefs: com.torve.domain.model.RatingDisplayPrefs = com.torve.domain.model.RatingDisplayPrefs(),
) {
    val enabledProviders = prefs.enabledProviders.filterNot {
        it == RatingSource.TORVE && !prefs.showTorveScoreOnCards
    }
    val providers = deriveProvidersToRender(
        enabledProviders = enabledProviders,
        providerOrder = prefs.providerOrder,
        maxRatingsOnCard = prefs.maxRatingsOnCard,
        fallbackToTmdbWhenNoneSelected = prefs.enabledProviders.isEmpty(),
    )
    val pills = providers.mapNotNull { source ->
        val value = getRatingValue(source, ratings, prefs)
        if (value != null) source to value else null
    }
    if (pills.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pills.forEach { (source, value) ->
            RatingBadge(
                iconRes = ratingSourceIconRes(source, ratings),
                value = value,
            )
        }
    }
}

@Composable
private fun RatingBadge(iconRes: Int?, value: String) {
    val scale = 1.2f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(Charcoal.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp * scale),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = MaterialTheme.typography.labelMedium.fontSize * scale,
            ),
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FallbackRatingRow(
    tmdbRating: Double,
    prefs: com.torve.domain.model.RatingDisplayPrefs = com.torve.domain.model.RatingDisplayPrefs(),
) {
    // TMDB is a fallback only when no providers are selected, or when selected explicitly.
    if (prefs.maxRatingsOnCard <= 0 || !prefs.allowsTmdbRatingProvider()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatingBadge(
            iconRes = R.drawable.tmbd_logo,
            value = "%.1f".format(tmdbRating),
        )
    }
}
