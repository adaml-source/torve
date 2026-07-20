package com.torve.presentation.contentpolicy

import com.torve.domain.model.AddonPolicyFlags
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentAgeBand
import com.torve.domain.model.ContentFilterAction
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.SensitiveClassification
import com.torve.domain.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentPolicyFilterTest {
    private val filter = ContentPolicyFilter()

    // ── Locked discovery surfaces ──

    @Test
    fun lockedUsersHideSensitiveItemsOnDiscovery() {
        val result = filter.filterItems(
            policy = lockedPolicy(),
            context = ContentAccessContext.DEFAULT_DISCOVERY,
            items = listOf(sensitiveItem()),
            sourceType = ContentSourceType.TMDB,
        )

        assertTrue(result.items.isEmpty())
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun lockedUsersGetStubDetailForSensitiveItem() {
        val decision = filter.decide(
            policy = lockedPolicy(),
            context = ContentAccessContext.DETAIL_PAGE,
            item = sensitiveItem(),
            sourceType = ContentSourceType.TMDB,
            addonPolicyFlags = null,
            allowSensitiveBecauseUserReachedSensitiveParent = false,
        )

        assertEquals(ContentFilterAction.STUB_DETAIL, decision.action)
    }

    @Test
    fun lockedSearchSuggestionHidesSensitiveItems() {
        val result = filter.filterItems(
            policy = lockedPolicy(),
            context = ContentAccessContext.SEARCH_SUGGESTION,
            items = listOf(sensitiveItem(), safeItem()),
            sourceType = ContentSourceType.TMDB,
        )

        assertEquals(1, result.items.size)
        assertEquals("Family Movie", result.items.first().title)
        assertEquals(1, result.hiddenCount)
    }

    // ── Adult-enabled users ──

    @Test
    fun adultEnabledUsersCanSeeSensitiveDirectSearchResults() {
        val result = filter.filterItems(
            policy = adultEnabledPolicy(),
            context = ContentAccessContext.DIRECT_SEARCH,
            items = listOf(sensitiveItem()),
            sourceType = ContentSourceType.TMDB,
        )

        assertEquals(1, result.items.size)
        assertEquals("Explicit Title", result.items.first().title)
    }

    @Test
    fun adultEnabledUsersStillCannotSeeSensitiveGlobalPromotion() {
        val result = filter.filterItems(
            policy = adultEnabledPolicy(),
            context = ContentAccessContext.GLOBAL_RECOMMENDATION,
            items = listOf(sensitiveItem()),
            sourceType = ContentSourceType.TMDB,
        )

        assertTrue(result.items.isEmpty())
        assertEquals(1, result.hiddenCount)
    }

    // ── Addon policy flags ──

    @Test
    fun ambiguousAddonCatalogFailsClosed() {
        val decision = filter.decide(
            policy = adultEnabledPolicy(),
            context = ContentAccessContext.ADDON_SHELF,
            item = MediaItem(
                id = "addon-1",
                type = MediaType.MOVIE,
                title = "Sparse item",
            ),
            sourceType = ContentSourceType.ADDON,
            addonPolicyFlags = AddonPolicyFlags(),
            allowSensitiveBecauseUserReachedSensitiveParent = false,
        )

        assertEquals(ContentFilterAction.HIDE, decision.action)
    }

    @Test
    fun addonFlagsCanBlockLockedCatalogQueries() {
        val decision = filter.decide(
            policy = lockedPolicy(),
            context = ContentAccessContext.SEARCH_SUGGESTION,
            item = safeItem(),
            sourceType = ContentSourceType.ADDON,
            addonPolicyFlags = AddonPolicyFlags(catalogQueryable = false),
            allowSensitiveBecauseUserReachedSensitiveParent = false,
        )

        assertEquals(ContentFilterAction.HIDE, decision.action)
    }

    @Test
    fun addonNotQueryableHidesEvenSafeContentWhenLocked() {
        val result = filter.filterItems(
            policy = lockedPolicy(),
            context = ContentAccessContext.ADDON_SHELF,
            items = listOf(safeItem()),
            sourceType = ContentSourceType.ADDON,
            addonPolicyFlags = AddonPolicyFlags(catalogQueryable = false),
        )

        assertTrue(result.items.isEmpty())
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun addonNotQueryableAllowsSafeContentWhenAdultEnabled() {
        val result = filter.filterItems(
            policy = adultEnabledPolicy(),
            context = ContentAccessContext.ADDON_SHELF,
            items = listOf(safeItem()),
            sourceType = ContentSourceType.ADDON,
            addonPolicyFlags = AddonPolicyFlags(catalogQueryable = false),
        )

        assertEquals(1, result.items.size)
    }

    // ── Placeholder / locked content image binding verification ──

    @Test
    fun lockedHistoryKeepsPlaceholderInsteadOfRawMetadata() {
        val result = filter.filterItems(
            policy = lockedPolicy(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(sensitiveItem()),
            sourceType = ContentSourceType.LOCAL_LIBRARY,
        )

        assertEquals(1, result.items.size)
        assertTrue(result.items.first().isContentPlaceholder)
        assertFalse(result.items.first().title.contains("Explicit"))
    }

    @Test
    fun placeholderItemHasNullArtworkUrls() {
        val result = filter.filterItems(
            policy = lockedPolicy(),
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = listOf(sensitiveItem().copy(
                posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w780/backdrop.jpg",
            )),
            sourceType = ContentSourceType.LOCAL_LIBRARY,
        )

        assertEquals(1, result.items.size)
        val placeholder = result.items.first()
        assertTrue(placeholder.isContentPlaceholder)
        assertNull(placeholder.posterUrl)
        assertNull(placeholder.backdropUrl)
        assertNull(placeholder.logoUrl)
    }

    @Test
    fun stubDetailHasNullArtworkUrls() {
        val result = filter.filterItems(
            policy = lockedPolicy(),
            context = ContentAccessContext.DETAIL_PAGE,
            items = listOf(sensitiveItem().copy(
                posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w780/backdrop.jpg",
            )),
            sourceType = ContentSourceType.TMDB,
        )

        assertEquals(1, result.items.size)
        val stub = result.items.first()
        assertTrue(stub.isStubDetail)
        assertNull(stub.posterUrl)
        assertNull(stub.backdropUrl)
        assertNull(stub.logoUrl)
    }

    @Test
    fun placeholderItemHasNoCastOrGenres() {
        val item = sensitiveItem().copy(
            cast = listOf(
                com.torve.domain.model.CastMember(1, "Actor", "Role", "https://img.tmdb.org/profile.jpg"),
            ),
            genres = listOf(com.torve.domain.model.Genre(1, "Drama")),
        )
        val result = filter.filterItems(
            policy = lockedPolicy(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(item),
            sourceType = ContentSourceType.LOCAL_LIBRARY,
        )

        val placeholder = result.items.first()
        assertTrue(placeholder.cast.isEmpty())
        assertTrue(placeholder.genres.isEmpty())
    }

    // ── WatchProgress filtering ──

    @Test
    fun watchProgressPlaceholderHasNullArtwork() {
        val progress = WatchProgress(
            mediaId = "1",
            mediaType = MediaType.MOVIE,
            title = "Explicit Movie",
            posterUrl = "https://image.tmdb.org/t/p/poster.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/backdrop.jpg",
        )
        val result = filter.filterWatchProgress(
            policy = lockedPolicy(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(progress),
        )

        assertEquals(1, result.items.size)
        assertTrue(result.items.first().isContentPlaceholder)
        assertNull(result.items.first().posterUrl)
        assertNull(result.items.first().backdropUrl)
    }

    // ── Sensitive material enable/disable flow ──

    @Test
    fun disableSensitiveImmediatelyBlocksContent() {
        // Simulate: user had adult enabled, then disables
        val enabledPolicy = adultEnabledPolicy()
        val disabledPolicy = enabledPolicy.copy(sensitiveMaterialEnabled = false)

        // With enabled: visible
        val enabledResult = filter.filterItems(
            policy = enabledPolicy,
            context = ContentAccessContext.DIRECT_SEARCH,
            items = listOf(sensitiveItem()),
            sourceType = ContentSourceType.TMDB,
        )
        assertEquals(1, enabledResult.items.size)

        // With disabled: hidden
        val disabledResult = filter.filterItems(
            policy = disabledPolicy,
            context = ContentAccessContext.DIRECT_SEARCH,
            items = listOf(sensitiveItem()),
            sourceType = ContentSourceType.TMDB,
        )
        assertTrue(disabledResult.items.isEmpty())
    }

    // ── Enforcement disabled (non-Google Play) ──

    @Test
    fun enforcementDisabledPassesEverything() {
        val policy = ContentPolicyState.unrestricted()
        val result = filter.filterItems(
            policy = policy,
            context = ContentAccessContext.DEFAULT_DISCOVERY,
            items = listOf(sensitiveItem(), safeItem()),
            sourceType = ContentSourceType.TMDB,
        )

        assertEquals(2, result.items.size)
        assertEquals(0, result.hiddenCount)
    }

    // ── Keyword classification ──

    @Test
    fun classifiesItemsWithSensitiveKeywordsInOverview() {
        val item = MediaItem(
            id = "kw1",
            type = MediaType.MOVIE,
            title = "Normal Title",
            overview = "This movie contains explicit scenes",
        )
        assertEquals(
            SensitiveClassification.SENSITIVE,
            filter.classify(item, ContentSourceType.TMDB),
        )
    }

    @Test
    fun classifiesItemsWithAdultFlagAsSensitive() {
        val item = MediaItem(
            id = "adult1",
            type = MediaType.MOVIE,
            title = "Normal Title",
            adult = true,
        )
        assertEquals(
            SensitiveClassification.SENSITIVE,
            filter.classify(item, ContentSourceType.TMDB),
        )
    }

    @Test
    fun classifiesSafeItemsCorrectly() {
        assertEquals(
            SensitiveClassification.SAFE,
            filter.classify(safeItem(), ContentSourceType.TMDB),
        )
    }

    @Test
    fun classifiesAmbiguousAddonItemsAsUnknown() {
        val item = MediaItem(
            id = "addon-sparse",
            type = MediaType.MOVIE,
            title = "Sparse Addon Item",
        )
        assertEquals(
            SensitiveClassification.UNKNOWN,
            filter.classify(item, ContentSourceType.ADDON),
        )
    }

    // ── Relock after unlock does not leave stale data ──

    @Test
    fun relockAfterUnlockProducesStubForSensitiveDetail() {
        // Simulate: user was unlocked, saw detail, now relocked
        val relocked = lockedPolicy()
        val decision = filter.decide(
            policy = relocked,
            context = ContentAccessContext.DETAIL_PAGE,
            item = sensitiveItem().copy(
                posterUrl = "https://image.tmdb.org/t/p/poster.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/backdrop.jpg",
            ),
            sourceType = ContentSourceType.TMDB,
            addonPolicyFlags = null,
            allowSensitiveBecauseUserReachedSensitiveParent = false,
        )
        assertEquals(ContentFilterAction.STUB_DETAIL, decision.action)
    }

    @Test
    fun safeContentAlwaysVisibleRegardlessOfPolicyState() {
        for (context in ContentAccessContext.entries) {
            val decision = filter.decide(
                policy = lockedPolicy(),
                context = context,
                item = safeItem(),
                sourceType = ContentSourceType.TMDB,
                addonPolicyFlags = null,
                allowSensitiveBecauseUserReachedSensitiveParent = false,
            )
            assertEquals(
                ContentFilterAction.ALLOW_FULL,
                decision.action,
                "Safe content should always be ALLOW_FULL for context $context",
            )
        }
    }

    // ── Helpers ──

    private fun lockedPolicy(): ContentPolicyState = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = false,
        ageBand = ContentAgeBand.UNKNOWN,
        adultEligible = false,
        sensitiveMaterialEnabled = false,
    )

    private fun adultEnabledPolicy(): ContentPolicyState = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        ageBand = ContentAgeBand.ADULT,
        adultEligible = true,
        sensitiveMaterialEnabled = true,
    )

    private fun sensitiveItem(): MediaItem = MediaItem(
        id = "1",
        type = MediaType.MOVIE,
        title = "Explicit Title",
        adult = true,
        overview = "Explicit overview",
    )

    private fun safeItem(): MediaItem = MediaItem(
        id = "2",
        type = MediaType.MOVIE,
        title = "Family Movie",
        overview = "Safe overview",
    )
}
