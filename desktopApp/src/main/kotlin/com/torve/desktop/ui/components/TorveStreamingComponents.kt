package com.torve.desktop.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URL
import kotlin.math.absoluteValue

private data class TorveArtworkPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val glow: Color,
)

@Composable
private fun torveArtworkPalette(
    seed: String?,
): TorveArtworkPalette {
    val colors = TorveDesktopThemeTokens.colors
    val variant = (seed?.hashCode() ?: 0).absoluteValue % 4
    return when (variant) {
        0 -> TorveArtworkPalette(
            primary = colors.accent.copy(alpha = 0.82f),
            secondary = Color(0xFF8C4A1E),
            tertiary = colors.stageSurface,
            glow = colors.accent.copy(alpha = 0.24f),
        )

        1 -> TorveArtworkPalette(
            primary = Color(0xFF355C9A),
            secondary = Color(0xFF171F39),
            tertiary = colors.stageSurface,
            glow = colors.info.copy(alpha = 0.24f),
        )

        2 -> TorveArtworkPalette(
            primary = Color(0xFF6B3D84),
            secondary = Color(0xFF241A39),
            tertiary = colors.stageSurface,
            glow = Color(0xFFB281D3).copy(alpha = 0.22f),
        )

        else -> TorveArtworkPalette(
            primary = Color(0xFF3E6A63),
            secondary = Color(0xFF162A2A),
            tertiary = colors.stageSurface,
            glow = colors.success.copy(alpha = 0.18f),
        )
    }
}

object ImageBitmapCache {
    private const val MAX_ENTRIES = 1500
    private const val MAX_DISK_BYTES = 500L * 1024L * 1024L // 500 MB cap
    private val cache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private val diskDir: java.io.File by lazy {
        java.io.File(com.torve.desktop.platform.desktopDataDir(), "image-cache").apply {
            runCatching { if (!exists()) mkdirs() }
        }
    }

    @Synchronized
    fun get(url: String): ImageBitmap? = cache[url]

    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        cache[url] = bitmap
    }

    private fun diskFileFor(url: String): java.io.File {
        val hash = url.hashCode().toLong() and 0xFFFFFFFFL
        // Include a short suffix of the URL tail to reduce hash collisions.
        val tail = url.takeLast(16).replace(Regex("[^a-zA-Z0-9]"), "_")
        return java.io.File(diskDir, "${hash}_${tail}.bin")
    }

    /** Returns cached bytes from disk, or null. */
    fun readDiskBytes(url: String): ByteArray? {
        return runCatching {
            val f = diskFileFor(url)
            if (f.exists() && f.length() > 0) {
                runCatching { f.setLastModified(System.currentTimeMillis()) }
                f.readBytes()
            } else {
                null
            }
        }.getOrNull()
    }

    /** Persists bytes to disk and opportunistically trims the cache if oversized. */
    fun writeDiskBytes(url: String, bytes: ByteArray) {
        runCatching {
            val f = diskFileFor(url)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        runCatching { trimDiskIfNeeded() }
    }

    private fun trimDiskIfNeeded() {
        val files = diskDir.listFiles()?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        // Evict oldest-accessed files until under cap.
        val ordered = files.sortedBy { it.lastModified() }
        for (f in ordered) {
            if (total <= MAX_DISK_BYTES) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }
}

@Composable
private fun rememberArtworkBitmap(
    candidate: String?,
): ImageBitmap? {
    val imageUrl = candidate?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val cached = imageUrl?.let { ImageBitmapCache.get(it) }
    val imageState = produceState<ImageBitmap?>(initialValue = cached, imageUrl) {
        if (imageUrl == null) {
            value = null
        } else {
            val hit = ImageBitmapCache.get(imageUrl)
            if (hit != null) {
                value = hit
            } else {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ImageBitmapCache.readDiskBytes(imageUrl)
                            ?: URL(imageUrl).readBytes().also { ImageBitmapCache.writeDiskBytes(imageUrl, it) }
                        val bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                        ImageBitmapCache.put(imageUrl, bitmap)
                        bitmap
                    }.getOrNull()
                }
            }
        }
    }
    return imageState.value
}

@Composable
fun TorveAtmosphereBackdrop(
    seed: String?,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val palette = torveArtworkPalette(seed)
    val glow by animateColorAsState(palette.glow, label = "torveAtmosphereGlow")
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colors = listOf(
                    glow,
                    colors.shellGradientTop.copy(alpha = 0.12f),
                    Color.Transparent,
                ),
                radius = 880f,
            ),
        ),
    )
}

@Composable
fun TorveShelfHeader(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            supportingText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun TorveSoftContentBand(
    modifier: Modifier = Modifier,
    title: String? = null,
    supportingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    Surface(
        modifier = modifier,
        color = colors.elevatedSurface.copy(alpha = 0.34f),
        shape = RoundedCornerShape(radii.xl),
        border = BorderStroke(1.dp, colors.borderSubtle.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!title.isNullOrBlank() || trailing != null) {
                TorveShelfHeader(
                    title = title.orEmpty(),
                    supportingText = supportingText,
                    trailing = trailing,
                )
            }
            content()
        }
    }
}

