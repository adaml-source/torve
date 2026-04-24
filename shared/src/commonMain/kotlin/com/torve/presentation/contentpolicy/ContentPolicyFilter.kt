package com.torve.presentation.contentpolicy

import com.torve.domain.model.AddonPolicyFlags
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentFilterAction
import com.torve.domain.model.ContentFilterDecision
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.LOCKED_CONTENT_MESSAGE
import com.torve.domain.model.LOCKED_CONTENT_TITLE
import com.torve.domain.model.MediaItem
import com.torve.domain.model.STUB_DETAIL_MESSAGE
import com.torve.domain.model.STUB_DETAIL_TITLE
import com.torve.domain.model.Download
import com.torve.domain.model.MediaType
import com.torve.domain.model.SensitiveClassification
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.WatchProgress

data class FilteredItemsResult(
    val items: List<MediaItem>,
    val hiddenCount: Int = 0,
)

/**
 * Result of a classification split: [resolved] items whose sensitivity
 * verdict is known (SAFE or SENSITIVE — both can still be policy-gated
 * downstream), and [unresolvedCount] = number of items whose sensitivity
 * could not be positively confirmed from current metadata (UNKNOWN).
 *
 * Used by the search commit pipeline: resolved items are stored raw in
 * the ViewModel and the combine-stage filter applies the live policy
 * (letting toggles reveal/hide SENSITIVE items), while unresolved items
 * are permanently excluded so no poster is ever rendered for an item
 * whose safety can't be confirmed at commit time.
 */
data class ClassificationSplit(
    val resolved: List<MediaItem>,
    val unresolvedCount: Int,
)

data class FilteredWatchProgressResult(
    val items: List<WatchProgress>,
    val hiddenCount: Int = 0,
)

class ContentPolicyFilter {
    /**
     * Filter a list of media items for the current policy.
     *
     * @param strictUnknown When true, any item whose sensitivity cannot be
     *   positively confirmed from the current metadata is treated as
     *   UNKNOWN → HIDE. In practice this means items with `adult == null`
     *   and no matching sensitive keyword/genre signal are dropped rather
     *   than passed through as SAFE. The intended use is the search
     *   commit pipeline, where a later enrichment call would otherwise
     *   produce the "poster appears → data arrives → item removed" flicker:
     *   if we can't verify safety at commit time, we never render the card
     *   in the first place. Non-search contexts (home shelves, catalog)
     *   default to `strictUnknown = false` to avoid over-blocking shelves
     *   where TMDB routinely omits the adult flag for legitimate entries.
     */
    fun filterItems(
        policy: ContentPolicyState,
        context: ContentAccessContext,
        items: List<MediaItem>,
        sourceType: ContentSourceType,
        addonPolicyFlags: AddonPolicyFlags? = null,
        allowSensitiveBecauseUserReachedSensitiveParent: Boolean = false,
        strictUnknown: Boolean = false,
    ): FilteredItemsResult {
        if (!policy.enforcementEnabled) return FilteredItemsResult(items = items)

        var hiddenCount = 0
        val filtered = buildList {
            items.forEach { item ->
                when (val decision = decide(policy, context, item, sourceType, addonPolicyFlags, allowSensitiveBecauseUserReachedSensitiveParent, strictUnknown).action) {
                    ContentFilterAction.ALLOW_FULL -> add(item)
                    ContentFilterAction.ALLOW_PLACEHOLDER -> add(item.asLockedPlaceholder())
                    ContentFilterAction.HIDE -> hiddenCount += 1
                    ContentFilterAction.STUB_DETAIL -> add(item.asStubDetail())
                }
            }
        }
        return FilteredItemsResult(items = filtered, hiddenCount = hiddenCount)
    }

