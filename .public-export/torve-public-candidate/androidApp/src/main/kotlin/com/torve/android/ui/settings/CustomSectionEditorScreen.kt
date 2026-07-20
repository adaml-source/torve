package com.torve.android.ui.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.data.ai.KeywordSearchService
import com.torve.data.ai.SpecificItem
import com.torve.data.metadata.TmdbGenres
import com.torve.domain.model.CustomSection
import com.torve.domain.model.CustomSectionFilters
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.SavedPerson
import com.torve.domain.model.SpecificTmdbItem
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.settings.SettingsViewModel
import java.time.LocalDate
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
    var originCountries by remember { mutableStateOf(existingSection?.filters?.originCountries ?: emptyList()) }
    var originalLanguage by remember { mutableStateOf(existingSection?.filters?.originalLanguage ?: "") }
    var runtimeGte by remember { mutableStateOf(existingSection?.filters?.runtimeGte?.toString() ?: "") }
    var runtimeLte by remember { mutableStateOf(existingSection?.filters?.runtimeLte?.toString() ?: "") }
    var certification by remember { mutableStateOf(existingSection?.filters?.certification ?: "") }
    var certificationGte by remember { mutableStateOf(existingSection?.filters?.certificationGte ?: "") }
    var certificationLte by remember { mutableStateOf(existingSection?.filters?.certificationLte ?: "") }
    var certificationCountry by remember { mutableStateOf(existingSection?.filters?.certificationCountry ?: "US") }
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
    var inferredKeywordTerms by remember { mutableStateOf<List<String>>(emptyList()) }

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
                val normalizedQuery = normalizeAiQuery(aiSearchQuery)
                val result = if (aiKey.isNotBlank()) {
                    keywordSearchService.searchWithAi(settingsState.aiProvider, aiKey, normalizedQuery)
                } else {
                    keywordSearchService.searchWithTmdbFallback(normalizedQuery)
                }

                if (result.mode == "specific" && result.specificItems.isNotEmpty()) {
                    // Specific mode: set matched titles, clear discover filters
                    specificItems = result.specificItems
                    if (result.title.isNotBlank()) title = result.title
                    selectedGenres = emptyList()
                    keywordIds = emptyList()
                    inferredKeywordTerms = result.inferredKeywordTerms
                    yearFrom = ""
                    yearTo = ""
                    minRating = ""
                    sortBy = "popularity.desc"
                } else if (result.mode == "person_filtered" && result.specificItems.isNotEmpty()) {
                    // Person filtered with AI-identified specific titles
                    specificItems = result.specificItems
                    if (result.title.isNotBlank()) title = result.title
                    selectedGenres = emptyList()
                    keywordIds = emptyList()
                    inferredKeywordTerms = emptyList()
                    yearFrom = ""
                    yearTo = ""
                    minRating = ""
                    sortBy = "popularity.desc"
                } else if ((result.mode == "person_credits" || result.mode == "person_filtered") && result.personId != null) {
                    // Person credits or person_filtered fallback (no specific titles): add as cast/crew filter
                    specificItems = emptyList()
                    if (result.title.isNotBlank()) title = result.title
                    val pId = result.personId!!
                    val saved = SavedPerson(pId, result.personName ?: "")
                    if (result.isDirector) {
                        crewPersons = (crewPersons + saved).distinctBy { it.id }
                    } else {
                        castPersons = (castPersons + saved).distinctBy { it.id }
                    }
                    if (result.genreIds.isNotEmpty()) selectedGenres = result.genreIds
                    if (result.keywordIds.isNotEmpty()) keywordIds = result.keywordIds
                    inferredKeywordTerms = result.inferredKeywordTerms
                    result.yearFrom?.let { yearFrom = it.toString() }
                    result.yearTo?.let { yearTo = it.toString() }
                    if (result.sortBy.isNotBlank()) sortBy = result.sortBy
                    result.minRating?.let { minRating = it.toString() }
                    result.mediaType?.let { mediaType = it }
                } else {
                    // Discover mode: auto-fill filter fields, clear specific items
                    specificItems = emptyList()
                    if (result.title.isNotBlank()) title = result.title
                    if (result.genreIds.isNotEmpty()) selectedGenres = result.genreIds
                    if (result.keywordIds.isNotEmpty()) keywordIds = result.keywordIds
                    inferredKeywordTerms = result.inferredKeywordTerms
                    result.yearFrom?.let { yearFrom = it.toString() }
                    result.yearTo?.let { yearTo = it.toString() }
                    if (result.sortBy.isNotBlank()) sortBy = result.sortBy
                    result.minRating?.let { minRating = it.toString() }
                    result.mediaType?.let { mediaType = it }

                    val lowered = normalizedQuery.lowercase()

                    // Country / region parsing (origin country)
                    val countryMap = mapOf(
                        "united states" to "US",
                        "usa" to "US",
                        "us" to "US",
                        "america" to "US",
                        "united kingdom" to "GB",
                        "uk" to "GB",
                        "british" to "GB",
                        "germany" to "DE",
                        "german" to "DE",
                        "france" to "FR",
                        "french" to "FR",
                        "italy" to "IT",
                        "italian" to "IT",
                        "spain" to "ES",
                        "spanish" to "ES",
                        "japan" to "JP",
                        "japanese" to "JP",
                        "korea" to "KR",
                        "korean" to "KR",
                        "china" to "CN",
                        "chinese" to "CN",
                        "india" to "IN",
                        "hindi" to "IN",
                        "canada" to "CA",
                        "canadian" to "CA",
                        "australia" to "AU",
                        "australian" to "AU",
                        "mexico" to "MX",
                        "mexican" to "MX",
                        "brazil" to "BR",
                        "brazilian" to "BR",
                        "russia" to "RU",
                        "russian" to "RU",
                    )
                    val countryMatches = countryMap.filterKeys { key ->
                        if (key == "us" || key == "uk") Regex("\\b$key\\b").containsMatchIn(lowered) else lowered.contains(key)
                    }.values.toSet()
                    if (countryMatches.isNotEmpty()) {
                        originCountries = countryMatches.toList()
                    }

                    // Language parsing
                    val languageMap = mapOf(
                        "english" to "en",
                        "german" to "de",
                        "french" to "fr",
                        "spanish" to "es",
                        "italian" to "it",
                        "japanese" to "ja",
                        "korean" to "ko",
                        "chinese" to "zh",
                        "hindi" to "hi",
                        "russian" to "ru",
                        "portuguese" to "pt",
                    )
                    val langMatch = languageMap.keys.firstOrNull { lowered.contains("in $it") || lowered.contains("$it language") || lowered.contains("$it audio") || lowered.contains("$it-speaking") }
                    if (langMatch != null) {
                        originalLanguage = languageMap[langMatch] ?: ""
                    }

                    // Runtime parsing
                    val underMinutes = Regex("""under (\d{2,3}) (?:minutes|min)""").find(lowered)?.groupValues?.get(1)?.toIntOrNull()
                    val overMinutes = Regex("""over (\d{2,3}) (?:minutes|min)""").find(lowered)?.groupValues?.get(1)?.toIntOrNull()
                    val underHours = Regex("""under (\d{1,2}) hours?""").find(lowered)?.groupValues?.get(1)?.toIntOrNull()
                    val overHours = Regex("""over (\d{1,2}) hours?""").find(lowered)?.groupValues?.get(1)?.toIntOrNull()
                    val betweenMinutes = Regex("""between (\d{2,3}) and (\d{2,3}) (?:minutes|min)""")
                        .find(lowered)?.groupValues
                    if (underMinutes != null) runtimeLte = underMinutes.toString()
                    if (overMinutes != null) runtimeGte = overMinutes.toString()
                    if (underHours != null) runtimeLte = (underHours * 60).toString()
                    if (overHours != null) runtimeGte = (overHours * 60).toString()
                    if (betweenMinutes != null && betweenMinutes.size >= 3) {
                        runtimeGte = betweenMinutes[1]
                        runtimeLte = betweenMinutes[2]
                    }
                    if (lowered.contains("short")) runtimeLte = "90"
                    if (lowered.contains("long")) runtimeGte = "140"

                    // Certification / age restriction parsing (US default)
                    if (lowered.contains("pg-13")) certification = "PG-13"
                    else if (Regex("\\bpg\\b").containsMatchIn(lowered)) certification = "PG"
                    else if (Regex("\\br\\b").containsMatchIn(lowered)) certification = "R"
                    else if (lowered.contains("nc-17")) certification = "NC-17"
                    else if (Regex("\\bg\\b").containsMatchIn(lowered)) certification = "G"

                    if (lowered.contains("family") || lowered.contains("kids") || lowered.contains("children") || lowered.contains("child")) {
                        certificationLte = "PG"
                        certificationCountry = "US"
                    }
                    if (lowered.contains("no r") || lowered.contains("not r") || lowered.contains("without r")) {
                        certificationLte = "PG-13"
                        certificationCountry = "US"
                    }
                    if (lowered.contains("18+")) {
                        certificationGte = "R"
                        certificationCountry = "US"
                    }
                    if (certification.isNotBlank()) {
                        certificationCountry = "US"
                    }

                    // Director parsing (supplement — only if AI didn't use person_credits)
                    val directorMatch = Regex("""(?:directed by|by)\s+([a-zA-Z .'\-]+)""")
                        .find(normalizedQuery)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                    if (!directorMatch.isNullOrBlank()) {
                        val director = try { metadataRepo.searchPerson(directorMatch).firstOrNull() } catch (_: Exception) { null }
                        director?.let { person ->
                            crewPersons = (crewPersons + SavedPerson(person.id, person.name)).distinctBy { it.id }
                        }
                    }

                    // If the prompt mentions people, auto-add them as cast filters.
                    val personTrigger = listOf("with ", "featuring ", "starring ", "starring:", "feat ", "feat.")
                    if (personTrigger.any { lowered.contains(it) }) {
                        val tail = lowered
                            .substringAfter("with ", lowered)
                            .substringAfter("featuring ", lowered)
                            .substringAfter("starring ", lowered)
                            .substringAfter("starring:", lowered)
                            .substringAfter("feat ", lowered)
                            .substringAfter("feat.", lowered)
                        val names = tail
                            .split(" and ", " or ", ",", "&")
                            .map { it.trim() }
                            .filter { it.length >= 3 }
                            .take(3)
                        val found = names.mapNotNull { name ->
                            try {
                                metadataRepo.searchPerson(name).firstOrNull()
                            } catch (_: Exception) { null }
                        }.map { SavedPerson(it.id, it.name) }
                        if (found.isNotEmpty()) {
                            castPersons = (castPersons + found).distinctBy { it.id }
                        }
                    }

                    // Parse common age/decade constraints (override AI when explicit)
                    val now = LocalDate.now().year
                    val pastYears = Regex("""(?:past|last)\s+(\d{1,2})\s+years?""")
                        .find(lowered)?.groupValues?.get(1)?.toIntOrNull()
                    if (pastYears != null) {
                        yearFrom = (now - pastYears + 1).toString()
                        yearTo = now.toString()
                    }

                    val ageLimit = Regex("""no (?:movies?|films?) older than (\d{1,2}) years?""")
                        .find(lowered)?.groupValues?.get(1)?.toIntOrNull()
                    if (ageLimit != null) {
                        yearFrom = (now - ageLimit).toString()
                    }

                    val decade = Regex("""from the (\d{2})s|(\d{4})s""").find(lowered)
                    val decadeTwo = decade?.groupValues?.get(1)?.toIntOrNull()
                    val decadeFour = decade?.groupValues?.get(2)?.toIntOrNull()
                    val decadeStart = when {
                        decadeFour != null -> decadeFour
                        decadeTwo != null -> 1900 + decadeTwo
                        else -> null
                    }
                    if (decadeStart != null) {
                        yearFrom = decadeStart.toString()
                        yearTo = (decadeStart + 9).toString()
                    }

                    // Parse rating constraints if AI didn't set them
                    if (result.minRating == null) {
                        val ratingMatch = Regex("""(?:not rated below|at least|minimum|>=)\s*([0-9]+(?:\.[0-9])?)""")
                            .find(lowered)
                        val starsMatch = Regex("""([0-9]+(?:\.[0-9])?)\s*stars?""").find(lowered)
                        val rating = ratingMatch?.groupValues?.get(1)?.toFloatOrNull()
                            ?: starsMatch?.groupValues?.get(1)?.toFloatOrNull()
                        if (rating != null) {
                            minRating = rating.toString()
                        }
                    }

                    // If user explicitly wants documentaries + movies, ensure genre/media type reflect that
                    if (result.genreIds.isEmpty() && (lowered.contains("documentary") || lowered.contains("documentaries"))) {
                        selectedGenres = (selectedGenres + 99).distinct()
                    }
                    if (result.mediaType.isNullOrBlank() && (lowered.contains("documentary") || lowered.contains("documentaries"))) {
                        mediaType = if (lowered.contains("movie") || lowered.contains("movies")) "both" else "movie"
                    }
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

            if (inferredKeywordTerms.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("Inferred Keywords")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    inferredKeywordTerms.take(10).forEach { term ->
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text(term) },
                            colors = chipColors(),
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
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
                label = { Text(stringResource(R.string.custom_section_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
            )

            // Discover filters (hidden when specific titles are selected)
            if (specificItems.isEmpty()) {
                Spacer(Modifier.height(20.dp))

                // Media Type
                SectionLabel(stringResource(R.string.custom_media_type))
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
                SectionLabel(stringResource(R.string.catalog_sort_by))
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
                SectionLabel(stringResource(R.string.custom_genres))
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
                    SectionLabel(stringResource(R.string.custom_keywords))
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
                        label = { Text(stringResource(R.string.custom_min_rating)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = yearFrom,
                        onValueChange = { yearFrom = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.custom_year_from)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = yearTo,
                        onValueChange = { yearTo = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.custom_year_to)) },
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
                            originCountries = if (originCountries.isEmpty()) listOf("US") else originCountries,
                            originalLanguage = originalLanguage.takeIf { it.isNotBlank() },
                            runtimeGte = runtimeGte.toIntOrNull(),
                            runtimeLte = runtimeLte.toIntOrNull(),
                            certification = certification.takeIf { it.isNotBlank() },
                            certificationGte = certificationGte.takeIf { it.isNotBlank() },
                            certificationLte = certificationLte.takeIf { it.isNotBlank() },
                            certificationCountry = certificationCountry.takeIf { it.isNotBlank() } ?: "US",
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
                Text(
                    if (isEditing) stringResource(R.string.custom_save_changes) else stringResource(R.string.custom_create_section),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun normalizeAiQuery(input: String): String {
    val wordRegex = Regex("\\b[\\p{L}']+\\b")
    val sb = StringBuilder()
    var last = 0
    for (m in wordRegex.findAll(input)) {
        sb.append(input.substring(last, m.range.first))
        val original = m.value
        val lower = original.lowercase()
        val corrected = correctToken(lower)
        sb.append(corrected)
        last = m.range.last + 1
    }
    if (last < input.length) sb.append(input.substring(last))
    return sb.toString()
}

private fun correctToken(token: String): String {
    if (token.length < 4) return token
    if (SPELLING_DICT.contains(token)) return token
    var best = token
    var bestDist = 3
    for (candidate in SPELLING_DICT) {
        val dist = editDistance(token, candidate)
        if (dist < bestDist) {
            bestDist = dist
            best = candidate
            if (bestDist == 1) break
        }
    }
    return if (bestDist <= 2) best else token
}

private fun editDistance(a: String, b: String): Int {
    val n = a.length
    val m = b.length
    if (n == 0) return m
    if (m == 0) return n
    val dp = IntArray(m + 1) { it }
    for (i in 1..n) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..m) {
            val tmp = dp[j]
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[j] = minOf(
                dp[j] + 1,
                dp[j - 1] + 1,
                prev + cost,
            )
            prev = tmp
        }
    }
    return dp[m]
}

private val SPELLING_DICT = setOf(
    "beach", "island", "jungle", "forest", "desert", "ocean", "sea", "underwater",
    "space", "spaceship", "rocketship", "mountain", "snow", "ice",
    "spiritual", "spirituality", "meditation", "mindfulness", "zen", "retreat",
    "buddhism", "buddhist", "monk", "monastery", "dharma", "tibet", "dalai", "lama",
    "faith", "religion", "enlightenment",
    "action", "adventure", "animation", "animated", "anime", "comedy", "crime",
    "documentary", "documentaries", "drama", "family", "fantasy", "history",
    "historical", "horror", "music", "musical", "mystery", "romance",
    "romantic", "sci", "scifi", "science", "fiction", "thriller", "war", "western",
    "nudity", "sexuality", "erotic", "adult",
    "united", "states", "america", "usa", "us", "kingdom", "uk", "british",
    "germany", "german", "france", "french", "italy", "italian", "spain", "spanish",
    "japan", "japanese", "korea", "korean", "china", "chinese", "india", "hindi",
    "canada", "canadian", "australia", "australian", "mexico", "mexican",
    "brazil", "brazilian", "russia", "russian",
    "english", "german", "french", "spanish", "italian", "japanese", "korean",
    "chinese", "hindi", "russian", "portuguese",
    "pg", "pg-13", "r", "nc-17", "g",
)

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
        placeholder = { Text(stringResource(R.string.custom_search_person), color = Silver) },
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
