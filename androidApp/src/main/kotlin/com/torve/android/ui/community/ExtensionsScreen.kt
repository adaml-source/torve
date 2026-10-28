package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.torve.data.addon.ExtensionLinkDecoder
import com.torve.data.addon.ExtensionLinkDocument
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.data.metadata.TmdbUserCredentials
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.settings.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The same provider-neutral extension workflow is used by phone and TV. */
@Composable
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
fun ExtensionsScreen(onBack: () -> Unit, isTv: Boolean = false,
    settings: SettingsViewModel = koinInject(), addons: AddonViewModel = koinInject(),
    client: HttpClient = koinInject(), repository: AddonRepository = koinInject(),
    ownerProvider: UserIdProvider = koinInject(), credentials: TmdbUserCredentials = koinInject(),
    notifier: com.torve.presentation.settings.SettingsRefreshNotifier = koinInject(),
    embedded: Boolean = false, embeddedEntry: FocusRequester? = null,
    onEmbeddedFocus: ((FocusRequester) -> Unit)? = null,
) {
    val settingsState by settings.state.collectAsState()
    key(settingsState.personalDataUserId) {
        val state by addons.state.collectAsState()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val keyboard = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.isImeVisible
        val previewFocus = remember { FocusRequester() }
        val selectionFocus = remember { FocusRequester() }
        var link by remember { mutableStateOf("") }
        var document by remember { mutableStateOf<ExtensionLinkDocument?>(null) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var key by remember { mutableStateOf("") }
        var personalKey by remember { mutableStateOf(false) }
        var showKey by remember { mutableStateOf(false) }
        var removeUrl by remember { mutableStateOf<String?>(null) }
        val entryFocus = if (isTv && embedded) Modifier else communityEntryFocus()
        val urlFocus = remember { FocusRequester() }
        val catalogueFocus = remember { FocusRequester() }
        val metadataFocus = remember { FocusRequester() }
        val installedFocus = remember { FocusRequester() }
        val sectionFocus = remember { List(4) { FocusRequester() } }
        val sectionLabels = listOf("Add extension", "Built-in catalogue", "Metadata", "Installed")
        var tvSection by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
        var enterSection by remember { mutableStateOf(false) }
        val scroll = rememberLazyListState()
        fun firstSectionControl(): FocusRequester = when (tvSection) {
            0 -> urlFocus; 1 -> catalogueFocus; 2 -> metadataFocus; else -> if (state.addons.isNotEmpty()) installedFocus else sectionFocus[3]
        }
        fun showSection(index: Int) { tvSection = index; enterSection = true }
        LaunchedEffect(tvSection, enterSection) {
            if (isTv && enterSection) {
                scroll.scrollToItem(0)
                withFrameNanos { }
                firstSectionControl().requestFocus()
                enterSection = false
            }
        }
        fun Modifier.nextSectionOnDown(index: Int) = if (!isTv) this else onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                showSection(index); true
            } else false
        }
        val owner = settingsState.personalDataUserId
        val firstSelectable = document?.entries?.firstOrNull { entry ->
            !entry.requiresConfiguration && state.addons.none {
                AddonViewModel.normalizeManifestUrl(it.manifestUrl) == AddonViewModel.normalizeManifestUrl(entry.manifestUrl)
            }
        }
        LaunchedEffect(document, busy, firstSelectable?.manifestUrl) {
            if (isTv && document != null && !busy) {
                withFrameNanos { }
                runCatching { (if (firstSelectable != null) selectionFocus else previewFocus).requestFocus() }
            }
        }
        LaunchedEffect(owner) { personalKey = credentials.read() != null; addons.loadAddons() }
        BackHandler {
            if (imeVisible) {
                keyboard?.hide()
                if (link.isNotBlank()) previewFocus.requestFocus()
            } else onBack()
        }
        fun action(block: suspend () -> Unit) {
            if (busy) return
            busy = true; error = null; message = null
            scope.launch {
                try { ownerProvider.withPersonalDataScope { block() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Couldn’t complete this action. Check the link or connection and try again." }
                finally { busy = false }
            }
        }
        Column(Modifier.fillMaxSize().then(if (isTv && embeddedEntry != null)
            Modifier.focusRequester(embeddedEntry)
                .onFocusChanged { if (it.hasFocus) onEmbeddedFocus?.invoke(embeddedEntry) }.focusGroup()
            else Modifier).testTag("extensions.screen")) {
        if (isTv) {
            if (!embedded) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(localizedUiText("Extensions"), style = MaterialTheme.typography.headlineMedium)
                com.torve.android.ui.components.BackButton(onClick = onBack, modifier = Modifier.focusProperties { down = sectionFocus[tvSection] })
            }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = if (embedded) 4.dp else 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                sectionLabels.forEachIndexed { index, label ->
                    FilterChip(selected = tvSection == index, onClick = { showSection(index) },
                        modifier = Modifier.then(if (index == tvSection) entryFocus else Modifier)
                            .focusRequester(sectionFocus[index]).focusProperties { up = FocusRequester.Default; down = if (index == tvSection) firstSectionControl() else FocusRequester.Cancel }.testTag("extensions.section.$index"),
                        label = { Text(localizedUiText(label)) })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = scroll,
            contentPadding = PaddingValues(horizontal = if (isTv && embedded) 4.dp else if (isTv) 48.dp else 20.dp, vertical = if (isTv && embedded) 12.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv && embedded) 12.dp else 18.dp)) {
            if (!isTv) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedUiText("Extensions"), style = MaterialTheme.typography.headlineMedium)
                    com.torve.android.ui.components.BackButton(onClick = onBack, modifier = if (isTv) entryFocus.focusProperties { down = urlFocus } else Modifier)
                }
            }
            if (!isTv || tvSection == 0) item {
                ExtensionSection(localizedUiText("Add an extension")) {
                    Text(localizedUiText("Paste a manifest or collection link supplied by the extension provider you choose. Preview it before installing."))
                    if (isTv) com.torve.android.tv.components.TvClickToEditOutlinedTextField(
                        link, { link = it; document = null }, label = { Text(localizedUiText("JSON or manifest URL")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(urlFocus)
                            .focusProperties { up = sectionFocus[0]; if (link.isNotBlank() && !busy) down = previewFocus }
                            .then(if (link.isBlank() || busy) Modifier.nextSectionOnDown(1) else Modifier).testTag("extensions.url"))
                    else OutlinedTextField(link, { link = it; document = null }, label = { Text(localizedUiText("JSON or manifest URL")) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); if (link.isNotBlank()) previewFocus.requestFocus() }),
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("extensions.url"))
                    Button(enabled = !busy && link.isNotBlank(), onClick = {
                        action {
                            val address = ExtensionLinkDecoder.url(link)
                            val preview = client.prepareGet(address).execute { response ->
                                require(response.status.isSuccess())
                                val finalAddress = ExtensionLinkDecoder.url(response.call.request.url.toString())
                                val bytes = response.bodyAsChannel().readRemaining((ExtensionLinkDecoder.MAX_BYTES + 1).toLong()).readBytes()
                                ExtensionLinkDecoder.decode(bytes, finalAddress)
                            }
                            document = preview
                            selected = preview.entries.filterNot { it.requiresConfiguration }.map { it.manifestUrl }.toSet()
                        }
                    }, modifier = Modifier.focusRequester(previewFocus).focusProperties { if (isTv) up = urlFocus }
                        .then(if (document == null) Modifier.nextSectionOnDown(1) else Modifier).testTag("extensions.preview")) { Text(localizedUiText(if (busy) "Please wait…" else "Preview link")) }
                }
            }
            if (error != null) item { Text(localizedUiText(error!!), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("extensions.error")) }
            if (message != null) item { Text(localizedUiText(message!!), modifier = Modifier.testTag("extensions.message")) }
            document?.takeIf { !isTv || tvSection == 0 }?.let { preview ->
                item {
                    ExtensionSection(preview.name) {
                        if (preview.summary.isNotBlank()) Text(preview.summary)
                        preview.entries.forEach { entry ->
                            val installed = state.addons.any { AddonViewModel.normalizeManifestUrl(it.manifestUrl) == AddonViewModel.normalizeManifestUrl(entry.manifestUrl) }
                            Row(Modifier.fillMaxWidth()) {
                                Checkbox(entry.manifestUrl in selected, modifier = Modifier.then(if (entry == firstSelectable) Modifier.focusRequester(selectionFocus).semantics { contentDescription = entry.name } else Modifier).semantics { contentDescription = entry.name }, enabled = !busy && !entry.requiresConfiguration && !installed,
                                    onCheckedChange = { checked -> selected = if (checked) selected + entry.manifestUrl else selected - entry.manifestUrl })
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                    Text(localizedUiText(if (installed) "Installed" else entry.capabilities.joinToString(" · ")), style = MaterialTheme.typography.bodySmall)
                                    if (entry.summary.isNotBlank()) Text(entry.summary)
                                    if (entry.requiresConfiguration) Text(localizedUiText("Configure this extension with its provider, then paste the configured manifest URL."))
                                    entry.configurationUrl?.let { address ->
                                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) } }) { Text(localizedUiText("Configure with provider")) }
                                    }
                                }
                            }
                        }
                        Button(enabled = !busy && preview.entries.any { it.manifestUrl in selected && !it.requiresConfiguration && state.addons.none { addon -> addon.manifestUrl == it.manifestUrl } },
                            onClick = {
                                action {
                                    val failures = mutableListOf<String>()
                                    var installedCount = 0
                                    for (entry in preview.entries.filter { it.manifestUrl in selected && !it.requiresConfiguration }) {
                                        if (state.addons.any { it.manifestUrl == entry.manifestUrl }) continue
                                        try {
                                            require(addons.isInstallAllowed(entry.manifestUrl))
                                            repository.installAddon(entry.manifestUrl); installedCount++
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { failures += entry.name }
                                    }
                                    addons.loadAddons(); settings.refreshSettings(); notifier.notifyRefresh(System.currentTimeMillis())
                                    message = "$installedCount extension(s) installed."
                                    if (failures.isNotEmpty()) error = "Couldn’t install: ${failures.joinToString()}. Check the configured links and retry."
                                }
                            }, modifier = Modifier.testTag("extensions.install")) { Text(localizedUiText("Install selected")) }
                    }
                }
            }
            if (!isTv || tvSection == 1) item {
                ExtensionSection(localizedUiText("Built-in catalogue")) {
                    Text(localizedUiText("TMDB catalogue"), style = MaterialTheme.typography.titleMedium)
                    Text(localizedUiText("Optional movie and TV discovery, search and title details. Playback sources are not included."))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(localizedUiText("Browse with TMDB"), Modifier.weight(1f))
                        Switch(settingsState.tmdbCatalogueEnabled, settings::setTmdbCatalogueEnabled, Modifier.focusRequester(catalogueFocus).focusProperties { if (isTv) up = sectionFocus[1] }.testTag("extensions.tmdb.catalogue").semantics { contentDescription = "Browse with TMDB" }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary, uncheckedThumbColor = MaterialTheme.colorScheme.onSurface, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant))
                    }
                    TextButton(onClick = { showKey = !showKey }, modifier = if (!showKey) Modifier.nextSectionOnDown(2) else Modifier) { Text(localizedUiText("Personal TMDB API key")) }
                    if (showKey) {
                        if (isTv) com.torve.android.tv.components.TvClickToEditOutlinedTextField(key, { key = it }, label = { Text(localizedUiText("TMDB API key (v3)")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        else OutlinedTextField(key, { key = it }, label = { Text(localizedUiText("TMDB API key (v3)")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && key.isNotBlank(), onClick = { action { credentials.save(key); settings.setTmdbCatalogueEnabled(true); key = ""; personalKey = true; message = "Key saved. TMDB browsing is enabled." } }) { Text(localizedUiText("Save key & enable browsing")) }
                        if (personalKey) TextButton(enabled = !busy, onClick = { action { credentials.clear(); personalKey = false; message = "Personal key removed." } }) { Text(localizedUiText("Remove personal key")) }
                    }
                }
            }
            if (!isTv || tvSection == 2) item {
                ExtensionSection(localizedUiText("Built-in metadata enrichment")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(localizedUiText("Enrich extension metadata with TMDB"), Modifier.weight(1f))
                        Switch(settingsState.tmdbEnrichmentEnabled, settings::setTmdbEnrichmentEnabled, Modifier.focusRequester(metadataFocus).focusProperties { if (isTv) up = sectionFocus[2] }.nextSectionOnDown(3).testTag("extensions.tmdb.enrichment").semantics { contentDescription = "Enrich extension metadata with TMDB" }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary, uncheckedThumbColor = MaterialTheme.colorScheme.onSurface, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant))
                    }
                    Text(localizedUiText("Fills missing artwork, descriptions and cast for recognised IMDb/TMDB titles. You can enable this without browsing the TMDB catalogue."))
                }
            }
            if (!isTv || tvSection == 3) item {
                ExtensionSection(localizedUiText("Installed")) {
                    if (state.isLoading) CircularProgressIndicator()
                    if (!state.isLoading && state.addons.isEmpty()) Text(localizedUiText("No extensions installed. Add a link above to get started."))
                    state.addons.forEach { addon ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(addon.manifest.name, style = MaterialTheme.typography.titleMedium)
                            Text(addon.manifest.description, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(addon.isEnabled, { addons.toggleAddon(addon.manifestUrl, it) }, modifier = if (addon == state.addons.firstOrNull()) Modifier.focusRequester(installedFocus).focusProperties { if (isTv) up = sectionFocus[3] } else Modifier, enabled = addons.isInstallAllowed(addon.manifestUrl))
                                TextButton(onClick = { removeUrl = addon.manifestUrl }) { Text(localizedUiText("Remove")) }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(if (isTv) 32.dp else 88.dp)) }
        }
        }
        removeUrl?.let { address ->
            AlertDialog(onDismissRequest = { removeUrl = null }, title = { Text(localizedUiText("Remove extension?")) },
                text = { Text(localizedUiText("This removes its catalogues and sources from this device.")) },
                confirmButton = { TextButton(onClick = { addons.removeAddonOnly(address); removeUrl = null }) { Text(localizedUiText("Remove")) } },
                dismissButton = { TextButton(onClick = { removeUrl = null }) { Text(localizedUiText("Cancel")) } })
        }
    }
}

@Composable private fun ExtensionSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = com.torve.android.ui.theme.menuPanelColor()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localizedUiText(title), style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
