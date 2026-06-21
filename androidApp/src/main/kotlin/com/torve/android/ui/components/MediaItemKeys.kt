package com.torve.android.ui.components

import com.torve.domain.model.MediaItem

/**
 * Stable LazyList key for a [MediaItem]. Deliberately omits the list index:
 * including the index means any upstream change to the list's ordering or
 * length (e.g. the content-policy filter hiding a sensitive entry above
 * this row) gives the same item a different key, which forces Compose to
 * treat it as a brand-new item and re-mount the row. That's the "jumpy"
 * re-layout users see when sensitive filtering churns the list.
 *
 * TMDB id + media type uniquely identifies an item across searches,
 * catalogs, similar-to panels, and person credits; the [index] parameter
 * is kept on the signature to keep call sites cheap to refactor but is
 * intentionally unused.
 */
@Suppress("UNUSED_PARAMETER")
fun mediaItemLazyKey(item: MediaItem, index: Int): String {
    val stableId = item.tmdbId?.toString().takeUnless { it.isNullOrBlank() } ?: item.id
    return "${stableId}_${item.type}"
}