    /**
     * Partition [items] by classification without applying any policy gate.
     *
     * Items classifying as SAFE or SENSITIVE end up in [ClassificationSplit.resolved] —
     * they have a known verdict, so later policy gating (in the combine stage
     * of the caller's render pipeline) can decide visibility. Items
     * classifying as UNKNOWN are counted in [ClassificationSplit.unresolvedCount]
     * and intentionally dropped from the output — they must never be
     * rendered provisionally, because a later enrichment step could flip
     * them to SENSITIVE and create the "poster appears, then vanishes"
     * flicker this primitive is designed to prevent.
     *
     * Pass `strictUnknown = true` to force `adult == null` items (with
     * no positive safety signal) into the UNKNOWN bucket; the search
     * commit pipeline uses this.
     */
    fun splitByClassification(
        policy: ContentPolicyState,
        items: List<MediaItem>,
        sourceType: ContentSourceType,
        strictUnknown: Boolean = false,
    ): ClassificationSplit {
        // When enforcement is disabled (non-Play channels like Amazon),
        // classification is a no-op and every item is considered resolved.
        // Matches the short-circuit in filterItems.
        if (!policy.enforcementEnabled) return ClassificationSplit(items, 0)
        val resolved = mutableListOf<MediaItem>()
        var unresolvedCount = 0
        for (item in items) {
            when (classify(item, sourceType, strictUnknown)) {
                SensitiveClassification.UNKNOWN -> unresolvedCount += 1
                SensitiveClassification.SAFE,
                SensitiveClassification.SENSITIVE -> resolved.add(item)
            }
        }
        return ClassificationSplit(resolved, unresolvedCount)
    }

    fun filterWatchProgress(
        policy: ContentPolicyState,
        context: ContentAccessContext,
        items: List<WatchProgress>,
    ): FilteredWatchProgressResult {
        if (!policy.enforcementEnabled) return FilteredWatchProgressResult(items = items)

        var hiddenCount = 0
        val filtered = buildList {
            items.forEach { progress ->
                val synthetic = MediaItem(
                    id = progress.mediaId,
                    title = progress.title,
                    type = progress.mediaType,
                    posterUrl = progress.posterUrl,
                    backdropUrl = progress.backdropUrl,
                )
                when (decide(policy, context, synthetic, ContentSourceType.LOCAL_LIBRARY, addonPolicyFlags = null, allowSensitiveBecauseUserReachedSensitiveParent = false).action) {
                    ContentFilterAction.ALLOW_FULL -> add(progress)
                    ContentFilterAction.ALLOW_PLACEHOLDER,
                    ContentFilterAction.STUB_DETAIL,
                    -> add(progress.asLockedPlaceholder())
                    ContentFilterAction.HIDE -> hiddenCount += 1
                }
            }
        }
        return FilteredWatchProgressResult(items = filtered, hiddenCount = hiddenCount)
    }

    fun decide(
        policy: ContentPolicyState,
        context: ContentAccessContext,
        item: MediaItem,
        sourceType: ContentSourceType,
        addonPolicyFlags: AddonPolicyFlags?,
        allowSensitiveBecauseUserReachedSensitiveParent: Boolean,
        strictUnknown: Boolean = false,
    ): ContentFilterDecision {
        if (!policy.enforcementEnabled) {
            return ContentFilterDecision(ContentFilterAction.ALLOW_FULL, SensitiveClassification.SAFE)
        }

        if (sourceType == ContentSourceType.ADDON) {
            if (addonPolicyFlags?.catalogQueryable == false &&
                (policy.isLocked || context == ContentAccessContext.SEARCH_SUGGESTION)
            ) {
                return ContentFilterDecision(ContentFilterAction.HIDE, SensitiveClassification.UNKNOWN, "addon_not_queryable")
            }
        }

        val classification = classify(item, sourceType, strictUnknown)
        val promotionalSurface = when (context) {
            ContentAccessContext.DEFAULT_DISCOVERY,
            ContentAccessContext.GLOBAL_RECOMMENDATION,
            ContentAccessContext.SEARCH_SUGGESTION,
            -> true
            ContentAccessContext.SIMILAR_OR_MORE_LIKE_THIS -> !allowSensitiveBecauseUserReachedSensitiveParent
            else -> false
        }

        if (classification == SensitiveClassification.SAFE) {
            return ContentFilterDecision(ContentFilterAction.ALLOW_FULL, classification)
        }

        if (classification == SensitiveClassification.UNKNOWN) {
            return ContentFilterDecision(
                action = if (context == ContentAccessContext.DETAIL_PAGE) ContentFilterAction.STUB_DETAIL else ContentFilterAction.HIDE,
                classification = classification,
                reason = "classification_unknown",
            )
        }

        if (policy.isLocked || promotionalSurface) {
            return ContentFilterDecision(
                action = blockedActionForContext(context),
                classification = classification,
                reason = if (policy.isLocked) "policy_locked" else "promotional_surface_safe_only",
            )
        }

        val allowedForAdult = when (context) {
            ContentAccessContext.DIRECT_SEARCH,
            ContentAccessContext.DETAIL_PAGE,
            ContentAccessContext.HISTORY_DERIVED,
            ContentAccessContext.LIBRARY_OR_WATCHLIST,
            ContentAccessContext.ADDON_SHELF,
            ContentAccessContext.ACCELERATION_OR_INVENTORY,
            -> true
            ContentAccessContext.SIMILAR_OR_MORE_LIKE_THIS -> allowSensitiveBecauseUserReachedSensitiveParent
            else -> false
        }

        return if (policy.adultEnabled && allowedForAdult) {
            ContentFilterDecision(ContentFilterAction.ALLOW_FULL, classification)
        } else {
            ContentFilterDecision(blockedActionForContext(context), classification, "adult_not_enabled_for_context")
        }
    }

