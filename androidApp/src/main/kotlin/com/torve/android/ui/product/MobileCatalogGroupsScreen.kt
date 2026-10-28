package com.torve.android.ui.product

import com.torve.android.ui.community.parityLabel as localizedUiText

import com.torve.android.ui.system.TorveModalBottomSheet as ModalBottomSheet
import androidx.compose.ui.platform.LocalContext
import com.torve.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.data.catalog.CatalogGroupRepository
import com.torve.domain.model.*
import com.torve.presentation.home.*
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileCatalogGroupsScreen(onBack: () -> Unit, onOpen: (String) -> Unit,
    repository: CatalogGroupRepository = koinInject(), home: HomeViewModel = koinInject()) {
    val context = LocalContext.current
    val groups by repository.groups.collectAsState()
    val homeState by home.state.collectAsState()
    val custom by home.customSections.collectAsState()
    val sources = catalogGroupSources(homeState, custom)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CatalogGroup?>(null) }
    var deleting by remember { mutableStateOf<CatalogGroup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repository) { repository.refresh() }
    ProductPage(stringResource(R.string.flow_groups), stringResource(R.string.flow_groups_sub), onBack, actions = {
        IconButton(onClick = { editing = CatalogGroup(UUID.randomUUID().toString(), "", emptyList()) }, enabled = !busy) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.flow_group_create))
        }
    }) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { item("error") { ProductFeedback(it, isError = true) } }
            if (groups.isEmpty()) item("empty") {
                ProductEmptyState(stringResource(R.string.flow_groups_empty), stringResource(R.string.flow_groups_empty_sub)) {
                    FilledTonalButton(onClick = { editing = CatalogGroup(UUID.randomUUID().toString(), "", emptyList()) }) { Text(stringResource(R.string.flow_group_create)) }
                }
            }
            items(groups, key = { it.id }) { group ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ProductToolRow(group.name, stringResource(R.string.flow_catalogs_count, group.catalogIds.size) + " · " + stringResource(if (group.pinned) R.string.flow_pinned_home else R.string.flow_library_only),
                        Icons.Rounded.CollectionsBookmark, { onOpen(group.id) }, modifier = Modifier.testTag("group_${group.id}"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = group }, enabled = !busy) { Text(stringResource(R.string.flow_edit)) }
                        TextButton(onClick = { deleting = group }, enabled = !busy) { Text(stringResource(R.string.common_delete)) }
                    }
                }
            }
        }
    }
    editing?.let { group ->
        MobileCatalogGroupEditor(group, sources, busy, error, onDismiss = { if (!busy) { editing = null; error = null } }, onSave = { updated ->
            if (!busy) {
                busy = true
                error = null
                scope.launch {
                    try { repository.save(updated); editing = null }
                    catch (e: Exception) { if (e is CancellationException) throw e; error = context.getString(R.string.flow_group_save_error) }
                    finally { busy = false }
                }
            }
        })
    }
    deleting?.let { group ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text(stringResource(R.string.flow_delete_named, group.name)) },
            text = { Text(stringResource(R.string.flow_group_delete_sub)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { repository.delete(group.id); deleting = null }
                    catch (e: Exception) { if (e is CancellationException) throw e; error = context.getString(R.string.flow_group_delete_error) }
                    finally { busy = false }
                }
            }) { Text(if (busy) stringResource(R.string.flow_deleting) else stringResource(R.string.flow_group_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileCatalogGroupEditor(group: CatalogGroup, sources: List<CatalogGroupSource>, busy: Boolean,
    error: String?, onDismiss: () -> Unit, onSave: (CatalogGroup) -> Unit) {
    var name by remember(group.id) { mutableStateOf(group.name) }
    var selected by remember(group.id) { mutableStateOf(group.catalogIds.toSet()) }
    var pinned by remember(group.id) { mutableStateOf(group.pinned) }
    var presentation by remember(group.id) { mutableStateOf(group.presentation) }
    val available = sources + selected.filter { id -> sources.none { it.id == id } }
        .map { CatalogGroupSource(it, stringResource(R.string.flow_catalog_unavailable), emptyList()) }
    com.torve.android.ui.system.TorveModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).imePadding().padding(horizontal = 20.dp)) {
          Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(if (group.name.isBlank()) stringResource(R.string.flow_group_create) else stringResource(R.string.flow_group_edit), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.flow_group_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("group_name"), enabled = !busy)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.flow_pin_home), Modifier.weight(1f))
                Switch(pinned, { pinned = it }, enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(presentation == CatalogGroupPresentation.POSTERS, { presentation = CatalogGroupPresentation.POSTERS }, label = { Text(stringResource(R.string.flow_posters)) }, enabled = !busy)
                FilterChip(presentation == CatalogGroupPresentation.LANDSCAPE, { presentation = CatalogGroupPresentation.LANDSCAPE }, label = { Text(stringResource(R.string.flow_landscape)) }, enabled = !busy)
            }
            Text(stringResource(R.string.flow_catalogs_selected, selected.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(available, key = { it.id }) { source ->
                    Surface(onClick = { selected = if (source.id in selected) selected - source.id else selected + source.id },
                        enabled = !busy, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(source.id in selected, onCheckedChange = null)
                            Text(source.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (available.isEmpty()) item { ProductFeedback(stringResource(R.string.flow_catalogs_empty)) }
            }
            error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
          }
            Button(onClick = { onSave(group.copy(name = name.trim(), catalogIds = selected.toList(), pinned = pinned, presentation = presentation)) },
                enabled = !busy && name.isNotBlank() && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 48.dp)) { Text(if (busy) stringResource(R.string.flow_saving) else stringResource(R.string.flow_group_save)) }
        }
    }
}

@Composable
fun MobileCatalogGroupContentScreen(groupId: String, onBack: () -> Unit, onMedia: (MediaItem) -> Unit,
    repository: CatalogGroupRepository = koinInject(), home: HomeViewModel = koinInject(), settings: SettingsViewModel = koinInject()) {
    val groups by repository.groups.collectAsState()
    val state by home.state.collectAsState()
    val custom by home.customSections.collectAsState()
    val settingsState by settings.state.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }
    val items = group?.let { mergeCatalogGroup(it, catalogGroupSources(state, custom)) }.orEmpty()
    LaunchedEffect(repository) { repository.refresh() }
    CompositionLocalProvider(LocalRatingPrefs provides settingsState.ratingPrefs) {
        ProductPage(group?.name ?: stringResource(R.string.flow_group_singular), stringResource(R.string.flow_titles_count, items.size), onBack) {
            MobileMediaGrid(items, onMedia, landscape = group?.presentation == CatalogGroupPresentation.LANDSCAPE,
                emptyMessage = stringResource(R.string.flow_group_content_empty))
        }
    }
}

@Composable
internal fun ColumnScope.MobileMediaGrid(items: List<MediaItem>, onMedia: (MediaItem) -> Unit,
    landscape: Boolean = false, emptyMessage: String? = null) {
    if (items.isEmpty()) ProductEmptyState(stringResource(R.string.flow_titles_empty), emptyMessage ?: stringResource(R.string.flow_titles_empty_sub))
    else LazyVerticalGrid(columns = GridCells.Adaptive(if (landscape) 210.dp else 132.dp), modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(items, key = { "${it.type}_${it.id}" }) { item ->
            PosterCard(item, onClick = { onMedia(item) }, sizeOverride = if (landscape) CardSize.LANDSCAPE else CardSize.MEDIUM, fillCell = true)
        }
    }
}
