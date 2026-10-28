package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.torve.android.tv.components.*
import com.torve.android.ui.theme.*
import com.torve.data.catalog.CatalogGroupRepository
import com.torve.domain.model.*
import com.torve.presentation.home.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import java.util.UUID

@Composable
fun TvCatalogGroupsScreen(onBack: () -> Unit, onOpenGroup: (CatalogGroup, List<MediaItem>) -> Unit,
    onContentFocused: (FocusRequester) -> Unit, repository: CatalogGroupRepository = koinInject(),
    home: HomeViewModel = koinInject()) {
    TvWorkflowSurface("catalog_groups") { TvCatalogGroupsContent(onBack, onOpenGroup, onContentFocused, repository, home) }
}

@Composable
private fun TvCatalogGroupsContent(onBack: () -> Unit, onOpenGroup: (CatalogGroup, List<MediaItem>) -> Unit,
    onContentFocused: (FocusRequester) -> Unit, repository: CatalogGroupRepository, home: HomeViewModel) {
    val context = LocalContext.current
    val groups by repository.groups.collectAsState()
    val homeState by home.state.collectAsState()
    val custom by home.customSections.collectAsState()
    val sources = catalogGroupSources(homeState, custom)
    val scope = rememberCoroutineScope()
    val entry = remember { FocusRequester() }
    val create = remember { FocusRequester() }
    var editing by remember { mutableStateOf<CatalogGroup?>(null) }
    var deleting by remember { mutableStateOf<CatalogGroup?>(null) }
    var origin by remember { mutableStateOf<FocusRequester?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { repository.refresh() }
    TvWorkflowPage(stringResource(R.string.flow_groups), error ?: stringResource(R.string.flow_groups_sub),
        onBack, entry, onContentFocused, toolbar = {
            TvWorkflowAction(stringResource(R.string.flow_group_create), {
                origin = create
                editing = CatalogGroup(UUID.randomUUID().toString(), "", emptyList())
            }, requester = create)
        }) {
        if (groups.isEmpty()) item("empty") { Text(stringResource(R.string.flow_groups_empty_sub), color = Silver) }
        items(groups, key = { it.id }) { group ->
            val requester = remember(group.id) { FocusRequester() }
            val editRequester = remember(group.id) { FocusRequester() }
            val deleteRequester = remember(group.id) { FocusRequester() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvWorkflowAction(group.name, { onOpenGroup(group, mergeCatalogGroup(group, sources)) }, Modifier.weight(1f),
                    subtitle = stringResource(R.string.flow_catalogs_count, group.catalogIds.size) + " · " + stringResource(if (group.pinned) R.string.flow_pinned_home else R.string.flow_library_only),
                    requester = requester, onFocused = { onContentFocused(requester) })
                TvWorkflowAction(stringResource(R.string.flow_edit), { origin = editRequester; editing = group }, requester = editRequester)
                TvWorkflowAction(stringResource(R.string.common_delete), { origin = deleteRequester; deleting = group }, destructive = true, requester = deleteRequester)
            }
        }
    }
    editing?.let { group ->
        var name by remember(group.id) { mutableStateOf(group.name) }
        var selected by remember(group.id) { mutableStateOf(group.catalogIds.toSet()) }
        var pinned by remember(group.id) { mutableStateOf(group.pinned) }
        var presentation by remember(group.id) { mutableStateOf(group.presentation) }
        var saving by remember { mutableStateOf(false) }
        var formError by remember { mutableStateOf<String?>(null) }
        TvWorkflowDialog(if (group.name.isEmpty()) stringResource(R.string.flow_group_create) else stringResource(R.string.flow_group_edit), { if (!saving) editing = null }, origin) {
            TvClickToEditOutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.flow_group_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvWorkflowAction(if (pinned) stringResource(R.string.flow_pinned_home) else stringResource(R.string.flow_pin_home), { pinned = !pinned }, selected = pinned)
                TvWorkflowAction(if (presentation == CatalogGroupPresentation.POSTERS) stringResource(R.string.flow_posters) else stringResource(R.string.flow_landscape),
                    { presentation = if (presentation == CatalogGroupPresentation.POSTERS) CatalogGroupPresentation.LANDSCAPE else CatalogGroupPresentation.POSTERS })
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 80.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val available = sources + selected.filter { id -> sources.none { it.id == id } }.map { CatalogGroupSource(it, context.getString(R.string.flow_catalog_unavailable), emptyList()) }
                items(available, key = { it.id }) { source ->
                    TvWorkflowAction("${if (source.id in selected) "✓  " else ""}${source.title}",
                        { selected = if (source.id in selected) selected - source.id else selected + source.id }, Modifier.fillMaxWidth(), selected = source.id in selected)
                }
                if (available.isEmpty()) item { Text(stringResource(R.string.flow_catalogs_empty), color = Silver) }
            }
            formError?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            TvWorkflowAction(stringResource(R.string.flow_group_save), {
                if (!saving) scope.launch {
                    saving = true
                    runCatching { repository.save(group.copy(name = name, catalogIds = selected.toList(), pinned = pinned, presentation = presentation)) }
                        .onSuccess { editing = null }.onFailure {
                            if (it is CancellationException) throw it
                            formError = context.getString(if (name.isBlank() || selected.isEmpty()) R.string.flow_group_required else R.string.flow_group_save_error)
                        }
                    saving = false
                }
            }, subtitle = if (saving) stringResource(R.string.flow_saving) else stringResource(R.string.flow_catalogs_selected, selected.size))
        }
    }
    deleting?.let { group ->
        TvWorkflowDialog(stringResource(R.string.flow_delete_named, group.name), { deleting = null }, origin) {
            Text(stringResource(R.string.flow_group_delete_sub), color = Silver)
            TvWorkflowAction(stringResource(R.string.flow_group_delete), { scope.launch {
                runCatching { repository.delete(group.id) }.onSuccess { deleting = null; origin = create }
                    .onFailure { if (it is CancellationException) throw it; error = context.getString(R.string.flow_group_delete_error); deleting = null }
            } }, destructive = true)
        }
    }
}