@Composable
fun TorveBackdropHero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    seed: String? = null,
    titleArtUrl: String? = null,
    accentLabel: String? = null,
    metadata: List<String> = emptyList(),
    progress: Float? = null,
    topLeft: (@Composable BoxScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val palette = torveArtworkPalette(seed ?: title)
    val primary by animateColorAsState(palette.primary, label = "torveHeroPrimary")
    val secondary by animateColorAsState(palette.secondary, label = "torveHeroSecondary")
    val tertiary by animateColorAsState(palette.tertiary, label = "torveHeroTertiary")
    val artwork = rememberArtworkBitmap(seed)
    val titleArt = rememberArtworkBitmap(titleArtUrl)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(580.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(primary, secondary, tertiary),
                ),
            ),
    ) {
        artwork?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            colors.shellBackground.copy(alpha = 0.72f),
                            colors.shellBackground.copy(alpha = 0.96f),
                        ),
                    ),
                ),
        )
        topLeft?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(22.dp),
                content = it,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.66f)
                .padding(horizontal = 40.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            accentLabel?.let {
                TorveBadge(
                    text = it,
                    tone = TorveBadgeTone.Accent,
                )
            }
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata.joinToString(" \u00B7 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary.copy(alpha = 0.8f),
                )
            }
            TorveTitleTreatment(
                title = title,
                titleArtUrl = titleArtUrl,
                loadedTitleArt = titleArt,
                fallbackStyle = MaterialTheme.typography.displayMedium,
                maxHeight = 96.dp,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            supportingContent?.invoke(this)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.54f),
                )
            }
        }
    }
}

@Composable
fun TorvePosterCardLarge(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    seed: String? = null,
    titleArtUrl: String? = null,
    accentLabel: String? = null,
    metadata: List<String> = emptyList(),
    progress: Float? = null,
    selected: Boolean = false,
    primaryActionLabel: String = "Play",
    onSelect: () -> Unit,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .clickable(onClick = onSelect),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TorveArtworkCard(
                title = title,
                seed = seed ?: title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(radii.lg)),
            )
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata.take(3).joinToString(" \u00B7 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun TorvePosterCardCompact(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    seed: String? = null,
    titleArtUrl: String? = null,
    accentLabel: String? = null,
    metadata: List<String> = emptyList(),
    selected: Boolean = false,
    primaryActionLabel: String = "Play",
    onSelect: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .clickable(onClick = onSelect),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TorveArtworkCard(
                title = title,
                seed = seed ?: title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(radii.lg)),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TorveLandscapeFeatureCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    seed: String? = null,
    titleArtUrl: String? = null,
    accentLabel: String? = null,
    metadata: List<String> = emptyList(),
    progress: Float? = null,
    selected: Boolean = false,
    primaryActionLabel: String = "Play",
    onSelect: () -> Unit,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .clickable(onClick = onSelect),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TorveArtworkCard(
                title = title,
                seed = seed ?: title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(radii.lg)),
            )
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TorvePreviewSheet(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    seed: String? = null,
    titleArtUrl: String? = null,
    accentLabel: String? = null,
    metadata: List<String> = emptyList(),
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    Surface(
        modifier = modifier,
        color = colors.drawerSurface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(radii.xl),
        border = BorderStroke(1.dp, colors.borderSubtle.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TorveArtworkCard(
                title = title,
                seed = seed ?: title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(248.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TorveTitleTreatment(
                    title = title,
                    titleArtUrl = titleArtUrl,
                    fallbackStyle = MaterialTheme.typography.headlineMedium,
                    maxHeight = 64.dp,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
            content()
        }
    }
}

@Composable
fun TorveSearchResultMediaRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    seed: String? = null,
    titleArtUrl: String? = null,
    selected: Boolean = false,
    accentLabel: String? = null,
    metadata: List<String> = emptyList(),
    primaryActionLabel: String,
    onSelect: () -> Unit,
    onPrimaryAction: () -> Unit,
    onChooseSource: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val container by animateColorAsState(
        targetValue = if (selected) colors.accentContainer.copy(alpha = 0.28f) else Color.Transparent,
        label = "torveSearchRowContainer",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.lg))
            .clickable(onClick = onSelect),
        color = container,
        shape = RoundedCornerShape(radii.lg),
        border = BorderStroke(
            1.dp,
            if (selected) colors.accent.copy(alpha = 0.32f) else colors.borderSubtle.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveArtworkCard(
                title = title,
                seed = seed ?: title,
                modifier = Modifier
                    .width(90.dp)
                    .height(126.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metadata.joinToString(" \u00B7 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TorveGhostButton(
                            text = "Details",
                            onClick = onOpenDetails,
                        )
                        TorveSecondaryButton(
                            text = primaryActionLabel,
                            onClick = onPrimaryAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TorveArtworkCard(
    title: String,
    seed: String,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val palette = torveArtworkPalette(seed)
    val primary by animateColorAsState(palette.primary, label = "torveArtworkPrimary")
    val secondary by animateColorAsState(palette.secondary, label = "torveArtworkSecondary")
    val artwork = rememberArtworkBitmap(seed)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radii.lg))
            .background(
                Brush.linearGradient(
                    colors = listOf(primary, secondary),
                ),
            ),
    ) {
        artwork?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (artwork == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                colors.shellBackground.copy(alpha = 0.48f),
                            ),
                        ),
                    ),
            )
            Text(
                text = title,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TorveTitleTreatment(
    title: String,
    titleArtUrl: String?,
    fallbackStyle: TextStyle,
    maxHeight: androidx.compose.ui.unit.Dp,
    loadedTitleArt: ImageBitmap? = rememberArtworkBitmap(titleArtUrl),
) {
    Box(
        modifier = Modifier
            .height(maxHeight)
            .fillMaxWidth(0.78f),
        contentAlignment = Alignment.BottomStart,
    ) {
        Crossfade(targetState = loadedTitleArt, label = "titleTreatment") { art ->
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = title,
                    modifier = Modifier
                        .height(maxHeight)
                        .fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                )
            } else {
                Text(
                    text = title,
                    style = fallbackStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
