package com.torve.presentation.apple

import com.torve.data.addon.*
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import com.torve.data.ai.AiProvider
import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.catalog.SortOption
import com.torve.presentation.search.matches
import com.torve.presentation.search.order
import com.torve.presentation.search.SearchFilter
import com.torve.presentation.search.SearchViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.ThemeMode
import kotlinx.serialization.encodeToString
import org.koin.core.Koin
import kotlinx.datetime.*
import com.torve.presentation.detail.primaryPlaybackTarget

/** Typed adapters keep default arguments and enum policy in shared code. */
class AppleNativeFeatures(private val koin: Koin) {
    @Throws(Exception::class)
    suspend fun contentFacts(item: MediaItem): List<com.torve.presentation.detail.ContentFact> = koin.get<com.torve.presentation.detail.ContentCommunity>().facts(item)
    @Throws(Exception::class)
    suspend fun contentComments(item: MediaItem, page: Int): com.torve.presentation.detail.ContentCommentPage = koin.get<com.torve.presentation.detail.ContentCommunity>().comments(item, page)

    fun watchlistCard(item: WatchlistItem): MediaItem = MediaItem(
        id = item.mediaId, tmdbId = item.tmdbId, imdbId = item.imdbId,
        title = item.title, posterUrl = item.posterUrl, backdropUrl = item.backdropUrl,
        rating = item.rating, year = item.year, type = item.mediaType,
    )
    @Throws(Exception::class)
    suspend fun watchlistRatings(items: List<WatchlistItem>): List<MediaItem> =
        AppleCollections(koin).enrich(items.map(::watchlistCard))
    /** Lookup runs after presentation and never blocks playback or replaces an exact episode with its show. */
    @Throws(Exception::class)
    suspend fun fullSynopsis(item: MediaItem, season: Int?, episode: Int?, fallback: String): ApplePlaybackSynopsis =
        koin.get<UserIdProvider>().withPersonalDataScope {
            val isEpisode = item.type == MediaType.SERIES && (season != null || episode != null)
            var story = ApplePlaybackSynopsis(if (isEpisode) "${item.title} Â· S$season E$episode" else item.title, fallback)
            if (isEpisode && (season == null || episode == null)) return@withPersonalDataScope story
            suspend fun <T> safely(block: suspend () -> T): T? = try {
                kotlinx.coroutines.withTimeoutOrNull(6_000) { block() }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            var imdb = item.imdbId
            if (item.tmdbId != null) {
                val metadata = koin.get<MetadataRepository>()
                val detail = safely { metadata.getDetail(if (item.type == MediaType.SERIES) "tv" else "movie", item.tmdbId) }
                imdb = imdb ?: detail?.imdbId
                val overview = if (isEpisode) safely { metadata.getSeasonDetail(item.tmdbId, season!!).episodes.firstOrNull { it.episodeNumber == episode }?.overview }
                    else detail?.overview
                story = ApplePlaybackSynopsisPolicy.preferFull(story, overview, "TMDB")
            }
            if (imdb != null && koin.get<com.torve.data.metadata.TmdbApiClient>().resolvedContentLanguage().startsWith("en", true)) {
                val plot = safely { koin.get<com.torve.data.ratings.OmdbClient>().fetchFullPlot(imdb,
                    if (isEpisode) "episode" else if (item.type == MediaType.SERIES) "series" else "movie", season, episode) }
                story = ApplePlaybackSynopsisPolicy.preferFull(story, plot, "OMDb")
            }
            story
        }

    @Throws(Exception::class)
    suspend fun playbackSynopsis(sessions: ApplePlaybackSessions, token: String): ApplePlaybackSynopsis? {
        val identity = sessions.identity(token) ?: return null
        val item = sessions.sourceSnapshot(token)?.mediaItem ?: return sessions.synopsis(token)
        val base = sessions.synopsis(token) ?: return null
        val result = fullSynopsis(item, identity.season, identity.episode, base.overview)
        return result.takeIf { sessions.identity(token) == identity }
    }
    @Throws(Exception::class)
    suspend fun playbackSpoilersHidden(): Boolean = koin.get<UserIdProvider>().withPersonalDataScope {
        koin.get<com.torve.domain.repository.PreferencesRepository>().getString(
            com.torve.presentation.watching.KEY_HIDE_UNWATCHED_SPOILERS) == "true"
    }
    @Throws(Exception::class)
    suspend fun hasPersonalTmdbKey(): Boolean = koin.get<com.torve.data.metadata.TmdbUserCredentials>().read() != null

    @Throws(Exception::class)
    suspend fun savePersonalTmdbKey(value: String) = koin.get<UserIdProvider>().withPersonalDataScope {
        koin.get<com.torve.data.metadata.TmdbUserCredentials>().save(value)
        koin.get<SettingsViewModel>().setTmdbCatalogueEnabled(true)
    }

    @Throws(Exception::class)
    suspend fun removePersonalTmdbKey() = koin.get<UserIdProvider>().withPersonalDataScope {
        koin.get<com.torve.data.metadata.TmdbUserCredentials>().clear()
    }

    /** A fresh Apple installation starts in Torve's dark appearance; saved choices win. */
    @Throws(Exception::class)
    suspend fun initializeAppearance(fallbackMode: ThemeMode): ThemeMode {
        val prefs = koin.get<com.torve.domain.repository.PreferencesRepository>()
        val saved = prefs.getString(SettingsViewModel.KEY_THEME_MODE)
        val mode = resolveAppleAppearance(saved, fallbackMode)
        if (saved == null) prefs.setString(SettingsViewModel.KEY_THEME_MODE, mode.name)
        koin.get<SettingsViewModel>().setThemeMode(mode)
        return mode
    }
    /** Public artwork lookup only; never opens a request service or changes library state. */
    @Throws(Exception::class)
    suspend fun titleArtwork(mediaId: String, type: String): String? {
        val id = mediaId.toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (type != "movie" && type != "tv") return null
        return koin.get<MetadataRepository>().getLogoUrl(type, id)
    }
    @Throws(Exception::class)
    suspend fun localizedMedia(mediaId: String, type: String, language: String): MediaItem? =
        koin.get<com.torve.data.metadata.LocalizedMediaRepository>().presentation(mediaId, type, language)
    @Throws(Exception::class)
    suspend fun extensionMetadata(mediaId: String, type: String, language: String): MediaItem? =
        koin.get<com.torve.data.metadata.LocalizedMediaRepository>().presentation(mediaId, type, language, includeCast = true)
    @Throws(Exception::class)
    suspend fun catalogueDetail(seedJSON: String, language: String): String =
        koin.get<UserIdProvider>().withPersonalDataScope {
            val seed = AppleCatalogueDetail.decode(seedJSON)
            val item = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                AppleCatalogueDetail.resolve(seed, koin.get<com.torve.domain.repository.AddonRepository>().getEnabledAddons(),
                    fetchMeta = { addon, type, id ->
                        kotlinx.coroutines.withTimeoutOrNull(2_000) {
                            koin.get<StremioAddonClient>().fetchMeta(addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'), type, id).meta
                        }
                    }, enrich = { supplied ->
                        val type = if (supplied.type == MediaType.SERIES) "tv" else "movie"
                        extensionMetadata(supplied.id, type, language)?.let { detail ->
                            val first = detail.seasons.firstOrNull { it.seasonNumber > 0 }
                            if (detail.type == MediaType.SERIES && detail.tmdbId != null && first != null) {
                                val season = try { koin.get<MetadataRepository>().getSeasonDetail(detail.tmdbId, first.seasonNumber) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { null }
                                if (season != null) detail.copy(seasons = detail.seasons.map { if (it.seasonNumber == season.seasonNumber) season else it }) else detail
                            } else detail
                        }
                    })
            } ?: seed
            AppleCatalogueDetail.encode(item)
        }
    /** Public cast metadata for the focused Search preview; does not open a detail page. */
    @Throws(Exception::class)
    suspend fun previewCast(mediaId: String, type: String): List<CastMember> {
        val id = AppleDetailNavigation.catalogId(mediaId) ?: return emptyList()
        if (type != "movie" && type != "tv") return emptyList()
        return koin.get<MetadataRepository>().getDetail(type, id).cast.distinctBy { it.id }.take(4)
    }
    @Throws(Exception::class)
    suspend fun mediaArtwork(mediaId: String, type: String, landscape: Boolean): List<String> {
        val id = mediaId.toIntOrNull()?.takeIf { it > 0 } ?: return emptyList()
        if (type != "movie" && type != "tv") return emptyList()
        val metadata = koin.get<MetadataRepository>()
        val item = try { metadata.getDetail(type, id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        val gallery = try { metadata.getMediaImageUrls(type, id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
        // The metadata gallery exports posters at w500 and backdrops at w1280.
        // Preserve a poster-shaped choice for portrait catalogue cards when possible.
        val preferredSize = if (landscape) "/w1280/" else "/w500/"
        val primary = if (landscape) item?.backdropUrl else item?.posterUrl
        val secondary = if (landscape) item?.posterUrl else item?.backdropUrl
        return (listOfNotNull(primary) + gallery.filter { preferredSize in it } +
            listOfNotNull(secondary) + gallery).filter { it.isNotBlank() }.distinct().take(12)
    }
    private companion object { val traktLock = Mutex(); val alertLock = Mutex() }
    val settingsChanges get() = koin.get<com.torve.presentation.settings.SettingsRefreshNotifier>().events
    @Throws(Exception::class)
    suspend fun connectionAlerts(): AppleConnectionAlertPreferences = koin.get<UserIdProvider>().withPersonalDataScope {
        val prefs = koin.get<com.torve.domain.repository.PreferencesRepository>()
        decodeConnectionAlertPreferences(prefs.getString(KEY_CONNECTION_ALERTS_ENABLED), prefs.getString(KEY_CONNECTION_ALERT_MUTED_CATEGORIES))
    }
    @Throws(Exception::class)
    suspend fun setConnectionAlertsEnabled(enabled: Boolean) = koin.get<UserIdProvider>().withPersonalDataScope {
        koin.get<com.torve.domain.repository.PreferencesRepository>().setString(KEY_CONNECTION_ALERTS_ENABLED, enabled.toString())
    }
    @Throws(Exception::class)
    suspend fun setConnectionAlert(category: String, enabled: Boolean) = koin.get<UserIdProvider>().withPersonalDataScope { alertLock.withLock {
        require(com.torve.domain.providerhealth.ProviderHealthCategory.entries.any { it.name == category })
        val prefs = koin.get<com.torve.domain.repository.PreferencesRepository>()
        val current = decodeConnectionAlertPreferences(null, prefs.getString(KEY_CONNECTION_ALERT_MUTED_CATEGORIES)).mutedCategories.toSet()
        val muted = if (enabled) current - category else current + category
        prefs.setString(KEY_CONNECTION_ALERT_MUTED_CATEGORIES, kotlinx.serialization.json.Json.encodeToString(muted.sorted()))
    } }
    fun cardStyle(state: com.torve.presentation.settings.SettingsUiState): CardStyle =
        resolveCardStyle(state.cardStylePresets, null, state.globalDefaultPresetId)
    fun editCardPreset(presetId: String, field: String, value: String) {
        val settings = koin.get<SettingsViewModel>()
        val style = settings.state.value.cardStylePresets.firstOrNull { it.presetId == presetId }?.cardStyle ?: return
        settings.updateCardStylePreset(presetId, editAppleCardStyle(style, field, value))
    }
    fun setHomeRowPreset(key: String, presetId: String) {
        val home = koin.get<com.torve.presentation.home.HomeViewModel>()
        val id = presetId.takeUnless { it.isBlank() || it == "global" }
        if (key.startsWith("custom:")) home.updateCustomSectionPreset(key.removePrefix("custom:"), id)
        else HomeSection.entries.firstOrNull { "section:${it.name}" == key }?.let { home.updateSectionPreset(it, id) }
    }
    fun moveHomeRow(key: String, direction: Int) {
        val home = koin.get<com.torve.presentation.home.HomeViewModel>()
        val keys = (home.homeLayoutOrder.value + home.sectionConfigs.value.sortedBy { it.order }.map { "section:${it.section.name}" } +
            home.customSections.value.sortedBy { it.order }.map { "custom:${it.id}" }).distinct().toMutableList()
        val index = keys.indexOf(key); val target = index + direction.coerceIn(-1, 1)
        if (index < 0 || target !in keys.indices) return
        keys.add(target, keys.removeAt(index)); home.updateHomeLayoutOrder(keys)
    }
    fun saveCustomHomeRow(id: String, title: String, type: String, sort: String, genres: List<Int>, minimum: Float, from: Int, to: Int, presetId: String) {
        val home = koin.get<com.torve.presentation.home.HomeViewModel>()
        if (title.isBlank() || type !in listOf("movie", "tv", "both") || (from > 0 && to > 0 && from > to)) return
        val existing = home.customSections.value.firstOrNull { it.id == id }
        val filters = (existing?.filters ?: CustomSectionFilters()).copy(genreIds = genres.filter { it > 0 }.distinct(), sortBy = if (type == "tv" && sort == "primary_release_date.desc") "first_air_date.desc" else sort,
            minRating = minimum.takeIf { it in 0.1f..10f }, yearFrom = from.takeIf { it > 0 }, yearTo = to.takeIf { it > 0 })
        val row = CustomSection(id, title.trim(), type, filters, existing?.order ?: home.customSections.value.size, existing?.enabled ?: true,
            presetId.takeUnless { it.isBlank() || it == "global" })
        if (existing == null) home.addCustomSection(row) else home.updateCustomSection(row)
    }
    fun cardStyleForPreset(state: com.torve.presentation.settings.SettingsUiState, presetId: String?): CardStyle =
        resolveCardStyle(state.cardStylePresets, presetId, state.globalDefaultPresetId)
    fun presetWidth(state: com.torve.presentation.settings.SettingsUiState, presetId: String?): Int =
        cardStyleForPreset(state, presetId).size.resolvedWidthDp()
    fun presetAspect(state: com.torve.presentation.settings.SettingsUiState, presetId: String?): Float =
        cardStyleForPreset(state, presetId).size.resolvedAspectRatio()
    fun ratingsForStyle(item: MediaItem, style: CardStyle): List<AppleTvRating> = displayAppleRatings(item, style.ratingPrefs, false)
    fun retainHomeShelf(key: String, title: String, items: List<MediaItem>) {
        com.torve.presentation.seeall.SeeAllViewModel.pendingItems[key] = title to items
    }
    fun cardWidth(state: com.torve.presentation.settings.SettingsUiState): Int = cardStyle(state).size.resolvedWidthDp()
    fun cardAspect(state: com.torve.presentation.settings.SettingsUiState): Float = cardStyle(state).size.resolvedAspectRatio()
    fun ratings(item: MediaItem, detail: Boolean): List<AppleTvRating> = displayAppleRatings(item,
        appleRatingPreferences(koin.get<SettingsViewModel>().state.value, detail), detail)
    @Throws(Exception::class)
    suspend fun upcomingNotifications() = koin.get<UserIdProvider>().withPersonalDataScope {
        if (koin.get<com.torve.domain.repository.PreferencesRepository>().getString(com.torve.presentation.calendar.CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) != "true") emptyList()
        else koin.get<com.torve.data.trakt.api.TraktAuthorizedApi>().getCalendarCached(7).episodes
    }
    fun owner(): String = koin.get<UserIdProvider>().currentDataUserId()
    /** Read installed providers only in the account that opened the connection page. */
    @Throws(Exception::class)
    suspend fun playbackExtensions(expectedOwner: String): List<InstalledAddon> =
        koin.get<UserIdProvider>().withPersonalDataScope {
            if (owner() != expectedOwner) emptyList()
            else koin.get<com.torve.domain.repository.AddonRepository>().getEnabledAddons()
        }
    /** Local identity and catalogue choices are ready without waiting for backend health/auth. */
    @Throws(Exception::class)
    suspend fun prepareLocalSession(): com.torve.data.auth.AuthUser? {
        val user = koin.get<com.torve.data.auth.AuthClient>().restoreCachedSessionUser()
        koin.get<SettingsViewModel>().refreshSettings()
        return user
    }
    @Throws(Exception::class)
    suspend fun hasPlaybackSources(): Boolean = koin.get<com.torve.domain.repository.AddonRepository>()
        .getEnabledAddons().any { it.canResolveStreams() } ||
        koin.get<com.torve.data.usenet.ConfiguredUsenetSources>().hasSources()
    @Throws(Exception::class)
    suspend fun catalogLibraryAvailability(item: MediaItem): AppleCatalogLibraryAvailability? {
        val ids = koin.get<UserIdProvider>()
        val owner = ids.currentDataUserId()
        return ids.withPersonalDataScope {
            // External catalogues can identify a library copy by IMDb without enabling TMDB enrichment.
            val copies = if (item.tmdbId != null || !item.imdbId.isNullOrBlank())
                koin.get<com.torve.data.integrations.JellyfinLibraryOverlayService>()
                    .playableCatalogCopies(item.tmdbId ?: 0, item.type, item.imdbId) else null
            if (ids.currentDataUserId() != owner) return@withPersonalDataScope null
            AppleCatalogLibraryAvailability(copies.orEmpty(), hasPlaybackSources())
        }
    }
    @Throws(Exception::class)
    suspend fun catalogLibraryPlayback(item: MediaItem, season: Int?, episode: Int?): String? {
        val ids = koin.get<UserIdProvider>()
        val owner = ids.currentDataUserId()
        return ids.withPersonalDataScope {
            if (item.tmdbId == null && item.imdbId.isNullOrBlank()) return@withPersonalDataScope null
            val route = koin.get<com.torve.data.integrations.JellyfinLibraryOverlayService>()
                .verifiedCatalogPlaybackRoute(item.tmdbId ?: 0, item.type, season, episode, item.imdbId) ?: return@withPersonalDataScope null
            if (ids.currentDataUserId() != owner) return@withPersonalDataScope null
            com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff.stage(route)
            route.url
        }
    }
    @Throws(Exception::class)
    suspend fun playbackPreferences(key: String, expectedOwner: String): AppleContentPlaybackPreferences? {
        val ids = koin.get<UserIdProvider>()
        if (ids.currentDataUserId() != expectedOwner || !ApplePlaybackPreferencePolicy.validKey(key)) return null
        return ids.withPersonalDataScope {
            val prefs = koin.get<com.torve.domain.repository.PreferencesRepository>()
            ApplePlaybackPreferencePolicy.read(prefs.getString(key), prefs.getString("${key}_tracks"))
        }
    }
    @Throws(Exception::class)
    suspend fun savePlaybackPreferences(key: String, expectedOwner: String, delay: Int, speed: Double, picture: String,
                                        audio: String?, subtitle: String?, subtitlesEnabled: Boolean) {
        val ids = koin.get<UserIdProvider>()
        if (ids.currentDataUserId() != expectedOwner || !ApplePlaybackPreferencePolicy.validKey(key)) return
        ids.withPersonalDataScope {
            val prefs = koin.get<com.torve.domain.repository.PreferencesRepository>()
            prefs.setString(key, ApplePlaybackPreferencePolicy.playbackValue(delay, speed, picture))
            prefs.setString("${key}_tracks", ApplePlaybackPreferencePolicy.tracksValue(audio, subtitle, subtitlesEnabled))
        }
    }
    fun consumePlaybackHeaders(url: String): Map<String, String> =
        com.torve.data.replays.PendingReplayHeaders.value.consume(owner(), url)
            ?: com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff.consumeFor(url).orEmpty()
    fun peekPlaybackHeaders(url: String): Map<String, String> =
        com.torve.data.replays.PendingReplayHeaders.value.peek(owner(), url)
            ?: com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff.peekFor(url).orEmpty()
    fun channelPlaybackRequest(channel: Channel) = com.torve.domain.player.ChannelPlaybackRequestPolicy.request(channel)
    @Throws(Exception::class)
    suspend fun vodCategories(playlistId: String, type: ChannelContentType): List<ChannelCategory> {
        require(type == ChannelContentType.VOD_MOVIE || type == ChannelContentType.VOD_SERIES)
        return koin.get<UserIdProvider>().withPersonalDataScope {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                AppleVodCatalogPolicy.categories(
                    koin.get<com.torve.domain.repository.ChannelRepository>().getVodCategoryTypeCounts(playlistId), type)
            }
        }
    }
    @Throws(Exception::class)
    suspend fun vodItems(playlistId: String, category: String, type: ChannelContentType, limit: Int): List<Channel> {
        require(type == ChannelContentType.VOD_MOVIE || type == ChannelContentType.VOD_SERIES)
        return koin.get<UserIdProvider>().withPersonalDataScope {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                koin.get<com.torve.domain.repository.ChannelRepository>()
                    .getChannelsForCategoryContentType(playlistId, category, type, limit.coerceAtLeast(1))
            }
        }
    }
    @Throws(Exception::class)
    suspend fun vodEpisodes(series: Channel): List<Channel> = koin.get<UserIdProvider>().withPersonalDataScope {
        require(series.contentType == ChannelContentType.VOD_SERIES)
        koin.get<com.torve.domain.repository.ChannelRepository>().getVodSeriesEpisodes(series)
    }
    @Throws(Exception::class)
    suspend fun jellyfinPlayback(itemId: String): String? {
        val ids = koin.get<UserIdProvider>()
        val owner = ids.currentDataUserId()
        return ids.withPersonalDataScope {
            val route = koin.get<com.torve.data.integrations.JellyfinLibraryOverlayService>().buildAuthenticatedStream(itemId)
                ?: return@withPersonalDataScope null
            if (ids.currentDataUserId() != owner) return@withPersonalDataScope null
            com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff.stage(route)
            route.url
        }
    }
    fun playbackSegments(sessions: ApplePlaybackSessions, token: String): ApplePlaybackSegments =
        ApplePlaybackSegments(koin.get(), sessions, token, koin.get()) { koin.get<SettingsViewModel>().state.value }
    fun sourceRecovery(sessions: ApplePlaybackSessions, token: String): AppleSourceRecovery =
        AppleSourceRecovery(koin.get(), sessions, token, koin.get(),
            { koin.get<SettingsViewModel>().buildStreamPreferences() }, { koin.get<SettingsViewModel>().getDebridAccounts() })
    @Throws(Exception::class)
    suspend fun pairings(): List<com.torve.data.pairing.PairedDeviceDto> {
        val auth = koin.get<com.torve.data.auth.AuthClient>()
        val token = auth.getValidAccessToken() ?: return emptyList()
        val pairings = koin.get<com.torve.data.pairing.PairingApi>().listPairings(token).pairings
        val devices = koin.get<com.torve.data.device.DeviceApi>().getDevices(token).devices
        return resolveApplePairingPeers(pairings, devices, auth.currentDeviceRegistration().installation_id, auth.getServerDeviceId())
    }
    @Throws(Exception::class)
    suspend fun claimPair(code: String, signIn: Boolean): String {
        val auth = koin.get<com.torve.data.auth.AuthClient>()
        val token = auth.getValidAccessToken() ?: error("Sign in to Torve before pairing devices.")
        val api = koin.get<com.torve.data.pairing.PairingApi>()
        return if (signIn) api.claimSigninCode(token, code).status
        else api.claimPairingCode(token, code, auth.currentDeviceRegistration()).status
    }
    @Throws(Exception::class)
    suspend fun revokePair(id: String) {
        val token = koin.get<com.torve.data.auth.AuthClient>().getValidAccessToken() ?: error("Sign in first.")
        koin.get<com.torve.data.pairing.PairingApi>().revokePairing(token, id)
    }
    @Throws(Exception::class)
    suspend fun notificationsEnabled(): Boolean = koin.get<com.torve.domain.repository.PreferencesRepository>()
        .getString(com.torve.presentation.calendar.CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) == "true"
    @Throws(Exception::class)
    suspend fun setNotifications(enabled: Boolean) = koin.get<com.torve.domain.repository.PreferencesRepository>()
        .setString(com.torve.presentation.calendar.CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED, enabled.toString())
    fun searchFilteredItems(items: List<com.torve.domain.model.MediaItem>, filter: SearchFilter): List<com.torve.domain.model.MediaItem> =
        filter.order(items.filter { filter.matches(it) })
    fun searchCertifications(type: String, country: String): List<String> = when (country) {
        "DE" -> listOf("0", "6", "12", "16", "18")
        "GB" -> listOf("U", "PG", "12", "12A", "15", "18")
        "FR" -> listOf("U", "10", "12", "16", "18")
        "AU" -> listOf("G", "PG", "M", "MA15+", "R18+")
        else -> if (type == "tv") listOf("TV-Y", "TV-Y7", "TV-G", "TV-PG", "TV-14", "TV-MA")
            else if (type == "movie") listOf("G", "PG", "PG-13", "R", "NC-17")
            else listOf("G", "PG", "PG-13", "R", "NC-17", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "TV-14", "TV-MA")
    }
    fun searchGenres(type: String): List<com.torve.domain.model.Genre> = when (type) {
        "tv" -> com.torve.data.metadata.TmdbGenres.TV_GENRES
        "movie" -> com.torve.data.metadata.TmdbGenres.MOVIE_GENRES
        else -> com.torve.data.metadata.TmdbGenres.MOVIE_GENRES + com.torve.data.metadata.TmdbGenres.TV_GENRES
    }.map { com.torve.domain.model.Genre(it.key, it.value) }.sortedBy { it.name }
    fun searchFilterAdvanced(vm: SearchViewModel, type: String, imdb: Float, tmdb: Float, from: Int, to: Int, sort: String, genres: String, runtime: String, certification: String, country: String) {
        vm.applyFilter(SearchFilter(mediaType = type.takeIf { it == "movie" || it == "tv" },
            certification = certification.takeIf { it.isNotBlank() }, certificationCountry = country.takeIf { it in setOf("US", "DE", "GB", "FR", "AU") } ?: "US",
            genreIds = genres.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct(),
            minImdbScore = imdb.takeIf { it > 0 }, minTmdbScore = tmdb.takeIf { it > 0 },
            yearFrom = from.takeIf { it > 0 }, yearTo = to.takeIf { it > 0 },
            runtimeFilter = com.torve.presentation.catalog.RuntimeFilter.entries.firstOrNull { it.name == runtime },
            sortBy = SortOption.entries.firstOrNull { it.name == sort } ?: SortOption.IMDB_SCORE_DESC))
    }
    fun searchFilter(vm: SearchViewModel, type: String, minimum: Float, from: Int, to: Int, sort: String) {
        vm.applyFilter(SearchFilter(mediaType = type.takeIf { it == "movie" || it == "tv" },
            minImdbScore = minimum.takeIf { it > 0 }, yearFrom = from.takeIf { it > 0 }, yearTo = to.takeIf { it > 0 },
            sortBy = SortOption.entries.firstOrNull { it.name == sort } ?: SortOption.IMDB_SCORE_DESC))
    }
    fun sortedSources(sources: List<ParsedStream>, mode: String, quality: String, cachedOnly: Boolean): List<ParsedStream> =
        sortStreamResults(sources.filter { (quality.isEmpty() || normalizedQuality(it.quality) == normalizedQuality(quality)) && (!cachedOnly || it.isCached) },
            StreamSortMode.entries.firstOrNull { it.name == mode } ?: StreamSortMode.DEFAULT)
    private fun normalizedQuality(value: String?) = when (value?.lowercase()?.trim()) {
        "4k", "uhd", "2160p" -> "2160p"
        "full hd", "fhd", "1080p" -> "1080p"
        "hd", "720p" -> "720p"
        else -> value?.lowercase()?.trim().orEmpty()
    }
    fun setAiProvider(name: String) {
        AiProvider.entries.firstOrNull { it.name == name }?.let { koin.get<SettingsViewModel>().setAiProvider(it) }
    }
    fun quality(maximum: Boolean, name: String) {
        val value = StreamQuality.entries.firstOrNull { it.name == name } ?: return
        if (maximum) koin.get<SettingsViewModel>().setMaxQuality(value) else koin.get<SettingsViewModel>().setMinQuality(value)
    }
    fun connectStreamProvider(name: String, key: String) {
        DebridServiceType.entries.firstOrNull { it.name == name }?.let { koin.get<SettingsViewModel>().connectDebridProviderWithApiKey(it, key) }
    }
    fun disconnectStreamProvider(name: String) {
        DebridServiceType.entries.firstOrNull { it.name == name }?.let { provider ->
            koin.get<SettingsViewModel>().apply { setDebridProvider(provider); disconnectDebrid() }
        }
    }
    fun saveProfile(vm: com.torve.presentation.profile.ProfileViewModel, id: String, name: String, rating: String, pin: String, changePin: Boolean) =
        vm.saveProfile(id, name, ContentRating.entries.firstOrNull { it.name == rating }, pin.takeIf { it.isNotBlank() }, changePin)
    fun seeAllSort(vm: com.torve.presentation.seeall.SeeAllViewModel, mode: String) {
        com.torve.presentation.seeall.SeeAllSortMode.entries.firstOrNull { it.name == mode }?.let(vm::setSortMode)
    }
    @Throws(Exception::class)
    suspend fun restore() = koin.get<com.torve.presentation.session.AccountSessionCoordinator>().restoreSession()
    @Throws(Exception::class)
    suspend fun foreground() = koin.get<com.torve.presentation.session.AccountSessionCoordinator>().onAppForeground()
    @Throws(Exception::class)
    suspend fun bootstrap() = koin.get<com.torve.presentation.session.AccountSessionCoordinator>().bootstrapAfterSignIn()
    @Throws(Exception::class)
    suspend fun syncTrakt(): Boolean = koin.get<UserIdProvider>().withPersonalDataScope { traktLock.withLock {
        if (!koin.get<SettingsViewModel>().isTraktConnected()) return@withLock false
        koin.get<com.torve.domain.repository.WatchlistRepository>().syncFromTrakt()
        koin.get<com.torve.domain.repository.WatchHistoryRepository>().syncFromTrakt()
        koin.get<com.torve.domain.repository.WatchProgressRepository>().syncFromTrakt()
        koin.get<com.torve.data.trakt.repo.TraktSyncRepository>().apply { syncRatingsFromTrakt(); flushPendingWrites() }
        true
    } }
    fun continueEpisode(sessions: ApplePlaybackSessions, token: String,
        detail: com.torve.presentation.detail.DetailViewModel, season: Int, episode: Int): Boolean {
        val item = detail.state.value.mediaItem ?: return false
        if (item.type != MediaType.SERIES) return false
        val request = sessions.consumeContinuation(token, item.id, season, episode) ?: return false
        detail.fetchStreamsForContinuation(season, episode, request.source)
        return true
    }

    @Throws(Exception::class)
    suspend fun nextEpisode(sessions: ApplePlaybackSessions, token: String): ApplePlaybackIdentity? {
        val identity = sessions.identity(token) ?: return null
        val season = identity.season ?: return null
        val episode = identity.episode ?: return null
        val id = identity.mediaId.extractTmdbIdOrNull() ?: return null
        val repo = koin.get<com.torve.domain.repository.MetadataRepository>()
        val item = repo.getDetail("tv", id) ?: return null
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        for (number in item.seasons.filter { it.seasonNumber >= season }.sortedBy { it.seasonNumber }) {
            val detail = repo.getSeasonDetail(id, number.seasonNumber) ?: continue
            val next = detail.episodes.filter { it.episodeNumber > (if (number.seasonNumber == season) episode else 0) }
                .sortedBy { it.episodeNumber }.firstOrNull { !it.airDate.isNullOrBlank() && it.airDate!! <= today } ?: continue
            if (sessions.identity(token) == null) return null
            return identity.copy(season = number.seasonNumber, episode = next.episodeNumber)
        }
        return null
    }
    @Throws(Exception::class)
    suspend fun subtitles(sessions: ApplePlaybackSessions, token: String, language: String): List<com.torve.data.subtitles.OsSubtitleResult> {
        val identity = sessions.identity(token) ?: return emptyList()
        val client = koin.get<com.torve.data.subtitles.OpenSubtitlesClient>()
        if (!client.isConfiguredAsync()) return emptyList()
        val result = client.searchSubtitles(identity.imdbId, identity.season, identity.episode, language.takeIf { it.isNotBlank() }, identity.title)
        return result.takeIf { sessions.identity(token) != null }.orEmpty()
    }
    @Throws(Exception::class)
    suspend fun subtitleURL(fileId: Int) = koin.get<com.torve.data.subtitles.OpenSubtitlesClient>().getDownloadUrl(fileId)
    fun makeDownload(state: com.torve.presentation.detail.DetailUiState, url: String): Download? {
        val item = state.mediaItem ?: return null
        val target = state.primaryPlaybackTarget
        return Download(id = "apple-${kotlin.random.Random.nextLong().toString(16)}", mediaId = item.id, mediaType = item.type,
            title = item.title, posterUrl = item.posterUrl, streamUrl = url, seasonNumber = target.season, episodeNumber = target.episode)
    }
}

/** Account appearance wins when present; signed-out devices retain their local choice. */
internal fun resolveAppleAppearance(saved: String?, fallbackMode: ThemeMode): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == saved } ?: fallbackMode

data class AppleCatalogLibraryAvailability(val playableKeys: Set<String>, val canSearchSources: Boolean)

internal fun appleRatingPreferences(state: com.torve.presentation.settings.SettingsUiState, detail: Boolean): RatingDisplayPrefs =
    if (detail) state.ratingPrefs
    else resolveCardStyle(state.cardStylePresets, null, state.globalDefaultPresetId).ratingPrefs

internal fun displayAppleRatings(item: MediaItem, prefs: RatingDisplayPrefs, detail: Boolean): List<AppleTvRating> {
    if (detail && !prefs.showRatingsOnDetailPage) return emptyList()
    val ratings = item.ratings.withFallbackTmdbScore(item.rating) ?: return emptyList()
    val torve = if (if (detail) prefs.showTorveScoreOnDetailPage && RatingSource.TORVE in prefs.enabledProviders else prefs.showTorveScoreOnCards)
        calculateTorveScore(ratings, prefs.torveWeights)?.let { AppleTvRating("TORVE", "${(it * 10).toInt() / 10.0}%") } else null
    val available = buildList {
        deriveProvidersToRender(prefs.enabledProviders, prefs.providerOrder, RatingSource.entries.size,
            fallbackToTmdbWhenNoneSelected = !detail)
            .filter { ratings.hasValueFor(it) }.forEach { source ->
                val normalized = ratings.valueForTorve(source) ?: return@forEach
                val value = when (source) {
                    RatingSource.ROTTEN_TOMATOES, RatingSource.RT_AUDIENCE, RatingSource.METACRITIC -> "${normalized.toInt()}%"
                    RatingSource.LETTERBOXD -> ((normalized / 20f * 10).toInt() / 10.0).toString()
                    else -> ((normalized / 10f * 10).toInt() / 10.0).toString()
                }
                add(AppleTvRating(source.name, value))
            }
        torve?.let(::add)
    }
    if (detail) return available
    val hasExternal = available.any { it.provider != "TMDB" && it.provider != "TORVE" }
    return available.filter { it.provider != "TMDB" || !hasExternal }
        .sortedBy { when (it.provider) { "IMDB" -> 0; "ROTTEN_TOMATOES" -> 1; else -> 2 } }
        .take(prefs.maxRatingsOnCard.coerceAtLeast(0))
}
