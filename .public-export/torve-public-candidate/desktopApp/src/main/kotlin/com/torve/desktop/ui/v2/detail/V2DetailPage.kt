package com.torve.desktop.ui.v2.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torve.desktop.playback.DesktopPlayerUiState
import com.torve.desktop.search.DesktopSearchDetailUiState
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.DesktopRatingPills
import com.torve.desktop.ui.v2.components.LocalRatingDisplayPrefs
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.V2Shelf
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import com.torve.domain.model.favoriteMediaKey
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.presentation.watchlist.containsMedia
import com.torve.presentation.watchlist.isMutatingMedia
import com.torve.presentation.watchlist.WatchlistUiState
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onPlayEpisode: (MediaItem, Int, Int) -> Unit,
    onChooseEpisodeSource: (MediaItem, Int, Int) -> Unit,
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
    val isInWatchlist = item != null && watchlistState.containsMedia(item.id)
    val isWatchlistUpdating = item != null && watchlistState.isMutatingMedia(item.id)
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
        val addWatchlistLabel = ds("Add to Watchlist")
        val removeWatchlistLabel = ds("Remove from Watchlist")
        val addFavoritesLabel = ds("Add to Favorites")
        val removeFavoritesLabel = ds("Remove from Favorites")
        val votesTemplate = ds("%1\$d votes")
        val trailerFallbackTitle = ds("Trailer")

        // ── Backdrop extends behind ENTIRE page ──
        val backdrop = rememberCachedBitmap(movieBackdropUrl(item))

        if (item.type == MediaType.MOVIE) {
            DesktopPremiumMovieDetailsPage(
                item = item,
                detailState = detailState,
                playerState = playerState,
                backdrop = backdrop,
                isInWatchlist = isInWatchlist,
                isWatchlistUpdating = isWatchlistUpdating,
                isFavorite = isFavorite,
                canDownloadMovies = canDownloadMovies,
                addWatchlistLabel = addWatchlistLabel,
                removeWatchlistLabel = removeWatchlistLabel,
                addFavoritesLabel = addFavoritesLabel,
                removeFavoritesLabel = removeFavoritesLabel,
                onBack = onBack,
                onPlay = onPlay,
                onChooseSource = onChooseSource,
                onDownloadMovie = onDownloadMovie,
                onToggleWatchlist = onToggleWatchlist,
                onToggleFavorite = onToggleFavorite,
                onOpenRelated = onOpenRelated,
                onOpenPerson = onOpenPerson,
                onTrailer = { trailerKey = it },
            )
        } else {
            if (item.type == MediaType.SERIES) {
                DesktopPremiumTvShowDetailsPage(
                    item = item,
                    detailState = detailState,
                    playerState = playerState,
                    backdrop = backdrop,
                    isInWatchlist = isInWatchlist,
                    isWatchlistUpdating = isWatchlistUpdating,
                    isFavorite = isFavorite,
                    canDownloadShows = canDownloadShows,
                    addWatchlistLabel = addWatchlistLabel,
                    removeWatchlistLabel = removeWatchlistLabel,
                    addFavoritesLabel = addFavoritesLabel,
                    removeFavoritesLabel = removeFavoritesLabel,
                    tvShowLabel = tvShowLabel,
                    willPlayLabel = willPlayLabel,
                    onBack = onBack,
                    onPlayEpisode = onPlayEpisode,
                    onChooseEpisodeSource = onChooseEpisodeSource,
                    onDownloadEpisode = onDownloadEpisode,
                    onToggleWatchlist = onToggleWatchlist,
                    onToggleFavorite = onToggleFavorite,
                    onSelectSeason = onSelectSeason,
                    onSelectEpisode = onSelectEpisode,
                    onOpenRelated = onOpenRelated,
                    onOpenPerson = onOpenPerson,
                    onTrailer = { trailerKey = it },
                )
            } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val vpH = maxHeight
            val scrollState = rememberScrollState()

            Box(Modifier.fillMaxSize()) {
                FloatingBackButton(
                    onBack = onBack,
                    label = ds("Back"),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(20f)
                        .padding(start = 72.dp, top = 18.dp),
                )

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
                    Modifier.fillMaxSize().verticalScroll(scrollState),
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
                                CinematicActionButton(text = detailPrimaryPlayLabel(item, detailState), primary = true, onClick = { onPlay(item) })
                                item.trailerKey?.let { key ->
                                    CinematicActionButton(text = ds("Trailer"), onClick = { trailerKey = key })
                                }
                                CinematicActionButton(text = ds("Sources"), onClick = { onChooseSource(item) })
                                if (item.type == MediaType.MOVIE) {
                                    CinematicActionButton(
                                        text = ds("Download"),
                                        onClick = { onDownloadMovie(item) },
                                        enabled = canDownloadMovies,
                                    )
                                } else {
                                    Box {
                                        CinematicActionButton(
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
                                CinematicActionButton(
                                    text = when {
                                        isWatchlistUpdating -> ds("Updating...")
                                        isInWatchlist -> removeWatchlistLabel
                                        else -> addWatchlistLabel
                                    },
                                    onClick = { onToggleWatchlist(item) },
                                    enabled = !isWatchlistUpdating,
                                )
                                CinematicActionButton(text = if (isFavorite) removeFavoritesLabel else addFavoritesLabel, onClick = { onToggleFavorite(item) })
                            }
                        }
                        CinematicDetailSidePanel(
                            item = item,
                            detailState = detailState,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 42.dp, bottom = 28.dp)
                                .width(360.dp),
                        )
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
                            CinematicSectionHeader(
                                title = "Episodes",
                                subtitle = "Choose a season, select an episode, then play, source, or download that exact episode.",
                                modifier = Modifier.padding(start = 72.dp, end = 420.dp),
                            )
                            Row(Modifier.padding(start = 72.dp, end = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item.seasons.forEach { season ->
                                    CinematicSeasonChip(
                                        text = season.name ?: seasonMenuTemplate.format(season.seasonNumber),
                                        selected = detailState.selectedSeasonNumber == season.seasonNumber,
                                        onClick = { onSelectSeason(season.seasonNumber) },
                                    )
                                }
                            }
                            if (detailState.isLoadingSeason) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                            }
                            detailState.selectedSeason?.let { selectedSeason ->
                                val episodes = selectedSeason.episodes
                                val episodeListState = rememberLazyListState()
                                LaunchedEffect(selectedSeason.seasonNumber, detailState.selectedEpisodeNumber, episodes.size) {
                                    val selectedIndex = episodes.indexOfFirst { it.episodeNumber == detailState.selectedEpisodeNumber }
                                    if (selectedIndex >= 0) {
                                        runCatching { episodeListState.animateScrollToItem(selectedIndex) }
                                    }
                                }

                                Box(
                                    Modifier
                                        .padding(start = 72.dp, end = 420.dp)
                                        .heightIn(min = 360.dp, max = 720.dp),
                                ) {
                                    if (episodes.isEmpty() && !detailState.isLoadingSeason) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "No episodes loaded for this season yet.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = colors.textSecondary,
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            state = episodeListState,
                                            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
                                            contentPadding = PaddingValues(bottom = 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            items(
                                                items = episodes,
                                                key = { episode -> "s${selectedSeason.seasonNumber}e${episode.episodeNumber}" },
                                            ) { episode ->
                                                val isSelected = detailState.selectedEpisodeNumber == episode.episodeNumber
                                                CinematicEpisodeCard(
                                                    item = item,
                                                    seasonNumber = selectedSeason.seasonNumber,
                                                    episode = episode,
                                                    selected = isSelected,
                                                    canDownload = canDownloadShows,
                                                    onSelect = { onSelectEpisode(episode) },
                                                    onPlay = {
                                                        onSelectEpisode(episode)
                                                        onPlayEpisode(item, selectedSeason.seasonNumber, episode.episodeNumber)
                                                    },
                                                    onSources = {
                                                        onSelectEpisode(episode)
                                                        onChooseEpisodeSource(item, selectedSeason.seasonNumber, episode.episodeNumber)
                                                    },
                                                    onDownload = {
                                                        onSelectEpisode(episode)
                                                        onDownloadEpisode(item, selectedSeason.seasonNumber, episode.episodeNumber)
                                                    },
                                                )
                                            }
                                        }
                                        VerticalScrollbar(
                                            adapter = rememberScrollbarAdapter(episodeListState),
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .fillMaxHeight(),
                                        )
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

internal data class MovieDetailInfoRowModel(
    val label: String,
    val value: String,
)

internal data class MovieRatingChipModel(
    val source: String,
    val value: String,
)

internal fun movieBackdropUrl(item: MediaItem): String? {
    if (item.isContentPlaceholder || item.isStubDetail) return null
    return item.backdropUrl ?: item.posterUrl
}

internal fun movieDetailsInfoRows(item: MediaItem): List<MovieDetailInfoRowModel> = buildList {
    item.releaseDate?.takeIf { it.isNotBlank() }?.let { add(MovieDetailInfoRowModel("Release Date", it)) }
    item.runtime?.takeIf { it > 0 }?.let { add(MovieDetailInfoRowModel("Runtime", "${it}m")) }
    item.director?.takeIf { it.isNotBlank() }?.let { add(MovieDetailInfoRowModel("Director", it)) }
    item.studios
        .map { it.name }
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.let { add(MovieDetailInfoRowModel("Studio", it.joinToString(", "))) }
    item.genres
        .map { it.name }
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.let { add(MovieDetailInfoRowModel("Genres", it.joinToString(", "))) }
}

internal fun movieRatingChips(item: MediaItem): List<MovieRatingChipModel> {
    val ratings = item.ratings.withFallbackTmdbScore(item.rating) ?: return emptyList()
    return buildList {
        ratings.imdbScore?.takeIf { it > 0f }?.let {
            add(MovieRatingChipModel("IMDb", "${"%.1f".format(it)}/10"))
        }
        ratings.tmdbScore?.takeIf { it > 0f }?.let {
            add(MovieRatingChipModel("TMDB", "${(it * 10f).roundToInt().coerceIn(0, 100)}%"))
        }
        ratings.traktScore?.takeIf { it > 0f }?.let {
            add(MovieRatingChipModel("Trakt", "${it.roundToInt().coerceIn(0, 100)}%"))
        }
        ratings.rottenTomatoesScore?.takeIf { it > 0 }?.let {
            add(MovieRatingChipModel("RT", "${it.coerceIn(0, 100)}%"))
        }
    }
}

internal fun shouldReserveDesktopDockSpace(playerState: DesktopPlayerUiState): Boolean =
    playerState.currentRequest != null ||
        playerState.preparedSession != null ||
        playerState.error != null ||
        playerState.preparingStream != null

internal data class TvEpisodeSelection(
    val season: Season,
    val episode: Episode,
)

internal fun selectedTvEpisodeContext(
    item: MediaItem,
    detailState: DesktopSearchDetailUiState,
): TvEpisodeSelection? {
    if (item.type != MediaType.SERIES) return null
    val seasons = tvSeasonsWithLoadedSelection(item, detailState)
    val season = seasons.firstOrNull { it.seasonNumber == detailState.selectedSeasonNumber }
        ?: seasons.firstOrNull { it.episodes.isNotEmpty() }
        ?: seasons.firstOrNull()
        ?: return null
    val episode = season.episodes.firstOrNull { it.episodeNumber == detailState.selectedEpisodeNumber }
        ?: season.episodes.firstOrNull()
        ?: return null
    return TvEpisodeSelection(season = season, episode = episode)
}

private fun tvSeasonsWithLoadedSelection(
    item: MediaItem,
    detailState: DesktopSearchDetailUiState,
): List<Season> {
    val loadedSeason = detailState.selectedSeason
    return if (loadedSeason == null) {
        item.seasons
    } else {
        listOf(loadedSeason) + item.seasons.filterNot { it.seasonNumber == loadedSeason.seasonNumber }
    }
}

internal fun tvEpisodeShortLabel(selection: TvEpisodeSelection): String =
    "S${selection.season.seasonNumber} E${selection.episode.episodeNumber}"

internal fun episodeHubInfoRows(
    item: MediaItem,
    selection: TvEpisodeSelection?,
): List<MovieDetailInfoRowModel> = buildList {
    selection?.let {
        add(MovieDetailInfoRowModel("Selected Episode", tvEpisodeShortLabel(it)))
        add(MovieDetailInfoRowModel("Season", seasonDisplayName(it.season)))
        add(MovieDetailInfoRowModel("Episode", it.episode.name.ifBlank { "Episode ${it.episode.episodeNumber}" }))
        it.episode.airDate?.takeIf { airDate -> airDate.isNotBlank() }?.let { airDate ->
            add(MovieDetailInfoRowModel("Air Date", airDate))
        }
        it.episode.runtime?.takeIf { runtime -> runtime > 0 }?.let { runtime ->
            add(MovieDetailInfoRowModel("Runtime", "${runtime}m"))
        }
        it.episode.rating.takeIf { rating -> rating > 0.0 }?.let { rating ->
            add(MovieDetailInfoRowModel("Rating", "%.1f/10".format(rating)))
        }
    }
    item.releaseDate?.takeIf { it.isNotBlank() }?.let { add(MovieDetailInfoRowModel("Release Date", it)) }
    item.studios.takeIf { it.isNotEmpty() }?.let { studios ->
        add(MovieDetailInfoRowModel("Network", studios.take(2).joinToString(", ") { it.name }))
    }
    item.genres.takeIf { it.isNotEmpty() }?.let { genres ->
        add(MovieDetailInfoRowModel("Genres", genres.take(4).joinToString(", ") { it.name }))
    }
}

private fun seasonDisplayName(season: Season): String =
    season.name?.takeIf { it.isNotBlank() }
        ?: if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}"

@Composable
private fun DesktopPremiumMovieDetailsPage(
    item: MediaItem,
    detailState: DesktopSearchDetailUiState,
    playerState: DesktopPlayerUiState,
    backdrop: androidx.compose.ui.graphics.ImageBitmap?,
    isInWatchlist: Boolean,
    isWatchlistUpdating: Boolean,
    isFavorite: Boolean,
    canDownloadMovies: Boolean,
    addWatchlistLabel: String,
    removeWatchlistLabel: String,
    addFavoritesLabel: String,
    removeFavoritesLabel: String,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onChooseSource: (MediaItem) -> Unit,
    onDownloadMovie: (MediaItem) -> Unit,
    onToggleWatchlist: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onOpenRelated: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
    onTrailer: (String) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val scrollState = rememberScrollState()
    val reserveDockSpace = shouldReserveDesktopDockSpace(playerState)

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.shellBackground)) {
        val showSideCard = maxWidth >= 1180.dp
        val sideCardWidth = when {
            maxWidth >= 1500.dp -> 420.dp
            maxWidth >= 1280.dp -> 390.dp
            else -> 360.dp
        }
        val leftInset = if (maxWidth >= 980.dp) 72.dp else 48.dp
        val contentEndInset = 56.dp
        val heroMinHeight = (maxHeight * 0.70f).coerceAtLeast(560.dp)
        val heroTextMaxWidth = if (showSideCard) {
            (maxWidth - leftInset - sideCardWidth - 180.dp).coerceIn(520.dp, 820.dp)
        } else {
            820.dp
        }
        val bottomSpace = if (reserveDockSpace) 152.dp else 72.dp

        Box(Modifier.fillMaxSize().background(colors.shellBackground)) {
            if (backdrop != null) {
                Image(
                    bitmap = backdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF030712).copy(alpha = 0.30f)))
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.78f)
                    .align(Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            0.00f to colors.shellBackground.copy(alpha = 0.95f),
                            0.34f to colors.shellBackground.copy(alpha = 0.78f),
                            0.72f to colors.shellBackground.copy(alpha = 0.26f),
                            1.00f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.38f to colors.shellBackground.copy(alpha = 0.74f),
                            1.00f to colors.shellBackground,
                        ),
                    ),
            )
            if (showSideCard) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(sideCardWidth + 180.dp)
                        .background(
                            Brush.horizontalGradient(
                                0.00f to Color.Transparent,
                                0.58f to colors.shellBackground.copy(alpha = 0.48f),
                                1.00f to colors.shellBackground.copy(alpha = 0.82f),
                            ),
                        ),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = bottomSpace),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = heroMinHeight)
                    .padding(start = leftInset, top = 116.dp, end = contentEndInset, bottom = 44.dp),
            ) {
                DesktopMovieHeroContentCluster(
                    item = item,
                    isInWatchlist = isInWatchlist,
                    isWatchlistUpdating = isWatchlistUpdating,
                    isFavorite = isFavorite,
                    canDownloadMovies = canDownloadMovies,
                    addWatchlistLabel = addWatchlistLabel,
                    removeWatchlistLabel = removeWatchlistLabel,
                    addFavoritesLabel = addFavoritesLabel,
                    removeFavoritesLabel = removeFavoritesLabel,
                    onPlay = onPlay,
                    onChooseSource = onChooseSource,
                    onDownloadMovie = onDownloadMovie,
                    onToggleWatchlist = onToggleWatchlist,
                    onToggleFavorite = onToggleFavorite,
                    onOpenPerson = onOpenPerson,
                    onTrailer = onTrailer,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .widthIn(max = heroTextMaxWidth),
                )
                if (showSideCard) {
                    MovieDetailsSideCard(
                        item = item,
                        relatedItems = detailState.similarItems,
                        onOpenRelated = onOpenRelated,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .width(sideCardWidth)
                    )
                }
            }

            if (!showSideCard) {
                MovieDetailsSideCard(
                    item = item,
                    relatedItems = detailState.similarItems,
                    onOpenRelated = onOpenRelated,
                    modifier = Modifier
                        .padding(start = leftInset, end = 56.dp, bottom = 28.dp)
                        .fillMaxWidth(),
                )
            }

            item.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
                Text(
                    text = tagline,
                    modifier = Modifier.padding(start = leftInset, end = contentEndInset, bottom = 18.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.cast.isNotEmpty()) {
                CinematicCastRail(
                    cast = item.cast,
                    startPadding = leftInset,
                    endPadding = contentEndInset,
                    onOpenPerson = onOpenPerson,
                )
                Spacer(Modifier.height(28.dp))
            }

            if (detailState.similarItems.isNotEmpty()) {
                CinematicRelatedRail(
                    items = detailState.similarItems,
                    startPadding = leftInset,
                    endPadding = contentEndInset,
                    onOpenRelated = onOpenRelated,
                )
            }

            detailState.detailError?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier.padding(start = leftInset, end = contentEndInset, top = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
        }

        FloatingBackButton(
            onBack = onBack,
            label = ds("Back"),
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(20f)
                .padding(start = leftInset, top = 26.dp),
        )

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopPremiumTvShowDetailsPage(
    item: MediaItem,
    detailState: DesktopSearchDetailUiState,
    playerState: DesktopPlayerUiState,
    backdrop: androidx.compose.ui.graphics.ImageBitmap?,
    isInWatchlist: Boolean,
    isWatchlistUpdating: Boolean,
    isFavorite: Boolean,
    canDownloadShows: Boolean,
    addWatchlistLabel: String,
    removeWatchlistLabel: String,
    addFavoritesLabel: String,
    removeFavoritesLabel: String,
    tvShowLabel: String,
    willPlayLabel: String,
    onBack: () -> Unit,
    onPlayEpisode: (MediaItem, Int, Int) -> Unit,
    onChooseEpisodeSource: (MediaItem, Int, Int) -> Unit,
    onDownloadEpisode: (MediaItem, Int, Int) -> Unit,
    onToggleWatchlist: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onOpenRelated: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
    onTrailer: (String) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val listState = rememberLazyListState()
    val selection = selectedTvEpisodeContext(item, detailState)
    val seasons = remember(item.seasons, detailState.selectedSeason) {
        tvSeasonsWithLoadedSelection(item, detailState)
            .distinctBy { it.seasonNumber }
            .sortedBy { it.seasonNumber }
    }
    val selectedSeason = selection?.season
        ?: detailState.selectedSeason
        ?: seasons.firstOrNull { it.seasonNumber == detailState.selectedSeasonNumber }
        ?: seasons.firstOrNull()
    val episodes = selectedSeason?.episodes.orEmpty()
    val reserveDockSpace = shouldReserveDesktopDockSpace(playerState)

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.shellBackground)) {
        val showEpisodeHub = maxWidth >= 1180.dp
        val hubWidth = when {
            maxWidth >= 1500.dp -> 430.dp
            maxWidth >= 1280.dp -> 400.dp
            else -> 368.dp
        }
        val leftInset = if (maxWidth >= 980.dp) 72.dp else 48.dp
        val contentEndInset = 56.dp
        val heroMinHeight = (maxHeight * 0.66f).coerceAtLeast(540.dp)
        val heroTextMaxWidth = if (showEpisodeHub) {
            (maxWidth - leftInset - hubWidth - 184.dp).coerceIn(520.dp, 840.dp)
        } else {
            840.dp
        }
        val bottomSpace = if (reserveDockSpace) 164.dp else 84.dp

        Box(Modifier.fillMaxSize().background(colors.shellBackground)) {
            if (backdrop != null) {
                Image(
                    bitmap = backdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF020711).copy(alpha = 0.34f)))
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
                    .align(Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            0.00f to colors.shellBackground.copy(alpha = 0.97f),
                            0.30f to colors.shellBackground.copy(alpha = 0.82f),
                            0.66f to colors.shellBackground.copy(alpha = 0.35f),
                            1.00f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.68f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.34f to colors.shellBackground.copy(alpha = 0.78f),
                            1.00f to colors.shellBackground,
                        ),
                    ),
            )
            if (showEpisodeHub) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(hubWidth + 184.dp)
                        .background(
                            Brush.horizontalGradient(
                                0.00f to Color.Transparent,
                                0.52f to colors.shellBackground.copy(alpha = 0.52f),
                                1.00f to colors.shellBackground.copy(alpha = 0.86f),
                            ),
                        ),
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomSpace),
        ) {
            item("hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = heroMinHeight)
                        .padding(start = leftInset, top = 116.dp, end = contentEndInset, bottom = 42.dp),
                ) {
                    DesktopTvHeroContentCluster(
                        item = item,
                        selection = selection,
                        isInWatchlist = isInWatchlist,
                        isWatchlistUpdating = isWatchlistUpdating,
                        isFavorite = isFavorite,
                        canDownloadShows = canDownloadShows,
                        addWatchlistLabel = addWatchlistLabel,
                        removeWatchlistLabel = removeWatchlistLabel,
                        addFavoritesLabel = addFavoritesLabel,
                        removeFavoritesLabel = removeFavoritesLabel,
                        tvShowLabel = tvShowLabel,
                        willPlayLabel = willPlayLabel,
                        onPlayEpisode = onPlayEpisode,
                        onChooseEpisodeSource = onChooseEpisodeSource,
                        onDownloadEpisode = onDownloadEpisode,
                        onToggleWatchlist = onToggleWatchlist,
                        onToggleFavorite = onToggleFavorite,
                        onOpenPerson = onOpenPerson,
                        onTrailer = onTrailer,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .widthIn(max = heroTextMaxWidth),
                    )
                    if (showEpisodeHub) {
                        EpisodeHubSideCard(
                            item = item,
                            selection = selection,
                            canDownloadShows = canDownloadShows,
                            onPlayEpisode = onPlayEpisode,
                            onChooseEpisodeSource = onChooseEpisodeSource,
                            onDownloadEpisode = onDownloadEpisode,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .width(hubWidth)
                        )
                    }
                }
            }

            if (!showEpisodeHub) {
                item("episode-hub-inline") {
                    EpisodeHubSideCard(
                        item = item,
                        selection = selection,
                        canDownloadShows = canDownloadShows,
                        onPlayEpisode = onPlayEpisode,
                        onChooseEpisodeSource = onChooseEpisodeSource,
                        onDownloadEpisode = onDownloadEpisode,
                        modifier = Modifier
                            .padding(start = leftInset, end = 56.dp, bottom = 28.dp)
                            .fillMaxWidth(),
                    )
                }
            }

            item.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
                item("tagline") {
                    Text(
                        text = tagline,
                        modifier = Modifier.padding(start = leftInset, end = contentEndInset, bottom = 18.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (item.cast.isNotEmpty()) {
                item("cast") {
                    CinematicCastRail(
                        cast = item.cast,
                        startPadding = leftInset,
                        endPadding = contentEndInset,
                        onOpenPerson = onOpenPerson,
                    )
                    Spacer(Modifier.height(30.dp))
                }
            }

            item("episodes-header") {
                CinematicSectionHeader(
                    title = "Episodes",
                    subtitle = "Choose a season, select an episode, then play, source, or download that exact episode.",
                    modifier = Modifier.padding(start = leftInset, end = contentEndInset, bottom = 14.dp),
                )
            }

            if (seasons.isNotEmpty()) {
                item("season-selector") {
                    PremiumSeasonSelector(
                        seasons = seasons,
                        selectedSeasonNumber = selectedSeason?.seasonNumber ?: detailState.selectedSeasonNumber,
                        startPadding = leftInset,
                        endPadding = contentEndInset,
                        onSelectSeason = onSelectSeason,
                    )
                    Spacer(Modifier.height(18.dp))
                }
            }

            when {
                detailState.isLoadingSeason && episodes.isEmpty() -> {
                    item("episodes-loading") {
                        Row(
                            modifier = Modifier.padding(start = leftInset, end = contentEndInset, top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = colors.accent, strokeWidth = 2.dp)
                            Text("Loading episodes", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                selectedSeason != null && episodes.isEmpty() -> {
                    item("episodes-empty") {
                        Text(
                            "No episodes are available for ${seasonDisplayName(selectedSeason)} yet.",
                            modifier = Modifier.padding(start = leftInset, end = contentEndInset, top = 12.dp),
                            color = Color.White.copy(alpha = 0.66f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                selectedSeason != null -> {
                    items(
                        items = episodes,
                        key = { episode -> "${selectedSeason.seasonNumber}-${episode.episodeNumber}" },
                    ) { episode ->
                        PremiumEpisodeCard(
                            item = item,
                            seasonNumber = selectedSeason.seasonNumber,
                            episode = episode,
                            selected = selection?.season?.seasonNumber == selectedSeason.seasonNumber &&
                                selection.episode.episodeNumber == episode.episodeNumber,
                            canDownload = canDownloadShows,
                            onSelect = { onSelectEpisode(episode) },
                            onPlay = {
                                onSelectEpisode(episode)
                                onPlayEpisode(item, selectedSeason.seasonNumber, episode.episodeNumber)
                            },
                            onSources = {
                                onSelectEpisode(episode)
                                onChooseEpisodeSource(item, selectedSeason.seasonNumber, episode.episodeNumber)
                            },
                            onDownload = {
                                onSelectEpisode(episode)
                                onDownloadEpisode(item, selectedSeason.seasonNumber, episode.episodeNumber)
                            },
                            modifier = Modifier.padding(start = leftInset, end = contentEndInset, bottom = 12.dp),
                        )
                    }
                }
            }

            if (detailState.similarItems.isNotEmpty()) {
                item("related") {
                    Spacer(Modifier.height(22.dp))
                    CinematicRelatedRail(
                        items = detailState.similarItems,
                        startPadding = leftInset,
                        endPadding = contentEndInset,
                        onOpenRelated = onOpenRelated,
                    )
                }
            }

            detailState.detailError?.let { error ->
                item("detail-error") {
                    Text(
                        text = error,
                        modifier = Modifier.padding(start = leftInset, end = contentEndInset, top = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }
        }

        FloatingBackButton(
            onBack = onBack,
            label = ds("Back"),
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(20f)
                .padding(start = leftInset, top = 26.dp),
        )

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopTvHeroContentCluster(
    item: MediaItem,
    selection: TvEpisodeSelection?,
    isInWatchlist: Boolean,
    isWatchlistUpdating: Boolean,
    isFavorite: Boolean,
    canDownloadShows: Boolean,
    addWatchlistLabel: String,
    removeWatchlistLabel: String,
    addFavoritesLabel: String,
    removeFavoritesLabel: String,
    tvShowLabel: String,
    willPlayLabel: String,
    onPlayEpisode: (MediaItem, Int, Int) -> Unit,
    onChooseEpisodeSource: (MediaItem, Int, Int) -> Unit,
    onDownloadEpisode: (MediaItem, Int, Int) -> Unit,
    onToggleWatchlist: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
    onTrailer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val logo = rememberCachedBitmap(item.logoUrl)
    val playLabel = selection?.let { "Play ${tvEpisodeShortLabel(it)}" } ?: "Choose episode"
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Surface(
            color = colors.accent.copy(alpha = 0.16f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.38f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Theaters, contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
                Text(tvShowLabel, color = colors.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        val seasonCount = item.seasons.size.takeIf { it > 0 }?.let { count ->
            if (count == 1) "1 season" else "$count seasons"
        }
        val metadata = listOfNotNull(
            item.year?.toString(),
            item.genres.take(3).joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
            item.status?.takeIf { it.isNotBlank() },
            seasonCount,
        )
        if (metadata.isNotEmpty()) {
            Text(
                text = metadata.joinToString("  \u2022  "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        MovieRatingSourceChips(item = item)

        Box(Modifier.heightIn(min = 82.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
            if (logo != null) {
                Image(
                    bitmap = logo,
                    contentDescription = item.title,
                    modifier = Modifier.heightIn(max = 96.dp).fillMaxWidth(0.82f),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            DetailSummary(overview = overview, colors = colors)
        }

        TvSelectedEpisodeChip(label = willPlayLabel, selection = selection)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CinematicIconActionButton(
                text = playLabel,
                contentDescription = "Play selected episode",
                icon = Icons.Filled.PlayArrow,
                primary = true,
                enabled = selection != null,
                onClick = {
                    selection?.let { onPlayEpisode(item, it.season.seasonNumber, it.episode.episodeNumber) }
                },
            )
            item.trailerKey
                ?.takeUnless { item.isContentPlaceholder || item.isStubDetail }
                ?.let { key ->
                    CinematicIconActionButton(
                        text = ds("Trailer"),
                        contentDescription = "Play trailer",
                        icon = Icons.Filled.Theaters,
                        onClick = { onTrailer(key) },
                    )
                }
            CinematicIconActionButton(
                text = ds("Sources"),
                contentDescription = "Choose sources for selected episode",
                icon = Icons.Filled.Info,
                enabled = selection != null,
                onClick = {
                    selection?.let { onChooseEpisodeSource(item, it.season.seasonNumber, it.episode.episodeNumber) }
                },
            )
            CinematicIconActionButton(
                text = ds("Download"),
                contentDescription = "Download selected episode",
                icon = Icons.Filled.Download,
                enabled = canDownloadShows && selection != null,
                onClick = {
                    selection?.let { onDownloadEpisode(item, it.season.seasonNumber, it.episode.episodeNumber) }
                },
            )
            CinematicCompactIconAction(
                icon = if (isInWatchlist) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (isInWatchlist) removeWatchlistLabel else addWatchlistLabel,
                enabled = !isWatchlistUpdating,
                selected = isInWatchlist,
                onClick = { onToggleWatchlist(item) },
            )
            CinematicCompactIconAction(
                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) removeFavoritesLabel else addFavoritesLabel,
                selected = isFavorite,
                onClick = { onToggleFavorite(item) },
            )
        }

        item.director?.takeIf { it.isNotBlank() }?.let { creator ->
            TvCreatorMiniCredit(
                creator = creator,
                profileUrl = item.directorProfileUrl,
                personId = item.directorId,
                onOpenPerson = onOpenPerson,
            )
        }
    }
}

@Composable
private fun TvSelectedEpisodeChip(
    label: String,
    selection: TvEpisodeSelection?,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        color = Color(0xFF09111F).copy(alpha = 0.64f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, colors.accent.copy(alpha = if (selection != null) 0.42f else 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.accent.copy(alpha = 0.92f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(
                selection?.let { "${tvEpisodeShortLabel(it)}  \u2022  ${it.episode.name.ifBlank { "Episode ${it.episode.episodeNumber}" }}" }
                    ?: "Choose episode",
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvCreatorMiniCredit(
    creator: String,
    profileUrl: String?,
    personId: Int?,
    onOpenPerson: (Int) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val photo = rememberCachedBitmap(profileUrl)
    val clickableModifier = if (personId != null) {
        Modifier.clickable { onOpenPerson(personId) }
    } else {
        Modifier
    }
    Row(
        modifier = clickableModifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF0B1220).copy(alpha = 0.44f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo != null) {
            Image(photo, creator, Modifier.size(36.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Text(creator.firstOrNull()?.uppercase() ?: "?", color = Color.White.copy(alpha = 0.72f), fontWeight = FontWeight.Bold)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Created by", color = Color.White.copy(alpha = 0.52f), style = MaterialTheme.typography.labelSmall)
            Text(creator, color = if (personId != null) colors.accent else Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeHubSideCard(
    item: MediaItem,
    selection: TvEpisodeSelection?,
    canDownloadShows: Boolean,
    onPlayEpisode: (MediaItem, Int, Int) -> Unit,
    onChooseEpisodeSource: (MediaItem, Int, Int) -> Unit,
    onDownloadEpisode: (MediaItem, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val rows = remember(item, selection) { episodeHubInfoRows(item, selection) }
    Surface(
        modifier = modifier.shadow(34.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)),
        color = Color(0xFF07101D).copy(alpha = 0.70f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    0.00f to Color.White.copy(alpha = 0.08f),
                    0.20f to Color(0xFFFFC857).copy(alpha = 0.04f),
                    1.00f to Color.Black.copy(alpha = 0.14f),
                ),
            ),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 24.dp)
                    .background(Color.White.copy(alpha = 0.18f)),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Text("Episode hub", color = colors.accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                selection?.let {
                    Text(
                        "Selected: ${tvEpisodeShortLabel(it)}",
                        color = Color.White.copy(alpha = 0.95f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        it.episode.name.ifBlank { "Episode ${it.episode.episodeNumber}" },
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } ?: Text(
                    "Choose an episode to enable play, sources, and download.",
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.bodyMedium,
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CinematicIconActionButton(
                        text = "Play Episode",
                        contentDescription = "Play selected episode",
                        icon = Icons.Filled.PlayArrow,
                        primary = true,
                        enabled = selection != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selection?.let { onPlayEpisode(item, it.season.seasonNumber, it.episode.episodeNumber) }
                        },
                    )
                    CinematicIconActionButton(
                        text = "Sources",
                        contentDescription = "Choose sources for selected episode",
                        icon = Icons.Filled.Info,
                        enabled = selection != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selection?.let { onChooseEpisodeSource(item, it.season.seasonNumber, it.episode.episodeNumber) }
                        },
                    )
                    CinematicIconActionButton(
                        text = "Download",
                        contentDescription = "Download selected episode",
                        icon = Icons.Filled.Download,
                        enabled = canDownloadShows && selection != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selection?.let { onDownloadEpisode(item, it.season.seasonNumber, it.episode.episodeNumber) }
                        },
                    )
                }

                rows.forEachIndexed { index, row ->
                    MetadataInfoRow(row)
                    if (index < rows.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumSeasonSelector(
    seasons: List<Season>,
    selectedSeasonNumber: Int?,
    startPadding: Dp,
    endPadding: Dp,
    onSelectSeason: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
    ) {
        items(seasons, key = { it.seasonNumber }) { season ->
            PremiumSeasonChip(
                text = seasonDisplayName(season),
                selected = season.seasonNumber == selectedSeasonNumber,
                onClick = { onSelectSeason(season.seasonNumber) },
            )
        }
    }
}

@Composable
private fun PremiumSeasonChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val active = selected || hovered || focused
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "premiumSeasonScale")
    Surface(
        modifier = Modifier
            .height(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(999.dp))
            .hoverable(interaction)
            .onFocusChanged { focused = it.isFocused }
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) {
                contentDescription = if (selected) "$text, selected" else text
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        color = when {
            selected -> colors.accent.copy(alpha = 0.18f)
            active -> Color(0xFF101827).copy(alpha = 0.70f)
            else -> Color(0xFF0B1220).copy(alpha = 0.48f)
        },
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (active) colors.accent.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.11f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (selected || focused) Color(0xFFFFE4A8) else Color.White.copy(alpha = 0.84f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PremiumEpisodeCard(
    item: MediaItem,
    seasonNumber: Int,
    episode: Episode,
    selected: Boolean,
    canDownload: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val active = selected || hovered || focused
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.99f
            focused -> 1.01f
            else -> 1f
        },
        label = "premiumEpisodeScale",
    )
    val still = rememberCachedBitmap(
        if (item.isContentPlaceholder || item.isStubDetail) null else episode.stillUrl ?: movieBackdropUrl(item),
    )
    val episodeRatings = item.ratings.withFallbackTmdbScore(episode.rating)
    val episodeLabel = "S$seasonNumber E${episode.episodeNumber}"
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (active) 24.dp else 10.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .hoverable(interaction)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect()
            }
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(
                    episodeLabel,
                    episode.name.ifBlank { "Episode ${episode.episodeNumber}" },
                    if (selected) "selected" else "",
                ).filter { it.isNotBlank() }.joinToString(", ")
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect),
        color = if (selected) colors.accent.copy(alpha = 0.14f) else Color(0xFF07101D).copy(alpha = 0.62f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, if (active) colors.accent.copy(alpha = 0.66f) else Color.White.copy(alpha = 0.12f)),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(176.dp)
                    .height(100.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                if (still != null) {
                    Image(still, episode.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(episodeLabel, color = Color.White.copy(alpha = 0.64f), fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(episodeLabel, color = colors.accent.copy(alpha = 0.92f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    episode.name.ifBlank { "Episode ${episode.episodeNumber}" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.overview.isNotBlank()) {
                    Text(
                        episode.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.64f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    episode.runtime?.takeIf { it > 0 }?.let { TorveBadge("${it}m", tone = TorveBadgeTone.Neutral) }
                    episode.airDate?.takeIf { it.isNotBlank() }?.let { TorveBadge(it, tone = TorveBadgeTone.Neutral) }
                    if (episodeRatings != null) DesktopRatingPills(ratings = episodeRatings, showBackground = false)
                }
            }
            Column(
                modifier = Modifier.width(144.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                CinematicIconActionButton(
                    text = "Play",
                    contentDescription = "Play $episodeLabel",
                    icon = Icons.Filled.PlayArrow,
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPlay,
                )
                CinematicIconActionButton(
                    text = "Sources",
                    contentDescription = "Choose sources for $episodeLabel",
                    icon = Icons.Filled.Info,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSources,
                )
                CinematicIconActionButton(
                    text = "Download",
                    contentDescription = "Download $episodeLabel",
                    icon = Icons.Filled.Download,
                    enabled = canDownload,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDownload,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopMovieHeroContentCluster(
    item: MediaItem,
    isInWatchlist: Boolean,
    isWatchlistUpdating: Boolean,
    isFavorite: Boolean,
    canDownloadMovies: Boolean,
    addWatchlistLabel: String,
    removeWatchlistLabel: String,
    addFavoritesLabel: String,
    removeFavoritesLabel: String,
    onPlay: (MediaItem) -> Unit,
    onChooseSource: (MediaItem) -> Unit,
    onDownloadMovie: (MediaItem) -> Unit,
    onToggleWatchlist: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
    onTrailer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val logo = rememberCachedBitmap(item.logoUrl)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Surface(
            color = colors.accent.copy(alpha = 0.16f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.38f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
                Text("Movie", color = colors.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        val metadata = listOfNotNull(
            item.year?.toString(),
            item.runtime?.takeIf { it > 0 }?.let { "${it}m" },
            item.genres.take(3).joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
        )
        if (metadata.isNotEmpty()) {
            Text(
                text = metadata.joinToString("  \u2022  "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        MovieRatingSourceChips(item = item)

        Box(Modifier.heightIn(min = 82.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
            if (logo != null) {
                Image(
                    bitmap = logo,
                    contentDescription = item.title,
                    modifier = Modifier.heightIn(max = 96.dp).fillMaxWidth(0.82f),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            DetailSummary(overview = overview, colors = colors)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CinematicIconActionButton(
                text = ds("Play"),
                contentDescription = "Play movie",
                icon = Icons.Filled.PlayArrow,
                primary = true,
                onClick = { onPlay(item) },
            )
            item.trailerKey
                ?.takeUnless { item.isContentPlaceholder || item.isStubDetail }
                ?.let { key ->
                CinematicIconActionButton(
                    text = ds("Trailer"),
                    contentDescription = "Play trailer",
                    icon = Icons.Filled.Theaters,
                    onClick = { onTrailer(key) },
                )
                }
            CinematicIconActionButton(
                text = ds("Sources"),
                contentDescription = "Choose sources",
                icon = Icons.Filled.Info,
                onClick = { onChooseSource(item) },
            )
            CinematicIconActionButton(
                text = ds("Download"),
                contentDescription = "Download movie",
                icon = Icons.Filled.Download,
                enabled = canDownloadMovies,
                onClick = { onDownloadMovie(item) },
            )
            CinematicCompactIconAction(
                icon = if (isInWatchlist) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (isInWatchlist) removeWatchlistLabel else addWatchlistLabel,
                enabled = !isWatchlistUpdating,
                selected = isInWatchlist,
                onClick = { onToggleWatchlist(item) },
            )
            CinematicCompactIconAction(
                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) removeFavoritesLabel else addFavoritesLabel,
                selected = isFavorite,
                onClick = { onToggleFavorite(item) },
            )
        }

        item.director?.takeIf { it.isNotBlank() }?.let { director ->
            DirectorMiniCredit(
                director = director,
                profileUrl = item.directorProfileUrl,
                directorId = item.directorId,
                onOpenPerson = onOpenPerson,
            )
        }
    }
}

@Composable
private fun MovieRatingSourceChips(item: MediaItem, modifier: Modifier = Modifier) {
    val ratings = remember(item.ratings, item.rating) { item.ratings.withFallbackTmdbScore(item.rating) }
    val prefs = detailEnrichedRatingPrefs(maxRatings = 8)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (ratings != null) {
            DesktopRatingPills(
                ratings = ratings,
                showBackground = false,
                prefs = prefs,
                visualScale = 1.32f,
            )
        }
    }
}

@Composable
private fun detailEnrichedRatingPrefs(maxRatings: Int): RatingDisplayPrefs {
    val current = LocalRatingDisplayPrefs.current
    val preferred = listOf(
        RatingSource.IMDB,
        RatingSource.TMDB,
        RatingSource.TRAKT,
        RatingSource.ROTTEN_TOMATOES,
    )
    return current.copy(
        enabledProviders = (preferred + current.enabledProviders).distinct(),
        providerOrder = (preferred + current.providerOrder).distinct(),
        maxRatingsOnCard = current.maxRatingsOnCard.coerceAtLeast(maxRatings),
    )
}

@Composable
private fun RatingSourceChip(chip: MovieRatingChipModel) {
    Surface(
        color = Color(0xFF09111F).copy(alpha = 0.70f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(chip.source, color = Color(0xFFFFC857), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
            Text(chip.value, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CinematicIconActionButton(
    text: String,
    contentDescription: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val active = hovered || focused
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.98f
            active -> 1.025f
            else -> 1f
        },
        label = "movieActionScale",
    )
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFF0B1220).copy(alpha = 0.28f)
            primary -> colors.accent.copy(alpha = if (active) 0.96f else 0.88f)
            active -> Color(0xFF101827).copy(alpha = 0.72f)
            else -> Color(0xFF0B1220).copy(alpha = 0.54f)
        },
        label = "movieActionBackground",
    )
    val border by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.06f)
            active -> colors.accent.copy(alpha = 0.70f)
            else -> Color.White.copy(alpha = 0.13f)
        },
        label = "movieActionBorder",
    )
    val contentColor = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        primary -> Color(0xFF080A10)
        else -> Color.White.copy(alpha = 0.90f)
    }
    Surface(
        modifier = modifier
            .height(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (active) 18.dp else 8.dp, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .hoverable(interaction, enabled)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled, interactionSource = interaction)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        color = background,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (primary) 19.dp else 15.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(19.dp))
            Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun CinematicCompactIconAction(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val active = hovered || focused
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.98f
            active -> 1.035f
            else -> 1f
        },
        label = "movieCompactActionScale",
    )
    val border by animateColorAsState(
        targetValue = if (active || selected) colors.accent.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.12f),
        label = "movieCompactActionBorder",
    )
    Box(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (active) 18.dp else 8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color(0xFF0B1220).copy(alpha = 0.58f))
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .hoverable(interaction, enabled)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled, interactionSource = interaction)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun DirectorMiniCredit(
    director: String,
    profileUrl: String?,
    directorId: Int?,
    onOpenPerson: (Int) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val photo = rememberCachedBitmap(profileUrl)
    Row(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF08111E).copy(alpha = if (hovered) 0.72f else 0.52f))
            .border(1.dp, if (hovered) colors.accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .let { base ->
                if (directorId != null) {
                    base
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null) { onOpenPerson(directorId) }
                } else {
                    base
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo != null) {
            Image(photo, director, Modifier.size(36.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Text(director.firstOrNull()?.uppercase() ?: "?", color = Color.White.copy(alpha = 0.72f), fontWeight = FontWeight.Bold)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Directed by", color = Color.White.copy(alpha = 0.52f), style = MaterialTheme.typography.labelSmall)
            Text(director, color = if (directorId != null) colors.accent else Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieDetailsSideCard(
    item: MediaItem,
    relatedItems: List<MediaItem>,
    onOpenRelated: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val rows = remember(item) { movieDetailsInfoRows(item) }
    val ratingChips = remember(item.ratings, item.rating) { movieRatingChips(item) }
    Surface(
        modifier = modifier.shadow(34.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)),
        color = Color(0xFF07101D).copy(alpha = 0.68f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    0.00f to Color.White.copy(alpha = 0.08f),
                    0.22f to Color(0xFFFFC857).copy(alpha = 0.035f),
                    1.00f to Color.Black.copy(alpha = 0.12f),
                ),
            ),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 24.dp)
                    .background(Color.White.copy(alpha = 0.18f)),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Text("Movie details", color = colors.accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                rows.forEachIndexed { index, row ->
                    MetadataInfoRow(row)
                    if (index < rows.lastIndex || ratingChips.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                    }
                }
                if (ratingChips.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = colors.accent.copy(alpha = 0.86f), modifier = Modifier.size(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Rating", color = Color.White.copy(alpha = 0.50f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                ratingChips.forEach { RatingSourceChip(it) }
                            }
                        }
                    }
                }
                if (relatedItems.isNotEmpty()) {
                    MiniRecommendationRail(
                        items = relatedItems,
                        onOpenRelated = onOpenRelated,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniRecommendationRail(
    items: List<MediaItem>,
    onOpenRelated: (MediaItem) -> Unit,
) {
    val displayItems = remember(items) { items.take(10) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visibleCount = listState.layoutInfo.visibleItemsInfo.size
    val hasOverflow = visibleCount > 0 && displayItems.size > visibleCount
    val canScrollBackward = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    val canScrollForward = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it < displayItems.lastIndex } == true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("You might also like", color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (hasOverflow) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    DetailRailScrollButton(
                        icon = Icons.Filled.ChevronLeft,
                        contentDescription = "Scroll recommendations left",
                        enabled = canScrollBackward,
                        size = 28.dp,
                        onClick = {
                            val page = (visibleCount - 1).coerceAtLeast(1)
                            scope.launch {
                                listState.animateScrollToItem((listState.firstVisibleItemIndex - page).coerceAtLeast(0))
                            }
                        },
                    )
                    DetailRailScrollButton(
                        icon = Icons.Filled.ChevronRight,
                        contentDescription = "Scroll recommendations right",
                        enabled = canScrollForward,
                        size = 28.dp,
                        onClick = {
                            val page = (visibleCount - 1).coerceAtLeast(1)
                            scope.launch {
                                listState.animateScrollToItem((listState.firstVisibleItemIndex + page).coerceAtMost(displayItems.lastIndex))
                            }
                        },
                    )
                }
            }
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
            contentPadding = PaddingValues(end = 8.dp, bottom = 3.dp),
        ) {
            items(displayItems, key = { it.id }) { related ->
                MiniRelatedTile(item = related, onClick = { onOpenRelated(related) })
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }
}

@Composable
private fun MetadataInfoRow(row: MovieDetailInfoRowModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
        Icon(movieInfoIcon(row.label), contentDescription = null, tint = TorveDesktopThemeTokens.colors.accent.copy(alpha = 0.76f), modifier = Modifier.size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.label, color = Color.White.copy(alpha = 0.50f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            val valueMaxLines = when (row.label) {
                "Studio", "Network", "Genres" -> 2
                else -> 3
            }
            Text(row.value, color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodySmall, maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun movieInfoIcon(label: String): ImageVector = when (label) {
    "Status" -> Icons.Filled.Info
    "Release Date" -> Icons.Filled.CalendarToday
    "Runtime" -> Icons.Filled.Schedule
    "Year" -> Icons.Filled.Movie
    "Director" -> Icons.Filled.Person
    "Studio" -> Icons.Filled.Business
    "Genres" -> Icons.Filled.LocalOffer
    else -> Icons.Filled.Info
}

@Composable
private fun MiniRelatedTile(item: MediaItem, onClick: () -> Unit) {
    val poster = rememberCachedBitmap(
        if (item.isContentPlaceholder || item.isStubDetail) null else item.posterUrl ?: item.backdropUrl,
    )
    val displayRatings = remember(item.ratings, item.rating) { item.ratings.withFallbackTmdbScore(item.rating) }
    val ratingPrefs = detailEnrichedRatingPrefs(maxRatings = 4)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val active = hovered || focused
    val scale by animateFloatAsState(if (active) 1.035f else 1f, label = "miniRelatedScale")
    Box(
        modifier = Modifier
            .width(68.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (active) TorveDesktopThemeTokens.colors.accent.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .onFocusChanged { focused = it.isFocused }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                contentDescription = listOfNotNull(item.title, item.year?.toString()).joinToString(", ")
                role = Role.Button
            },
    ) {
        if (poster != null) {
            Image(poster, item.title, Modifier.fillMaxWidth().height(96.dp), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxWidth().height(96.dp).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Text(item.title.take(1), color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
            }
        }
        DesktopRatingPills(
            ratings = displayRatings,
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
            prefs = ratingPrefs,
            showBackground = true,
        )
    }
}

@Composable
private fun DetailRailHeader(
    title: String,
    startPadding: Dp,
    endPadding: Dp,
    listState: LazyListState,
    itemCount: Int,
) {
    val scope = rememberCoroutineScope()
    val visibleCount = listState.layoutInfo.visibleItemsInfo.size
    val hasOverflow = visibleCount > 0 && itemCount > visibleCount
    val canScrollBackward = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    val canScrollForward = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it < itemCount - 1 } == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.95f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (hasOverflow) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DetailRailScrollButton(
                    icon = Icons.Filled.ChevronLeft,
                    contentDescription = "Scroll $title left",
                    enabled = canScrollBackward,
                    onClick = {
                        val page = (visibleCount - 1).coerceAtLeast(1)
                        scope.launch {
                            listState.animateScrollToItem((listState.firstVisibleItemIndex - page).coerceAtLeast(0))
                        }
                    },
                )
                DetailRailScrollButton(
                    icon = Icons.Filled.ChevronRight,
                    contentDescription = "Scroll $title right",
                    enabled = canScrollForward,
                    onClick = {
                        val page = (visibleCount - 1).coerceAtLeast(1)
                        scope.launch {
                            listState.animateScrollToItem((listState.firstVisibleItemIndex + page).coerceAtMost(itemCount - 1))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailRailScrollButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    size: Dp = 34.dp,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border = if (enabled && hovered) colors.accent.copy(alpha = 0.64f) else Color.White.copy(alpha = if (enabled) 0.14f else 0.07f)
    val background = if (enabled && hovered) colors.accent.copy(alpha = 0.18f) else Color(0xFF0B1220).copy(alpha = if (enabled) 0.58f else 0.28f)
    Surface(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(999.dp))
            .hoverable(interaction, enabled)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        color = background,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (enabled) 0.88f else 0.30f),
                modifier = Modifier.size((size.value * 0.58f).dp),
            )
        }
    }
}

@Composable
private fun CinematicCastRail(
    cast: List<com.torve.domain.model.CastMember>,
    startPadding: Dp,
    endPadding: Dp,
    onOpenPerson: (Int) -> Unit,
) {
    val displayCast = remember(cast) { cast.take(24) }
    val listState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailRailHeader(
            title = "Cast & Crew",
            startPadding = startPadding,
            endPadding = endPadding,
            listState = listState,
            itemCount = displayCast.size,
        )
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(start = startPadding, end = endPadding, top = 8.dp, bottom = 8.dp),
        ) {
            items(displayCast, key = { it.id }) { member ->
                CircularCastMemberCard(member = member, onClick = { onOpenPerson(member.id) })
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = endPadding)
                .height(4.dp),
        )
    }
}

@Composable
private fun CircularCastMemberCard(
    member: com.torve.domain.model.CastMember,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val active = hovered || focused
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.98f
            active -> 1.035f
            else -> 1f
        },
        label = "castCircularScale",
    )
    val photo = rememberCachedBitmap(member.profileUrl)
    Column(
        modifier = Modifier
            .width(104.dp)
            .hoverable(interaction)
            .onFocusChanged { focused = it.isFocused }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(member.name, member.character).joinToString(", ")
                role = Role.Button
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.16f))
                        .shadow(18.dp, CircleShape),
                )
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (active) colors.accent.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.12f), CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) {
                    Image(photo, member.name, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Text(member.name.firstOrNull()?.uppercase() ?: "?", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(member.name, color = if (active) colors.accent else Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        member.character?.let {
            Text(it, color = Color.White.copy(alpha = if (active) 0.70f else 0.48f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CinematicRelatedRail(
    items: List<MediaItem>,
    title: String = "Related",
    startPadding: Dp,
    endPadding: Dp,
    onOpenRelated: (MediaItem) -> Unit,
) {
    val displayItems = remember(items) { items.take(20) }
    val listState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailRailHeader(
            title = title,
            startPadding = startPadding,
            endPadding = endPadding,
            listState = listState,
            itemCount = displayItems.size,
        )
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(start = startPadding, end = endPadding, top = 8.dp, bottom = 8.dp),
        ) {
            items(displayItems, key = { it.id }) { related ->
                CinematicRelatedPosterCard(item = related, onClick = { onOpenRelated(related) })
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = endPadding)
                .height(4.dp),
        )
    }
}

@Composable
private fun CinematicRelatedPosterCard(item: MediaItem, onClick: () -> Unit) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val active = hovered || focused
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.98f
            active -> 1.03f
            else -> 1f
        },
        label = "relatedPosterScale",
    )
    val poster = rememberCachedBitmap(if (item.isContentPlaceholder || item.isStubDetail) null else item.posterUrl)
    val displayRatings = remember(item.ratings, item.rating) { item.ratings.withFallbackTmdbScore(item.rating) }
    val ratingPrefs = detailEnrichedRatingPrefs(maxRatings = 4)
    Column(
        modifier = Modifier
            .width(156.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .zIndex(if (active) 2f else 0f)
            .hoverable(interaction)
            .onFocusChanged { focused = it.isFocused }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(item.title, item.year?.toString(), item.rating?.let { "%.1f".format(it) }).joinToString(", ")
                role = Role.Button
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(234.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0B1220).copy(alpha = 0.72f))
                .border(1.dp, if (active) colors.accent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
        ) {
            if (poster != null) {
                Image(poster, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.74f)),
                        ),
                    ),
            )
            DesktopRatingPills(
                ratings = displayRatings,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                prefs = ratingPrefs,
                showBackground = true,
            )
        }
        Text(item.title, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.year?.let {
            Text(it.toString(), color = Color.White.copy(alpha = 0.50f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FloatingBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Back",
    compact: Boolean = false,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val active = hovered || focused
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.98f
            active -> 1.035f
            else -> 1f
        },
        label = "desktopFloatingBackScale",
    )
    val border by animateColorAsState(
        targetValue = when {
            focused -> colors.accent.copy(alpha = 0.74f)
            hovered -> colors.accent.copy(alpha = 0.54f)
            else -> Color.White.copy(alpha = 0.14f)
        },
        label = "desktopFloatingBackBorder",
    )
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (active) 0.70f else 0.54f,
        label = "desktopFloatingBackAlpha",
    )
    val shape = RoundedCornerShape(999.dp)

    Box(
        modifier = modifier
            .then(if (compact) Modifier.size(42.dp) else Modifier.height(42.dp).widthIn(min = 96.dp, max = 128.dp))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (active) 22.dp else 12.dp, shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (active) 0.13f else 0.08f),
                        Color(0xFF0B1220).copy(alpha = backgroundAlpha),
                        Color(0xFF030711).copy(alpha = (backgroundAlpha + 0.08f).coerceAtMost(0.78f)),
                    ),
                ),
            )
            .border(1.dp, border, shape)
            .hoverable(interaction)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .padding(horizontal = if (compact) 14.dp else 18.dp)
                .background(Color.White.copy(alpha = if (active) 0.26f else 0.16f)),
        )
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 0.dp else 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(18.dp),
            )
            if (!compact) {
                Spacer(Modifier.width(7.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CinematicActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            !enabled -> colors.fieldSurface.copy(alpha = 0.26f)
            primary -> colors.accent.copy(alpha = if (hovered) 0.96f else 0.88f)
            hovered -> colors.accent.copy(alpha = 0.18f)
            else -> Color(0xFF0B1220).copy(alpha = 0.56f)
        },
        label = "detailActionBg",
    )
    val border by animateColorAsState(
        when {
            !enabled -> Color.White.copy(alpha = 0.06f)
            primary -> colors.accent.copy(alpha = 0.92f)
            hovered -> colors.accent.copy(alpha = 0.62f)
            else -> Color.White.copy(alpha = 0.13f)
        },
        label = "detailActionBorder",
    )
    val content = when {
        !enabled -> Color.White.copy(alpha = 0.38f)
        primary -> Color(0xFF0A0B14)
        else -> Color.White.copy(alpha = 0.88f)
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .hoverable(interaction, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        color = background,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = if (primary) 18.dp else 15.dp, vertical = 10.dp),
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CinematicDetailSidePanel(
    item: MediaItem,
    detailState: DesktopSearchDetailUiState,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val selectedEpisode = detailState.selectedSeason?.episodes
        ?.firstOrNull { it.episodeNumber == detailState.selectedEpisodeNumber }
    Surface(
        modifier = modifier.clip(RoundedCornerShape(24.dp)),
        color = Color(0xFF07101D).copy(alpha = 0.66f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.06f),
                        1.0f to Color.Black.copy(alpha = 0.08f),
                    ),
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (item.type == MediaType.SERIES) "Episode hub" else "Movie details",
                color = colors.accent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            if (item.type == MediaType.SERIES && selectedEpisode != null) {
                Text(
                    "Selected: S${detailState.selectedSeasonNumber ?: "?"} E${selectedEpisode.episodeNumber}",
                    color = Color.White.copy(alpha = 0.94f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    selectedEpisode.name.ifBlank { "Episode ${selectedEpisode.episodeNumber}" },
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.status?.let { CinematicInfoLine("Status", it) }
            item.year?.let { CinematicInfoLine("Year", it.toString()) }
            item.runtime?.let { CinematicInfoLine("Runtime", "${it}m") }
            item.releaseDate?.let { CinematicInfoLine("Release", it) }
            item.director?.takeIf { it.isNotBlank() }?.let {
                CinematicInfoLine(if (item.type == MediaType.SERIES) "Creator" else "Director", it)
            }
            if (item.studios.isNotEmpty()) {
                CinematicInfoLine("Studio", item.studios.take(2).joinToString(", ") { it.name })
            }
            if (item.genres.isNotEmpty()) {
                CinematicInfoLine("Genres", item.genres.take(4).joinToString(", ") { it.name })
            }
        }
    }
}

@Composable
private fun CinematicInfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(74.dp), color = Color.White.copy(alpha = 0.50f), style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CinematicSectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.96f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CinematicSeasonChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        color = when {
            selected -> colors.accent.copy(alpha = 0.16f)
            hovered -> Color(0xFF101827).copy(alpha = 0.68f)
            else -> Color(0xFF0B1220).copy(alpha = 0.46f)
        },
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (selected || hovered) colors.accent.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.10f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (selected) Color(0xFFFFE4A8) else Color.White.copy(alpha = 0.84f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CinematicEpisodeCard(
    item: MediaItem,
    seasonNumber: Int,
    episode: Episode,
    selected: Boolean,
    canDownload: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onDownload: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val still = rememberCachedBitmap(episode.stillUrl)
    val episodeRatings = item.ratings.withFallbackTmdbScore(episode.rating)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect),
        color = if (selected) colors.accent.copy(alpha = 0.13f) else Color(0xFF07101D).copy(alpha = 0.58f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (selected) colors.accent.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.11f)),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.width(150.dp).height(84.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                if (still != null) Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("S${seasonNumber}E${episode.episodeNumber}", color = Color.White.copy(alpha = 0.62f), fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Episode ${episode.episodeNumber}", color = colors.accent.copy(alpha = 0.92f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(episode.name.ifBlank { "Episode ${episode.episodeNumber}" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.94f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (episode.overview.isNotBlank()) {
                    Text(episode.overview, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.62f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    episode.runtime?.let { TorveBadge("${it}m", tone = TorveBadgeTone.Neutral) }
                    episode.airDate?.let { TorveBadge(it, tone = TorveBadgeTone.Neutral) }
                    if (episodeRatings != null) DesktopRatingPills(ratings = episodeRatings, showBackground = false)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.End) {
                CinematicActionButton("Play", primary = true, onClick = onPlay)
                CinematicActionButton("Sources", onClick = onSources)
                CinematicActionButton("Download", enabled = canDownload, onClick = onDownload)
            }
        }
    }
}

private fun detailPrimaryPlayLabel(
    item: MediaItem,
    detailState: DesktopSearchDetailUiState,
): String {
    if (item.type != MediaType.SERIES) return "Play"
    val season = detailState.selectedSeasonNumber
    val episode = detailState.selectedEpisodeNumber
    return if (season != null && episode != null) "Play S$season E$episode" else "Play next episode"
}

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
    val prefs = detailEnrichedRatingPrefs(maxRatings = 8)
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
                prefs = prefs,
                visualScale = 1.32f,
            )
        }
    }
}
