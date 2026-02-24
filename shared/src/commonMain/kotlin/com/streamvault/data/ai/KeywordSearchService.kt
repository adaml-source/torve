package com.streamvault.data.ai

import com.streamvault.data.metadata.TmdbGenres
import com.streamvault.domain.repository.MetadataRepository

data class KeywordSearchResult(
    val title: String,
    val mode: String = "discover", // "discover" or "specific"
    val genreIds: List<Int> = emptyList(),
    val keywordIds: List<Int> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val sortBy: String = "popularity.desc",
    val minRating: Float? = null,
    val mediaType: String? = null,
    val specificItems: List<SpecificItem> = emptyList(),
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
     * Primary path: AI interprets the phrase, choosing discover or specific mode.
     */
    suspend fun searchWithAi(provider: AiProvider, apiKey: String, phrase: String): KeywordSearchResult {
        val suggestion = aiSuggestClient.suggest(provider, apiKey, phrase)
        val title = suggestion.title.ifBlank { phrase.replaceFirstChar { it.uppercase() } }

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
                            com.streamvault.domain.model.MediaType.SERIES -> "tv"
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

        // Discover mode: resolve keyword terms to TMDB keyword IDs
        val keywordIds = suggestion.keywordTerms.mapNotNull { term ->
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
        val lower = phrase.lowercase()
        val terms = extractSearchTerms(phrase)

        // Infer genres from phrase
        val genreIds = inferGenres(lower)

        // Only resolve non-genre terms as keywords (specific themes like "beach", "christmas")
        val genreWords = GENRE_WORD_MAP.keys
        val keywordTerms = terms.filter { term ->
            term.split(" ").none { it in genreWords }
        }

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
            title = phrase.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
            genreIds = genreIds,
            keywordIds = keywordIds,
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
    }
}
