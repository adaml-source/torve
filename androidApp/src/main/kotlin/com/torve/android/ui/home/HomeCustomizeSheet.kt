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
    StreamingService("Max", Color(0xFF002BE7), 1899),
    StreamingService("Hulu", Color(0xFF1CE783), 15),
    StreamingService("Paramount+", Color(0xFF0064FF), 531),
    StreamingService("Peacock", Color(0xFF000000), 386),
    StreamingService("Crunchyroll", Color(0xFFF47521), 283),
    StreamingService("Mubi", Color(0xFF001C3C), 11),
    StreamingService("Starz", Color(0xFF000000), 43),
    StreamingService("Showtime", Color(0xFFFF0000), 37),
    StreamingService("BritBox", Color(0xFF053560), 380),
    StreamingService("Criterion", Color(0xFF000000), 258),
    StreamingService("Tubi", Color(0xFFF88500), 73),
    StreamingService("Pluto TV", Color(0xFF000033), 300),
    StreamingService("Freevee", Color(0xFF39B54A), 613),
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
    providerLogos: Map<Int, String> = emptyMap(),
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
                val logoUrl = providerLogos[service.tmdbProviderId]
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
                            logoUrl = logoUrl,
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
 * A 16:9 Torve treatment for official provider artwork.
 *
 * TMDB provider marks are requested at original resolution and cropped
 * edge-to-edge into the 16:9 frame. No separate square icon or text treatment
 * is layered over the artwork.
 */
@Composable
fun StreamingProviderBrandArtwork(
    service: StreamingService,
    logoUrl: String?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val tmdbFallbackUrl = remember(logoUrl) {
        logoUrl
            ?.replace(Regex("/w\\d+/"), "/original/")
            ?.replace(Regex("/h\\d+/"), "/original/")
    }
    val wideWordmarkUrl = remember(service.tmdbProviderId) {
        STREAMING_PROVIDER_WIDE_PNGS[service.tmdbProviderId]
    }
    var wideWordmarkFailed by remember(wideWordmarkUrl, tmdbFallbackUrl) { mutableStateOf(false) }
    val artworkUrl = if (!wideWordmarkFailed) {
        wideWordmarkUrl ?: tmdbFallbackUrl
    } else {
        tmdbFallbackUrl
    }
    val usesWideWordmark = artworkUrl != null && artworkUrl == wideWordmarkUrl
    Box(
        modifier = modifier
            .clip(shape)
            // Keep every provider on the same quiet neutral canvas. The
            // sourced PNG contains the branding; competing gradients reduce
            // wordmark contrast and make the rail look inconsistent.
            .background(Color(0xFFE6E6E8))
            .border(1.dp, Color.Black.copy(alpha = 0.16f), shape),
    ) {
        if (!artworkUrl.isNullOrBlank()) {
            coil3.compose.AsyncImage(
                model = artworkUrl,
                contentDescription = service.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (usesWideWordmark) 0.dp else 34.dp,
                        vertical = if (usesWideWordmark) 0.dp else 22.dp,
                    ),
                contentScale = if (usesWideWordmark) {
                    // The sourced transparent PNG canvases are already wide;
                    // crop only their empty top/bottom margin into the 16:9
                    // frame. The wordmark itself is never stretched.
                    androidx.compose.ui.layout.ContentScale.Crop
                } else {
                    // A square TMDB mark is a last-resort fallback. Keep it
                    // entirely visible and never zoom it.
                    androidx.compose.ui.layout.ContentScale.Fit
                },
                onError = {
                    if (usesWideWordmark && !tmdbFallbackUrl.isNullOrBlank()) {
                        wideWordmarkFailed = true
                    }
                },
            )
        }
    }
}

/**
 * High-resolution transparent PNG wordmarks used as TV title art.
 *
 * These are deliberately separate from TMDB's square watch-provider icons.
 * Each image is rendered over Torve's 16:9 brand canvas at its native aspect
 * ratio. Unsupported regional services retain the un-cropped TMDB fallback.
 */
private val STREAMING_PROVIDER_WIDE_PNGS = mapOf(
    8 to "https://download.logo.wine/logo/Netflix/Netflix-Logo.wine.png",
    9 to "https://download.logo.wine/logo/Prime_Video/Prime_Video-Logo.wine.png",
    337 to "https://download.logo.wine/logo/Disney%2B/Disney%2B-Logo.wine.png",
    350 to "https://download.logo.wine/logo/Apple_TV/Apple_TV-Logo.wine.png",
    1899 to "https://download.logo.wine/logo/HBO_Max/HBO_Max-Logo.wine.png",
    15 to "https://download.logo.wine/logo/Hulu/Hulu-Logo.wine.png",
    531 to "https://download.logo.wine/logo/Paramount%2B/Paramount%2B-Logo.wine.png",
    386 to "https://download.logo.wine/logo/Peacock_(streaming_service)/Peacock_(streaming_service)-Logo.wine.png",
    283 to "https://download.logo.wine/logo/Crunchyroll/Crunchyroll-Logo.wine.png",
    11 to "https://download.logo.wine/logo/Mubi_(streaming_service)/Mubi_(streaming_service)-Logo.wine.png",
    43 to "https://download.logo.wine/logo/Starz/Starz-Logo.wine.png",
    37 to "https://download.logo.wine/logo/Showtime_(TV_network)/Showtime_(TV_network)-Logo.wine.png",
    380 to "https://download.logo.wine/logo/BritBox/BritBox-Logo.wine.png",
    258 to "https://download.logo.wine/logo/The_Criterion_Collection/The_Criterion_Collection-Logo.wine.png",
    73 to "https://download.logo.wine/logo/Tubi/Tubi-Logo.wine.png",
    300 to "https://download.logo.wine/logo/Pluto_TV/Pluto_TV-Logo.wine.png",
    613 to "https://download.logo.wine/logo/Amazon_Freevee/Amazon_Freevee-Logo.wine.png",
    190 to "https://download.logo.wine/logo/CuriosityStream/CuriosityStream-Logo.wine.png",
    439 to "https://download.logo.wine/logo/Shudder_(streaming_service)/Shudder_(streaming_service)-Logo.wine.png",
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
