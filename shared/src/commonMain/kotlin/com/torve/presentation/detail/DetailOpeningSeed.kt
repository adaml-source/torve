package com.torve.presentation.detail

import com.torve.domain.model.*
import com.torve.presentation.apple.AppleDetailNavigation
import com.torve.presentation.contentpolicy.*

/** A selected card may paint before full details, subject to the current policy. */
internal object DetailOpeningSeed {
    fun extension(seed: MediaItem, policy: ContentPolicyState, filter: ContentPolicyFilter): MediaItem? =
        seed.takeIf { filter.decide(policy, ContentAccessContext.DETAIL_PAGE, it,
            ContentSourceType.ADDON, null, false).action == ContentFilterAction.ALLOW_FULL }

    fun accept(type: String, id: Int, seed: MediaItem?, policy: ContentPolicyState,
               filter: ContentPolicyFilter): MediaItem? {
        val expected = when (type) { "movie" -> MediaType.MOVIE; "tv" -> MediaType.SERIES; else -> return null }
        if (id <= 0 || seed == null || seed.type != expected || seed.catalogueNotice != null ||
            seed.isStubDetail || seed.isContentPlaceholder) return null
        val identity = seed.tmdbId ?: AppleDetailNavigation.catalogId(seed.id)
        if (identity != id) return null
        if (filter.decide(policy, ContentAccessContext.DETAIL_PAGE, seed, ContentSourceType.TMDB,
                null, false).action != ContentFilterAction.ALLOW_FULL) return null
        // This route uses the numeric catalogue/playback identity. Calendar and
        // shelf row IDs must not become the temporary Watch target.
        return seed.copy(id = id.toString(), tmdbId = id)
    }
}
