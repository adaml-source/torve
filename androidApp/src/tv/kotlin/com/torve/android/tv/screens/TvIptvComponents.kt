package com.torve.android.tv.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Badge1080p
import com.torve.android.ui.theme.Badge4K
import com.torve.android.ui.theme.Badge720p
import com.torve.android.ui.theme.BadgeSD
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.presentation.channels.ChannelsFilterType
import com.torve.presentation.channels.ChannelsSortType
import java.util.Calendar
import java.util.Locale

@Composable
internal fun TvIptvControlChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) Amber.copy(alpha = 0.22f) else Charcoal.copy(alpha = 0.5f)
    val borderColor by animateColorAsState(
        targetValue = if (focused) Amber else Color.Transparent,
        label = "controlChipBorder",
    )
    Text(
        text = label,
        color = if (focused) Snow else Silver,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

internal fun filterLabel(filter: ChannelsFilterType): String = when (filter) {
    ChannelsFilterType.ALL -> "All"
    ChannelsFilterType.HD -> "HD"
    ChannelsFilterType.FHD -> "FHD"
    ChannelsFilterType.UHD -> "UHD"
    ChannelsFilterType.FAVORITES -> "Favorites"
}

internal fun sortLabel(sort: ChannelsSortType): String = when (sort) {
    ChannelsSortType.DEFAULT -> "Default"
    ChannelsSortType.NAME_AZ -> "A-Z"
    ChannelsSortType.NAME_ZA -> "Z-A"
    ChannelsSortType.RECENTLY_ADDED -> "Recent"
}

internal fun nextFilter(filter: ChannelsFilterType): ChannelsFilterType = when (filter) {
    ChannelsFilterType.ALL -> ChannelsFilterType.HD
    ChannelsFilterType.HD -> ChannelsFilterType.FHD
    ChannelsFilterType.FHD -> ChannelsFilterType.UHD
    ChannelsFilterType.UHD -> ChannelsFilterType.FAVORITES
    ChannelsFilterType.FAVORITES -> ChannelsFilterType.ALL
}

internal fun nextSort(sort: ChannelsSortType): ChannelsSortType = when (sort) {
    ChannelsSortType.DEFAULT -> ChannelsSortType.NAME_AZ
    ChannelsSortType.NAME_AZ -> ChannelsSortType.NAME_ZA
    ChannelsSortType.NAME_ZA -> ChannelsSortType.RECENTLY_ADDED
    ChannelsSortType.RECENTLY_ADDED -> ChannelsSortType.DEFAULT
}

internal fun channelMatchesCountryFilter(
    channel: Channel,
    selectedCountries: Set<String>,
): Boolean {
    if (selectedCountries.isEmpty()) return true
    val selectedLower = selectedCountries.map { it.lowercase() }.toSet()
    val country = channel.tvgCountry ?: return false
    val channelCountries = country
        .split(",", ";")
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
    return channelCountries.any { it in selectedLower }
}

internal fun countryGroupKey(country: String): String {
    val first = country.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first.isLetter()) first.toString() else "#"
}

internal fun countrySearchText(country: String): String {
    val normalized = country.trim()
    if (normalized.isBlank()) return ""
    val upper = normalized.uppercase()
    val resolved = resolveCountryName(normalized)?.lowercase().orEmpty()
    return "$normalized $upper $resolved".lowercase()
}

internal fun resolveCountryName(country: String): String? {
    val normalized = country.trim()
    val upper = normalized.uppercase()
    return when {
        upper.length == 2 && upper.all { it.isLetter() } -> {
            val locale = Locale("", upper)
            locale.getDisplayCountry(Locale.ENGLISH)
                .trim()
                .takeIf { it.isNotBlank() && !it.equals(upper, ignoreCase = true) }
        }

        upper.length == 3 && upper.all { it.isLetter() } -> {
            val iso2 = ISO3_TO_ISO2[upper] ?: return null
            val locale = Locale("", iso2)
            locale.getDisplayCountry(Locale.ENGLISH)
                .trim()
                .takeIf { it.isNotBlank() && !it.equals(iso2, ignoreCase = true) }
        }

        else -> null
    }
}

