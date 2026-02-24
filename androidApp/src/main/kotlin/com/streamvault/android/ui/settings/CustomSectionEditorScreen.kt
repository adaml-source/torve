package com.streamvault.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Ruby
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.data.ai.KeywordSearchService
import com.streamvault.data.ai.SpecificItem
import com.streamvault.data.metadata.TmdbGenres
import com.streamvault.domain.model.CustomSection
import com.streamvault.domain.model.CustomSectionFilters
import com.streamvault.domain.model.PersonSummary
import com.streamvault.domain.model.SavedPerson
import com.streamvault.domain.model.SpecificTmdbItem
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.presentation.home.HomeViewModel
import com.streamvault.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomSectionEditorScreen(
    sectionId: String?,
    onBack: () -> Unit,
    viewModel: HomeViewModel = koinInject(),
    metadataRepo: MetadataRepository = koinInject(),
    keywordSearchService: KeywordSearchService = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val isEditing = sectionId != null
    val settingsState by settingsViewModel.state.collectAsState()

    // Load existing section if editing
    val existingSection = remember(sectionId) {
        sectionId?.let { id -> viewModel.customSections.value.find { it.id == id } }
    }

    var title by remember { mutableStateOf(existingSection?.title ?: "") }
    var mediaType by remember { mutableStateOf(existingSection?.mediaType ?: "movie") }
    var sortBy by remember { mutableStateOf(existingSection?.filters?.sortBy ?: "popularity.desc") }
    var selectedGenres by remember { mutableStateOf(existingSection?.filters?.genreIds ?: emptyList()) }
    var minRating by remember { mutableStateOf(existingSection?.filters?.minRating?.toString() ?: "") }
    var yearFrom by remember { mutableStateOf(existingSection?.filters?.yearFrom?.toString() ?: "") }
    var yearTo by remember { mutableStateOf(existingSection?.filters?.yearTo?.toString() ?: "") }
    var castPersons by remember { mutableStateOf(existingSection?.filters?.withCast ?: emptyList()) }
    var crewPersons by remember { mutableStateOf(existingSection?.filters?.withCrew ?: emptyList()) }
    var keywordIds by remember { mutableStateOf(existingSection?.filters?.withKeywords ?: emptyList()) }
    var specificItems: List<SpecificItem> by remember {
        val initial = existingSection?.filters?.specificTmdbIds?.map {
            SpecificItem(tmdbId = it.tmdbId, title = it.title, mediaType = it.mediaType)
        } ?: emptyList()
        mutableStateOf(initial)
    }

    // AI search state
    var aiSearchQuery by remember { mutableStateOf("") }
    var isAiSearching by remember { mutableStateOf(false) }
    var aiSearchError by remember { mutableStateOf<String?>(null) }

    // Person search state
    var personSearchQuery by remember { mutableStateOf("") }
    var personSearchResults by remember { mutableStateOf(emptyList<PersonSummary>()) }
    var searchingForRole by remember { mutableStateOf("cast") }

    val genres = remember(mediaType) {
        when (mediaType) {
            "tv" -> TmdbGenres.TV_GENRES
            else -> TmdbGenres.MOVIE_GENRES
        }
    }

    val sortOptions = listOf(
        "popularity.desc" to "Most Popular",
        "vote_average.desc" to "Highest Rated",
        "primary_release_date.desc" to "Newest First",
        "revenue.desc" to "Highest Revenue",
    )

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Amber,
        unfocusedBorderColor = Gunmetal,
        focusedLabelColor = Amber,
        unfocusedLabelColor = Silver,
        cursorColor = Amber,
        focusedTextColor = Snow,
        unfocusedTextColor = Snow,
    )

    fun performAiSearch() {
        if (aiSearchQuery.isBlank()) return
        isAiSearching = true
        aiSearchError = null
        scope.launch {
            try {
                val aiKey = settingsState.activeAiApiKey
                val result = if (aiKey.isNotBlank()) {
                    keywordSearchService.searchWithAi(settingsState.aiProvider, aiKey, aiSearchQuery)
                } else {
                    keywordSearchService.searchWithTmdbFallback(aiSearchQuery)
                }

                if (result.mode == "specific" && result.specificItems.isNotEmpty()) {
                    // Specific mode: set matched titles, clear discover filters
                    specificItems = result.specificItems
                    if (result.title.isNotBlank()) title = result.title
                    selectedGenres = emptyList()
                    keywordIds = emptyList()
                    yearFrom = ""
                    yearTo = ""
                    minRating = ""
                    sortBy = "popularity.desc"
                } else {
                    // Discover mode: auto-fill filter fields, clear specific items
                    specificItems = emptyList()
                    if (result.title.isNotBlank()) title = result.title
                    if (result.genreIds.isNotEmpty()) selectedGenres = result.genreIds
                    if (result.keywordIds.isNotEmpty()) keywordIds = result.keywordIds
                    result.yearFrom?.let { yearFrom = it.toString() }
                    result.yearTo?.let { yearTo = it.toString() }
                    if (result.sortBy.isNotBlank()) sortBy = result.sortBy
                    result.minRating?.let { minRating = it.toString() }
                    result.mediaType?.let { mediaType = it }
                }
            } catch (e: Exception) {
                aiSearchError = e.message ?: "Search failed"
            } finally {
                isAiSearching = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                if (isEditing) "Edit Section" else "New Custom Section",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
                modifier = Modifier.weight(1f),
            )
            if (isEditing) {
                IconButton(onClick = {
                    viewModel.deleteCustomSection(sectionId!!)
                    onBack()
                }) {
                    Icon(Icons.Rounded.Delete, "Delete", tint = Ruby)
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            // ── AI-Powered Search ──
            SectionLabel("Describe what you want")
            OutlinedTextField(
                value = aiSearchQuery,
                onValueChange = { aiSearchQuery = it },
                placeholder = {
                    Text(
                        "e.g. christmas movies, dark sci-fi thriller 90s...",
                        color = Silver.copy(alpha = 0.6f),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Amber)
                },
                trailingIcon = {
                    if (isAiSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Amber,
                        )
                    } else {
                        IconButton(onClick = { performAiSearch() }) {
                            Icon(Icons.Rounded.Search, "Search", tint = Amber)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performAiSearch() }),
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
            )

            if (settingsState.activeAiApiKey.isBlank()) {
                Text(
                    "Tip: Add an AI API key in Settings for smarter AI results",
                    style = MaterialTheme.typography.labelSmall,
                    color = Silver.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            aiSearchError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ruby,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Matched Titles (specific mode)
            if (specificItems.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("Matched Titles")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    specificItems.forEach { item ->
                        FilterChip(
                            selected = true,
                            onClick = { specificItems = specificItems.filter { it.tmdbId != item.tmdbId } },
                            label = { Text(item.title) },
                            trailingIcon = {
                                Icon(Icons.Rounded.Close, "Remove", modifier = Modifier.size(16.dp))
                            },
                            colors = chipColors(),
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Section Name
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Section Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
            )

            // Discover filters (hidden when specific titles are selected)
            if (specificItems.isEmpty()) {
                Spacer(Modifier.height(20.dp))

                // Media Type
                SectionLabel("Media Type")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("movie" to "Movies", "tv" to "TV Shows", "both" to "Both").forEach { (value, label) ->
                        FilterChip(
                            selected = mediaType == value,
                            onClick = { mediaType = value },
                            label = { Text(label) },
                            colors = chipColors(),
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Sort By
                SectionLabel("Sort By")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sortOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = sortBy == value,
                            onClick = { sortBy = value },
                            label = { Text(label) },
                            colors = chipColors(),
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Genres
                SectionLabel("Genres")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    genres.forEach { (id, name) ->
                        FilterChip(
                            selected = id in selectedGenres,
                            onClick = {
                                selectedGenres = if (id in selectedGenres) selectedGenres - id
                                else selectedGenres + id
                            },
                            label = { Text(name) },
                            colors = chipColors(),
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }

                // Keywords (from AI search)
                if (keywordIds.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Keywords")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        keywordIds.forEach { kwId ->
                            FilterChip(
                                selected = true,
                                onClick = { keywordIds = keywordIds - kwId },
                                label = { Text("#$kwId") },
                                trailingIcon = {
                                    Icon(Icons.Rounded.Close, "Remove", modifier = Modifier.size(16.dp))
                                },
                                colors = chipColors(),
                                shape = RoundedCornerShape(20.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Rating & Year
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = minRating,
                        onValueChange = { minRating = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Min Rating") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = yearFrom,
                        onValueChange = { yearFrom = it.filter { c -> c.isDigit() } },
                        label = { Text("Year From") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = yearTo,
                        onValueChange = { yearTo = it.filter { c -> c.isDigit() } },
                        label = { Text("Year To") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Actors
                SectionLabel("Actors")
                PersonChips(persons = castPersons, onRemove = { p -> castPersons = castPersons.filter { it.id != p.id } })
                PersonSearchField(
                    query = personSearchQuery.takeIf { searchingForRole == "cast" } ?: "",
                    onQueryChange = { personSearchQuery = it; searchingForRole = "cast" },
                    onSearch = {
                        scope.launch {
                            personSearchResults = try { metadataRepo.searchPerson(personSearchQuery) } catch (_: Exception) { emptyList() }
                        }
                    },
                    results = personSearchResults.takeIf { searchingForRole == "cast" } ?: emptyList(),
                    onSelect = { person ->
                        castPersons = castPersons + SavedPerson(person.id, person.name)
                        personSearchQuery = ""
                        personSearchResults = emptyList()
                    },
                )

                Spacer(Modifier.height(20.dp))

                // Directors
                SectionLabel("Directors / Crew")
                PersonChips(persons = crewPersons, onRemove = { p -> crewPersons = crewPersons.filter { it.id != p.id } })
                PersonSearchField(
                    query = personSearchQuery.takeIf { searchingForRole == "crew" } ?: "",
                    onQueryChange = { personSearchQuery = it; searchingForRole = "crew" },
                    onSearch = {
                        scope.launch {
                            personSearchResults = try { metadataRepo.searchPerson(personSearchQuery) } catch (_: Exception) { emptyList() }
                        }
                    },
                    results = personSearchResults.takeIf { searchingForRole == "crew" } ?: emptyList(),
                    onSelect = { person ->
                        crewPersons = crewPersons + SavedPerson(person.id, person.name)
                        personSearchQuery = ""
                        personSearchResults = emptyList()
                    },
                )
            }

            Spacer(Modifier.height(32.dp))

            // Save button
            Button(
                onClick = {
                    if (title.isBlank()) return@Button
                    val filters = if (specificItems.isNotEmpty()) {
                        CustomSectionFilters(
                            specificTmdbIds = specificItems.map {
                                SpecificTmdbItem(tmdbId = it.tmdbId, title = it.title, mediaType = it.mediaType)
                            },
                        )
                    } else {
                        CustomSectionFilters(
                            genreIds = selectedGenres,
                            sortBy = sortBy,
                            minRating = minRating.toFloatOrNull(),
                            yearFrom = yearFrom.toIntOrNull(),
                            yearTo = yearTo.toIntOrNull(),
                            withCast = castPersons,
                            withCrew = crewPersons,
                            withKeywords = keywordIds,
                        )
                    }
                    if (isEditing && existingSection != null) {
                        viewModel.updateCustomSection(
                            existingSection.copy(title = title, mediaType = mediaType, filters = filters),
                        )
                    } else {
                        viewModel.addCustomSection(
                            CustomSection(
                                id = "custom_${System.currentTimeMillis()}",
                                title = title,
                                mediaType = mediaType,
                                filters = filters,
                                order = viewModel.customSections.value.size,
                            ),
                        )
                    }
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = Obsidian,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (isEditing) "Save Changes" else "Create Section", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Silver,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Amber,
    selectedLabelColor = Obsidian,
    containerColor = Gunmetal,
    labelColor = Silver,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonChips(persons: List<SavedPerson>, onRemove: (SavedPerson) -> Unit) {
    if (persons.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        persons.forEach { person ->
            FilterChip(
                selected = true,
                onClick = { onRemove(person) },
                label = { Text(person.name) },
                trailingIcon = {
                    Icon(Icons.Rounded.Close, "Remove", modifier = Modifier.size(16.dp))
                },
                colors = chipColors(),
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}

@Composable
private fun PersonSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    results: List<PersonSummary>,
    onSelect: (PersonSummary) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search person...", color = Silver) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Rounded.Search, "Search", tint = Silver)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber,
            unfocusedBorderColor = Gunmetal,
            focusedLabelColor = Amber,
            unfocusedLabelColor = Silver,
            cursorColor = Amber,
            focusedTextColor = Snow,
            unfocusedTextColor = Snow,
        ),
        shape = RoundedCornerShape(12.dp),
    )

    if (results.isNotEmpty()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = Gunmetal,
        ) {
            Column {
                results.take(5).forEach { person ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(person) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = person.profileUrl,
                            contentDescription = person.name,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(person.name, color = Snow, style = MaterialTheme.typography.bodyMedium)
                            person.knownForDepartment?.let {
                                Text(it, color = Silver, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
