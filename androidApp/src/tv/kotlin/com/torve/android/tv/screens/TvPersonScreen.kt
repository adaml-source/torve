package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.tv.*
import com.torve.android.tv.components.*
import com.torve.android.ui.community.parityLabel as localizedUiText
import com.torve.android.ui.theme.*
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.detail.PersonFilmographyPolicy
import com.torve.presentation.detail.PersonViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

internal fun tvPersonFilmography(credits: List<MediaItem>, department: String, filter: String, sort: String): List<MediaItem> {
    if (department.isBlank() || department == "Acting" || filter == "other")
        return PersonFilmographyPolicy.items(credits, filter, sort)
    // A director's films are crew credits; the acting-only policy would hide them.
    return PersonFilmographyPolicy.items(credits, "all", sort).filter {
        when (filter) { "movies" -> it.type == MediaType.MOVIE; "tv" -> it.type == MediaType.SERIES; else -> true }
    }
}

/** One TV filmography surface for Home people, directors and detail-page cast. */
@Composable
internal fun TvPersonScreen(
    personId: Int, onBack: () -> Unit, onMediaClick: (MediaItem) -> Unit,
    railFocusRequester: FocusRequester, initialName: String = "",
    onFirstContentRequester: (FocusRequester) -> Unit = {},
    onContentFocused: (FocusRequester) -> Unit = {},
) {
    val viewModel: PersonViewModel = koinInject()
    val settings: SettingsViewModel = koinInject()
    val state by viewModel.state.collectAsState()
    val prefs by settings.state.collectAsState()
    val owner = prefs.personalDataUserId
    var filter by rememberSaveable(personId, owner) { mutableStateOf("roles") }
    var sort by rememberSaveable(personId, owner) { mutableStateOf("imdb") }
    var enteredGrid by rememberSaveable(personId, owner) { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    val first = remember(personId) { FocusRequester() }
    val filterRequesters = remember(personId) { listOf(first, FocusRequester(), FocusRequester(), FocusRequester()) }
    val grid = remember(personId) { FocusRequester() }
    val biography = remember(personId) { FocusRequester() }
    val sortButton = remember(personId) { FocusRequester() }
    val window = LocalWindowInfo.current
    val credits = remember(state.credits, state.knownFor, filter, sort) { tvPersonFilmography(state.credits, state.knownFor, filter, sort) }
    val roleLabel = if (state.knownFor.isBlank() || state.knownFor == "Acting") "Acting roles" else "All"
    val filters = listOf("roles" to roleLabel, "movies" to "Movies", "tv" to "TV shows", "other" to "Appearances & extras")
    val sorts = listOf("imdb" to "Highest IMDb rating", "popular" to "Popular", "newest" to "Newest")
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    LaunchedEffect(personId, owner, prefs.appLanguage) { viewModel.loadPerson(personId) }
    LaunchedEffect(personId, owner, state.isLoading) {
        if (!state.isLoading) {
            withFrameNanos { }
            runCatching { (if (enteredGrid && credits.isNotEmpty()) grid else first).requestFocus() }
        }
    }
    LaunchedEffect(dialog) {
        if (dialog == null) returnTo?.let { requester ->
            snapshotFlow { window.isWindowFocused }.first { it }
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            returnTo = null
        }
    }
    BackHandler { if (dialog != null) dialog = null else onBack() }
    Column(Modifier.fillMaxSize().testTag("tv_person:$personId")
        .padding(start = TV_PAGE_CONTENT_GUTTER, end = TV_PAGE_END_GUTTER,
            top = TV_PAGE_TOP_GUTTER, bottom = TV_PAGE_BOTTOM_GUTTER),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            TvPersonPortrait(personId, state.personName.ifBlank { initialName }, state.profileUrl,
                Modifier.width(104.dp).height(156.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.personName.ifBlank { initialName.ifBlank { localizedUiText("Cast") } },
                    color = Snow, style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(state.knownFor.takeIf { it.isNotBlank() }?.let { localizedUiText(it) },
                    if (!state.isLoading) localizedUiText("${state.credits.size} credits") else null).joinToString(" · "),
                    color = Silver, style = MaterialTheme.typography.bodyMedium)
                if (state.biography.isNotBlank()) {
                    Text(state.biography, color = Silver, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TvWorkflowAction(localizedUiText("Read more"), { returnTo = biography; dialog = "bio" },
                        requester = biography, appearance = TvWorkflowActionAppearance.COMPACT,
                        onFocused = { onContentFocused(biography) })
                }
            }
        }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AmberLight)
        else if (state.error != null) {
            Text(localizedUiText("This person's credits could not be loaded."), color = Silver)
            TvWorkflowAction(localizedUiText("Retry"), { viewModel.loadPerson(personId) }, requester = first)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                filters.forEachIndexed { index, (key, label) ->
                    TvWorkflowAction(localizedUiText(label), { filter = key }, selected = filter == key,
                        requester = filterRequesters[index], appearance = TvWorkflowActionAppearance.COMPACT,
                        modifier = Modifier.focusProperties { if (credits.isNotEmpty()) down = grid },
                        onFocused = { onContentFocused(filterRequesters[index]) })
                }
                Spacer(Modifier.weight(1f))
                TvWorkflowAction(localizedUiText(sorts.first { it.first == sort }.second),
                    { returnTo = sortButton; dialog = "sort" }, requester = sortButton,
                    appearance = TvWorkflowActionAppearance.COMPACT, onFocused = { onContentFocused(sortButton) })
            }
            if (credits.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(localizedUiText("No credits in this category."), color = Silver)
            } else TvFocusPreviewGrid(credits, "person:$personId:$filter", 6,
                railFocusRequester, grid, headerRequester = first, modifier = Modifier.weight(1f),
                onFirstContentRequester = onFirstContentRequester,
                onContentFocused = onContentFocused,
                onFocused = { _, _ -> enteredGrid = true },
                onExitUp = { first.requestFocus() }, onClick = onMediaClick)
        }
    }
    if (dialog == "bio") TvWorkflowDialog(localizedUiText("Biography"), { dialog = null }, biography) {
        Text(state.biography, color = Silver, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).focusable())
    }
    if (dialog == "sort") TvWorkflowDialog(localizedUiText("Filmography"), { dialog = null }, sortButton) {
        sorts.forEach { (key, label) ->
            TvWorkflowAction(localizedUiText(label), { sort = key; dialog = null }, selected = sort == key,
                modifier = Modifier.fillMaxWidth(), appearance = TvWorkflowActionAppearance.COMPACT)
        }
    }
}