internal fun countryPrimaryLabel(country: String): String {
    val normalized = country.trim()
    return if (normalized.length in 2..3 && normalized.all { it.isLetter() }) {
        normalized.uppercase()
    } else {
        normalized
    }
}

internal val ISO3_TO_ISO2: Map<String, String> = Locale.getISOCountries()
    .mapNotNull { iso2 ->
        val iso3 = runCatching { Locale("", iso2).isO3Country.uppercase() }.getOrNull()
        if (iso3.isNullOrBlank()) null else (iso3 to iso2)
    }
    .toMap()

internal fun alignedHalfHour(now: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

internal fun consumeSearchInput(
    event: KeyEvent,
    query: String,
    onQueryChange: (String) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Backspace -> {
            if (query.isNotEmpty()) {
                onQueryChange(query.dropLast(1))
                true
            } else {
                false
            }
        }

        Key.Spacebar -> {
            onQueryChange("$query ")
            true
        }

        else -> {
            val keyCode = event.key.keyCode.toInt()
            val unicode = android.view.KeyCharacterMap
                .load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .get(keyCode, 0)
            if (unicode > 0) {
                val c = unicode.toChar()
                if (!c.isISOControl()) {
                    onQueryChange("$query$c")
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }
}

@Composable
internal fun TvCountryFilterOverlay(
    availableCountries: List<String>,
    selectedCountries: Set<String>,
    onToggleCountry: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSetCountries: (Set<String>) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var countrySearchQuery by rememberSaveable { mutableStateOf("") }
    val firstItemFocus = remember { FocusRequester() }
    val filteredCountries = remember(availableCountries, countrySearchQuery) {
        val query = countrySearchQuery.trim()
        if (query.isBlank()) {
            availableCountries
        } else {
            availableCountries.filter { countrySearchText(it).contains(query, ignoreCase = true) }
        }
    }
    val groupedCountries = remember(filteredCountries) {
        filteredCountries
            .groupBy { countryGroupKey(it) }
            .toSortedMap()
            .entries
            .toList()
    }
    LaunchedEffect(groupedCountries) {
        if (groupedCountries.isNotEmpty()) {
            try { firstItemFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.94f))
            .padding(horizontal = 52.dp, vertical = 34.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Charcoal.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .onPreviewKeyEvent { event ->
                    consumeSearchInput(
                        event = event,
                        query = countrySearchQuery,
                        onQueryChange = { countrySearchQuery = it },
                    )
                }
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.tv_iptv_country_filter_title),
                style = MaterialTheme.typography.titleLarge,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvIptvControlChip(
                    label = stringResource(R.string.tv_iptv_country_select_all),
                    onClick = onSelectAll,
                )
                TvIptvControlChip(
                    label = stringResource(R.string.tv_iptv_country_clear_all),
                    onClick = onClearAll,
                )
                TvIptvControlChip(
                    label = stringResource(R.string.tv_iptv_country_include_visible),
                    onClick = {
                        if (filteredCountries.isNotEmpty()) {
                            onSetCountries(filteredCountries.toSet())
                        }
                    },
                )
                TvIptvControlChip(
                    label = stringResource(R.string.common_done),
                    onClick = onDismiss,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvIptvControlChip(
                    label = if (countrySearchQuery.isBlank()) {
                        stringResource(R.string.tv_iptv_country_search_prompt)
                    } else {
                        stringResource(R.string.tv_iptv_country_search_query, countrySearchQuery)
                    },
                    onClick = { countrySearchQuery = "" },
                )
                Text(
                    text = "${filteredCountries.size}/${availableCountries.size}",
                    color = Silver,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            if (groupedCountries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.tv_iptv_country_no_results),
                        color = Silver,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    groupedCountries.forEachIndexed { groupIndex, (groupKey, countries) ->
                        item(key = "country_group_$groupKey") {
                            Text(
                                text = groupKey,
                                color = Ash,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }

                        itemsIndexed(countries, key = { _, code -> "country_${groupKey}_$code" }) { index, code ->
                            val selected = code in selectedCountries
                            var focused by remember { mutableStateOf(false) }
                            val countryBorderColor by animateColorAsState(
                                targetValue = when {
                                    focused -> Amber
                                    selected -> Amber.copy(alpha = 0.45f)
                                    else -> Color.Transparent
                                },
                                label = "countryBorder",
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (groupIndex == 0 && index == 0) {
                                            Modifier.focusRequester(firstItemFocus)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .onFocusChanged { focused = it.isFocused }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onToggleCountry(code) },
                                    )
                                    .border(
                                        2.dp,
                                        countryBorderColor,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            focused -> Amber.copy(alpha = 0.2f)
                                            selected -> AmberSubtle
                                            else -> Graphite.copy(alpha = 0.5f)
                                        },
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val countryName = resolveCountryName(code)
                                Text(
                                    text = countryPrimaryLabel(code),
                                    color = if (selected || focused) Snow else Silver,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (!countryName.isNullOrBlank()) {
                                    Text(
                                        text = countryName,
                                        color = Ash,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                Text(
                                    text = if (selected) {
                                        stringResource(R.string.tv_iptv_country_included)
                                    } else {
                                        stringResource(R.string.tv_iptv_country_filtered)
                                    },
                                    color = if (selected) Amber else Silver,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CountryBadge(code: String, size: Int = 24) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(Amber.copy(alpha = 0.2f), CircleShape)
            .border(1.dp, Amber.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            color = Amber,
            fontSize = (size / 2.5).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun IptvCategoryItem(
    category: ChannelCategory,
    isSelected: Boolean,
    guideOwnsFocus: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bgColor = when {
        focused -> Amber.copy(alpha = 0.20f)
        isSelected && guideOwnsFocus -> Amber.copy(alpha = 0.07f)
        isSelected -> AmberSubtle
        else -> Graphite.copy(alpha = 0.18f)
    }
    val catBorderColor by animateColorAsState(
        targetValue = when {
            focused -> Amber
            isSelected && guideOwnsFocus -> Amber.copy(alpha = 0.20f)
            isSelected -> Amber.copy(alpha = 0.42f)
            else -> Steel.copy(alpha = 0.12f)
        },
        label = "catBorder",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .zIndex(if (focused) 1f else 0f)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .border(if (focused) 2.dp else 1.dp, catBorderColor, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val countryCode = category.countryCode
        when (countryCode) {
            "\u2764" -> {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
            }

            "\u23F2" -> {
                Text(
                    text = "\u23F2",
                    fontSize = 14.sp,
                )
                Spacer(Modifier.size(10.dp))
            }

            else -> if (countryCode != null && countryCode.length in 2..3 && countryCode.all { it.isLetter() }) {
                CountryBadge(code = countryCode, size = 24)
                Spacer(Modifier.size(10.dp))
            }
        }

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = category.name,
                color = if (focused || isSelected) Snow else Silver,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (category.qualityTags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    category.qualityTags.take(2).forEach { tag ->
                        QualityBadge(tag = tag)
                    }
                }
            }
        }

        Text(
            text = "${category.channelCount}",
            color = if (focused) Amber else Ash,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
internal fun QualityBadge(tag: String) {
    val badgeColor = when (tag) {
        "4K" -> Badge4K
        "FHD" -> Badge1080p
        "HD" -> Badge720p
        else -> BadgeSD
    }
    Text(
        text = tag,
        color = badgeColor,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .border(0.5.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
