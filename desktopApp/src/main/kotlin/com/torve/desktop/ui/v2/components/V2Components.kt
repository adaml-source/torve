package com.torve.desktop.ui.v2.components

import androidx.compose.animation.core.animateFloatAsState
import com.torve.desktop.ui.l10n.ds
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.painterResource
import com.torve.desktop.ui.components.ImageBitmapCache
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.model.CardStyle
import com.torve.domain.model.CardTitlePosition
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingPillPosition
import com.torve.domain.model.RatingSource
import com.torve.domain.model.calculateTorveScore
import com.torve.domain.model.deriveProvidersToRender
import com.torve.domain.model.resolvedAspectRatio
import com.torve.domain.model.withFallbackTmdbScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URL
import kotlin.math.absoluteValue

// ── Image loading (reuses global LRU cache) ──────────────────────

@Composable
fun V2FloatingBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Back",
    size: Dp = 46.dp,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused = remember { androidx.compose.runtime.mutableStateOf(false) }
    val active = hovered || focused.value
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.97f
            active -> 1.045f
            else -> 1f
        },
        label = "v2FloatingBackScale",
    )
    val border by animateColorAsState(
        targetValue = when {
            focused.value -> colors.accent.copy(alpha = 0.78f)
            hovered -> colors.accent.copy(alpha = 0.56f)
            else -> Color.White.copy(alpha = 0.15f)
        },
        label = "v2FloatingBackBorder",
    )
    val glassAlpha by animateFloatAsState(
        targetValue = if (active) 0.76f else 0.58f,
        label = "v2FloatingBackGlass",
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (active) 22.dp else 12.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (active) 0.14f else 0.08f),
                        Color(0xFF101827).copy(alpha = glassAlpha),
                        Color(0xFF030711).copy(alpha = (glassAlpha + 0.08f).coerceAtMost(0.84f)),
                    ),
                ),
            )
            .border(1.dp, border, CircleShape)
            .hoverable(interaction)
            .onFocusChanged { focused.value = it.isFocused }
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 7.dp)
                .width(16.dp)
                .height(1.dp)
                .background(Color.White.copy(alpha = if (active) 0.32f else 0.18f)),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.93f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun rememberCachedBitmap(url: String?): ImageBitmap? {
    val imageUrl = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val cached = imageUrl?.let { ImageBitmapCache.get(it) }
    val state = produceState<ImageBitmap?>(initialValue = cached, imageUrl) {
        if (imageUrl == null) { value = null; return@produceState }
        val hit = ImageBitmapCache.get(imageUrl)
        if (hit != null) { value = hit; return@produceState }
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ImageBitmapCache.readDiskBytes(imageUrl)
                    ?: downloadImageBytes(imageUrl)?.also { ImageBitmapCache.writeDiskBytes(imageUrl, it) }
                if (bytes == null) return@runCatching null
                val bmp = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                ImageBitmapCache.put(imageUrl, bmp)
                bmp
            }.getOrNull()
        }
    }
    return state.value
}

/**
 * Shared HTTP/2 client for poster/backdrop/cast image fetches. Replaces
 * the older HttpURLConnection path: that opened a fresh TCP+TLS
 * connection per request and serialised handshakes, so loading the 32
 * images on a typical detail page (cast circles + Related rail +
 * backdrop + logo + episode posters) took 1-2 minutes on slow CDN
 * edges even with http.maxConnections=64.
 *
 * Java 11's HttpClient negotiates HTTP/2 on first contact and
 * multiplexes ALL subsequent image fetches on the same TCP+TLS
 * connection -- no per-request handshake. image.tmdb.org supports
 * HTTP/2, so a 32-image burst becomes one connection, one TLS
 * handshake, and 32 concurrent streams.
 */
private val imageHttpClient: java.net.http.HttpClient by lazy {
    java.net.http.HttpClient.newBuilder()
        .version(java.net.http.HttpClient.Version.HTTP_2)
        .connectTimeout(java.time.Duration.ofSeconds(8))
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .build()
}

