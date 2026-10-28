package com.torve.presentation.search

import com.torve.domain.model.MediaItem

/** Token prefixes support incomplete words, punctuation and multiword titles. */
internal fun titleSearchTokens(value: String): List<String> = value.lowercase()
    .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
    .split(' ').filter { it.isNotEmpty() }

internal fun MediaItem.matchesTitlePrefix(query: String): Boolean {
    val terms = titleSearchTokens(query)
    val words = titleSearchTokens(title)
    return terms.isNotEmpty() && terms.all { term -> words.any { it.startsWith(term) } }
}

internal fun List<MediaItem>.bestTitleMatches(query: String): List<MediaItem> {
    val phrase = titleSearchTokens(query).joinToString(" ")
    return sortedWith(compareByDescending<MediaItem> { it.matchesTitlePrefix(query) }
        // Whole-word matches should not bury a well-known prefix completion
        // beneath dozens of obscure films sharing a one-word title.
        .thenByDescending {
            kotlin.math.ln((it.voteCount ?: 0).coerceAtLeast(0) + 1.0) +
                if (titleSearchTokens(it.title).joinToString(" ") == phrase) 2.0 else 0.0
        }
        .thenByDescending { it.popularity ?: 0.0 })
}
