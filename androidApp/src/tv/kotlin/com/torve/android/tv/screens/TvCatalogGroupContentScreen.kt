package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.torve.android.tv.components.*
import com.torve.data.catalog.CatalogGroupRepository
import com.torve.domain.model.*
import com.torve.presentation.home.*
import org.koin.compose.koinInject

@Composable
fun TvCatalogGroupContentScreen(groupId: String, onBack: () -> Unit, railFocusRequester: FocusRequester,
    onMedia: (MediaItem) -> Unit, onContentFocused: (FocusRequester) -> Unit,
    groups: CatalogGroupRepository = koinInject(), home: HomeViewModel = koinInject()) {
    val definitions by groups.groups.collectAsState(); val state by home.state.collectAsState()
    val custom by home.customSections.collectAsState()
    val definition = definitions.firstOrNull { it.id == groupId }
    val entry = remember { FocusRequester() }
    var inContent by remember { mutableStateOf(false) }
    BackHandler { if (inContent) entry.requestFocus() else onBack() }
    val items = definition?.let { mergeCatalogGroup(it, catalogGroupSources(state, custom)) }.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 44.dp, top = 24.dp, bottom = 8.dp)) {
            TvWorkflowAction(localizedUiText("Back"), onBack, requester = entry, onFocused = { inContent = false; onContentFocused(entry) })
        }
        TvMediaRails(rails = listOf(TvContentRail("group:$groupId", definition?.name ?: "Catalog group", items,
            cardStyle = if (definition?.presentation == CatalogGroupPresentation.LANDSCAPE) TvCardStyle.BACKDROP else TvCardStyle.POSTER,
            showSeeAllCard = false, allowCrossRailDuplicates = true)),
            railFocusRequester = railFocusRequester, headerFocusRequester = entry, onMediaClick = onMedia,
            onFirstContentRequester = onContentFocused, onContentFocused = { inContent = true; onContentFocused(it) },
            screenId = "catalog_group:$groupId", modifier = Modifier.weight(1f),
            emptyMessage = localizedUiText("No titles are available from the selected catalogs yet. Check providers or edit the group."))
    }
}
