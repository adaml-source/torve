package com.torve.domain.usenet

/**
 * KMP subset of the indexer-category map. Only carries the helpers
 * that the cross-platform Sports surface needs today; the desktop
 * Adult catalog still owns the language-aware Movies/TV mapping in
 * `com.torve.desktop.adult.IndexerCategoryMap`.
 *
 * Spec category 5060 covers TV sport for every Newznab indexer.
 * scenenzbs additionally exposes 5160 as the German-language sport
 * sub-cat (same +100 language pattern it uses everywhere). Combining
 * both broadens the result set for users who want both English and
 * German releases without forcing them to flip a language filter.
 */
object UsenetIndexerCategoryMap {
    fun sportsCategoriesFor(indexerType: String): String =
        if (indexerType.equals("scenenzbs", ignoreCase = true)) "5060,5160"
        else "5060"
}
