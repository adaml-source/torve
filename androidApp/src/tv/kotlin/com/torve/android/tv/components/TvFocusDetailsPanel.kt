package com.torve.android.tv.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.torve.domain.model.RatingSource
import com.torve.domain.model.allowsTmdbRatingProvider
import com.torve.domain.model.deriveProvidersToRender
import com.torve.domain.model.hasAnyEnabledDisplayValue
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.domain.repository.MetadataRepository
import com.torve.android.ui.components.getRatingValue
import com.torve.presentation.settings.SettingsViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** App-lifetime enriched item cache — survives all recomposition/navigation.
 *  SnapshotStateMap so Compose observes reads and recomposes when entries are added. */
private val enrichedItemCache = androidx.compose.runtime.mutableStateMapOf<String, MediaItem>()

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
    val enrichScope = rememberCoroutineScope()

    LaunchedEffect(focusedItem?.logoUrl) {
        val resolved = focusedItem?.logoUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        lastResolvedLogoUrl = resolved
    }

    // Per-focus enrichment: calls enrichSingle which checks SQLite cache first (instant
    // for items enriched by HomeViewModel or rail-level enrichment). For cache misses,
    // does network calls. Results stored in static enrichedItemCache so returning to an
    // item is always instant.
    LaunchedEffect(
        focusedItem?.let { "${it.type}:${it.tmdbId ?: it.id}" },
        settingsState.ratingPrefs.enabledProviders,
    ) {
        val item = focusedItem ?: return@LaunchedEffect
        val itemKey = "${item.type}:${item.tmdbId ?: item.id}"
        val tmdbId = item.tmdbId ?: return@LaunchedEffect
        // Skip if already in static cache
        if (enrichedItemCache.containsKey(itemKey)) return@LaunchedEffect
        // Skip only when the item already has values for the user's selected
        // providers. A raw TMDB score should not block IMDb/RT enrichment.
        val displayRatings = item.ratings.withFallbackTmdbScore(item.rating)
        if (displayRatings?.hasAnyEnabledDisplayValue(settingsState.ratingPrefs) == true) {
            enrichedItemCache[itemKey] = item
            return@LaunchedEffect
        }

        enrichScope.launch {
            try {
                // Fetch TMDB detail for imdbId + cast + overview
                val mediaType = if (item.type == MediaType.MOVIE) "movie" else "tv"
                val detail = withContext(Dispatchers.IO) {
                    runCatching { metadataRepo.getDetail(mediaType, tmdbId) }.getOrNull()
                }
                val itemWithDetail = item.copy(
                    imdbId = detail?.imdbId ?: item.imdbId,
                    overview = item.overview ?: detail?.overview,
                    genres = item.genres.ifEmpty { detail?.genres ?: emptyList() },
                    year = item.year ?: detail?.year,
                    runtime = item.runtime ?: detail?.runtime,
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
            } catch (_: Exception) {
                // Cache raw item so we don't retry failed enrichments endlessly
                enrichedItemCache[itemKey] = item
            }
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
            targetState = focusedItem,
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
                        ratings = item.ratings ?: cached.ratings,
                        overview = item.overview ?: cached.overview,
                        genres = item.genres.ifEmpty { cached.genres },
                        year = item.year ?: cached.year,
                        runtime = item.runtime ?: cached.runtime,
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
                    cast = displayItem.cast.take(8),
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FocusPanelContent(
    item: MediaItem,
    progress: Float?,
    logoLookupInFlight: Boolean,
    logoLookupResolved: Boolean,
    retainedLogoUrl: String?,
    cast: List<CastMember>,
) {
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
        val ratings = item.ratings.withFallbackTmdbScore(item.rating)
        if (ratings != null && ratings.hasAnyEnabledDisplayValue(ratingPrefs)) {
            Spacer(Modifier.height(14.dp))
            RatingsRow(ratings, ratingPrefs)
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
        // Year
        item.year?.let { add(it.toString()) }

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
        r.metacriticScore != null
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