/**
 * Pre-fetches a list of image URLs into the disk cache. Called by the
 * catalog top-cache worker after each genre's metadata refresh so the
 * disk image cache is warm before the user clicks any genre chip.
 *
 * Skips URLs that already have a disk entry (idempotent across runs).
 * No decode / no in-memory cache write — that happens lazily when the
 * grid actually renders the poster. Failures per URL are swallowed:
 * one stalled image must not block sibling downloads.
 */
suspend fun prefetchImagesToDisk(urls: List<String>) {
    if (urls.isEmpty()) return
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        urls.forEach { url ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
            // Already on disk? skip.
            if (ImageBitmapCache.readDiskBytes(url) != null) return@forEach
            runCatching {
                downloadImageBytes(url)?.also {
                    ImageBitmapCache.writeDiskBytes(url, it)
                }
            }
        }
    }
}

/**
 * Bounded HTTP/2 fetch for poster / backdrop / cast images. Returns
 * null on any failure / timeout so the caller's runCatching block
 * completes cleanly and the placeholder gradient remains visible
 * instead of blocking on a dead connection forever.
 */
private fun downloadImageBytes(imageUrl: String): ByteArray? = runCatching {
    val request = java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(imageUrl))
        .timeout(java.time.Duration.ofSeconds(15))
        .header("User-Agent", "Torve-Desktop/1.0")
        .GET()
        .build()
    val response = imageHttpClient.send(
        request,
        java.net.http.HttpResponse.BodyHandlers.ofByteArray(),
    )
    if (response.statusCode() in 200..299) response.body() else null
}.getOrNull()

// ── Color palette for artwork placeholders ────────────────────────

private data class ArtPalette(val primary: Color, val secondary: Color, val glow: Color)

@Composable
private fun artPalette(seed: String?): ArtPalette {
    val c = TorveDesktopThemeTokens.colors
    return when ((seed?.hashCode() ?: 0).absoluteValue % 4) {
        0 -> ArtPalette(c.accent.copy(alpha = 0.82f), Color(0xFF8C4A1E), c.accent.copy(alpha = 0.24f))
        1 -> ArtPalette(Color(0xFF355C9A), Color(0xFF171F39), c.info.copy(alpha = 0.24f))
        2 -> ArtPalette(Color(0xFF6B3D84), Color(0xFF241A39), Color(0xFFB281D3).copy(alpha = 0.22f))
        else -> ArtPalette(Color(0xFF3E6A63), Color(0xFF162A2A), c.success.copy(alpha = 0.18f))
    }
}

// ── Rating display ──────────────────────────────────────────────

val LocalRatingDisplayPrefs = compositionLocalOf { RatingDisplayPrefs() }

private val ImdbYellow = Color(0xFFF5C518)
private val RtFresh = Color(0xFFFA320A)
private val RtRotten = Color(0xFF6AC239)
private val TmdbGreen = Color(0xFF01D277)
private val MetacriticYellow = Color(0xFFFFCC34)
private val TorveAccent = Color(0xFFE8A838)
private val LetterboxdOrange = Color(0xFFFF8000)
private val TraktRed = Color(0xFFED1C24)
private val MdbBlue = Color(0xFF5B9BD5)
private val MalBlue = Color(0xFF2E51A2)

private fun ratingSourceColor(source: RatingSource): Color = when (source) {
    RatingSource.TORVE -> TorveAccent
    RatingSource.IMDB -> ImdbYellow
    RatingSource.ROTTEN_TOMATOES -> RtFresh
    RatingSource.RT_AUDIENCE -> RtRotten
    RatingSource.TMDB -> TmdbGreen
    RatingSource.METACRITIC -> MetacriticYellow
    RatingSource.LETTERBOXD -> LetterboxdOrange
    RatingSource.TRAKT -> TraktRed
    RatingSource.MDBLIST -> MdbBlue
    RatingSource.MAL -> MalBlue
}

