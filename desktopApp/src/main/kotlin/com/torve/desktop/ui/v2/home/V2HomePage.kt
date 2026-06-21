package com.torve.desktop.ui.v2.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.VerticalScrollbar
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.V2Shelf
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.desktop.ui.v2.discovery.resolveBrandLogoUrl
import com.torve.desktop.ui.v2.movies.DESKTOP_WATCH_PROVIDERS
import com.torve.desktop.ui.v2.seeall.SeeAllRequest
import androidx.compose.foundation.rememberScrollbarAdapter
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.CardStyle
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.MediaItem
import com.torve.domain.model.resolvedWidthDp
import com.torve.presentation.home.HomeUiState
import kotlin.math.absoluteValue

@Composable
fun V2HomePage(
    homeState: HomeUiState,
    scrollState: ScrollState,
    onPlay: (MediaItem) -> Unit = {},
    onOpenDetail: (MediaItem) -> Unit = {},
    onSeeAll: (SeeAllRequest) -> Unit = {},
    sectionConfigs: List<HomeSectionConfig> = emptyList(),
    enabledServiceIds: Set<Int> = emptySet(),
    providerLogos: Map<Int, String> = emptyMap(),
    cardStyleFor: (HomeSection?) -> CardStyle = { CardStyle() },
    onOpenPerson: (Int) -> Unit = {},
    // Optional CTA hooks for the zero-source empty state. When the
    // user skipped onboarding without configuring any source, Home
    // has nothing to render — these callbacks let the empty-state
    // route to Panda setup or Settings → Integrations. Default to
    // no-ops; V2App is expected to wire them up. Added for the
    // onboarding simplification (Fix B in
    // docs/onboarding-simplification-plan.md).
    onSetUpSources: () -> Unit = {},
    onConnectTrakt: () -> Unit = {},
) {
    val colors = TorveDesktopThemeTokens.colors

    // Stage 2A: respect mobile's section visibility choices when sectionConfigs
    // is supplied. When empty (or section missing), default to visible.
    val configBySection = remember(sectionConfigs) {
        sectionConfigs.associateBy { it.section }
    }
    fun isSectionVisible(section: HomeSection): Boolean =
        configBySection[section]?.enabled ?: section.defaultEnabled
    @Composable
    fun titleFor(section: HomeSection, fallback: String = section.defaultTitle): String =
        configBySection[section]?.customTitle?.takeIf { it.isNotBlank() } ?: ds(fallback)

    // Resolve CardStyle and width per section. Falls back to a non-null default
    // when no preset is configured (CardStyle() defaults render the legacy look).
    val defaultCardStyle = remember { CardStyle() }
    fun cardStyleForOrDefault(section: HomeSection?): CardStyle =
        cardStyleFor(section)
            .let { if (it == defaultCardStyle) defaultCardStyle else it }
            .couchFirstHomeStyle()
    fun widthFor(section: HomeSection?): androidx.compose.ui.unit.Dp {
        val style = cardStyleForOrDefault(section)
        // When the user hasn't customized, keep the historical 150dp shelf card
        // width to avoid disrupting the default home look.
        val resolved = style.size.resolvedWidthDp()
        return if (resolved == CardStyle().size.resolvedWidthDp()) 150.dp else resolved.dp
    }
    val heroItem = remember(homeState) {
        // Prefer items with full metadata (logo, overview) for the hero
        homeState.heroItem
            ?: homeState.shelves.firstOrNull()?.items?.firstOrNull { it.logoUrl != null }
            ?: homeState.recommendedItems.firstOrNull()?.item
            ?: homeState.watchlistItems.firstOrNull()
            ?: homeState.shelves.firstOrNull()?.items?.firstOrNull()
            ?: homeState.recentlyWatched.firstOrNull()
            ?: homeState.continueWatching.firstOrNull()?.let { wp ->
                MediaItem(id = wp.mediaId, tmdbId = wp.mediaId.toIntOrNull(), type = wp.mediaType,
                    title = wp.showTitle ?: wp.title, posterUrl = wp.posterUrl, backdropUrl = wp.backdropUrl)
            }
    }

    val backdrop = rememberCachedBitmap(heroItem?.backdropUrl)
    val logo = rememberCachedBitmap(heroItem?.logoUrl)

    val variant = (heroItem?.backdropUrl?.hashCode() ?: 0).absoluteValue % 4
    val gradTop by animateColorAsState(when (variant) {
        0 -> Color(0xFF1A0E05); 1 -> Color(0xFF0A0F1E); 2 -> Color(0xFF140A1E); else -> Color(0xFF0A1614)
    }, label = "heroGradTop")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vpH = maxHeight

        Box(Modifier.fillMaxSize()) {
            // Full-bleed backdrop pinned behind entire page
            Box(Modifier.fillMaxWidth().height(vpH).background(Brush.verticalGradient(listOf(gradTop, colors.shellBackground)))) {
                backdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Box(
                Modifier.fillMaxWidth().height(vpH).background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.25f to Color.Transparent,
                        0.50f to colors.shellBackground.copy(alpha = 0.50f),
                        0.72f to colors.shellBackground.copy(alpha = 0.82f),
                        1.0f to colors.shellBackground.copy(alpha = 0.96f),
                    ),
                ),
            )

            Column(Modifier.fillMaxWidth().height(vpH).verticalScroll(scrollState)) {
                // ── Hero area ──
                if (isSectionVisible(HomeSection.HERO)) {
                Box(Modifier.fillMaxWidth().height(vpH * 0.78f)) {
                    if (heroItem != null) {
                        Column(
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.55f)
                                .padding(start = 72.dp, bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val isResume = homeState.continueWatching.any { it.mediaId == heroItem.id }
                            if (isResume) {
                                Surface(color = colors.accent.copy(alpha = 0.25f), shape = RoundedCornerShape(4.dp)) {
                                    Text(ds("Resume"), Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.accent)
                                }
                            }
                            val meta = listOfNotNull(heroItem.year?.toString(), heroItem.rating?.let { String.format("%.1f", it) }, heroItem.runtime?.let { "${it}m" })
                            if (meta.isNotEmpty()) Text(meta.joinToString("  \u00B7  "), style = MaterialTheme.typography.bodySmall, color = colors.textPrimary.copy(alpha = 0.6f))
                            Box(Modifier.height(80.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                                Crossfade(logo, label = "heroLogo") { art ->
                                    if (art != null) Image(bitmap = art, contentDescription = heroItem.title, modifier = Modifier.height(80.dp).fillMaxWidth(0.85f), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                                    else Text(heroItem.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.textPrimary)
                                }
                            }
                            heroItem.overview?.take(180)?.let { desc ->
                                if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TorvePrimaryButton(text = if (isResume) ds("Resume") else ds("Play"), onClick = { onPlay(heroItem) })
                                TorveGhostButton(text = ds("Details"), onClick = { onOpenDetail(heroItem) })
                            }
                        }
                    }
                }
                } else {
                    Spacer(Modifier.height(96.dp))
                }

                // ── Zero-source empty state ──
                // When the user skipped onboarding (no sources
                // configured) and the home data is genuinely empty,
                // surface CTAs instead of a silent blank stage. The
                // emptiness check uses heroItem because heroItem
                // resolves from any of: shelves, recommended,
                // watchlist, recentlyWatched, continueWatching — so
                // null heroItem means none of those are populated.
                val isHomeEmpty = heroItem == null &&
                    homeState.shelves.isEmpty() &&
                    homeState.watchlistItems.isEmpty() &&
                    homeState.upcomingSchedule.isEmpty() &&
                    homeState.continueWatching.isEmpty() &&
                    homeState.recommendedItems.isEmpty() &&
                    homeState.recentlyWatched.isEmpty()
                if (isHomeEmpty) {
                    HomeZeroSourceEmptyState(
                        onSetUpSources = onSetUpSources,
                        onConnectTrakt = onConnectTrakt,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 72.dp, vertical = 32.dp),
                    )
                }

                // ── Shelves - continuous stage ──
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (homeState.continueWatching.size > 1 && isSectionVisible(HomeSection.CONTINUE_WATCHING)) {
                        // Group by mediaId (show TMDb ID for series, unique per movie)
                        val grouped = homeState.continueWatching.drop(1)
                            .groupBy { it.mediaId }
                            .values
                            .map { episodes -> episodes.maxByOrNull { it.updatedAt } ?: episodes.first() }
                            .sortedByDescending { it.updatedAt }
                        val cwStyle = cardStyleForOrDefault(HomeSection.CONTINUE_WATCHING)
                        val cwWidth = widthFor(HomeSection.CONTINUE_WATCHING)
                        V2Shelf(
                            ds("Continue Watching"),
                            modifier = Modifier.padding(start = 72.dp),
                            onSeeAll = { onSeeAll(SeeAllRequest("continue_watching", "Continue Watching")) },
                        ) {
                            grouped.take(15).forEach { wp ->
                                V2PosterCard(wp.showTitle ?: wp.title, wp.posterUrl, Modifier.width(cwWidth),
                                    progress = wp.progressPercent.takeIf { it > 0f },
                                    ratings = homeState.continueWatchingRatings[wp.mediaId],
                                    cardStyle = cwStyle,
                                    onClick = { onOpenDetail(MediaItem(id = wp.mediaId, tmdbId = wp.mediaId.toIntOrNull(), type = wp.mediaType,
                                        title = wp.showTitle ?: wp.title, posterUrl = wp.posterUrl, backdropUrl = wp.backdropUrl)) })
                            }
                        }
                    }

                    if (homeState.watchlistItems.isNotEmpty() && isSectionVisible(HomeSection.WATCHLIST)) {
                        val wlStyle = cardStyleForOrDefault(HomeSection.WATCHLIST)
                        val wlWidth = widthFor(HomeSection.WATCHLIST)
                        V2Shelf(
                            ds("Watchlist"),
                            modifier = Modifier.padding(start = 72.dp),
                            onSeeAll = { onSeeAll(SeeAllRequest("watchlist", "Watchlist")) },
                        ) {
                            homeState.watchlistItems.take(20).forEach { item ->
                                val heatBadge = watchlistHeatBadge(item.releaseDate)
                                androidx.compose.foundation.layout.Box {
                                    V2PosterCard(item.title, item.posterUrl, Modifier.width(wlWidth),
                                        year = item.year?.toString(), rating = item.rating?.let { String.format("%.1f", it) },
                                        ratings = item.ratings, backdropUrl = item.backdropUrl, overview = item.overview,
                                        cardStyle = wlStyle,
                                        onClick = { onOpenDetail(item) })
                                    if (heatBadge != null) {
                                        androidx.compose.foundation.layout.Box(
                                            Modifier
                                                .align(androidx.compose.ui.Alignment.TopStart)
                                                .padding(6.dp),
                                        ) {
                                            com.torve.desktop.ui.components.TorveBadge(
                                                text = ds(heatBadge.label),
                                                tone = heatBadge.tone,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (homeState.upcomingSchedule.isNotEmpty() && isSectionVisible(HomeSection.UPCOMING_SCHEDULE)) {
                        val scheduleStyle = cardStyleForOrDefault(HomeSection.UPCOMING_SCHEDULE)
                        val scheduleWidth = widthFor(HomeSection.UPCOMING_SCHEDULE)
                        V2Shelf(
                            ds("Upcoming Schedule"),
                            modifier = Modifier.padding(start = 72.dp),
                            onSeeAll = {
                                onSeeAll(
                                    SeeAllRequest(
                                        "upcoming_schedule",
                                        "Upcoming Schedule",
                                        homeState.upcomingSchedule,
                                    ),
                                )
                            },
                        ) {
                            homeState.upcomingSchedule.take(20).forEach { item ->
                                V2PosterCard(
                                    item.title,
                                    item.posterUrl,
                                    Modifier.width(scheduleWidth),
                                    upcomingScheduleDateTime(item.releaseDate),
                                    item.rating?.let { String.format("%.1f", it) },
                                    ratings = item.ratings,
                                    backdropUrl = item.backdropUrl,
                                    overview = item.overview,
                                    cardStyle = scheduleStyle,
                                    onClick = { onOpenDetail(item) },
                                )
                            }
                        }
                    }

                    val curatedItems = homeState.becauseYouWatched.firstOrNull()?.items ?: homeState.recommendedItems.map { it.item }
                    val curatedSection = if (homeState.becauseYouWatched.isNotEmpty()) HomeSection.BECAUSE_YOU_WATCHED else HomeSection.RECOMMENDED
                    if (curatedItems.isNotEmpty() && isSectionVisible(curatedSection)) {
                        val curatedTitle = if (homeState.becauseYouWatched.isNotEmpty()) ds("Because You Watched") else ds("Recommended")
                        val curatedShelfKey = "shelf:recommended_${curatedTitle.hashCode()}"
                        val curatedStyle = cardStyleForOrDefault(curatedSection)
                        val curatedWidth = widthFor(curatedSection)
                        V2Shelf(
                            curatedTitle,
                            modifier = Modifier.padding(start = 72.dp),
                            onSeeAll = { onSeeAll(SeeAllRequest(curatedShelfKey, curatedTitle, curatedItems)) },
                        ) {
                            curatedItems.take(15).forEach { item ->
                                V2PosterCard(item.title, item.posterUrl, Modifier.width(curatedWidth),
                                    year = item.year?.toString(), rating = item.rating?.let { String.format("%.1f", it) },
                                    ratings = item.ratings, backdropUrl = item.backdropUrl, overview = item.overview,
                                    cardStyle = curatedStyle,
                                    onClick = { onOpenDetail(item) })
                            }
                        }
                    }

                    val allShelves = homeState.shelves +
                        homeState.addonShelves +
                        homeState.mdbListShelves +
                        listOfNotNull(homeState.hiddenGemsShelf)
                    // Skeleton placeholders while the home state is
                    // still loading and no shelves have arrived yet.
                    // Once any shelf shows up, real cards take over.
                    if (homeState.isLoading && allShelves.all { it.items.isEmpty() } &&
                        homeState.continueWatching.isEmpty() && homeState.recentlyWatched.isEmpty()
                    ) {
                        repeat(3) {
                            com.torve.desktop.ui.v2.components.V2ShelfSkeleton(
                                modifier = Modifier.padding(start = 72.dp),
                            )
                        }
                    }
                    allShelves.forEach { shelf ->
                        val mappedSection = sectionForHomeShelf(
                            shelf = shelf,
                            addonShelves = homeState.addonShelves,
                            mdbListShelves = homeState.mdbListShelves,
                        )
                        val visible = mappedSection?.let { isSectionVisible(it) } ?: false
                        if (shelf.items.isNotEmpty() && visible) {
                            val request = shelfToSeeAllRequest(shelf.id, shelf.title, shelf.items)
                            val shelfStyle = cardStyleForOrDefault(mappedSection)
                            val shelfWidth = widthFor(mappedSection)
                            V2Shelf(
                                shelf.title,
                                modifier = Modifier.padding(start = 72.dp),
                                onSeeAll = { onSeeAll(request) },
                            ) {
                                shelf.items.take(20).forEach { item ->
                                    V2PosterCard(item.title, item.posterUrl, Modifier.width(shelfWidth),
                                        year = item.year?.toString(), rating = item.rating?.let { String.format("%.1f", it) },
                                        ratings = item.ratings, backdropUrl = item.backdropUrl, overview = item.overview,
                                        cardStyle = shelfStyle,
                                        onClick = { onOpenDetail(item) })
                                }
                            }
                        }
                    }

                    if (homeState.recentlyWatched.isNotEmpty() && isSectionVisible(HomeSection.RECENTLY_WATCHED)) {
                        val rwStyle = cardStyleForOrDefault(HomeSection.RECENTLY_WATCHED)
                        val rwWidth = widthFor(HomeSection.RECENTLY_WATCHED)
                        V2Shelf(
                            titleFor(HomeSection.RECENTLY_WATCHED, "Recently Watched"),
                            modifier = Modifier.padding(start = 72.dp),
                            onSeeAll = { onSeeAll(SeeAllRequest("recently_watched", "Recently Watched")) },
                        ) {
                            homeState.recentlyWatched.take(15).forEach { item ->
                                V2PosterCard(item.title, item.posterUrl, Modifier.width(rwWidth),
                                    year = item.year?.toString(), rating = item.rating?.let { String.format("%.1f", it) },
                                    ratings = item.ratings, backdropUrl = item.backdropUrl, overview = item.overview,
                                    cardStyle = rwStyle,
                                    onClick = { onOpenDetail(item) })
                            }
                        }
                    }

                    if (enabledServiceIds.isNotEmpty() && isSectionVisible(HomeSection.STREAMING_SERVICES)) {
                        val services = DESKTOP_WATCH_PROVIDERS.filter { it.id in enabledServiceIds }
                        if (services.isNotEmpty()) {
                            val serviceWidth = widthFor(HomeSection.STREAMING_SERVICES).coerceAtLeast(132.dp)
                            V2Shelf(
                                titleFor(HomeSection.STREAMING_SERVICES, "Streaming Services"),
                                modifier = Modifier.padding(start = 72.dp),
                            ) {
                                services.forEach { provider ->
                                    StreamingServiceLogoTile(
                                        label = provider.label,
                                        logoUrl = providerLogos[provider.id],
                                        modifier = Modifier.width(serviceWidth),
                                    )
                                }
                            }
                        }
                    }

                    if (homeState.popularActors.isNotEmpty() && isSectionVisible(HomeSection.ACTORS)) {
                        val actorStyle = cardStyleForOrDefault(HomeSection.ACTORS)
                        val actorWidth = widthFor(HomeSection.ACTORS)
                        V2Shelf(
                            titleFor(HomeSection.ACTORS, "Popular Actors"),
                            modifier = Modifier.padding(start = 72.dp),
                        ) {
                            homeState.popularActors.take(20).forEach { person ->
                                V2PosterCard(
                                    person.name,
                                    person.profileUrl,
                                    Modifier.width(actorWidth),
                                    cardStyle = actorStyle,
                                    onClick = { onOpenPerson(person.id) },
                                )
                            }
                        }
                    }

                    if (homeState.popularDirectors.isNotEmpty() && isSectionVisible(HomeSection.DIRECTORS)) {
                        val directorStyle = cardStyleForOrDefault(HomeSection.DIRECTORS)
                        val directorWidth = widthFor(HomeSection.DIRECTORS)
                        V2Shelf(
                            titleFor(HomeSection.DIRECTORS, "Popular Directors"),
                            modifier = Modifier.padding(start = 72.dp),
                        ) {
                            homeState.popularDirectors.take(20).forEach { person ->
                                V2PosterCard(
                                    person.name,
                                    person.profileUrl,
                                    Modifier.width(directorWidth),
                                    cardStyle = directorStyle,
                                    onClick = { onOpenPerson(person.id) },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
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
}

private fun CardStyle.couchFirstHomeStyle(): CardStyle =
    copy(
        hover = hover.copy(
            scalePercent = hover.scalePercent.coerceAtMost(103),
            animationDurationMs = hover.animationDurationMs.coerceAtMost(180),
        ),
        appearance = appearance.copy(
            cornerRadiusDp = appearance.cornerRadiusDp.coerceAtLeast(14),
        ),
    )

@Composable
private fun StreamingServiceLogoTile(
    label: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val resolvedLogoUrl = remember(logoUrl, label) { resolveDesktopProviderLogoUrl(label, logoUrl) }
    val logo = rememberCachedBitmap(resolvedLogoUrl)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.58f),
            color = Color(0xFF07101C).copy(alpha = 0.88f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.07f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.12f),
                            ),
                        ),
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (logo != null) {
                    Image(
                        bitmap = logo,
                        contentDescription = "$label logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = label,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = label,
            color = colors.textPrimary.copy(alpha = 0.92f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun resolveDesktopProviderLogoUrl(label: String, logoUrl: String?): String? {
    val resolved = resolveBrandLogoUrl(
        absoluteLogoUrl = logoUrl,
        tmdbLogoPath = logoUrl,
        size = "w300",
    )
    if (resolved == null && !logoUrl.isNullOrBlank() && isDesktopDebugLoggingEnabled()) {
        println("[TorveDesktop] Provider logo source could not be resolved for $label")
    }
    return resolved
}

private fun isDesktopDebugLoggingEnabled(): Boolean =
    System.getProperty("torve.desktop.debug")?.toBooleanStrictOrNull() == true

internal data class WatchlistHeatBadge(
    val label: String,
    val tone: com.torve.desktop.ui.components.TorveBadgeTone,
)

private fun watchlistHeatBadge(releaseDate: String?): WatchlistHeatBadge? =
    classifyWatchlistHeat(releaseDate, java.time.LocalDate.now())

private fun upcomingScheduleDateTime(releaseDate: String?): String? {
    val raw = releaseDate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        java.time.ZonedDateTime.parse(raw)
            .withZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrElse {
        raw.take(16)
            .replace('T', ' ')
            .removeSuffix("Z")
            .takeIf { it.isNotBlank() }
    }
}

/**
 * Pure classifier for watchlist heat-map badging. Injects [today] so unit
 * tests don't depend on `LocalDate.now()`.
 *
 * Behaviour:
 *  - blank / null / unparseable date → null
 *  - date is today or up to 7 days ago → "Out now" (Success)
 *  - 8 to 30 days ago → "New" (Accent)
 *  - older than 30 days → null
 *  - 1 to 30 days in the future → "Soon" (Accent)
 *  - more than 30 days in the future → null
 */
internal fun classifyWatchlistHeat(
    releaseDate: String?,
    today: java.time.LocalDate,
): WatchlistHeatBadge? {
    if (releaseDate.isNullOrBlank()) return null
    val date = runCatching {
        java.time.LocalDate.parse(releaseDate.take(10))
    }.getOrNull() ?: return null
    val daysSince = java.time.temporal.ChronoUnit.DAYS.between(date, today)
    if (daysSince < 0) {
        return if (daysSince >= -30) WatchlistHeatBadge("Soon", com.torve.desktop.ui.components.TorveBadgeTone.Accent) else null
    }
    return when {
        daysSince <= 7 -> WatchlistHeatBadge("Out now", com.torve.desktop.ui.components.TorveBadgeTone.Success)
        daysSince <= 30 -> WatchlistHeatBadge("New", com.torve.desktop.ui.components.TorveBadgeTone.Accent)
        else -> null
    }
}

private fun shelfToSeeAllRequest(shelfId: String, title: String, items: List<MediaItem>): SeeAllRequest {
    val sectionId = when (shelfId) {
        "trending-movies" -> "TRENDING_MOVIES"
        "trending-tv" -> "TRENDING_TV"
        "popular-movies" -> "POPULAR_MOVIES"
        "popular-tv" -> "POPULAR_TV"
        "top-rated" -> "TOP_RATED_MOVIES"
        "now-playing" -> "NOW_PLAYING"
        "upcoming" -> "NEW_RELEASES"
        else -> "shelf:$shelfId"
    }
    return SeeAllRequest(sectionId, title, items)
}

private fun sectionForHomeShelf(
    shelf: CatalogShelf,
    addonShelves: List<CatalogShelf>,
    mdbListShelves: List<CatalogShelf>,
): HomeSection? =
    HomeSection.entries.firstOrNull { it.shelfId == shelf.id }
        ?: when {
            shelf.id == "hidden_gems" -> HomeSection.HIDDEN_GEMS
            addonShelves.any { it.id == shelf.id } -> HomeSection.ADDON_SHELVES
            mdbListShelves.any { it.id == shelf.id } -> HomeSection.MDBLIST_SHELVES
            else -> null
        }

/**
 * Empty state shown on Home when the user skipped source setup
 * during onboarding and there's nothing to render — no shelves, no
 * watchlist, no continue-watching, no recommendations, no hero. The
 * post-Fix-A onboarding allows zero-source admission, so we have to
 * surface a useful next-step here instead of a blank stage.
 *
 * Two CTAs:
 *   1. **Set up sources** — primary, routes to Panda setup (or
 *      Settings → Integrations if Panda is unavailable). Wired by
 *      the V2App caller via `onSetUpSources`.
 *   2. **Sync your watchlist with Trakt** — secondary, routes to
 *      Trakt OAuth in Settings → Integrations. Wired via
 *      `onConnectTrakt`.
 *
 * If the callbacks are no-ops (default) the buttons render but
 * clicking does nothing — that's a temporary state until V2App
 * passes real navigation hooks. The messaging itself still helps.
 */
@Composable
private fun HomeZeroSourceEmptyState(
    onSetUpSources: () -> Unit,
    onConnectTrakt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = modifier,
        color = colors.cardSurface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Welcome to Torve",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = "You're signed in, but no streaming source is connected yet. " +
                    "Built-in addons + Plex/Jellyfin auto-discovery will populate Home as soon as they find content. " +
                    "To enable debrid / Newznab / Usenet sources, set up Panda or configure them in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TorvePrimaryButton(
                    text = "Set up sources",
                    onClick = onSetUpSources,
                )
                TorveGhostButton(
                    text = "Sync with Trakt",
                    onClick = onConnectTrakt,
                )
            }
        }
    }
}
