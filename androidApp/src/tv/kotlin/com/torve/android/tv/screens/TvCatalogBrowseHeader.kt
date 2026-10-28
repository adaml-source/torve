package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import com.torve.android.tv.components.tvGlassControl
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel

/** One horizontal focus lane: DOWN always enters content, UP returns to the header. */
@Composable
internal fun TvCatalogBrowseHeader(
    genres: List<GenreSpec>,
    entryRequester: FocusRequester,
    railRequester: FocusRequester,
    headerRequester: FocusRequester?,
    searchTitle: String,
    onFocused: (FocusRequester) -> Unit,
    onSearch: () -> Unit,
    onGenre: (GenreSpec) -> Unit,
) {
    Column(Modifier.height(90.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tv_catalog_browse_by_genre),
            Modifier.padding(start = TV_PAGE_CONTENT_GUTTER),
            style = MaterialTheme.typography.titleSmall, color = Silver)
        LazyRow(
            contentPadding = PaddingValues(start = TV_PAGE_CONTENT_GUTTER, end = TV_PAGE_END_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "search") {
                CatalogBrowseChip(searchTitle, entryRequester, railRequester, headerRequester,
                    "catalog_browse_search", { onFocused(entryRequester) }, onSearch, search = true)
            }
            itemsIndexed(genres, key = { _, genre -> genre.id }) { _, genre ->
                val requester = remember(genre.id) { FocusRequester() }
                CatalogBrowseChip(genre.label, requester, railRequester, headerRequester,
                    "catalog_genre:${genre.id}", { onFocused(requester) }, { onGenre(genre) })
            }
        }
    }
}

@Composable
private fun CatalogBrowseChip(
    label: String,
    requester: FocusRequester,
    railRequester: FocusRequester,
    headerRequester: FocusRequester?,
    tag: String,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    search: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.testTag(tag).height(42.dp).focusRequester(requester)
            .focusProperties { left = if (search) railRequester else FocusRequester.Default; headerRequester?.let { up = it } }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvGlassControl(focused, cornerRadius = 24.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (search) Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = if (focused) Amber else Snow)
        Text(localizedUiText(label), style = MaterialTheme.typography.labelLarge, color = Snow, maxLines = 1)
    }
}
