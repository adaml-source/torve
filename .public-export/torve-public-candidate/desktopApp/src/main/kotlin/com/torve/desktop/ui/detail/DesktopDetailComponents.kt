package com.torve.desktop.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.desktop.playback.DesktopPlaybackSession
import com.torve.desktop.playback.DesktopPlaybackSourceCandidate
import com.torve.desktop.playback.DesktopPlayerUiState
import com.torve.desktop.search.DesktopSearchDetailUiState
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePill
import com.torve.desktop.ui.components.TorvePosterCardCompact
import com.torve.desktop.ui.components.TorvePreviewSheet
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveShelfHeader
import com.torve.desktop.ui.components.TorveSoftContentBand
import com.torve.desktop.ui.components.TorveBackdropHero
import com.torve.desktop.ui.pages.common.TorveSummaryCard
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.watchlist.WatchlistUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopDetailDrawer(
    title: String,
    detailState: DesktopSearchDetailUiState,
    watchlistState: WatchlistUiState,
    playerState: DesktopPlayerUiState,
    onPlay: () -> Unit,
    onChooseSource: () -> Unit,
    onOpenDetails: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onRefreshDetail: () -> Unit,
    onClearActionMessage: () -> Unit,
) {
    val detail = detailState.detailItem
    val selected = detailState.selectedResult
    val selectedOrDetail = detail ?: selected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = TorveDesktopThemeTokens.colors.textMuted,
        )

        if (selectedOrDetail == null) {
            TorveSummaryCard(
                title = "Nothing selected",
                lines = listOf("Pick a title to preview metadata, source confidence, and playback actions."),
            )
            return@Column
        }

        val watchlisted = watchlistState.watchlistIds.contains(selectedOrDetail.id)
        val activeSession = playerState.preparedSession
            ?.takeIf { session -> session.request.mediaId == selectedOrDetail.id }

        TorvePreviewSheet(
            title = selectedOrDetail.title,
            subtitle = detail?.tagline?.takeIf { it.isNotBlank() }
                ?: detail?.overview
                ?: selectedOrDetail.overview
                ?: "Open details or choose a source.",
            seed = selectedOrDetail.backdropUrl ?: selectedOrDetail.posterUrl ?: selectedOrDetail.id,
            titleArtUrl = selectedOrDetail.logoUrl,
            accentLabel = selectedOrDetail.type.name.lowercase(),
            metadata = baseMetadataBadges(selectedOrDetail),
            actions = {
                TorvePrimaryButton(
                    text = "Play",
                    onClick = onPlay,
                )
                TorveSecondaryButton(
                    text = "Choose Source",
                    onClick = onChooseSource,
                )
                TorveGhostButton(
                    text = "Open Details",
                    onClick = onOpenDetails,
                )
            },
        ) {
            if (detailState.isLoadingDetail) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            detailState.detailError?.let {
                TorveBanner(
                    title = "Detail unavailable",
                    description = it,
                    tone = TorveBannerTone.Error,
                )
            }

            detailState.actionMessage?.let {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveBanner(
                        title = "Playback note",
                        description = it,
                        tone = TorveBannerTone.Info,
                    )
                    TorveGhostButton(
                        text = "Dismiss",
                        onClick = onClearActionMessage,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TorveGhostButton(
                    text = if (watchlisted) "Remove Watchlist" else "Add Watchlist",
                    onClick = onToggleWatchlist,
                )
                TorveGhostButton(
                    text = "Refresh",
                    onClick = onRefreshDetail,
                )
            }

            activeSession?.let { session ->
                DesktopSourceTrustSummaryCard(session = session)
            }

            detail?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Overview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = TorveDesktopThemeTokens.colors.textSecondary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (selectedOrDetail.type == MediaType.SERIES && detail?.seasons?.isNotEmpty() == true) {
                Text(
                    text = "Episode and season browsing lives in the full detail route.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textMuted,
                )
            }

            if (detailState.similarItems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveShelfHeader(
                        title = "Related titles",
                        supportingText = "Richer related-content exploration stays on the full detail route.",
                    )
                    Text(
                        text = detailState.similarItems.take(4).joinToString(" | ") { it.title },
                        style = MaterialTheme.typography.bodySmall,
                        color = TorveDesktopThemeTokens.colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopFullDetailPage(
    detailState: DesktopSearchDetailUiState,
    watchlistState: WatchlistUiState,
    playerState: DesktopPlayerUiState,
    onBack: () -> Unit,
    onPlay: (seasonNumber: Int?, episodeNumber: Int?) -> Unit,
    onChooseSource: (seasonNumber: Int?, episodeNumber: Int?) -> Unit,
    onToggleWatchlist: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onOpenRelated: (MediaItem) -> Unit,
) {
    val detail = detailState.detailItem ?: detailState.selectedResult
    if (detail == null) {
        TorveSummaryCard(
            title = "Detail unavailable",
            lines = listOf("This detail route no longer has a selected item."),
        )
        return
    }

    val watchlisted = watchlistState.watchlistIds.contains(detail.id)
    val selectedSeason = detailState.selectedSeason
    val selectedEpisode = selectedSeason?.episodes?.firstOrNull { it.episodeNumber == detailState.selectedEpisodeNumber }
    val playbackTarget = if (detail.type == MediaType.SERIES) {
        detailState.selectedSeasonNumber to detailState.selectedEpisodeNumber
    } else {
        null to null
    }
    val activeSession = playerState.preparedSession
        ?.takeIf { session -> session.request.mediaId == detail.id }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TorveBackdropHero(
            title = detail.title,
            subtitle = detail.tagline?.takeIf { it.isNotBlank() }
                ?: detail.overview
                ?: "Choose a source and start watching.",
            seed = detail.backdropUrl ?: detail.posterUrl ?: detail.id,
            titleArtUrl = detail.logoUrl,
            accentLabel = if (watchlisted) "Watchlist" else detail.type.name.lowercase(),
            metadata = baseMetadataBadges(detail),
            topLeft = {
                TorveGhostButton(
                    text = "Back",
                    onClick = onBack,
                )
            },
            actions = {
                TorvePrimaryButton(
                    text = "Play",
                    onClick = { onPlay(playbackTarget.first, playbackTarget.second) },
                )
                TorveSecondaryButton(
                    text = "Choose Source",
                    onClick = { onChooseSource(playbackTarget.first, playbackTarget.second) },
                )
                TorveGhostButton(
                    text = if (watchlisted) "Remove Watchlist" else "Add Watchlist",
                    onClick = onToggleWatchlist,
                )
            },
            supportingContent = {
                selectedEpisode?.let {
                    TorveBadge(
                        text = "S${selectedSeason?.seasonNumber ?: "?"}E${it.episodeNumber}",
                        tone = TorveBadgeTone.Accent,
                    )
                }
            },
        )

        detailState.detailError?.let {
            TorveBanner(
                title = "Metadata issue",
                description = it,
                tone = TorveBannerTone.Error,
            )
        }

        val overview = detail.overview
        if (!overview.isNullOrBlank() && detail.tagline?.isNotBlank() == true) {
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyMedium,
                color = TorveDesktopThemeTokens.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 40.dp),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }

        DetailMetadataBand(detail = detail)

        var trailerKey by remember { mutableStateOf<String?>(null) }
        if (detail.trailerKey != null) {
            Row(
                modifier = Modifier.padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TorveSecondaryButton(
                    text = "Trailer",
                    onClick = { trailerKey = detail.trailerKey },
                )
            }
        }
        trailerKey?.let { key ->
            com.torve.desktop.ui.trailer.TrailerOverlay(
                youtubeKey = key,
                title = detail.title.takeIf { it.isNotBlank() } ?: "Trailer",
                onDismiss = { trailerKey = null },
            )
        }

        if (detail.director != null) {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Director",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TorveDesktopThemeTokens.colors.textPrimary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        color = TorveDesktopThemeTokens.colors.fieldSurface,
                        shape = CircleShape,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = detail.director?.take(1)?.uppercase() ?: "D",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TorveDesktopThemeTokens.colors.textSecondary,
                            )
                        }
                    }
                    Text(
                        text = detail.director ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TorveDesktopThemeTokens.colors.textPrimary,
                    )
                }
            }
        }

        if (detail.cast.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Cast",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TorveDesktopThemeTokens.colors.textPrimary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    detail.cast.take(12).forEach { castMember ->
                        CastMemberCard(
                            name = castMember.name,
                            character = castMember.character,
                            profileUrl = castMember.profileUrl,
                        )
                    }
                }
            }
        }

        activeSession?.let { session ->
            DesktopSourceTrustSummaryCard(session = session)
        }

        if (detail.type == MediaType.SERIES && detail.seasons.isNotEmpty()) {
            TorveSoftContentBand(
                title = "Episodes",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        detail.seasons
                            .filter { it.seasonNumber > 0 }
                            .forEach { season ->
                                if (season.seasonNumber == detailState.selectedSeasonNumber) {
                                    TorveSecondaryButton(
                                        text = "Season ${season.seasonNumber}",
                                        onClick = {},
                                        enabled = false,
                                    )
                                } else {
                                    TorveGhostButton(
                                        text = "Season ${season.seasonNumber}",
                                        onClick = { onSelectSeason(season.seasonNumber) },
                                    )
                                }
                            }
                    }

                    if (detailState.isLoadingSeason) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    selectedSeason?.episodes?.take(12)?.forEach { episode ->
                        EpisodeFeatureRow(
                            episode = episode,
                            selected = episode.episodeNumber == detailState.selectedEpisodeNumber,
                            onSelect = { onSelectEpisode(episode.episodeNumber) },
                            onPlay = {
                                onPlay(selectedSeason.seasonNumber, episode.episodeNumber)
                            },
                            onChooseSource = {
                                onChooseSource(selectedSeason.seasonNumber, episode.episodeNumber)
                            },
                        )
                    }
                }
            }
        }

        if (detailState.similarItems.isNotEmpty()) {
            TorveSoftContentBand(
                title = "Related titles",
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    detailState.similarItems.take(8).forEach { item ->
                        TorvePosterCardCompact(
                            title = item.title,
                            subtitle = fullDetailSubtitle(item),
                            modifier = Modifier.width(164.dp),
                            seed = item.posterUrl ?: item.backdropUrl ?: item.id,
                            titleArtUrl = item.logoUrl,
                            metadata = baseMetadataBadges(item),
                            primaryActionLabel = "Open",
                            onSelect = { onOpenRelated(item) },
                            onPrimaryAction = { onOpenRelated(item) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailMetadataBand(
    detail: MediaItem,
) {
    val items = buildList {
        detail.genres.takeIf { it.isNotEmpty() }?.let { genres ->
            add(genres.joinToString(", ") { it.name })
        }
        detail.director?.let { add("Dir. $it") }
        detail.releaseDate?.let { add(it) }
        detail.status?.let { add(it) }
    }
    if (items.isEmpty()) return

    FlowRow(
        modifier = Modifier.padding(horizontal = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { label ->
            TorvePill(text = label)
        }
    }
}

@Composable
private fun EpisodeFeatureRow(
    episode: Episode,
    selected: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onChooseSource: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) colors.accentContainer.copy(alpha = 0.3f) else colors.fieldSurface.copy(alpha = 0.34f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(TorveDesktopThemeTokens.radii.xl),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) colors.accent.copy(alpha = 0.4f) else colors.borderSubtle.copy(alpha = 0.2f),
        ),
        onClick = onSelect,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = buildString {
                    append("Episode ")
                    append(episode.episodeNumber)
                    if (episode.name.isNotBlank()) {
                        append(" - ")
                        append(episode.name)
                    }
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = episode.overview.ifBlank {
                    buildString {
                        episode.runtime?.let {
                            append("${it}m")
                        }
                        episode.airDate?.let {
                            if (isNotBlank()) append(" | ")
                            append(it)
                        }
                    }.ifBlank { "Episode metadata available." }
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveGhostButton(
                    text = "Source",
                    onClick = onChooseSource,
                )
                TorveSecondaryButton(
                    text = "Play",
                    onClick = onPlay,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopSourceTrustSummaryCard(
    session: DesktopPlaybackSession,
) {
    val selected = session.selectedCandidate
    TorveSoftContentBand(
        title = "Playback confidence",
    ) {
        if (selected == null) {
            Text(
                text = "No source candidate is selected yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sourceTrustBadges(
                        candidate = selected,
                        session = session,
                        isRecommended = session.recommendedCandidateId == selected.candidateId,
                        failedPreviously = false,
                    ).forEach { badge ->
                        if (badge.accent) {
                            TorveBadge(text = badge.label, tone = badge.tone)
                        } else {
                            TorvePill(text = badge.label)
                        }
                    }
                }
                Text(
                    text = selected.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
        }
    }
}

data class DesktopTrustBadge(
    val label: String,
    val tone: TorveBadgeTone = TorveBadgeTone.Neutral,
    val accent: Boolean = false,
)

@Composable
private fun CastMemberCard(
    name: String,
    character: String?,
    profileUrl: String?,
) {
    val colors = TorveDesktopThemeTokens.colors
    val photoBitmap = profileUrl?.let { url ->
        val imageUrl = url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val cached = imageUrl?.let { com.torve.desktop.ui.components.ImageBitmapCache.get(it) }
        val imageState = androidx.compose.runtime.produceState(initialValue = cached, imageUrl) {
            if (imageUrl == null) {
                value = null
            } else {
                val hit = com.torve.desktop.ui.components.ImageBitmapCache.get(imageUrl)
                if (hit != null) {
                    value = hit
                } else {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val bitmap = org.jetbrains.skia.Image.makeFromEncoded(
                                java.net.URL(imageUrl).readBytes(),
                            ).toComposeImageBitmap()
                            com.torve.desktop.ui.components.ImageBitmapCache.put(imageUrl, bitmap)
                            bitmap
                        }.getOrNull()
                    }
                }
            }
        }
        imageState.value
    }
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            color = colors.fieldSurface,
            shape = CircleShape,
        ) {
            if (photoBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = photoBitmap,
                    contentDescription = name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textMuted,
                    )
                }
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        character?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 9.sp,
            )
        }
    }
}

private fun baseMetadataBadges(
    item: MediaItem,
): List<String> = buildList {
    add(item.type.name.lowercase())
    item.year?.let { add(it.toString()) }
    item.runtime?.let { add("${it}m") }
    item.rating?.let { add(String.format("%.1f", it)) }
}

fun sourceTrustBadges(
    candidate: DesktopPlaybackSourceCandidate,
    session: DesktopPlaybackSession,
    isRecommended: Boolean,
    failedPreviously: Boolean,
): List<DesktopTrustBadge> = buildList {
    add(DesktopTrustBadge(label = candidate.addonName))
    add(DesktopTrustBadge(label = candidate.quality))
    if (candidate.isCached) {
        add(DesktopTrustBadge(label = "Cached", tone = TorveBadgeTone.Success, accent = true))
    }
    val subtitleCount = session.subtitleCandidates.size
    if (subtitleCount > 0) {
        add(DesktopTrustBadge(label = "$subtitleCount subtitles"))
    }
    inferLanguage(candidate)?.let { add(DesktopTrustBadge(label = it)) }
    if (isRecommended) {
        add(DesktopTrustBadge(label = "Recommended", tone = TorveBadgeTone.Accent, accent = true))
    }
    if (failedPreviously) {
        add(DesktopTrustBadge(label = "Failed previously", tone = TorveBadgeTone.Warning, accent = true))
    }
    candidate.size?.takeIf { it.isNotBlank() }?.let { add(DesktopTrustBadge(label = it)) }
}

fun inferLanguage(
    candidate: DesktopPlaybackSourceCandidate,
): String? {
    val haystack = listOfNotNull(candidate.title, candidate.source).joinToString(" ").lowercase()
    return when {
        "english" in haystack || " eng " in " $haystack " -> "English"
        "german" in haystack || " deutsch" in haystack || " ger " in " $haystack " -> "German"
        "spanish" in haystack || " esp " in " $haystack " -> "Spanish"
        "french" in haystack || " fra " in " $haystack " -> "French"
        else -> null
    }
}

private fun fullDetailSubtitle(
    item: MediaItem,
): String = buildString {
    append(item.type.name.lowercase())
    item.year?.let {
        append(" | ")
        append(it)
    }
    item.runtime?.let {
        append(" | ")
        append("${it}m")
    }
    item.rating?.let {
        append(" | ")
        append(String.format("%.1f", it))
    }
}
