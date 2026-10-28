package com.torve.android.ui.watchlist

import com.torve.android.ui.community.parityLabel as localizedUiText

import com.torve.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.*

/** Direct Library destinations keep the existing collection selection when returning. */
@Composable
internal fun MobileLibraryTabs(labels: List<String>, selectedIndex: Int, onTab: (Int) -> Unit,
    onYourShows: () -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            item("collection_$index") {
                LibraryChip(label, selectedIndex == index, Modifier.testTag("library_tab_$index")) { onTab(index) }
            }
            if (index == 0) item("your_shows") {
                LibraryChip(stringResource(R.string.flow_your_shows), false, Modifier.testTag("library_your_shows"), onYourShows)
            }
        }
    }
}

@Composable
private fun LibraryChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
        .background(if (selected) Amber.copy(alpha = .2f) else Gunmetal)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(localizedUiText(label), style = MaterialTheme.typography.labelLarge,
            color = if (selected) Amber else Torve.colors.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}