    fun classify(
        item: MediaItem,
        sourceType: ContentSourceType,
        strictUnknown: Boolean = false,
    ): SensitiveClassification {
        if (item.isContentPlaceholder || item.isStubDetail) return SensitiveClassification.SENSITIVE
        if (item.adult == true) return SensitiveClassification.SENSITIVE

        val haystacks = listOfNotNull(
            item.title,
            item.overview,
            item.tagline,
            item.director,
        ).map { it.lowercase() }
        if (haystacks.any(::containsSensitiveKeyword)) {
            return SensitiveClassification.SENSITIVE
        }

        val genreSignals = item.genres.map { it.name.lowercase() } + item.genreIds.map { it.toString() }
        if (genreSignals.any(::containsSensitiveKeyword)) {
            return SensitiveClassification.SENSITIVE
        }

        if (sourceType == ContentSourceType.ADDON && item.overview.isNullOrBlank() && item.adult == null) {
            return SensitiveClassification.UNKNOWN
        }

        // Strict mode: require a positive safety signal (adult == false)
        // before returning SAFE. TMDB's multi-search endpoint omits the
        // adult flag for some result types (historic TV records, edge
        // cases), so `adult == null` leaves us without confirmation that
        // the item is SFW. Commit pipelines that can't tolerate a later
        // "poster appears, then vanishes" flicker pass strictUnknown=true
        // so those null-adult items stay UNKNOWN → HIDE until a stronger
        // signal resolves them. Non-strict callers (home shelves, etc.)
        // keep the existing permissive SAFE default, matching the rest of
        // the product's historical behavior.
        if (strictUnknown && item.adult == null) {
            return SensitiveClassification.UNKNOWN
        }

        return SensitiveClassification.SAFE
    }

    private fun blockedActionForContext(context: ContentAccessContext): ContentFilterAction = when (context) {
        ContentAccessContext.DETAIL_PAGE -> ContentFilterAction.STUB_DETAIL
        ContentAccessContext.HISTORY_DERIVED,
        ContentAccessContext.LIBRARY_OR_WATCHLIST,
        ContentAccessContext.ACCELERATION_OR_INVENTORY,
        -> ContentFilterAction.ALLOW_PLACEHOLDER
        else -> ContentFilterAction.HIDE
    }

    private fun containsSensitiveKeyword(text: String): Boolean {
        return SENSITIVE_KEYWORDS.any { keyword -> text.contains(keyword) }
    }

    /**
     * Check whether the user-typed search [query] explicitly intends
     * sensitive content. The check is the same lowercase-substring match
     * the per-item keyword classifier uses, applied to the query string.
     *
     * Search surfaces use this to pre-empt the network call when the
     * policy is locked: TMDB will happily return non-adult-flagged
     * documentaries with explicit titles/posters for queries like
     * "porn", and those items would otherwise pass [classify] as SAFE
     * (adult=false, no keyword in title) and render. Returning empty
     * results before the fetch is the only way to guarantee no leak.
     */
    fun isSensitiveQuery(query: String): Boolean {
        val lower = query.trim().lowercase()
        if (lower.isEmpty()) return false
        return containsSensitiveKeyword(lower)
    }

