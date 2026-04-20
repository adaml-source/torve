package com.torve.android.tv.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import com.torve.android.ui.theme.*
import com.torve.data.ai.KeywordSearchService
import com.torve.domain.model.CustomSection
import com.torve.domain.model.CustomSectionFilters
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.SavedPerson
import com.torve.domain.model.SpecificTmdbItem
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.UUID

@Composable
fun TvHomeLayoutScreen(
    railFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    entryFocusRequester: FocusRequester,
    onEntryFocusReadyChanged: (Boolean) -> Unit = {},
    onEntryFocusFocused: () -> Unit = {},
    homeViewModel: HomeViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    keywordSearchService: KeywordSearchService = koinInject(),
) {
    BackHandler { onBack() }
    val configs by homeViewModel.sectionConfigs.collectAsState()
    val customSections by homeViewModel.customSections.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val sortedConfigs = remember(configs) { configs.sortedBy { it.order } }
    val scope = rememberCoroutineScope()

    // Create custom section form state
    var showCreateCustom by remember { mutableStateOf(false) }
    var customTitle by remember { mutableStateOf("") }
    var aiQuery by remember { mutableStateOf("") }
    var isAiSearching by remember { mutableStateOf(false) }
    var aiResultLabel by remember { mutableStateOf<String?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var pendingFilters by remember { mutableStateOf<CustomSectionFilters?>(null) }
    var pendingMediaType by remember { mutableStateOf("movie") }
    var movingSectionKey by remember { mutableStateOf<String?>(null) }
    // Media type cycling
    val mediaTypes = remember { listOf("movie", "tv", "both") }

    LaunchedEffect(entryFocusRequester) {
        onFirstContentRequester(entryFocusRequester)
    }
    DisposableEffect(Unit) {
        onEntryFocusReadyChanged(false)
        onDispose { onEntryFocusReadyChanged(false) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, top = 20.dp, end = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title
        item(key = "title") {
            TvSettingCard(
                title = stringResource(R.string.tv_home_layout_title),
                subtitle = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { left = railFocusRequester }
                    .onGloballyPositioned { onEntryFocusReadyChanged(true) },
                focusRequester = entryFocusRequester,
                onFocused = {
                    onEntryFocusFocused()
                    onContentFocused(entryFocusRequester)
                },
                onClick = onBack,
            )
        }

        item(key = "reorder_hint") {
            Text(
                text = stringResource(R.string.tv_home_layout_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp, start = 4.dp),
            )
        }

        // Section rows
        itemsIndexed(
            sortedConfigs,
            key = { _, config -> "section_${config.section.name}" },
        ) { index, config ->
            val requester = remember("hl_${config.section.name}") { FocusRequester() }
            val rowKey = config.section.name
            HomeSectionRow(
                config = config,
                railFocusRequester = railFocusRequester,
                focusRequester = requester,
                onContentFocused = onContentFocused,
                isMoveMode = movingSectionKey == rowKey,
                onToggleMoveMode = {
                    movingSectionKey = if (movingSectionKey == rowKey) null else rowKey
                },
                onExitMoveMode = {
                    if (movingSectionKey == rowKey) movingSectionKey = null
                },
                onToggle = {
                    homeViewModel.toggleSection(config.section, !config.enabled)
                },
                onMoveUp = {
                    if (index > 0) {
                        val mutableList = sortedConfigs.toMutableList()
                        val prev = mutableList[index - 1]
                        mutableList[index - 1] = config.copy(order = prev.order)
                        mutableList[index] = prev.copy(order = config.order)
                        homeViewModel.updateSectionOrder(mutableList)
                    }
                },
                onMoveDown = {
                    if (index < sortedConfigs.lastIndex) {
                        val mutableList = sortedConfigs.toMutableList()
                        val next = mutableList[index + 1]
                        mutableList[index + 1] = config.copy(order = next.order)
                        mutableList[index] = next.copy(order = config.order)
                        homeViewModel.updateSectionOrder(mutableList)
                    }
                },
            )
        }

        // Custom sections (editable)
        if (customSections.isNotEmpty()) {
            items(customSections, key = { "custom_${it.id}" }) { section ->
                val requester = remember("hl_custom_${section.id}") { FocusRequester() }
                TvSettingCard(
                    title = section.title,
                    subtitle = if (section.enabled) stringResource(R.string.tv_home_layout_enabled)
                               else stringResource(R.string.tv_home_layout_disabled),
                    modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                    focusRequester = requester,
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        homeViewModel.deleteCustomSection(section.id)
                    },
                )
            }
        }

        // Reset to Defaults
        item(key = "reset") {
            val requester = remember("hl_reset") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_home_layout_reset),
                subtitle = stringResource(R.string.tv_home_layout_reset_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { homeViewModel.resetSections() },
            )
        }

        // Create Custom Section
        item(key = "create_custom") {
            val requester = remember("hl_create") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_home_layout_create_custom),
                subtitle = stringResource(R.string.tv_home_layout_create_custom_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    showCreateCustom = !showCreateCustom
                    if (!showCreateCustom) {
                        // Reset form
                        customTitle = ""
                        aiQuery = ""
                        aiResultLabel = null
                        aiError = null
                        pendingFilters = null
                    }
                },
            )
        }

        if (showCreateCustom) {
            item(key = "custom_form") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Charcoal.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .border(1.dp, Steel.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Section title
                    TvClickToEditOutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text(stringResource(R.string.tv_home_layout_section_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Media type cycling card
                    val mtRequester = remember { FocusRequester() }
                    val mtIndex = remember(pendingMediaType) {
                        mediaTypes.indexOf(pendingMediaType).coerceAtLeast(0)
                    }
                    TvSettingCard(
                        title = stringResource(R.string.tv_home_layout_media_type),
                        subtitle = pendingMediaType,
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = mtRequester,
                        onFocused = { onContentFocused(mtRequester) },
                        onClick = {
                            pendingMediaType = mediaTypes[(mtIndex + 1) % mediaTypes.size]
                        },
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // AI search prompt
                    TvClickToEditOutlinedTextField(
                        value = aiQuery,
                        onValueChange = { aiQuery = it },
                        label = { Text(stringResource(R.string.tv_home_layout_ai_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // AI search button
                    val aiRequester = remember { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_home_layout_ai_search),
                        subtitle = when {
                            isAiSearching -> stringResource(R.string.tv_home_layout_ai_searching)
                            aiResultLabel != null -> aiResultLabel!!
                            else -> ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = aiRequester,
                        onFocused = { onContentFocused(aiRequester) },
                        onClick = {
                            if (aiQuery.isBlank() || isAiSearching) return@TvSettingCard
                            isAiSearching = true
                            aiError = null
                            aiResultLabel = null
                            scope.launch {
                                try {
                                    val aiKey = settingsState.activeAiApiKey
                                    val result = if (aiKey.isNotBlank()) {
                                        keywordSearchService.searchWithAi(
                                            settingsState.aiProvider, aiKey, aiQuery,
                                        )
                                    } else {
                                        keywordSearchService.searchWithTmdbFallback(aiQuery)
                                    }

                                    // Auto-fill title from AI if blank
                                    if (customTitle.isBlank() && result.title.isNotBlank()) {
                                        customTitle = result.title
                                    }

                                    // Convert AI result to CustomSectionFilters
                                    pendingFilters = when {
                                        result.mode == "specific" && result.specificItems.isNotEmpty() -> {
                                            CustomSectionFilters(
                                                specificTmdbIds = result.specificItems.map {
                                                    SpecificTmdbItem(it.tmdbId, it.title, it.mediaType)
                                                },
                                            )
                                        }
                                        result.mode == "person_filtered" && result.specificItems.isNotEmpty() -> {
                                            CustomSectionFilters(
                                                specificTmdbIds = result.specificItems.map {
                                                    SpecificTmdbItem(it.tmdbId, it.title, it.mediaType)
                                                },
                                            )
                                        }
                                        (result.mode == "person_credits" || result.mode == "person_filtered") && result.personId != null -> {
                                            val saved = SavedPerson(result.personId!!, result.personName ?: "")
                                            CustomSectionFilters(
                                                genreIds = result.genreIds,
                                                sortBy = result.sortBy.ifBlank { "popularity.desc" },
                                                minRating = result.minRating,
                                                yearFrom = result.yearFrom,
                                                yearTo = result.yearTo,
                                                withKeywords = result.keywordIds,
                                                withCast = if (!result.isDirector) listOf(saved) else emptyList(),
                                                withCrew = if (result.isDirector) listOf(saved) else emptyList(),
                                            )
                                        }
                                        else -> {
                                            // Discover mode
                                            CustomSectionFilters(
                                                genreIds = result.genreIds,
                                                sortBy = result.sortBy.ifBlank { "popularity.desc" },
                                                minRating = result.minRating,
                                                yearFrom = result.yearFrom,
                                                yearTo = result.yearTo,
                                                withKeywords = result.keywordIds,
                                            )
                                        }
                                    }

                                    // Infer media type from AI result
                                    result.mediaType?.let { pendingMediaType = it }

                                    aiResultLabel = "AI: ${result.title.ifBlank { aiQuery }}"
                                } catch (e: Throwable) {
                                    aiError = e.message ?: "Search failed"
                                } finally {
                                    isAiSearching = false
                                }
                            }
                        },
                    )

                    aiError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ruby,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Save button
                    val saveRequester = remember { FocusRequester() }
                    TvSettingCard(
                        title = stringResource(R.string.tv_home_layout_save),
                        subtitle = "",
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = saveRequester,
                        onFocused = { onContentFocused(saveRequester) },
                        onClick = {
                            if (customTitle.isNotBlank()) {
                                homeViewModel.addCustomSection(
                                    CustomSection(
                                        id = UUID.randomUUID().toString(),
                                        title = customTitle,
                                        mediaType = pendingMediaType,
                                        filters = pendingFilters ?: CustomSectionFilters(),
                                        order = sortedConfigs.size + customSections.size,
                                        enabled = true,
                                    ),
                                )
                                customTitle = ""
                                aiQuery = ""
                                aiResultLabel = null
                                aiError = null
                                pendingFilters = null
                                pendingMediaType = "movie"
                                showCreateCustom = false
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionRow(
    config: HomeSectionConfig,
    railFocusRequester: FocusRequester,
    focusRequester: FocusRequester,
    onContentFocused: (FocusRequester) -> Unit,
    isMoveMode: Boolean,
    onToggleMoveMode: () -> Unit,
    onExitMoveMode: () -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.02f else 1f, label = "sectionScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            isMoveMode && focused -> AmberLight
            focused -> Amber
            else -> Color.Transparent
        },
        label = "sectionBorder",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties { left = railFocusRequester }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onContentFocused(focusRequester)
            }
            .onPreviewKeyEvent { event ->
                if (!focused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> {
                        if (!isMoveMode) {
                            onToggleMoveMode()
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionLeft -> {
                        if (isMoveMode) {
                            onExitMoveMode()
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionUp -> {
                        if (isMoveMode) {
                            onMoveUp()
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionDown -> {
                        if (isMoveMode) {
                            onMoveDown()
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter
                    -> {
                        if (isMoveMode) {
                            onExitMoveMode()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp),
            )
            .background(
                color = if (focused) Graphite.copy(alpha = 0.5f) else Charcoal.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = config.customTitle ?: config.section.defaultTitle,
            style = MaterialTheme.typography.titleMedium,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (config.enabled) stringResource(R.string.tv_home_layout_enabled)
                   else stringResource(R.string.tv_home_layout_disabled),
            style = MaterialTheme.typography.bodySmall,
            color = if (config.enabled) Amber else Ash,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = if (isMoveMode) {
                "Move mode active: UP or DOWN reorders this section. Press LEFT to finish."
            } else {
                "Press RIGHT to move this section."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (isMoveMode) Amber else Silver,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.tv_home_layout_move_up),
                style = MaterialTheme.typography.labelMedium,
                color = if (isMoveMode) Amber else Silver,
            )
            Text(
                text = stringResource(R.string.tv_home_layout_move_down),
                style = MaterialTheme.typography.labelMedium,
                color = if (isMoveMode) Amber else Silver,
            )
        }
    }
}
