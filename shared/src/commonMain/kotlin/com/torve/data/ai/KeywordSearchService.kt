package com.torve.data.ai

import com.torve.data.metadata.TmdbGenres
import com.torve.domain.repository.MetadataRepository

data class KeywordSearchResult(
    val title: String,
    val mode: String = "discover", // "discover", "specific", "person_credits", or "person_filtered"
    val genreIds: List<Int> = emptyList(),
    val keywordIds: List<Int> = emptyList(),
    val inferredKeywordTerms: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val sortBy: String = "popularity.desc",
    val minRating: Float? = null,
    val mediaType: String? = null,
    val specificItems: List<SpecificItem> = emptyList(),
    val personId: Int? = null,
    val personName: String? = null,
    val isDirector: Boolean = false,
)

data class SpecificItem(
    val tmdbId: Int,
    val title: String,
    val mediaType: String, // "movie" or "tv"
)

class KeywordSearchService(
    private val aiSuggestClient: AiSuggestClient,
    private val metadataRepo: MetadataRepository,
) {
    /**
     * Primary path: AI interprets the phrase, choosing discover, specific, or person_credits mode.
     */
    suspend fun searchWithAi(provider: AiProvider, apiKey: String, phrase: String): KeywordSearchResult {
        val normalizedPhrase = normalizePhrase(phrase)
        val suggestion = aiSuggestClient.suggest(provider, apiKey, phrase)
        val title = suggestion.title.ifBlank { phrase.replaceFirstChar { it.uppercase() } }

        // Person credits mode: AI identified an actor/director query (no extra filters)
        if (suggestion.mode == "person_credits" && !suggestion.personName.isNullOrBlank()) {
            return resolvePersonCredits(suggestion, title)
        }

        // Person filtered mode: person + genre/keyword/era constraints → discover with cast/crew
        if (suggestion.mode == "person_filtered" && !suggestion.personName.isNullOrBlank()) {
            return resolvePersonFiltered(suggestion, title)
        }

        // Specific mode: AI identified exact titles → resolve via TMDB search
        if (suggestion.mode == "specific" && suggestion.specificTitles.isNotEmpty()) {
            val specificItems = suggestion.specificTitles.mapNotNull { aiTitle ->
                try {
                    val results = metadataRepo.searchMulti(aiTitle.title)
                    // Find best match: prefer exact title + year match
                    val match = results.firstOrNull { item ->
                        val titleMatch = item.title.equals(aiTitle.title, ignoreCase = true)
                        val yearMatch = aiTitle.year == null || item.year == aiTitle.year
                        titleMatch && yearMatch
                    } ?: results.firstOrNull { item ->
                        item.title.contains(aiTitle.title, ignoreCase = true)
                    } ?: results.firstOrNull()
                    match?.let {
                        val tmdbId = it.tmdbId ?: it.id.toIntOrNull() ?: return@let null
                        val type = when (it.type) {
                            com.torve.domain.model.MediaType.SERIES -> "tv"
                            else -> "movie"
                        }
                        SpecificItem(tmdbId = tmdbId, title = it.title, mediaType = type)
                    }
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.tmdbId }

            return KeywordSearchResult(
                title = title,
                mode = "specific",
                specificItems = specificItems,
            )
        }

        // Discover mode: resolve ONLY the AI-provided keyword terms to TMDB keyword IDs
        // Do NOT add extracted/expanded terms — trust the AI's structured output
        val keywordTerms = suggestion.keywordTerms
            .map { normalizeTerm(it) }
            .distinct()
            .take(5)
        val keywordIds = keywordTerms.mapNotNull { term ->
            try {
                val results = metadataRepo.searchKeywords(term)
                results.firstOrNull { it.name.equals(term, ignoreCase = true) }?.id
                    ?: results.firstOrNull()?.id
            } catch (_: Exception) {
                null
            }
        }.distinct().take(5)

        return KeywordSearchResult(
            title = title,
            mode = "discover",
            genreIds = suggestion.genreIds,
            keywordIds = keywordIds,
            inferredKeywordTerms = keywordTerms,
            yearFrom = suggestion.yearFrom,
            yearTo = suggestion.yearTo,
            sortBy = suggestion.sortBy,
            minRating = suggestion.minRating,
            mediaType = suggestion.mediaType,
        )
    }

    /**
     * Resolve person_credits mode: search for the person, get their credits,
     * and return as a discover result with cast/crew filter.
     */
    private suspend fun resolvePersonCredits(
        suggestion: AiSuggestResult,
        title: String,
    ): KeywordSearchResult {
        val personName = suggestion.personName!!
        val isDirector = suggestion.personRole == "directing"

        // Search for the person to get their TMDB ID
        val personResults = try { metadataRepo.searchPerson(personName) } catch (_: Exception) { emptyList() }
        val person = personResults.firstOrNull()

        if (person == null) {
            // Fallback: if person not found, return empty discover
            return KeywordSearchResult(
                title = title,
                mode = "discover",
            )
        }

        return KeywordSearchResult(
            title = title,
            mode = "person_credits",
            personId = person.id,
            personName = person.name,
            isDirector = isDirector,
            genreIds = suggestion.genreIds,
            yearFrom = suggestion.yearFrom,
            yearTo = suggestion.yearTo,
            sortBy = suggestion.sortBy,
            minRating = suggestion.minRating,
            mediaType = suggestion.mediaType,
        )
    }

    /**
     * Resolve person_filtered mode: AI identifies specific titles matching
     * person + constraints. We resolve those titles via TMDB search,
     * and keep the person ID as fallback for discover if needed.
     */
    private suspend fun resolvePersonFiltered(
        suggestion: AiSuggestResult,
        title: String,
    ): KeywordSearchResult {
        val personName = suggestion.personName!!
        val isDirector = suggestion.personRole == "directing"

        // Resolve person name → TMDB person ID (for fallback / custom section editor)
        val personResults = try { metadataRepo.searchPerson(personName) } catch (_: Exception) { emptyList() }
        val person = personResults.firstOrNull()

        // Resolve AI-identified specific titles via TMDB search
        val specificItems = if (suggestion.specificTitles.isNotEmpty()) {
            suggestion.specificTitles.mapNotNull { aiTitle ->
                try {
                    val results = metadataRepo.searchMulti(aiTitle.title)
                    val match = results.firstOrNull { item ->
                        val titleMatch = item.title.equals(aiTitle.title, ignoreCase = true)
                        val yearMatch = aiTitle.year == null || item.year == aiTitle.year
                        titleMatch && yearMatch
                    } ?: results.firstOrNull { item ->
                        item.title.contains(aiTitle.title, ignoreCase = true)
                    } ?: results.firstOrNull()
                    match?.let {
                        val tmdbId = it.tmdbId ?: it.id.toIntOrNull() ?: return@let null
                        val type = when (it.type) {
                            com.torve.domain.model.MediaType.SERIES -> "tv"
                            else -> "movie"
                        }
                        SpecificItem(tmdbId = tmdbId, title = it.title, mediaType = type)
                    }
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.tmdbId }
        } else {
            emptyList()
        }

        return KeywordSearchResult(
            title = title,
            mode = "person_filtered",
            specificItems = specificItems,
            personId = person?.id,
            personName = person?.name ?: personName,
            isDirector = isDirector,
            genreIds = suggestion.genreIds,
            yearFrom = suggestion.yearFrom,
            yearTo = suggestion.yearTo,
            sortBy = suggestion.sortBy,
            minRating = suggestion.minRating,
            mediaType = suggestion.mediaType,
        )
    }

    /**
     * Fallback: split phrase into terms, infer genres from common words,
     * search TMDB keyword API for specific terms.
     */
    suspend fun searchWithTmdbFallback(phrase: String): KeywordSearchResult {
        val normalizedPhrase = normalizePhrase(phrase)
        val lower = normalizedPhrase.lowercase()
        val terms = extractSearchTerms(normalizedPhrase)

        // Infer genres from phrase
        val genreIds = inferGenres(lower)

        // Only resolve non-genre terms as keywords (specific themes like "beach", "christmas")
        val genreWords = GENRE_WORD_MAP.keys
        val keywordTerms = terms.filter { term ->
            term.split(" ").none { it in genreWords }
        }.map { normalizeTerm(it) }
        val expandedTerms = keywordTerms.flatMap { expandThemeSynonyms(it) }.distinct().take(10)

        val keywordIds = expandedTerms.mapNotNull { term ->
            try {
                val results = metadataRepo.searchKeywords(term)
                results.firstOrNull { it.name.equals(term, ignoreCase = true) }?.id
                    ?: results.firstOrNull()?.id
            } catch (_: Exception) {
                null
            }
        }.distinct().take(5)

        return KeywordSearchResult(
            title = phrase.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
            genreIds = genreIds,
            keywordIds = keywordIds,
            inferredKeywordTerms = expandedTerms,
        )
    }

    private fun inferGenres(phrase: String): List<Int> {
        val matched = mutableSetOf<Int>()
        GENRE_WORD_MAP.forEach { (word, genreId) ->
            if (word in phrase) matched.add(genreId)
        }
        return matched.toList()
    }

    private fun extractSearchTerms(phrase: String): List<String> {
        val stopWords = setOf(
            "the", "a", "an", "with", "in", "for", "and", "or", "of",
            "about", "like", "by", "on", "at", "to", "from", "scene",
            "scenes", "movies", "movie", "shows", "show", "series",
        )
        val words = phrase.lowercase().split("\\s+".toRegex())
            .filter { it.length > 1 && it !in stopWords }

        val terms = mutableListOf<String>()

        // Full phrase if short enough
        if (words.size in 1..3) {
            terms.add(words.joinToString(" "))
        }

        // Individual significant words
        words.forEach { word ->
            if (word.length > 3) terms.add(word)
        }

        // 2-word combinations for longer phrases
        if (words.size > 3) {
            for (i in 0 until words.size - 1) {
                terms.add("${words[i]} ${words[i + 1]}")
            }
        }

        return terms.distinct().take(5)
    }

    private fun extractSettingKeywords(phrase: String): List<String> {
        val lower = phrase.lowercase()
        val terms = mutableSetOf<String>()

        fun addIfContains(token: String, keyword: String = token) {
            if (lower.contains(token)) terms.add(keyword)
        }

        addIfContains("beach")
        addIfContains("island")
        addIfContains("jungle")
        addIfContains("forest")
        addIfContains("desert")
        if (lower.contains("ocean") || lower.contains("sea")) terms.add("ocean")
        addIfContains("underwater")
        if (lower.contains("space") || lower.contains("outer space")) terms.add("space")
        if (lower.contains("spaceship") || lower.contains("space ship") || lower.contains("rocketship") || lower.contains("rocket ship")) terms.add("spaceship")
        addIfContains("mountain")
        if (lower.contains("snow") || lower.contains("ice")) terms.add("snow")

        // Spiritual / meditation themes
        if (lower.contains("buddhism") || lower.contains("buddhist")) terms.add("buddhism")
        if (lower.contains("meditation") || lower.contains("meditate") || lower.contains("meditating")) terms.add("meditation")
        if (lower.contains("spiritual") || lower.contains("spirituality")) terms.add("spirituality")
        if (lower.contains("dalai lama") || lower.contains("dalai-lama")) terms.add("dalai lama")

        // Gendered/targeted phrases (soft heuristics)
        if (lower.contains("for girls") || lower.contains("for women") || lower.contains("chick flick")) {
            terms.addAll(listOf("romance", "coming of age", "friendship", "drama"))
        }
        if (lower.contains("for guys") || lower.contains("for men") || lower.contains("bro movie")) {
            terms.addAll(listOf("action", "crime", "war", "sports"))
        }

        // Sexuality / nudity
        if (lower.contains("nudity") || lower.contains("sexual") || lower.contains("erotic")) {
            terms.addAll(listOf("nudity", "sexuality"))
        }

        // Animation / anime
        if (lower.contains("anime")) terms.add("anime")
        if (lower.contains("animation") || lower.contains("animated")) terms.add("animation")

        return terms.toList()
    }

    private fun normalizePhrase(phrase: String): String {
        val wordRegex = Regex("\\b[\\p{L}']+\\b")
        val sb = StringBuilder()
        var last = 0
        for (m in wordRegex.findAll(phrase)) {
            sb.append(phrase.substring(last, m.range.first))
            val original = m.value
            val lower = original.lowercase()
            val corrected = correctToken(lower)
            sb.append(corrected)
            last = m.range.last + 1
        }
        if (last < phrase.length) sb.append(phrase.substring(last))
        return sb.toString()
    }

    private fun normalizeTerm(term: String): String {
        val trimmed = term.trim()
        if (trimmed.isBlank()) return trimmed
        val lower = trimmed.lowercase()
        val corrected = correctToken(lower)
        return corrected
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

    private fun expandThemeSynonyms(term: String): List<String> {
        val lower = term.lowercase().trim()
        val out = mutableSetOf(lower)
        THEME_SYNONYMS[lower]?.forEach { out.add(it) }
        return out.toList()
    }

    companion object {
        /** Common words → TMDB movie genre IDs for fallback inference. */
        private val GENRE_WORD_MAP = mapOf(
            "action" to 28,
            "adventure" to 12,
            "animation" to 16,
            "animated" to 16,
            "comedy" to 35,
            "comedies" to 35,
            "funny" to 35,
            "crime" to 80,
            "documentary" to 99,
            "documentaries" to 99,
            "drama" to 18,
            "dramatic" to 18,
            "family" to 10751,
            "fantasy" to 14,
            "history" to 36,
            "historical" to 36,
            "horror" to 27,
            "scary" to 27,
            "music" to 10402,
            "musical" to 10402,
            "mystery" to 9648,
            "romance" to 10749,
            "romantic" to 10749,
            "sci-fi" to 878,
            "science fiction" to 878,
            "thriller" to 53,
            "war" to 10752,
            "western" to 37,
        )

        private val THEME_SYNONYMS = mapOf(
            "spirituality" to listOf("spiritual", "faith", "religion", "enlightenment"),
            "spiritual" to listOf("spirituality", "faith", "religion", "enlightenment"),
            "meditation" to listOf("mindfulness", "zen", "retreat"),
            "buddhism" to listOf("buddhist", "monk", "monastery", "dharma"),
            "buddhist" to listOf("buddhism", "monk", "monastery", "dharma"),
            "dalai lama" to listOf("tibet", "lama"),
            "beach" to listOf("coast", "seaside"),
            "island" to listOf("tropical", "deserted island"),
            "space" to listOf("sci-fi", "astronaut"),
            "spaceship" to listOf("space ship", "starship", "rocketship"),
            "jungle" to listOf("rainforest", "amazon"),
            "forest" to listOf("woods", "woodland"),
            "desert" to listOf("dune"),
            "ocean" to listOf("sea"),
            "nudity" to listOf("sexuality", "erotic", "adult"),
            "sexuality" to listOf("nudity", "erotic", "adult"),
            "anime" to listOf("animation", "manga"),
        )

        private val SPELLING_DICT = setOf(
            // settings
            "beach", "island", "jungle", "forest", "desert", "ocean", "sea", "underwater",
            "space", "spaceship", "rocketship", "mountain", "snow", "ice",
            // themes
            "spiritual", "spirituality", "meditation", "mindfulness", "zen", "retreat",
            "buddhism", "buddhist", "monk", "monastery", "dharma", "tibet", "dalai", "lama",
            "faith", "religion", "enlightenment",
            // genres / types
            "action", "adventure", "animation", "animated", "anime", "comedy", "crime",
            "documentary", "documentaries", "drama", "family", "fantasy", "history",
            "historical", "horror", "music", "musical", "mystery", "romance",
            "romantic", "sci", "scifi", "science", "fiction", "thriller", "war", "western",
            // content
            "nudity", "sexuality", "erotic", "adult",
            // countries
            "united", "states", "america", "usa", "us", "kingdom", "uk", "british",
            "germany", "german", "france", "french", "italy", "italian", "spain", "spanish",
            "japan", "japanese", "korea", "korean", "china", "chinese", "india", "hindi",
            "canada", "canadian", "australia", "australian", "mexico", "mexican",
            "brazil", "brazilian", "russia", "russian",
            // languages
            "english", "german", "french", "spanish", "italian", "japanese", "korean",
            "chinese", "hindi", "russian", "portuguese",
            // ratings
            "pg", "pg-13", "r", "nc-17", "g",
        )
    }
}
