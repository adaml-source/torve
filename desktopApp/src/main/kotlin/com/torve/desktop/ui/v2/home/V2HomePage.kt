package com.torve.desktop.ui.v2.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.V2Shelf
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.desktop.ui.v2.seeall.SeeAllRequest
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
    cardStyleFor: (HomeSection?) -> CardStyle = { CardStyle() },
) {
    val colors = TorveDesktopThemeTokens.colors

    // Stage 2A: respect mobile's section visibility choices when sectionConfigs
    // is supplied. When empty (or section missing), default to visible.
    val configBySection = remember(sectionConfigs) {
        sectionConfigs.associateBy { it.section }
    }
    fun isSectionVisible(section: HomeSection): Boolean =
        configBySection[section]?.enabled ?: true

    // Resolve CardStyle and width per section. Falls back to a non-null default
    // when no preset is configured (CardStyle() defaults render the legacy look).
    val defaultCardStyle = remember { CardStyle() }
    fun cardStyleForOrDefault(section: HomeSection?): CardStyle =
        cardStyleFor(section).let { if (it == defaultCardStyle) defaultCardStyle else it }
    fun widthFor(section: HomeSection?): androidx.compose.ui.unit.Dp {
        val style = cardStyleForOrDefault(section)
        // When the user hasn't customized, keep the historical 150dp shelf card
        // width to avoid disrupting the default home look.
        val resolved = style.size.resolvedWidthDp()
        return if (resolved == CardStyle().size.resolvedWidthDp()) 150.dp else resolved.dp
    }
    val heroItem = remember(homeState) {
        // Prefer items with full metadata (logo, overview) for the hero
        homeState.shelves.firstOrNull()?.items?.firstOrNull { it.logoUrl != null }
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

            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                // ── Hero area ──
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
                        listOfNotNull(homeState.hiddenGemsShelf) +
                        homeState.customShelves.map { (title, items) ->
                            com.torve.domain.model.CatalogShelf(id = "custom_$title", title = title, items = items)
                        }
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
                        val mappedSection = HomeSection.entries.firstOrNull { it.shelfId == shelf.id }
                        val visible = mappedSection?.let { isSectionVisible(it) } ?: true
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
                            ds("Recently Watched"),
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

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

internal data class WatchlistHeatBadge(
    val label: String,
    val tone: com.torve.desktop.ui.components.TorveBadgeTone,
)

private fun watchlistHeatBadge(releaseDate: String?): WatchlistHeatBadge? =
    classifyWatchlistHeat(releaseDate, java.time.LocalDate.now())

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
