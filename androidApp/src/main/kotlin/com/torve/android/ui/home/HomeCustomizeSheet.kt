package com.torve.android.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.automirrored.rounded.FeaturedPlayList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Smoke
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCustomizeSheet(
    sections: List<HomeSectionConfig>,
    onReorder: (List<HomeSectionConfig>) -> Unit,
    onToggle: (HomeSection, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var orderedSections by remember {
        mutableStateOf(
            sections
                .filter { it.section != HomeSection.DIRECTORS && it.section != HomeSection.ON_NOW }
                .sortedBy { it.order }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Charcoal,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Steel) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_customize_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Snow,
                )
                TextButton(onClick = {
                    onReset()
                    orderedSections = HomeSection.entries
                        .filter { it != HomeSection.DIRECTORS && it != HomeSection.ON_NOW }
                        .map { HomeSectionConfig(it, it.defaultEnabled, it.defaultOrder) }
                        .sortedBy { it.order }
                }) {
                    Text(stringResource(R.string.common_reset), color = Amber)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Draggable section list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                itemsIndexed(
                    items = orderedSections,
                    key = { _, item -> item.section.name },
                ) { index, config ->
                    SectionConfigRow(
                        config = config,
                        isFirst = index == 0,
                        isLast = index == orderedSections.size - 1,
                        onToggle = { enabled ->
                            onToggle(config.section, enabled)
                            orderedSections = orderedSections.map {
                                if (it.section == config.section) it.copy(enabled = enabled) else it
                            }
                        },
                        onMoveUp = {
                            if (index > 1) { // Can't move above hero
                                val list = orderedSections.toMutableList()
                                val item = list.removeAt(index)
                                list.add(index - 1, item)
                                orderedSections = list.mapIndexed { i, it -> it.copy(order = i) }
                                onReorder(orderedSections)
                            }
                        },
                        onMoveDown = {
                            if (index < orderedSections.size - 1) {
                                val list = orderedSections.toMutableList()
                                val item = list.removeAt(index)
                                list.add(index + 1, item)
                                orderedSections = list.mapIndexed { i, it -> it.copy(order = i) }
                                onReorder(orderedSections)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Done button
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = Obsidian,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.home_customize_done), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionConfigRow(
    config: HomeSectionConfig,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Reorder buttons
        Column {
            IconButton(
                onClick = onMoveUp,
                modifier = Modifier.size(24.dp),
                enabled = !isFirst,
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.home_move_up),
                    tint = if (isFirst) Smoke else Silver,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onMoveDown,
                modifier = Modifier.size(24.dp),
                enabled = !isLast,
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.home_move_down),
                    tint = if (isLast) Smoke else Silver,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Section icon
        Icon(
            imageVector = config.section.icon(),
            contentDescription = null,
            tint = if (config.enabled) Amber else Smoke,
            modifier = Modifier.size(20.dp),
        )

        Spacer(Modifier.width(12.dp))

        // Section name
        Text(
            text = config.customTitle ?: config.section.defaultTitle,
            style = MaterialTheme.typography.bodyLarge,
            color = if (config.enabled) Snow else Ash,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )

        // Toggle
        Switch(
            checked = config.enabled,
            onCheckedChange = onToggle,
            enabled = !isFirst, // Hero can't be disabled
            colors = SwitchDefaults.colors(
                checkedThumbColor = Amber,
                checkedTrackColor = AmberSubtle,
                uncheckedThumbColor = Steel,
                uncheckedTrackColor = Gunmetal,
            ),
        )
    }
}

// Section icons
fun HomeSection.icon(): ImageVector = when (this) {
    HomeSection.SEARCH_BAR -> Icons.Rounded.Search
    HomeSection.HERO -> Icons.AutoMirrored.Rounded.FeaturedPlayList
    HomeSection.ON_NOW -> Icons.Rounded.Tv
    HomeSection.CONTINUE_WATCHING -> Icons.Rounded.PlayCircleOutline
    HomeSection.UPCOMING_SCHEDULE -> Icons.Rounded.NewReleases
    HomeSection.WATCHLIST -> Icons.Rounded.BookmarkBorder
    HomeSection.WATCHLIST_MOVIES -> Icons.Rounded.Theaters
    HomeSection.WATCHLIST_TV -> Icons.Rounded.Tv
    HomeSection.TRENDING_MOVIES -> Icons.AutoMirrored.Rounded.TrendingUp
    HomeSection.TRENDING_TV -> Icons.Rounded.Tv
    HomeSection.POPULAR_MOVIES -> Icons.Rounded.Star
    HomeSection.NOW_PLAYING -> Icons.Rounded.Theaters
    HomeSection.RECOMMENDED -> Icons.Rounded.AutoAwesome
    HomeSection.NEW_RELEASES -> Icons.Rounded.NewReleases
    HomeSection.TOP_RATED -> Icons.Rounded.EmojiEvents
    HomeSection.STREAMING_SERVICES -> Icons.Rounded.Subscriptions
    HomeSection.RECENTLY_WATCHED -> Icons.Rounded.History
    HomeSection.ACTORS -> Icons.Rounded.Star
    HomeSection.DIRECTORS -> Icons.Rounded.Theaters
    HomeSection.HIDDEN_GEMS -> Icons.Rounded.AutoAwesome
    HomeSection.ADDON_SHELVES -> Icons.Rounded.Subscriptions
    HomeSection.BECAUSE_YOU_WATCHED -> Icons.Rounded.History
    HomeSection.MDBLIST_SHELVES -> Icons.Rounded.Star
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Streaming Services Row — Omni-style brand cards
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** TMDB watch provider IDs for streaming services. */
val ALL_STREAMING_SERVICES = listOf(
    StreamingService("Netflix", Color(0xFFE50914), 8),
    StreamingService("Prime Video", Color(0xFF00A8E1), 9),
    StreamingService("Disney+", Color(0xFF113CCF), 337),
    StreamingService("Apple TV+", Color(0xFF000000), 350),
    StreamingService("HBO Max", Color(0xFF002BE7), 1899),
    StreamingService("Hulu", Color(0xFF1CE783), 15),
    StreamingService("Paramount+", Color(0xFF0064FF), 531),
    StreamingService("Peacock", Color(0xFF000000), 386),
    StreamingService("Crunchyroll", Color(0xFFF47521), 283),
    StreamingService("Mubi", Color(0xFF001C3C), 11),
    StreamingService("Starz", Color(0xFF000000), 43),
    StreamingService("BritBox", Color(0xFF053560), 380),
    StreamingService("Criterion", Color(0xFF000000), 258),
    StreamingService("Tubi", Color(0xFFF88500), 73),
    StreamingService("Pluto TV", Color(0xFF000033), 300),
    StreamingService("Curiosity Stream", Color(0xFF17A2B8), 190),
    StreamingService("Shudder", Color(0xFF000AFF), 439),
    StreamingService("WOW", Color(0xFF1F1F1F), 30),
    StreamingService("RTL+", Color(0xFFE3000F), 298),
    StreamingService("Joyn", Color(0xFF1AE5BE), 421),
    StreamingService("MagentaTV", Color(0xFFE20074), 551),
)

@Composable
fun StreamingServicesRow(
    services: List<StreamingService> = ALL_STREAMING_SERVICES,
    onProviderClick: (providerId: Int, providerName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (services.isEmpty()) return
    Column(modifier = modifier) {
        com.torve.android.ui.components.SectionHeader(
            title = stringResource(R.string.home_streaming_services),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(services) { service ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(240.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(135.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        onClick = { onProviderClick(service.tmdbProviderId, service.name) },
                    ) {
                        StreamingProviderBrandArtwork(
                            service = service,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.home_tmdb_credit),
            style = MaterialTheme.typography.labelSmall,
            color = Silver,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * A 16:9 provider treatment using a provider-specific canvas and wordmark.
 *
 * Square TMDB provider tiles are deliberately not used as fallbacks: their
 * baked-in boxes look inconsistent in a wide rail and stale metadata can show
 * the wrong brand. A failed remote wordmark becomes a deterministic wordmark
 * for that provider instead, so a card can never render empty.
 */
@Composable
fun StreamingProviderBrandArtwork(
    service: StreamingService,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val brand = remember(service.tmdbProviderId) {
        STREAMING_PROVIDER_BRANDS[service.tmdbProviderId]
            ?: StreamingProviderBrand(
                background = service.brandColor,
                fallbackForeground = Color.White,
            )
    }
    var artworkFailed by remember(service.tmdbProviderId, brand.artworkUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(brand.background)
            .border(1.dp, brand.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!artworkFailed && !brand.artworkUrl.isNullOrBlank()) {
            coil3.compose.AsyncImage(
                model = brand.artworkUrl,
                contentDescription = service.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(brand.artworkInset),
                contentScale = if (brand.fitArtwork) {
                    androidx.compose.ui.layout.ContentScale.Fit
                } else {
                    androidx.compose.ui.layout.ContentScale.Crop
                },
                colorFilter = brand.artworkTint?.let { ColorFilter.tint(it) },
                onError = { artworkFailed = true },
            )
        } else {
            Text(
                text = service.name,
                style = MaterialTheme.typography.headlineSmall,
                color = brand.fallbackForeground,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** Provider-specific, high-contrast title-card styles. */
private data class StreamingProviderBrand(
    val background: Color,
    val fallbackForeground: Color,
    val artworkUrl: String? = null,
    val artworkTint: Color? = null,
    val fitArtwork: Boolean = false,
    val artworkInset: androidx.compose.ui.unit.Dp = 0.dp,
    val border: Color = Color.White.copy(alpha = 0.16f),
)

private val STREAMING_PROVIDER_BRANDS = mapOf(
    8 to StreamingProviderBrand(
        background = Color(0xFFF5F5F1),
        fallbackForeground = Color(0xFFE50914),
        artworkUrl = "https://download.logo.wine/logo/Netflix/Netflix-Logo.wine.png",
    ),
    9 to StreamingProviderBrand(
        background = Color(0xFF0F79AF),
        fallbackForeground = Color.White,
        artworkUrl = "https://download.logo.wine/logo/Prime_Video/Prime_Video-Logo.wine.png",
        artworkTint = Color.White,
    ),
    337 to StreamingProviderBrand(
        background = Color(0xFF062B5C),
        fallbackForeground = Color.White,
        artworkUrl = "https://download.logo.wine/logo/Disney%2B/Disney%2B-Logo.wine.png",
        artworkTint = Color.White,
    ),
    350 to StreamingProviderBrand(
        background = Color.Black,
        fallbackForeground = Color.White,
        artworkUrl = "https://download.logo.wine/logo/Apple_TV/Apple_TV-Logo.wine.png",
        artworkTint = Color.White,
    ),
    1899 to StreamingProviderBrand(
        background = Color(0xFF081A31),
        fallbackForeground = Color.White,
        artworkUrl = "https://download.logo.wine/logo/HBO_Max/HBO_Max-Logo.wine.png",
        artworkTint = Color.White,
    ),
    15 to StreamingProviderBrand(
        background = Color(0xFF0B0C0C),
        fallbackForeground = Color(0xFF1CE783),
        artworkUrl = "https://download.logo.wine/logo/Hulu/Hulu-Logo.wine.png",
    ),
    531 to StreamingProviderBrand(
        background = Color(0xFF0064FF),
        fallbackForeground = Color.White,
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4e/Paramount%2B_logo.svg/1280px-Paramount%2B_logo.svg.png",
        artworkTint = Color.White,
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    386 to StreamingProviderBrand(
        background = Color(0xFFF4F4F4),
        fallbackForeground = Color.Black,
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/2/20/NBCUniversal_Peacock_Logo_%282026%29.svg/1280px-NBCUniversal_Peacock_Logo_%282026%29.svg.png",
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    283 to StreamingProviderBrand(
        background = Color(0xFFF5F5F5),
        fallbackForeground = Color(0xFFF47521),
        artworkUrl = "https://download.logo.wine/logo/Crunchyroll/Crunchyroll-Logo.wine.png",
    ),
    11 to StreamingProviderBrand(
        background = Color(0xFFF5F5F5),
        fallbackForeground = Color.Black,
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/d/dc/MUBI_Logo_Standard_Black.png/1280px-MUBI_Logo_Standard_Black.png",
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    43 to StreamingProviderBrand(
        background = Color.Black,
        fallbackForeground = Color.White,
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/03/Starz_2022.svg/1280px-Starz_2022.svg.png",
        artworkTint = Color.White,
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    380 to StreamingProviderBrand(
        background = Color(0xFF271C5B),
        fallbackForeground = Color.White,
        artworkUrl = "https://download.logo.wine/logo/BritBox/BritBox-Logo.wine.png",
        artworkTint = Color.White,
    ),
    258 to StreamingProviderBrand(
        background = Color(0xFFF4F1EA),
        fallbackForeground = Color.Black,
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1a/The_Criterion_Collection_logo_and_wordmark.svg/1280px-The_Criterion_Collection_logo_and_wordmark.svg.png",
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    73 to StreamingProviderBrand(
        background = Color(0xFFF5F5F5),
        fallbackForeground = Color(0xFF6F2CFF),
        artworkUrl = "https://download.logo.wine/logo/Tubi/Tubi-Logo.wine.png",
    ),
    300 to StreamingProviderBrand(
        background = Color(0xFF000033),
        fallbackForeground = Color.White,
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5d/Pluto_TV_logo.svg/1280px-Pluto_TV_logo.svg.png",
        artworkTint = Color.White,
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    190 to StreamingProviderBrand(
        background = Color(0xFF082B3B),
        fallbackForeground = Color.White,
        artworkUrl = "https://download.logo.wine/logo/CuriosityStream/CuriosityStream-Logo.wine.png",
        artworkTint = Color.White,
    ),
    439 to StreamingProviderBrand(
        background = Color.Black,
        fallbackForeground = Color(0xFFE41D2F),
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/51/Shudder_2017.svg/1280px-Shudder_2017.svg.png",
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    30 to StreamingProviderBrand(
        background = Color(0xFFF5F5F5),
        fallbackForeground = Color(0xFF061420),
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2d/WOW_Logo_2022.svg/1280px-WOW_Logo_2022.svg.png",
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    298 to StreamingProviderBrand(
        background = Color.Black,
        fallbackForeground = Color.White,
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f4/RTL%2B_Logo_2021.svg/1280px-RTL%2B_Logo_2021.svg.png",
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    421 to StreamingProviderBrand(
        background = Color(0xFFF5F5F5),
        fallbackForeground = Color(0xFF16132F),
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/7/7f/Joyn.jpg",
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
    551 to StreamingProviderBrand(
        background = Color(0xFFF5F5F5),
        fallbackForeground = Color(0xFFE20074),
        artworkUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e4/Magenta_TV_Logo_2024.svg/1280px-Magenta_TV_Logo_2024.svg.png",
        fitArtwork = true,
        artworkInset = 16.dp,
    ),
)

data class StreamingService(
    val name: String,
    val brandColor: Color,
    val tmdbProviderId: Int,
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Empty Section Hint — Placeholder for empty sections
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun EmptySectionHint(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(80.dp),
        shape = RoundedCornerShape(12.dp),
        color = Gunmetal.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = Smoke, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, color = Ash, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