private fun ratingValue(source: RatingSource, ratings: MediaRatings, torveWeights: Map<RatingSource, Int>): String? = when (source) {
    RatingSource.TORVE -> calculateTorveScore(ratings, torveWeights)?.let { String.format("%.0f", it) }
    RatingSource.IMDB -> ratings.imdbScore?.let { String.format("%.1f", it) }
    RatingSource.ROTTEN_TOMATOES -> ratings.rottenTomatoesScore?.let { "$it%" }
    RatingSource.RT_AUDIENCE -> ratings.rtAudienceScore?.let { "$it%" }
    RatingSource.TMDB -> ratings.tmdbScore?.let { String.format("%.1f", it) }
    RatingSource.METACRITIC -> ratings.metacriticScore?.let { "$it" }
    RatingSource.LETTERBOXD -> ratings.letterboxdScore?.let { String.format("%.1f", it) }
    RatingSource.TRAKT -> ratings.traktScore?.let { String.format("%.0f", it) }
    RatingSource.MDBLIST -> ratings.mdblistScore?.let { String.format("%.1f", it) }
    RatingSource.MAL -> ratings.malScore?.let { String.format("%.1f", it) }
}

fun ratingIconPath(source: RatingSource, ratings: MediaRatings?): String? = when (source) {
    RatingSource.IMDB -> "icons/ic_rating_imdb.png"
    RatingSource.ROTTEN_TOMATOES -> {
        val pct = ratings?.rottenTomatoesScore ?: 0
        when {
            pct >= 75 -> "icons/ic_rt_certified_fresh.png"
            pct >= 60 -> "icons/ic_rt_fresh.png"
            else -> "icons/ic_rt_rotten.png"
        }
    }
    RatingSource.RT_AUDIENCE -> {
        val pct = ratings?.rtAudienceScore ?: 0
        if (pct >= 60) "icons/ic_rt_audience_fresh.png" else "icons/ic_rt_audience_rotten.png"
    }
    RatingSource.TMDB -> "icons/tmbd_logo.png"
    RatingSource.METACRITIC -> "icons/ic_rating_metacritic.png"
    RatingSource.LETTERBOXD -> "icons/ic_rating_letterboxd.png"
    RatingSource.TRAKT -> "icons/ic_rating_trakt.png"
    RatingSource.MDBLIST -> "icons/ic_rating_mdblist.png"
    RatingSource.MAL -> "icons/ic_rating_mal.png"
    RatingSource.TORVE -> null // Uses text fallback
}

@Composable
fun DesktopRatingPills(
    ratings: MediaRatings?,
    modifier: Modifier = Modifier,
    prefs: RatingDisplayPrefs = LocalRatingDisplayPrefs.current,
    includeTorve: Boolean = false,
    showBackground: Boolean = true,
    visualScale: Float = 1f,
) {
    if (ratings == null) return
    val scale = visualScale.coerceIn(0.85f, 1.8f)
    val enabledProviders = if (includeTorve) prefs.enabledProviders else prefs.enabledProviders.filter { it != RatingSource.TORVE }
    val providers = deriveProvidersToRender(
        enabledProviders = enabledProviders,
        providerOrder = prefs.providerOrder,
        maxRatingsOnCard = prefs.maxRatingsOnCard,
    )
    var pills = providers.mapNotNull { source ->
        val value = ratingValue(source, ratings, prefs.torveWeights) ?: return@mapNotNull null
        Triple(source, value, ratingSourceColor(source))
    }
    // Fallback: if enabled providers produced nothing, show whatever data exists
    if (pills.isEmpty()) {
        pills = RatingSource.entries
            .filter { it != RatingSource.TORVE }
            .mapNotNull { source ->
                val value = ratingValue(source, ratings, prefs.torveWeights) ?: return@mapNotNull null
                Triple(source, value, ratingSourceColor(source))
            }
            .take(prefs.maxRatingsOnCard.coerceAtLeast(1))
    }
    if (pills.isEmpty()) return

    // Slimmer pill silhouette for the "premium" look: 60%-opacity charcoal
    // background, 6.dp corner radius (nearly capsule at this size),
    // 4.dp/1.dp padding, monospace-leaning label so digits align across
    // pills. Icon stays at 11dp so the pill height matches the source
    // icons crisply rather than dwarfing them.
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((3f * scale).dp), verticalAlignment = Alignment.CenterVertically) {
        pills.forEach { (source, value, color) ->
            val iconPath = ratingIconPath(source, ratings)
            if (showBackground) {
                Surface(color = Color(0x99000000), shape = RoundedCornerShape((6f * scale).dp)) {
                    Row(
                        Modifier.padding(horizontal = (4f * scale).dp, vertical = (1.5f * scale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((2f * scale).dp),
                    ) {
                        if (iconPath != null) {
                            Image(painter = painterResource(iconPath), contentDescription = source.displayName, modifier = Modifier.size((11f * scale).dp))
                        } else {
                            Text(source.iconChar, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
                        }
                        Text(
                            text = value,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = (10f * scale).sp, letterSpacing = 0.sp),
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE8E8EC),
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((2f * scale).dp),
                ) {
                    if (iconPath != null) {
                        Image(painter = painterResource(iconPath), contentDescription = source.displayName, modifier = Modifier.size((11f * scale).dp))
                    } else {
                        Text(source.iconChar, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
                    }
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (10f * scale).sp, letterSpacing = 0.sp),
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFB8B8C2),
                    )
                }
            }
        }
    }
}

