package com.torve.presentation.search

import com.torve.data.ai.AiProvider
import com.torve.data.ai.KeywordSearchResult
import com.torve.data.ai.KeywordSearchService
import com.torve.data.metadata.TmdbGenres
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.catalog.SortOption
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

fun interface SearchAiResolver {
    suspend fun resolve(phrase: String): KeywordSearchResult
}

class SearchAiSetupRequired : Exception("Choose an AI provider and save its API key in Settings to use AI Search.")

class ConfiguredSearchAiResolver(
    private val service: KeywordSearchService,
    private val preferences: PreferencesRepository,
    private val secrets: IntegrationSecretStore,
) : SearchAiResolver {
    override suspend fun resolve(phrase: String): KeywordSearchResult {
        val provider = preferences.getString(SettingsViewModel.KEY_AI_PROVIDER)
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() } ?: AiProvider.CLAUDE
        val secret = when (provider) {
            AiProvider.CLAUDE -> IntegrationSecretKey.CLAUDE_API_KEY
            AiProvider.CHATGPT -> IntegrationSecretKey.CHATGPT_API_KEY
            AiProvider.GEMINI -> IntegrationSecretKey.GEMINI_API_KEY
            AiProvider.PERPLEXITY -> IntegrationSecretKey.PERPLEXITY_API_KEY
            AiProvider.DEEPSEEK -> IntegrationSecretKey.DEEPSEEK_API_KEY
        }
        val key = secrets.get(secret)?.trim().orEmpty()
        if (key.isEmpty()) throw SearchAiSetupRequired()
        return service.searchWithAi(provider, key, phrase)
    }
}

/** A resolved interpretation is reused for filters and pagination, without another AI call. */
internal suspend fun MetadataRepository.searchAiPage(
    query: KeywordSearchResult,
    filter: SearchFilter,
    page: Int,
): PagedResult {
    if (query.mode == "specific" || query.mode == "person_credits" ||
        (query.mode == "person_filtered" && query.specificItems.isNotEmpty())) {
        val items = if (query.mode == "person_credits" && query.personId != null) {
            getPersonCredits(query.personId)
        } else query.specificItems.mapNotNull { item ->
            try { getDetail(item.mediaType, item.tmdbId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        return PagedResult(items.filter { it.matchesAiType(filter.mediaType ?: query.mediaType) }, 1, 1, items.size)
    }

    suspend fun fetch(type: String): PagedResult {
        val requestedGenres = query.genreIds + filter.genreIds
        val genreGroups = requestedGenres.map { aiGenresForType(it, type) }
        // Never silently drop a requested genre and return an unrelated catalogue.
        if (genreGroups.any { it.isEmpty() }) return PagedResult(emptyList(), page, page, 0)
        val genres = genreGroups.flatten().distinct()
        return discover(
            type = type, page = page,
            sortBy = if (filter.sortBy == SortOption.POPULARITY_DESC) query.sortBy else filter.sortBy.apiValue,
            withGenres = genres.takeIf { it.isNotEmpty() }?.joinToString(","),
            minRating = listOfNotNull(query.minRating, filter.minRating).maxOrNull(),
            year = listOfNotNull(query.yearFrom, filter.yearFrom).maxOrNull(),
            yearTo = listOfNotNull(query.yearTo, filter.yearTo).minOrNull(),
            runtimeGte = filter.runtimeFilter?.minMinutes,
            runtimeLte = filter.runtimeFilter?.maxMinutes,
            withKeywords = query.keywordIds.takeIf { it.isNotEmpty() }?.joinToString("|"),
            withCast = query.personId?.takeUnless { query.isDirector }?.toString(),
            withCrew = query.personId?.takeIf { query.isDirector }?.toString(),
        )
    }
    val type = filter.mediaType ?: query.mediaType
    if (type == "movie" || type == "tv") return fetch(type)
    val pages = coroutineScope { listOf(async { fetch("movie") }, async { fetch("tv") }).awaitAll() }
    val items = buildList {
        repeat(pages.maxOf { it.items.size }) { index -> pages.forEach { it.items.getOrNull(index)?.let(::add) } }
    }
    return PagedResult(items, page, pages.maxOf { it.totalPages }, pages.sumOf { it.totalResults })
}

internal fun MediaItem.matchesAiGenres(genres: List<Int>): Boolean = genres.isEmpty() ||
    genres.any { id -> aiGenresForType(id, if (type == MediaType.SERIES) "tv" else "movie").any { it in genreIds } }

private fun aiGenresForType(id: Int, type: String): List<Int> = when {
    type == "tv" && id in listOf(28, 12) -> listOf(10759)
    type == "tv" && id in listOf(878, 14) -> listOf(10765)
    type == "tv" && id == 10752 -> listOf(10768)
    type == "movie" && id == 10759 -> listOf(28, 12)
    type == "movie" && id == 10765 -> listOf(878, 14)
    type == "movie" && id == 10768 -> listOf(10752)
    else -> listOf(id)
}.filter { it in if (type == "tv") TmdbGenres.TV_GENRES else TmdbGenres.MOVIE_GENRES }

private fun MediaItem.matchesAiType(type: String?): Boolean = when (type) {
    "movie" -> this.type == MediaType.MOVIE
    "tv" -> this.type == MediaType.SERIES
    else -> true
}
