package com.torve.desktop.ui.v2.person

import androidx.compose.animation.animateColorAsState
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2FloatingBackButton
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.V2Shelf
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.domain.model.MediaItem
import com.torve.presentation.detail.PersonViewModel
import kotlin.math.absoluteValue

@Composable
fun V2PersonPage(
    personId: Int,
    personViewModel: PersonViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val state by personViewModel.state.collectAsState()

    LaunchedEffect(personId) {
        personViewModel.loadPerson(personId)
    }

    Box(Modifier.fillMaxSize()) {
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().background(colors.shellBackground), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            return@Box
        }

        state.error?.let { error ->
            Box(Modifier.fillMaxSize().background(colors.shellBackground), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error, style = MaterialTheme.typography.bodyLarge, color = colors.error)
                    V2FloatingBackButton(onBack = onBack, contentDescription = ds("Back"))
                }
            }
            return@Box
        }

        // Pick the strongest credit's backdrop for the cinematic hero
        val heroBackdropUrl = remember(state.credits) {
            state.credits.firstOrNull { !it.backdropUrl.isNullOrBlank() }?.backdropUrl
        }
        val heroBackdrop = rememberCachedBitmap(heroBackdropUrl)

        // Palette tint based on person ID
        val variant = personId.absoluteValue % 4
        val gradTint by animateColorAsState(
            when (variant) { 0 -> Color(0xFF1A0E05); 1 -> Color(0xFF0A0F1E); 2 -> Color(0xFF140A1E); else -> Color(0xFF0A1614) },
            label = "personGrad",
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val vpH = maxHeight
            val scrollState = rememberScrollState()

            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopStart).zIndex(2f).padding(start = 72.dp, top = 18.dp)) {
                    V2FloatingBackButton(onBack = onBack, contentDescription = ds("Back"))
                }

                // Full-bleed backdrop pinned behind everything
                Box(Modifier.fillMaxWidth().height(vpH).background(Brush.verticalGradient(listOf(gradTint, colors.shellBackground)))) {
                    heroBackdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                // Long gradient that fades into shell
                Box(
                    Modifier.fillMaxWidth().height(vpH).background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.20f to Color.Transparent,
                            0.45f to colors.shellBackground.copy(alpha = 0.50f),
                            0.65f to colors.shellBackground.copy(alpha = 0.82f),
                            1.0f to colors.shellBackground.copy(alpha = 0.96f),
                        ),
                    ),
                )

                Column(
                    Modifier.fillMaxSize().verticalScroll(scrollState),
                ) {
                    // ── Cinematic hero area ──
                    Box(Modifier.fillMaxWidth().height(vpH * 0.52f)) {
                        // Person identity anchored bottom-left
                        Row(
                            modifier = Modifier.align(Alignment.BottomStart)
                                .padding(start = 60.dp, bottom = 28.dp, end = 60.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            // Profile photo - elevated circle over backdrop
                            val photo = rememberCachedBitmap(state.profileUrl)
                            Box {
                                if (photo != null) {
                                    Image(
                                        photo, state.personName,
                                        Modifier.size(150.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        Modifier.size(150.dp).clip(CircleShape).background(colors.fieldSurface),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            state.personName.firstOrNull()?.uppercase() ?: "?",
                                            style = MaterialTheme.typography.displayMedium,
                                            fontWeight = FontWeight.Bold, color = colors.textSecondary,
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    state.personName,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                )
                                if (state.knownFor.isNotBlank()) {
                                    Surface(color = colors.accent.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            state.knownFor, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold, color = colors.accent,
                                        )
                                    }
                                }
                                // Credits count
                                val creditCount = state.credits.size
                                if (creditCount > 0) {
                                    Text(
                                        "$creditCount credits",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                    )
                                }
                            }
                        }
                    }

                    // ── Content below hero - continuous stage ──
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        // Biography with expand/collapse
                        if (state.biography.isNotBlank()) {
                            PersonBiography(biography = state.biography, colors = colors)
                        }

                        // Split credits into Acting vs Directing
                        val directingCredits = state.credits.filter { credit ->
                            credit.director?.equals(state.personName, ignoreCase = true) == true
                        }
                        val actingOnly = state.credits.filter { credit ->
                            credit.director?.equals(state.personName, ignoreCase = true) != true
                        }

                        if (actingOnly.isNotEmpty()) {
                            V2Shelf(title = ds("Acting"), modifier = Modifier.padding(start = 60.dp, end = 16.dp)) {
                                actingOnly.take(25).forEach { credit ->
                                    V2PosterCard(credit.title, credit.posterUrl, Modifier.width(160.dp), credit.year?.toString(), credit.rating?.let { String.format("%.1f", it) }, ratings = credit.ratings, backdropUrl = credit.backdropUrl, overview = credit.overview, onClick = { onOpenDetail(credit) })
                                }
                            }
                        }

                        if (directingCredits.isNotEmpty()) {
                            V2Shelf(title = ds("Directing"), modifier = Modifier.padding(start = 60.dp, end = 16.dp)) {
                                directingCredits.take(25).forEach { credit ->
                                    V2PosterCard(credit.title, credit.posterUrl, Modifier.width(160.dp), credit.year?.toString(), credit.rating?.let { String.format("%.1f", it) }, ratings = credit.ratings, backdropUrl = credit.backdropUrl, overview = credit.overview, onClick = { onOpenDetail(credit) })
                                }
                            }
                        }

                        // Fallback: show all if no clear split
                        if (actingOnly.isEmpty() && directingCredits.isEmpty() && state.credits.isNotEmpty()) {
                            V2Shelf(title = ds("Known For"), modifier = Modifier.padding(start = 60.dp, end = 16.dp)) {
                                state.credits.take(25).forEach { credit ->
                                    V2PosterCard(credit.title, credit.posterUrl, Modifier.width(160.dp), credit.year?.toString(), credit.rating?.let { String.format("%.1f", it) }, ratings = credit.ratings, backdropUrl = credit.backdropUrl, overview = credit.overview, onClick = { onOpenDetail(credit) })
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
}

@Composable
private fun PersonBiography(
    biography: String,
    colors: com.torve.desktop.ui.theme.TorveDesktopColors,
) {
    var expanded by remember { mutableStateOf(false) }
    val maxLines = if (expanded) Int.MAX_VALUE else 4

    Column(
        Modifier.padding(start = 60.dp, end = 60.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            biography,
            modifier = Modifier.animateContentSize(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (biography.length > 250) {
            Text(
                if (expanded) "Less" else "More",
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