// ── Poster card ──────────────────────────────────────────────────

@Composable
fun V2PosterCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    year: String? = null,
    rating: String? = null,
    ratings: MediaRatings? = null,
    progress: Float? = null,
    backdropUrl: String? = null,
    overview: String? = null,
    cardStyle: CardStyle = CardStyle(),
    onClick: () -> Unit = {},
) {
    val ratingPrefs = LocalRatingDisplayPrefs.current
    val colors = TorveDesktopThemeTokens.colors
    // When the card is rendered in landscape orientation (aspect > 1),
    // a portrait TMDB poster gets cropped to its top strip and looks
    // wrong. Swap to the backdrop as the primary artwork in that case;
    // fall back to the poster only when no backdrop exists. Portrait
    // cards keep their original behavior (poster primary, backdrop on
    // hover-preview).
    val cardAspect = cardStyle.size.resolvedAspectRatio()
    val isLandscape = cardAspect > 1f
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val primaryUrl = if (isLandscape) backdropUrl ?: imageUrl else imageUrl
    val secondaryUrl = if (isLandscape) imageUrl else backdropUrl
    val artwork = rememberCachedBitmap(primaryUrl)
    val backdrop = rememberCachedBitmap(secondaryUrl?.takeIf { hovered })
    val palette = artPalette(primaryUrl ?: title)
    val pri by animateColorAsState(palette.primary, label = "posterPri")
    val sec by animateColorAsState(palette.secondary, label = "posterSec")
    val hoverScaleTarget = if (hovered && cardStyle.hover.enabled)
        (cardStyle.hover.scalePercent / 100f).coerceIn(1f, 1.035f)
    else 1f
    val posterScale by animateFloatAsState(hoverScaleTarget, label = "posterScale")
    val cornerRadius = cardStyle.appearance.cornerRadiusDp.dp
    val aspect = cardAspect
    val showTitle = cardStyle.appearance.titlePosition != CardTitlePosition.HIDDEN
    // Crossfade fraction: 0 = poster, 1 = backdrop. Tracks hover and is
    // bypassed (stays at 0) when no backdrop is provided.
    val hoverFraction by animateFloatAsState(
        targetValue = if (hovered && backdrop != null) 1f else 0f,
        label = "posterHoverFade",
    )
    val outlineColor by animateColorAsState(
        if (hovered) colors.accent.copy(alpha = 0.72f) else colors.borderSubtle.copy(alpha = 0.35f),
        label = "posterOutline",
    )
    val titleColor by animateColorAsState(
        if (hovered) colors.accent else colors.textPrimary,
        label = "posterTitleColor",
    )

    val displayRatings = ratings.withFallbackTmdbScore(rating?.toDoubleOrNull())
    val hasRatings = displayRatings != null

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .hoverable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .zIndex(if (hovered) 1f else 0f)
                    .graphicsLayer {
                        scaleX = posterScale
                        scaleY = posterScale
                    }
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Brush.linearGradient(listOf(pri, sec))),
            ) {
                artwork?.let {
                    Image(
                        it,
                        null,
                        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - hoverFraction },
                        contentScale = ContentScale.Crop,
                    )
                }
                // Backdrop layered on top - invisible at rest, fades in on
                // hover. Only present when caller passed a backdropUrl.
                backdrop?.let {
                    Image(
                        it,
                        null,
                        Modifier.fillMaxSize().graphicsLayer { alpha = hoverFraction },
                        contentScale = ContentScale.Crop,
                    )
                }
                if (artwork == null && backdrop == null) {
                    Text(
                        title, Modifier.align(Alignment.BottomStart).padding(8.dp),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                        color = colors.textPrimary.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Bottom scrim + overview snippet - fades in with the
                // hover backdrop so the text is only visible while hovering.
                if (!overview.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .graphicsLayer { alpha = hoverFraction }
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                                ),
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = overview,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(cornerRadius),
                    border = androidx.compose.foundation.BorderStroke(1.dp, outlineColor),
                ) {}
                // Rating pills inside card (only when multi-provider data available)
                if (hasRatings && ratingPrefs.pillPosition == RatingPillPosition.INSIDE) {
                    DesktopRatingPills(
                        ratings = displayRatings,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                }
            }
            progress?.let {
                LinearProgressIndicator({ it.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
            if (showTitle) {
                Text(
                    title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Meta line: year + inline rating pills (outside) or year + rating text
                if (hasRatings && ratingPrefs.pillPosition == RatingPillPosition.OUTSIDE) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        year?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted) }
                        DesktopRatingPills(ratings = displayRatings, showBackground = false)
                    }
                } else {
                    val meta = listOfNotNull(year, rating).joinToString(" \u00B7 ")
                    if (meta.isNotBlank()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun Modifier.v2RailDrag(
    scrollState: androidx.compose.foundation.ScrollState,
): Modifier = pointerInput(scrollState) {
    detectHorizontalDragGestures { _, dragAmount ->
        if (scrollState.maxValue <= 0) return@detectHorizontalDragGestures
        scrollState.dispatchRawDelta(-dragAmount)
    }
}

// ── Shelf ────────────────────────────────────────────────────────

@Composable
fun V2Shelf(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                onSeeAll?.let {
                    Text(ds("See all"), style = MaterialTheme.typography.labelSmall, color = colors.accent,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = it,
                        ).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Surface(
                    modifier = Modifier.size(26.dp),
                    color = colors.fieldSurface.copy(alpha = 0.4f),
                    shape = CircleShape,
                    onClick = { scope.launch { scrollState.animateScrollTo((scrollState.value - 600).coerceAtLeast(0)) } },
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.ChevronLeft, "Left", Modifier.size(16.dp), tint = colors.textSecondary) }
                }
                Surface(
                    modifier = Modifier.size(26.dp),
                    color = colors.fieldSurface.copy(alpha = 0.4f),
                    shape = CircleShape,
                    onClick = { scope.launch { scrollState.animateScrollTo((scrollState.value + 600).coerceAtMost(scrollState.maxValue)) } },
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.ChevronRight, "Right", Modifier.size(16.dp), tint = colors.textSecondary) }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .v2RailDrag(scrollState)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.width(4.dp))
            content()
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .padding(end = 64.dp),
        )
    }
}

