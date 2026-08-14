package com.torve.android.tv.screens

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class TvProviderCatalogModelTest {

    @Test
    fun providerBrowseHeroUsesSharedArtworkAndNoWorldwideLabel() {
        val source = readSource("screens/TvProviderCatalogScreen.kt")

        assertTrue("TvProviderBrandHeader(" in source)
        assertTrue("TvTitleArtworkOrText(" in source)
        assertTrue("maxLines = 4" in source)
        assertFalse("Worldwide catalog" in source)
        assertTrue(source.indexOf("TvProviderSearchButton(") < source.indexOf("TvTitleArtworkOrText("))
    }

    @Test
    fun providerSearchHeaderUsesSharedBrandAndRoundedTvSearchField() {
        val seeAllSource = readSource("screens/TvSeeAllScreen.kt")
        val inputSource = readSource("components/TvClickToEditOutlinedTextField.kt")

        assertTrue("TvProviderBrandHeader(" in seeAllSource)
        assertTrue("TvClickToEditSearchField(" in seeAllSource)
        assertTrue("RoundedCornerShape(999.dp)" in inputSource)
        assertTrue(".height(42.dp)" in inputSource)
        assertTrue(".border(if (focused) 2.dp else 1.dp, borderColor, shape)" in inputSource)
        assertTrue("Key.Back" in inputSource)
        assertTrue("Key.DirectionDown" in inputSource)
        assertTrue("onNavigateDown?.invoke()" in inputSource)
        assertTrue("providerMovieRequester.requestFocus()" in seeAllSource)
        assertTrue("internalFocusRequester.requestFocus()" in inputSource)
        assertTrue(".width(if (isNetflix) 176.dp else 220.dp)" in seeAllSource)
        assertTrue(".height(if (isNetflix) 53.dp else 66.dp)" in seeAllSource)
        assertTrue("providerService.tmdbProviderId == NETFLIX_TMDB_PROVIDER_ID" in seeAllSource)
    }

    @Test
    fun sharedProviderBrandIsStartAnchoredFittedAndBounded() {
        val source = readSource("components/TvProviderBrandHeader.kt")

        assertTrue("maxWidth.coerceAtMost(maxArtworkWidth)" in source)
        assertTrue("forceFitArtwork = true" in source)
        assertTrue("artworkAlignment = Alignment.CenterStart" in source)
        assertTrue("fallbackHorizontalPadding = 0.dp" in source)
        assertTrue("trimTransparentPadding = true" in source)
        assertTrue("maxArtworkWidth: Dp = 288.dp" in source)
        assertTrue("highResolutionBranding = true" in source)

        val browseSource = readSource("screens/TvProviderCatalogScreen.kt")
        assertTrue(".width(if (service.tmdbProviderId == NETFLIX_TMDB_PROVIDER_ID) 187.dp else 234.dp)" in browseSource)
        assertTrue(".height(if (service.tmdbProviderId == NETFLIX_TMDB_PROVIDER_ID) 50.dp else 62.dp)" in browseSource)
    }

    @Test
    fun sharedTitleArtworkFitsAndRetainsTextThroughLoadFailure() {
        val source = readSource("components/TvTitleArtwork.kt")

        assertTrue("ContentScale.Fit" in source)
        assertTrue("text = item.title" in source)
        assertTrue("is AsyncImagePainter.State.Error -> artworkFailed = true" in source)
        assertTrue("artworkFailed || (logoUrl == null && !artworkLookupPending)" in source)
        assertFalse("|| !artworkLoaded" in source)
    }

    @Test
    fun movieAndShowHeroWaitsForArtworkResolutionBeforeShowingTextFallback() {
        val overlaySource = readSource("components/TvHeroOverlay.kt")
        val rootSource = readSource("TvRoot.kt")

        assertTrue("val artworkPending = !logoFailed && (!allowLogoArtwork || logoLookupInFlight)" in overlaySource)
        assertTrue("!logoCache.containsKey(key)" in rootSource)
        assertTrue("logoCache[cacheKey] = url" in rootSource)
    }

    @Test
    fun pandaStepShortcutDefersToHorizontalFocusTraversal() {
        val source = readSource("screens/TvPandaSetupScreen.kt")

        val moveRight = source.indexOf("focusManager.moveFocus(FocusDirection.Right)")
        val advance = source.indexOf("viewModel.nextStep()", startIndex = moveRight)
        assertTrue(moveRight >= 0)
        assertTrue(advance > moveRight)
        assertTrue("focusManager.moveFocus(FocusDirection.Left)" in source)
    }

    @Test
    fun pandaProviderActionsUseStableFocusTargetsAndCompactGeometry() {
        val source = readMainSource("ui/panda/PandaAuthStep.kt")

        assertTrue("remember(provider.id, ProviderActionId.ENABLED_TOGGLE)" in source)
        assertTrue("action == ProviderActionId.ENABLED_TOGGLE && providerConnected" in source)
        assertTrue("effectiveReauthenticateRequester" in source)
        assertTrue("else -> providerCardRequester" in source)
        assertTrue(".heightIn(min = 48.dp)" in source)
        assertTrue("width = 1.dp" in source)
        assertTrue("isFocused -> Amber" in source)
    }

    @Test
    fun pandaDisconnectCancelsHydrationAndDeletesAccountCredential() {
        val pandaSource = readSharedSource("presentation/panda/PandaSetupViewModel.kt")
        val accountSource = readSharedSource("presentation/session/AccountSessionCoordinator.kt")

        assertTrue("configHydrationJob?.cancel()" in pandaSource)
        assertTrue("withExplicitDisconnect(provider.id)" in pandaSource)
        assertTrue("deleteIntegrationFromBackend(" in pandaSource)
        assertTrue("readPandaDebridActivationSnapshot().disconnectedProviderIds" in accountSource)
    }

    @Test
    fun incrementalStreamResultsDoNotRefocusOrReanimateThePicker() {
        val source = readSource("screens/TvDetailsScreen.kt")

        assertTrue("streamPickerFocusInitialized" in source)
        assertTrue("state.streams.isNotEmpty()" in source)
        assertFalse("LaunchedEffect(state.showStreamPicker, state.streams.size" in source)
        val initialFocusEffect = source.substring(
            source.indexOf("// Give the picker an initial focus owner once."),
            source.indexOf("LaunchedEffect(state.mediaItem?.tmdbId"),
        )
        assertFalse("animateScrollToItem" in initialFocusEffect)
        assertTrue("listState.scrollToItem(pickerIndex)" in initialFocusEffect)
        assertTrue("key = { _, stream -> \"stream_\${group.titleRes}_\${stream.streamUiKey()}\" }" in source)
    }

    @Test
    fun fireTvAccountSettingsExposePasswordReset() {
        val source = readSource("screens/TvSettingsScreen.kt")

        assertTrue("item(key = \"auth_forgot_password\")" in source)
        assertTrue("R.string.tv_settings_forgot_password" in source)
        assertTrue("authClient.requestPasswordReset(authEmail)" in source)
        assertTrue("R.string.login_reset_sent" in source)
        assertTrue("R.string.tv_auth_email_required" in source)
    }

    @Test
    fun providerFiltersReturnToTheRegisteredPosterRequester() {
        val source = readSource("screens/TvSeeAllScreen.kt")

        assertTrue("val rememberedPosterRequester = lastFocusedKey?.let(focusRequesters::get)" in source)
        assertTrue("focusRequesters[renderedItems.first().seeAllStableKey()]" in source)
        assertTrue("rememberedPosterRequester != null -> rememberedPosterRequester" in source)
    }

    @Test
    fun queryPlanCoversMovieAndSeriesForEveryProviderCategory() {
        val plan = tvProviderCatalogQueryPlan(currentYear = 2026)

        assertEquals(6, plan.size)
        assertEquals(3, plan.count { it.mediaType == "movie" })
        assertEquals(3, plan.count { it.mediaType == "tv" })
        assertTrue(
            plan.filter { it.bucket.name.startsWith("RECENT") }
                .all { it.startYear == 2025 && it.endYear == 2026 },
        )
        assertTrue(plan.filter { it.bucket.name.startsWith("TOP_RATED") }.all { it.minRating == 7f })
    }

    @Test
    fun catalogBuildsProviderStyleCoreRailsInStableOrder() {
        val rails = buildTvProviderCatalogRails(
            providerId = 8,
            providerName = "Netflix",
            region = "DE",
            itemsByBucket = mapOf(
                TvProviderCatalogBucket.RECENT_MOVIES to listOf(movie(1, "Recent Movie", year = 2026)),
                TvProviderCatalogBucket.RECENT_SERIES to listOf(series(2, "Recent Series", year = 2025)),
                TvProviderCatalogBucket.POPULAR_MOVIES to listOf(movie(3, "Popular Movie")),
                TvProviderCatalogBucket.POPULAR_SERIES to listOf(series(4, "Popular Series")),
                TvProviderCatalogBucket.TOP_RATED_MOVIES to listOf(movie(5, "Top Movie", rating = 8.9)),
                TvProviderCatalogBucket.TOP_RATED_SERIES to listOf(series(6, "Top Series", rating = 9.1)),
            ),
        )

        assertEquals(
            listOf(
                "New & Recent on Netflix",
                "Popular Movies",
                "Popular Series",
                "Top Rated on Netflix",
            ),
            rails.map { it.title },
        )
        assertEquals(listOf("Top Series", "Top Movie"), rails[3].items.map { it.title })
        assertTrue(rails.all { it.key.contains("_8_de_") })
        assertTrue(rails.all { it.key.startsWith("streaming_catalog_") })
    }

    @Test
    fun everyAvailableGenreGetsAnAlphabeticalRailAndSeeAllKeepsTheFullSet() {
        val items = (1..31).map { id -> movie(id, "Action $id", genres = listOf(28)) } +
            movie(40, "Western", genres = listOf(37)) +
            movie(41, "Science Fiction", genres = listOf(878))

        val rails = buildTvProviderCatalogRails(
            providerId = 8,
            providerName = "Netflix",
            region = "worldwide",
            itemsByBucket = mapOf(TvProviderCatalogBucket.POPULAR_MOVIES to items),
        )
        val genreTitles = rails.dropWhile { it.title == "Popular Movies" }.map { it.title }
        assertEquals(listOf("Action", "Science Fiction", "Westerns"), genreTitles)
        val action = rails.first { it.title == "Action" }
        assertEquals(24, action.items.size)
        assertEquals(31, action.seeAllItems?.size)
    }

    @Test
    fun strongestGenresBecomeCollectionsWithoutTextOnlyItems() {
        val actionItems = (1..5).map { id -> movie(id, "Action $id", genres = listOf(28)) }
        val posterless = movie(90, "No Poster", genres = listOf(28)).copy(posterUrl = null)
        val comedyItems = (10..13).map { id -> movie(id, "Comedy $id", genres = listOf(35)) }

        val rails = buildTvProviderCatalogRails(
            providerId = 9,
            providerName = "Prime Video",
            region = "US",
            itemsByBucket = mapOf(
                TvProviderCatalogBucket.POPULAR_MOVIES to actionItems + comedyItems + posterless,
            ),
        )

        assertTrue(rails.any { it.title == "Action" })
        assertTrue(rails.any { it.title == "Comedy" })
        assertFalse(rails.flatMap { it.items }.any { it.posterUrl.isNullOrBlank() })
    }

    private fun movie(
        id: Int,
        title: String,
        year: Int? = null,
        rating: Double? = null,
        genres: List<Int> = emptyList(),
    ) = item(id, title, MediaType.MOVIE, year, rating, genres)

    private fun series(
        id: Int,
        title: String,
        year: Int? = null,
        rating: Double? = null,
        genres: List<Int> = emptyList(),
    ) = item(id, title, MediaType.SERIES, year, rating, genres)

    private fun item(
        id: Int,
        title: String,
        type: MediaType,
        year: Int?,
        rating: Double?,
        genres: List<Int>,
    ) = MediaItem(
        id = "$type-$id",
        tmdbId = id,
        type = type,
        title = title,
        year = year,
        posterUrl = "https://image/$id.jpg",
        rating = rating,
        genreIds = genres,
        popularity = id.toDouble(),
    )

    private fun readSource(relativePath: String): String = String(
        Files.readAllBytes(Paths.get("src/tv/kotlin/com/torve/android/tv/$relativePath")),
        Charsets.UTF_8,
    )

    private fun readMainSource(relativePath: String): String = String(
        Files.readAllBytes(Paths.get("src/main/kotlin/com/torve/android/$relativePath")),
        Charsets.UTF_8,
    )

    private fun readSharedSource(relativePath: String): String = String(
        Files.readAllBytes(Paths.get("../shared/src/commonMain/kotlin/com/torve/$relativePath")),
        Charsets.UTF_8,
    )
}
