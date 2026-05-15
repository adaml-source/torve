package com.torve.desktop.ui.v2.detail

import androidx.compose.animation.animateColorAsState
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torve.desktop.playback.DesktopPlayerUiState
import com.torve.desktop.search.DesktopSearchDetailUiState
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.V2Shelf
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.favoriteMediaKey
import com.torve.presentation.watchlist.WatchlistUiState
import java.awt.Desktop
import java.net.URI

// Brand colors for rating providers

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun V2DetailPage(
    detailState: DesktopSearchDetailUiState,
    watchlistState: WatchlistUiState,
    favoriteKeys: Set<String> = emptySet(),
    playerState: DesktopPlayerUiState,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onChooseSource: (MediaItem) -> Unit,
    onDownloadMovie: (MediaItem) -> Unit,
    onDownloadEpisode: (MediaItem, Int, Int) -> Unit,
    onDownloadSeason: (MediaItem, Int, Int) -> Unit,
    onDownloadAll: (MediaItem) -> Unit,
    canDownloadMovies: Boolean = true,
    canDownloadShows: Boolean = true,
    onToggleWatchlist: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit = {},
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onOpenRelated: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
    windowState: androidx.compose.ui.window.WindowState? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val item = detailState.detailItem
    val isInWatchlist = item != null && watchlistState.watchlistIds.contains(item.id)
    val isFavorite = item != null && favoriteKeys.contains(item.favoriteMediaKey())
    var showDownloadMenu by remember(item?.id, detailState.selectedSeasonNumber, detailState.selectedEpisodeNumber) {
        mutableStateOf(false)
    }

    var trailerKey by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (detailState.isLoadingDetail && item == null) {
            Box(Modifier.fillMaxSize().background(colors.shellBackground), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            return@Box
        }

        if (item == null) {
            Box(Modifier.fillMaxSize().background(colors.shellBackground), contentAlignment = Alignment.Center) {
                Text(ds("Select a title to view details"), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
            }
            return@Box
        }
        val movieLabel = ds("Movie")
        val tvShowLabel = ds("TV Show")
        val willPlayLabel = ds("Will play")
        val episodeMenuTemplate = ds("Episode S%1\$02dE%2\$02d")
        val seasonMenuTemplate = ds("Season %1\$d")
        val allEpisodesLabel = ds("All Episodes")
        val watchlistLabel = ds("Watchlist")
        val inWatchlistLabel = ds("In Watchlist")
        val favoritesLabel = ds("Favorites")
        val inFavoritesLabel = ds("In Favorites")
        val votesTemplate = ds("%1\$d votes")
        val trailerFallbackTitle = ds("Trailer")

        // ── Backdrop extends behind ENTIRE page ──
        val backdrop = rememberCachedBitmap(item.backdropUrl ?: item.posterUrl)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val vpH = maxHeight

            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopStart).zIndex(2f).padding(start = 72.dp, top = 18.dp)) {
                    TorveGhostButton(text = ds("Back"), onClick = onBack)
                }

                // Full-bleed backdrop pinned behind everything
                Box(Modifier.fillMaxWidth().height(vpH).background(colors.shellBackground)) {
                    backdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                // Long gradient that fades backdrop into shell - extends past hero into content area
                Box(
                    Modifier.fillMaxWidth().height(vpH).background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.25f to Color.Transparent,
                            0.50f to colors.shellBackground.copy(alpha = 0.45f),
                            0.72f to colors.shellBackground.copy(alpha = 0.82f),
                            1.0f to colors.shellBackground.copy(alpha = 0.95f),
                        ),
                    ),
                )

                // Scrollable content on top of the backdrop
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    // ── Hero content area: ~80% viewport ──
                    Box(Modifier.fillMaxWidth().height(vpH * 0.80f)) {
                        // Hero content anchored to bottom-left
                        Column(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.55f)
                                .padding(start = 72.dp, bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Type badge
                            Surface(color = colors.accent.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    when (item.type) { MediaType.MOVIE -> movieLabel; MediaType.SERIES -> tvShowLabel },
                                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold, color = colors.accent,
                                )
                            }
                            // Metadata line
                            val meta = listOfNotNull(
                                item.year?.toString(),
                                item.runtime?.let { "${it}m" },
                                item.genres.take(2).joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                            )
                            if (meta.isNotEmpty()) {
                                Text(meta.joinToString("  \u00B7  "), style = MaterialTheme.typography.bodySmall, color = colors.textPrimary.copy(alpha = 0.6f))
                            }
                            // Rating pills
                            DetailRatingPills(item)
                            // Logo / title
                            val logo = rememberCachedBitmap(item.logoUrl)
                            Box(Modifier.height(72.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                                if (logo != null) {
                                    Image(logo, item.title, Modifier.height(72.dp).fillMaxWidth(0.9f), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                                } else {
                                    Text(item.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            // Summary
                            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                                DetailSummary(overview = overview, colors = colors)
                            }
                            // Actions - for series, show the currently-selected episode label
                            // next to the Play button so users can see exactly what will play.
                            if (item.type == MediaType.SERIES) {
                                val selectedSeasonNum = detailState.selectedSeasonNumber
                                val selectedEpNum = detailState.selectedEpisodeNumber
                                val episodeLabel = detailState.selectedSeason?.episodes
                                    ?.firstOrNull { it.episodeNumber == selectedEpNum }
                                    ?.name
                                if (selectedSeasonNum != null && selectedEpNum != null) {
                                    Surface(
                                        color = colors.accentContainer,
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            text = "$willPlayLabel: S${selectedSeasonNum}E${selectedEpNum}" +
                                                if (!episodeLabel.isNullOrBlank()) " · $episodeLabel" else "",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.accent,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TorvePrimaryButton(text = ds("Play"), onClick = { onPlay(item) })
                                item.trailerKey?.let { key ->
                                    TorveGhostButton(text = ds("Trailer"), onClick = { trailerKey = key })
                                }
                                TorveSecondaryButton(text = ds("Sources"), onClick = { onChooseSource(item) })
                                if (item.type == MediaType.MOVIE) {
                                    TorveSecondaryButton(
                                        text = ds("Download"),
                                        onClick = { onDownloadMovie(item) },
                                        enabled = canDownloadMovies,
                                    )
                                } else {
                                    Box {
                                        TorveSecondaryButton(
                                            text = ds("Download"),
                                            onClick = { showDownloadMenu = true },
                                            enabled = canDownloadShows,
                                        )
                                        val selectedSeason = detailState.selectedSeason
                                        val selectedEpisode = selectedSeason?.episodes?.firstOrNull {
                                            it.episodeNumber == detailState.selectedEpisodeNumber
                                        }
                                        TorveDropdownScaffold(
                                            expanded = showDownloadMenu,
                                            onDismissRequest = { showDownloadMenu = false },
                                            items = buildList {
                                                if (selectedSeason != null && selectedEpisode != null) {
                                                    add(
                                                        episodeMenuTemplate.format(selectedSeason.seasonNumber, selectedEpisode.episodeNumber) to {
                                                            showDownloadMenu = false
                                                            onDownloadEpisode(item, selectedSeason.seasonNumber, selectedEpisode.episodeNumber)
                                                        },
                                                    )
                                                }
                                                if (selectedSeason != null && selectedSeason.episodeCount > 0) {
                                                    add(
                                                        seasonMenuTemplate.format(selectedSeason.seasonNumber) to {
                                                            showDownloadMenu = false
                                                            onDownloadSeason(item, selectedSeason.seasonNumber, selectedSeason.episodeCount)
                                                        },
                                                    )
                                                }
                                                add(
                                                    allEpisodesLabel to {
                                                        showDownloadMenu = false
                                                        onDownloadAll(item)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                                TorveGhostButton(text = if (isInWatchlist) inWatchlistLabel else watchlistLabel, onClick = { onToggleWatchlist(item) })
                                TorveGhostButton(text = if (isFavorite) inFavoritesLabel else favoritesLabel, onClick = { onToggleFavorite(item) })
                            }
                        }
                    }

                    // ── Content flows continuously below hero - same stage ──
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        // Tagline
                        item.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
                            Text(tagline, Modifier.padding(start = 72.dp, end = 16.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.textSecondary)
                        }

                        // Detail badges
                        Row(Modifier.padding(start = 72.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.status?.let { TorveBadge(it, tone = TorveBadgeTone.Neutral) }
                            item.releaseDate?.let { TorveBadge(it, tone = TorveBadgeTone.Neutral) }
                            item.voteCount?.let { TorveBadge(votesTemplate.format(it), tone = TorveBadgeTone.Neutral) }
                        }

                        // Director with photo
                        item.director?.takeIf { it.isNotBlank() }?.let { director ->
                            val directorInteractionSource = remember { MutableInteractionSource() }
                            val directorHovered by directorInteractionSource.collectIsHoveredAsState()
                            val directorBackground by animateColorAsState(
                                if (directorHovered && item.directorId != null) colors.fieldSurface.copy(alpha = 0.72f) else Color.Transparent,
                                label = "directorBackground",
                            )
                            val directorBorder by animateColorAsState(
                                if (directorHovered && item.directorId != null) colors.borderStrong.copy(alpha = 0.8f) else Color.Transparent,
                                label = "directorBorder",
                            )
                            Row(
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(directorBackground)
                                    .border(1.dp, directorBorder, RoundedCornerShape(12.dp))
                                    .let { mod ->
                                        if (item.directorId != null) {
                                            mod
                                                .hoverable(interactionSource = directorInteractionSource)
                                                .clickable(
                                                    interactionSource = directorInteractionSource,
                                                    indication = null,
                                                    onClick = { onOpenPerson(item.directorId!!) },
                                                )
                                        } else mod
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Director photo
                                val dirPhoto = rememberCachedBitmap(item.directorProfileUrl)
                                if (dirPhoto != null) {
                                    Image(
                                        bitmap = dirPhoto, contentDescription = director,
                                        modifier = Modifier.size(36.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        Modifier.size(36.dp).clip(CircleShape).background(colors.fieldSurface),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(director.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.textSecondary)
                                    }
                                }
                                Column {
                                    Text(ds("Directed by"), style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                                    Text(
                                        director, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                        color = if (item.directorId != null) colors.accent else colors.textPrimary,
                                    )
                                }
                            }
                        }

                        // Cast row
                        if (item.cast.isNotEmpty()) {
                            Text(ds("Cast"), Modifier.padding(start = 72.dp, end = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Row(
                                Modifier.padding(horizontal = 36.dp).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item.cast.take(15).forEach { castMember ->
                                    val castInteractionSource = remember { MutableInteractionSource() }
                                    val castHovered by castInteractionSource.collectIsHoveredAsState()
                                    val castBackground by animateColorAsState(
                                        if (castHovered) colors.fieldSurface.copy(alpha = 0.68f) else Color.Transparent,
                                        label = "castBackground",
                                    )
                                    val castBorder by animateColorAsState(
                                        if (castHovered) colors.borderStrong.copy(alpha = 0.72f) else Color.Transparent,
                                        label = "castBorder",
                                    )
                                    Column(
                                        Modifier
                                            .width(80.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(castBackground)
                                            .border(1.dp, castBorder, RoundedCornerShape(12.dp))
                                            .hoverable(interactionSource = castInteractionSource)
                                            .clickable(
                                                interactionSource = castInteractionSource,
                                                indication = null,
                                                onClick = { onOpenPerson(castMember.id) },
                                            )
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        val photo = rememberCachedBitmap(castMember.profileUrl)
                                        if (photo != null) {
                                            Image(photo, castMember.name, Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                        } else {
                                            Box(Modifier.size(64.dp).clip(CircleShape).background(colors.fieldSurface), contentAlignment = Alignment.Center) {
                                                Text(castMember.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textSecondary)
                                            }
                                        }
                                        Text(castMember.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                        castMember.character?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                                    }
                                }
                            }
                        }

                        // Seasons / Episodes
                        if (item.type == MediaType.SERIES && item.seasons.isNotEmpty()) {
                            Text(ds("Seasons"), Modifier.padding(start = 72.dp, end = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Row(Modifier.padding(horizontal = 36.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item.seasons.forEach { season ->
                                    TorveFilterChip(text = season.name ?: seasonMenuTemplate.format(season.seasonNumber), selected = detailState.selectedSeasonNumber == season.seasonNumber, onClick = { onSelectSeason(season.seasonNumber) })
                                }
                            }
                            if (detailState.isLoadingSeason) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                            }
                            detailState.selectedSeason?.episodes?.let { episodes ->
                                Column(Modifier.padding(start = 72.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    episodes.forEach { episode ->
                                        val isSelected = detailState.selectedEpisodeNumber == episode.episodeNumber
                                        Surface(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelectEpisode(episode) },
                                            color = if (isSelected) colors.accentContainer else colors.fieldSurface.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                rememberCachedBitmap(episode.stillUrl)?.let { Image(it, null, Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop) }
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("E${episode.episodeNumber} - ${episode.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    if (episode.overview.isNotBlank()) Text(episode.overview, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        episode.runtime?.let { TorveBadge("${it}m", tone = TorveBadgeTone.Neutral) }
                                                        if (episode.rating > 0.0) TorveBadge(String.format("%.1f", episode.rating), tone = TorveBadgeTone.Neutral)
                                                        episode.airDate?.let { TorveBadge(it, tone = TorveBadgeTone.Neutral) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Error
                        detailState.detailError?.let { Text(it, Modifier.padding(start = 72.dp, end = 16.dp), style = MaterialTheme.typography.bodySmall, color = colors.error) }

                        // Related
                        if (detailState.similarItems.isNotEmpty()) {
                            V2Shelf(title = ds("Related"), modifier = Modifier.padding(start = 72.dp, end = 16.dp)) {
                                detailState.similarItems.take(15).forEach { related ->
                                    V2PosterCard(related.title, related.posterUrl, Modifier.width(160.dp), related.year?.toString(), related.rating?.let { String.format("%.1f", it) }, ratings = related.ratings, backdropUrl = related.backdropUrl, overview = related.overview, onClick = { onOpenRelated(related) })
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        trailerKey?.let { key ->
            com.torve.desktop.ui.trailer.TrailerOverlay(
                youtubeKey = key,
                title = item.title.takeIf { it.isNotBlank() } ?: trailerFallbackTitle,
                onDismiss = { trailerKey = null },
                windowState = windowState,
            )
        }
    }
}

// ── Summary with expand/collapse ─────────────────────────────────

@Composable
private fun DetailSummary(
    overview: String,
    colors: com.torve.desktop.ui.theme.TorveDesktopColors,
) {
    var expanded by remember { mutableStateOf(false) }
    val maxLines = if (expanded) Int.MAX_VALUE else 4

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = overview,
            modifier = Modifier.animateContentSize(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (overview.length > 180) {
            Text(
                text = if (expanded) ds("Less") else ds("More"),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
            )
        }
    }
}

// ── Rating pills with provider icons ─────────────────────────────

@Composable
private fun DetailRatingPills(item: MediaItem) {
    val ratings = item.ratings
        ?: item.rating?.let { com.torve.domain.model.MediaRatings(tmdbScore = it.toFloat()) }
    // Reserved height so the row below (logo / title) doesn't shift
    // downward when ratings finish hydrating. Without this, the user
    // saw pills appear → title text "overwriting" them → final TMDB
    // pill appearing as ratings enriched async. Now: empty space
    // initially, then pills fade in without disturbing the layout
    // around them.
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        contentAlignment = androidx.compose.ui.Alignment.CenterStart,
    ) {
        if (ratings != null) {
            com.torve.desktop.ui.v2.components.DesktopRatingPills(
                ratings = ratings,
                showBackground = false,
                prefs = com.torve.desktop.ui.v2.components.LocalRatingDisplayPrefs.current.copy(maxRatingsOnCard = 8),
            )
        }
    }
}