// ── Hero ─────────────────────────────────────────────────────────

@Composable
fun V2Hero(
    title: String,
    subtitle: String,
    backdropUrl: String?,
    modifier: Modifier = Modifier,
    logoUrl: String? = null,
    accentLabel: String? = null,
    metadata: List<String> = emptyList(),
    progress: Float? = null,
    overlap: Dp = 48.dp,
    topRight: (@Composable BoxScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = TorveDesktopThemeTokens.colors
    val palette = artPalette(backdropUrl ?: title)
    val pri by animateColorAsState(palette.primary, label = "heroPri")
    val sec by animateColorAsState(palette.secondary, label = "heroSec")
    val backdrop = rememberCachedBitmap(backdropUrl)
    val logo = rememberCachedBitmap(logoUrl)

    Box(
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val overlapPx = overlap.roundToPx()
            layout(placeable.width, (placeable.height - overlapPx).coerceAtLeast(0)) { placeable.placeRelative(0, 0) }
        },
    ) {
        Box(
            Modifier.fillMaxWidth().height(580.dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.linearGradient(listOf(pri, sec, colors.stageSurface))),
        ) {
            backdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Color.Transparent, Color.Transparent, colors.shellBackground.copy(alpha = 0.7f), colors.shellBackground.copy(alpha = 0.96f),
            ))))
            topRight?.let { Box(Modifier.align(Alignment.TopEnd).padding(14.dp), content = it) }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(0.62f).padding(horizontal = 36.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                accentLabel?.let {
                    Surface(color = colors.accent.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                        Text(it, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold, color = colors.accent)
                    }
                }
                if (metadata.isNotEmpty()) {
                    Text(metadata.joinToString(" \u00B7 "), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary.copy(alpha = 0.8f))
                }
                // Title treatment: stable layout, crossfade logo
                Box(Modifier.height(88.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                    Crossfade(logo, label = "heroTitle") { art ->
                        if (art != null) {
                            Image(bitmap = art, contentDescription = title, modifier = Modifier.height(88.dp).fillMaxWidth(), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                        } else {
                            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                progress?.let { LinearProgressIndicator({ it.coerceIn(0f, 1f) }, Modifier.fillMaxWidth(0.5f)) }
            }
        }
    }
}

// ── Atmosphere ────────────────────────────────────────────────────

@Composable
fun V2Atmosphere(seed: String?, modifier: Modifier = Modifier) {
    val palette = artPalette(seed)
    val glow by animateColorAsState(palette.glow, label = "atmoGlow")
    Box(modifier.background(Brush.radialGradient(listOf(glow, TorveDesktopThemeTokens.colors.shellGradientTop.copy(alpha = 0.12f), Color.Transparent), radius = 880f)))
}

// ── Backdrop luminance estimation (for adaptive nav rail) ────────

/**
 * Estimates the perceived brightness (0.0 = black, 1.0 = white) of the left
 * edge of a cached backdrop image. Samples a vertical strip of ~8 pixels on
 * the left side at evenly spaced rows. Returns a stable default (0.15 = dark)
 * when the image is not yet loaded.
 *
 * The result is computed off-main-thread and cached per URL.
 */
@Composable
fun rememberBackdropLuminance(url: String?): Float {
    val state = produceState(initialValue = 0.15f, url) {
        if (url == null) { value = 0.15f; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching { estimateLeftEdgeLuminance(url) }.getOrDefault(0.15f)
        }
    }
    return state.value
}

private fun estimateLeftEdgeLuminance(url: String): Float {
    val bitmap = ImageBitmapCache.get(url) ?: return 0.15f
    val w = bitmap.width
    val h = bitmap.height
    if (w == 0 || h == 0) return 0.15f

    // Sample ~8 pixels on the left strip (x = 0..~3% of width) at evenly spaced rows
    val sampleX = (w * 0.02f).toInt().coerceIn(0, w - 1)
    val sampleCount = 8
    val pixels = IntArray(1)
    var totalLum = 0.0

    for (i in 0 until sampleCount) {
        val y = ((h.toLong() * i) / sampleCount).toInt().coerceIn(0, h - 1)
        bitmap.readPixels(pixels, startX = sampleX, startY = y, width = 1, height = 1)
        val argb = pixels[0]
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        // Rec. 709 perceived luminance
        totalLum += 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    return (totalLum / (sampleCount * 255.0)).toFloat().coerceIn(0f, 1f)
}