    private fun MediaItem.asLockedPlaceholder(): MediaItem = copy(
        title = LOCKED_CONTENT_TITLE,
        adult = null,
        overview = LOCKED_CONTENT_MESSAGE,
        posterUrl = null,
        backdropUrl = null,
        logoUrl = null,
        tagline = null,
        trailerKey = null,
        cast = emptyList(),
        genres = emptyList(),
        genreIds = emptyList(),
        seasons = emptyList(),
        isContentPlaceholder = true,
    )

    fun MediaItem.asStubDetail(): MediaItem = copy(
        title = STUB_DETAIL_TITLE,
        adult = null,
        overview = STUB_DETAIL_MESSAGE,
        posterUrl = null,
        backdropUrl = null,
        logoUrl = null,
        tagline = null,
        trailerKey = null,
        cast = emptyList(),
        genres = emptyList(),
        genreIds = emptyList(),
        seasons = emptyList(),
        isStubDetail = true,
    )

    private fun WatchProgress.asLockedPlaceholder(): WatchProgress = copy(
        title = LOCKED_CONTENT_TITLE,
        posterUrl = null,
        backdropUrl = null,
        showTitle = null,
        isContentPlaceholder = true,
    )

    // ── Watch History ──

    fun filterWatchHistory(
        policy: ContentPolicyState,
        context: ContentAccessContext,
        items: List<WatchHistoryEntry>,
    ): List<WatchHistoryEntry> {
        if (!policy.enforcementEnabled) return items

        return buildList {
            items.forEach { entry ->
                val synthetic = MediaItem(
                    id = entry.mediaId,
                    title = entry.title,
                    type = if (entry.mediaType.equals("series", ignoreCase = true) || entry.mediaType.equals("tv", ignoreCase = true))
                        MediaType.SERIES else MediaType.MOVIE,
                    posterUrl = entry.posterUrl,
                    backdropUrl = entry.backdropUrl,
                )
                when (decide(policy, context, synthetic, ContentSourceType.LOCAL_LIBRARY, addonPolicyFlags = null, allowSensitiveBecauseUserReachedSensitiveParent = false).action) {
                    ContentFilterAction.ALLOW_FULL -> add(entry)
                    ContentFilterAction.ALLOW_PLACEHOLDER,
                    ContentFilterAction.STUB_DETAIL,
                    -> add(entry.asLockedPlaceholder())
                    ContentFilterAction.HIDE -> { /* drop */ }
                }
            }
        }
    }

    private fun WatchHistoryEntry.asLockedPlaceholder(): WatchHistoryEntry = copy(
        title = LOCKED_CONTENT_TITLE,
        posterUrl = null,
        backdropUrl = null,
        showTitle = null,
        isContentPlaceholder = true,
    )

    // ── Downloads ──

    fun filterDownloads(
        policy: ContentPolicyState,
        context: ContentAccessContext,
        items: List<Download>,
    ): List<Download> {
        if (!policy.enforcementEnabled) return items

        return items.map { download ->
            val synthetic = MediaItem(
                id = download.mediaId,
                title = download.title,
                type = download.mediaType,
                posterUrl = download.posterUrl,
            )
            when (decide(policy, context, synthetic, ContentSourceType.LOCAL_LIBRARY, addonPolicyFlags = null, allowSensitiveBecauseUserReachedSensitiveParent = false).action) {
                ContentFilterAction.ALLOW_FULL -> download
                else -> download.copy(
                    title = LOCKED_CONTENT_TITLE,
                    posterUrl = null,
                )
            }
        }
    }

    private companion object {
        // Keyword classification is defense-in-depth only.
        // The TMDB `adult = true` flag is the primary sensitive classifier.
        // Avoid overly broad keywords that catch mainstream titles
        // (e.g. "sex " would block "Sex and the City", "Sex Education").
        val SENSITIVE_KEYWORDS = setOf(
            "adults only",
            "explicit",
            "erotic",
            "erotica",
            "porn",
            "porno",
            "pornographic",
            "sexual",
            "sexvideo",
            "nude",
            "nudity",
            "nsfw",
            "uncensored",
            "fetish",
            "bdsm",
        )
    }
}
