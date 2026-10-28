package com.torve.domain.usenet

/**
 * KMP subset of the indexer-category map. Only carries the helpers
 * that the cross-platform Sports surface needs today; the desktop
 * Adult catalog still owns the language-aware Movies/TV mapping in
 * `com.torve.desktop.adult.IndexerCategoryMap`.
 *
 * Spec category 5060 covers TV sport for every Newznab indexer.
 * SceneNZBs / Treasure Maps additionally exposes German (5160),
 * Spanish (5260), and French (5360) Sports categories in its public
 * capabilities. Sports has no language restriction, so include each.
 */
object UsenetIndexerCategoryMap {
    fun sportsCategoriesFor(indexerType: String): String =
        if (indexerType.equals("scenenzbs", ignoreCase = true)) "5060,5160,5260,5360"
        else "5060"

    // Named-league searches can also find matches filed under general TV.
    // Sports validates those titles before displaying them.
    fun sportsSearchCategoriesFor(indexerType: String): String =
        if (indexerType.equals("scenenzbs", ignoreCase = true)) "5000,5100,5200,5300"
        else "5000"
}
