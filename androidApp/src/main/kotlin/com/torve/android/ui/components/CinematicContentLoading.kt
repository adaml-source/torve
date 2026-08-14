package com.torve.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import org.koin.compose.koinInject

object ContentLaunchArtworkStore {
    var current by mutableStateOf<MediaItem?>(null)
        private set

    fun show(item: MediaItem) {
        current = item
    }

    fun update(item: MediaItem) {
        current = current?.let { existing ->
            item.copy(
                backdropUrl = item.backdropUrl ?: existing.backdropUrl,
                posterUrl = item.posterUrl ?: existing.posterUrl,
                logoUrl = item.logoUrl ?: existing.logoUrl,
            )
        } ?: item
    }

    fun clear() {
        current = null
    }
}

@Composable
fun CinematicContentLoading(
    title: String,
    backdropUrl: String?,
    posterUrl: String?,
    logoUrl: String? = null,
    tmdbId: Int? = null,
    mediaType: MediaType? = null,
    modifier: Modifier = Modifier,
) {
    val metadataRepository: MetadataRepository = koinInject()
    var fetchedLogoUrl by remember(tmdbId, mediaType) { mutableStateOf<String?>(null) }
    var logoLookupComplete by remember(tmdbId, mediaType) { mutableStateOf(tmdbId == null) }
    LaunchedEffect(tmdbId, mediaType, logoUrl) {
        if (!logoUrl.isNullOrBlank() || tmdbId == null) {
            logoLookupComplete = true
            return@LaunchedEffect
        }
        logoLookupComplete = false
        fetchedLogoUrl = runCatching {
            metadataRepository.getLogoUrl(
                type = if (mediaType == MediaType.SERIES) "tv" else "movie",
                tmdbId = tmdbId,
            )
        }.getOrNull()
        logoLookupComplete = true
    }
    val effectiveLogoUrl = logoUrl?.takeIf { it.isNotBlank() } ?: fetchedLogoUrl
    val transition = rememberInfiniteTransition(label = "cinematicContentLoading")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cinematicContentLoadingPulse",
    )
    val artwork = backdropUrl?.takeIf { it.isNotBlank() }
        ?: posterUrl?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        artwork?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.36f),
                            Color.Black.copy(alpha = 0.52f),
                            Color.Black.copy(alpha = 0.84f),
                        ),
                    ),
                ),
        )
        if (!effectiveLogoUrl.isNullOrBlank()) {
            AsyncImage(
                model = effectiveLogoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .heightIn(max = 150.dp)
                    .padding(horizontal = 24.dp)
                    .alpha(pulseAlpha),
            )
        } else if (logoLookupComplete) {
            Text(
                text = title.ifBlank { "Loading" },
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .padding(horizontal = 24.dp)
                    .alpha(pulseAlpha),
            )
        }
    }
}
