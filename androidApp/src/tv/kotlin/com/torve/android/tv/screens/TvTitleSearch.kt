package com.torve.android.tv.screens

import com.torve.domain.model.MediaItem
import com.torve.domain.model.PagedResult
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private fun titleWords(text: String) = text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
    .joinToString("").split(' ').filter(String::isNotEmpty)

internal fun tvTitlePrefixMatches(query: String, item: MediaItem): Boolean {
    val terms = titleWords(query)
    val words = titleWords(item.title)
    return terms.isNotEmpty() && terms.all { term -> words.any { it.startsWith(term) } }
}

internal fun mergeTvTitleSearch(query: String, results: List<MediaItem>, suggestions: List<MediaItem>): List<MediaItem> =
    (suggestions.filter { tvTitlePrefixMatches(query, it) } + results)
        .distinctBy { "${it.type}:${it.tmdbId ?: it.id}" }
        .sortedWith(compareByDescending<MediaItem> { tvTitlePrefixMatches(query, it) }
            .thenByDescending { kotlin.math.ln((it.voteCount ?: 0).coerceAtLeast(0) + 1.0) }
            .thenByDescending { it.popularity ?: 0.0 })

/** Publish either source promptly; an optional completion failure cannot erase normal results. */
internal suspend fun MetadataRepository.searchTvTitles(
    query: String,
    type: String?,
    onResults: (List<MediaItem>, PagedResult?) -> Unit,
) = coroutineScope {
    var page: PagedResult? = null
    var suggestions = emptyList<MediaItem>()
    var searchFailure: Exception? = null
    fun publish() {
        if (page != null || suggestions.isNotEmpty()) onResults(mergeTvTitleSearch(query, page?.items.orEmpty(), suggestions), page)
    }
    val completionJob = launch {
        try {
            withTimeoutOrNull(2_500) {
                titleSuggestions(query, type).collect { suggestions = it; publish() }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Optional source. */ }
    }
    try {
        page = searchMultiPaged(query, 1, type)
        publish()
    } catch (e: CancellationException) { throw e } catch (e: Exception) { searchFailure = e }
    completionJob.join()
    if (page == null && suggestions.isEmpty()) searchFailure?.let { throw it }
}
