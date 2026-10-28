package com.torve.presentation.apple

import com.torve.data.addon.ParsedStream
import com.torve.data.auth.*
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.channels.*
import com.torve.presentation.detail.*
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.home.AppleHomeLayoutPolicy
import com.torve.presentation.search.SearchViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.watching.*
import com.torve.presentation.watchlist.WatchlistViewModel
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.Koin

@Serializable data class AppleTvRating(val provider: String, val value: String)
@Serializable data class AppleTvProgramme(val id: String, val title: String, val start: Long, val end: Long,
    val description: String = "", val replay: Boolean = false, val displayStart: Long = start, val displayEnd: Long = end)
@Serializable data class AppleTvRow(val id: String, val title: String, val subtitle: String = "", val artwork: String? = null,
    val mediaType: String = "movie", val season: Int = 0, val episode: Int = 0, val progress: Float = 0f,
    val targetId: String = id, val backdrop: String? = null, val logo: String? = null, val overview: String? = null,
    val ratings: List<AppleTvRating> = emptyList(), val watched: Boolean = false, val favorite: Boolean = false,
    val programmes: List<AppleTvProgramme> = emptyList(), val status: String = "", val year: Int? = null, val airDate: String? = null, val watchedAt: Long? = null, val remainingEpisodes: Int = 0)
@Serializable data class AppleTvShelf(val id: String, val title: String, val items: List<AppleTvRow>,
    val section: String = "", val emptyMessage: String = "No titles are available for this row yet.", val presetId: String? = null)
@Serializable data class AppleTvSnapshot(val screen: String = "home", val title: String = "Torve", val busy: Boolean = false,
    val error: String? = null, val shelves: List<AppleTvShelf> = emptyList(), val rows: List<AppleTvRow> = emptyList(),
    val groups: List<String> = emptyList(), val selectedGroup: String? = null, val artwork: String? = null,
    val logo: String? = null, val overview: String? = null, val sources: List<AppleTvRow> = emptyList(),
    val seasonNumbers: List<Int> = emptyList(), val playerUrl: String? = null, val playbackToken: String? = null,
    val signedIn: Boolean = false, val playerFallback: String? = null, val selectedSeason: Int = 1,
    val primaryPlayLabel: String = "Play", val inWatchlist: Boolean = false, val watched: Boolean = false,
    val canRequest: Boolean = false, val favorite: Boolean = false, val libraryStatus: String? = null, val cast: List<AppleTvRow> = emptyList(),
    val ratings: List<AppleTvRating> = emptyList(), val trailer: String? = null,
    val playlists: List<AppleTvRow> = emptyList(), val selectedPlaylist: String? = null, val channelMode: String = "live",
    val hiddenGroups: List<String> = emptyList(), val allGroups: List<String> = emptyList(), val message: String? = null,
    val aiSearchEnabled: Boolean = false, val sourcePickerVisible: Boolean = false,
    val related: AppleTvShelf? = null, val relatedBusy: Boolean = false, val relatedError: String? = null,
    val detailId: String? = null, val detailType: String = "movie", val hideUnwatchedSpoilers: Boolean = false,
    val hero: AppleTvRow? = null, val channelCountries: Map<String, String> = emptyMap(),
    val selectedCountry: String? = null, val featuredHeroEnabled: Boolean = true, val collectionId: Int? = null, val collectionName: String? = null)

/** Native TV renders shared policy and state rather than maintaining a separate content implementation. */
class AppleTvPresenter(private val koin: Koin, private val catalog: (String) -> CatalogViewModel,
    val playback: ApplePlaybackSessions) {
    private val json = Json { encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(json.encodeToString(AppleTvSnapshot(busy = true)))
    val state: StateFlow<String> = _state.asStateFlow()
    fun detailItem(): MediaItem? = details.state.value.mediaItem.takeIf { screen == "detail" }
    private var observer: Job? = null
    private var detailOpenJob: Job? = null
    private var visibleSnapshot = AppleTvSnapshot()
    private var seedOwner = ""
    private val catalogueSeeds = linkedMapOf<Pair<MediaType, String>, MediaItem>()
    private fun remember(item: MediaItem) {
        val owner = koin.get<UserIdProvider>().currentDataUserId()
        if (owner != seedOwner) { catalogueSeeds.clear(); seedOwner = owner }
        catalogueSeeds[item.type to item.id] = item
        while (catalogueSeeds.size > 512) catalogueSeeds.remove(catalogueSeeds.keys.first())
    }
    private var screen = "home"
    private var channelRows = emptyList<Channel>()
    private var sourceRows = emptyList<ParsedStream>()
    private var showRows = emptyList<WatchingEpisode>()
    private var playbackUrl: String? = null
    private var playbackFallback: String? = null
    private var playbackToken: String? = null
    private var sourceSort = "DEFAULT"
    private var sourceQuality = ""
    private var cachedOnly = false
    private var channelMode = "guide"
    private var calendarMode = false
    private var guideOffsetHours = 0
    private var libraryMode = "watchlist"
    private val home by lazy { koin.get<HomeViewModel>() }
    private val details by lazy { koin.get<DetailViewModel>() }
    private val channels by lazy { koin.get<ChannelsViewModel>() }
    private val search by lazy { koin.get<SearchViewModel>() }
    private val settings by lazy { koin.get<SettingsViewModel>() }
    private val shows by lazy { koin.get<WatchingHubViewModel>() }
    private val watches by lazy { koin.get<WatchlistViewModel>() }
    private val favorites by lazy { koin.get<MediaFavoritesRepository>() }
    private val health by lazy { koin.get<ProviderHealthCoordinator>() }
    private val features by lazy { AppleNativeFeatures(koin) }
    private val movies by lazy { catalog("movie") }
    private val series by lazy { catalog("tv") }
    private fun MediaItem.row(): AppleTvRow {
        remember(this)
        val typeName = if (type == MediaType.SERIES) "tv" else "movie"
        return AppleTvRow("$typeName:$id", title, listOfNotNull(year?.toString(), runtime?.let { "${it}m" }).joinToString(" · "),
            posterUrl, typeName, targetId = id, backdrop = backdropUrl, logo = logoUrl, overview = overview, ratings = ratingRows(this), year = year,
            status = if (catalogueNotice != null) "provider_error" else "")
    }
    private fun ratingRows(item: MediaItem): List<AppleTvRating> = features.ratings(item, false)
    private fun WatchingEpisode.row(hideSpoilers: Boolean) = AppleTvRow("${show.id}:s${season}e${episode.episodeNumber}", show.title,
        listOfNotNull("S$season E${episode.episodeNumber}", if (hideSpoilers && !watched) null else episode.name,
            if (resume) "Resume ${((positionMs ?: 0) / 60_000)}m" else date?.toString()).joinToString(" · "),
        show.posterUrl, "tv", season, episode.episodeNumber, progressPercent ?: 0f, show.id, show.backdropUrl,
        show.logoUrl, show.overview, ratingRows(show), watched, year = show.year, airDate = date?.toString(), remainingEpisodes = remainingAired)
    private fun emit(value: AppleTvSnapshot) {
        if (value.screen != screen) return
        visibleSnapshot = value
        _state.value = json.encodeToString(value.copy(signedIn = koin.get<AuthClient>().authUserFlow.value != null))
    }
    fun activate(destination: String) {
        if (destination != "detail") { detailOpenJob?.cancel(); detailOpenJob = null }
        if (screen == "detail" && destination != "detail") details.cancelSourceLookup()
        observer?.cancel(); screen = destination; playbackUrl = null; playbackFallback = null; playbackToken = null
        emit(AppleTvSnapshot(screen = screen, busy = true))
        observer = scope.launch {
            when (destination) {
                "home" -> {
                    val layout = combine(home.state, home.sectionConfigs, home.customSections, home.homeLayoutOrder,
                        settings.state.map { it.tmdbCatalogueEnabled }.distinctUntilChanged()) { state, configs, custom, order, catalogue ->
                        Triple(state, AppleHomeLayoutPolicy.rows(state, configs, custom, order), catalogue)
                    }
                    combine(layout, shows.state, channels.state, home.enabledServiceIds, home.providerLogos) { value, watching, live, services, logos ->
                        val (s, rows, catalogueEnabled) = value
                        val liveRows = channels.filterVisibleLiveChannels(
                            (live.favorites.ifEmpty { live.recentlyViewedChannels }).map { EnrichedChannel(it) }
                        ).map { it.channel }.filter { AppleChannelBrowsePolicy.isDisplayable(it) }.distinctBy(::stableChannelId)
                        channelRows = liveRows
                        val special = mapOf(
                            HomeSection.HERO to (listOfNotNull(s.heroItem) + s.shelves.flatMap { it.items }).distinctBy { it.id }.map { it.row() },
                            HomeSection.UP_NEXT to watching.upNext.map { it.row(watching.hideSpoilers) },
                            HomeSection.CONTINUE_WATCHING to s.continueWatching.map { p -> AppleTvRow(p.mediaId, p.title,
                                if (p.seasonNumber != null) "S${p.seasonNumber} E${p.episodeNumber} · Resume ${p.positionMs / 60_000}m" else "Resume ${p.positionMs / 60_000}m",
                                p.backdropUrl ?: p.posterUrl, if (p.mediaType == MediaType.SERIES) "tv" else "movie",
                                p.seasonNumber ?: 0, p.episodeNumber ?: 0, p.progressPercent) },
                            HomeSection.ON_NOW to liveRows.map { AppleTvRow(stableChannelId(it), it.name, artwork = it.tvgLogo, mediaType = "channel") },
                            HomeSection.STREAMING_SERVICES to services.sorted().map { id -> AppleTvRow("provider:$id",
                                streamingServiceNames[id] ?: "Provider $id", artwork = logos[id], mediaType = "provider", targetId = id.toString()) }
                        )
                        val heroEnabled = rows.any { it.section == HomeSection.HERO }
                        val hero = s.heroItem?.takeIf { catalogueEnabled && heroEnabled }
                        AppleTvSnapshot(screen, hero?.title.orEmpty(), s.isLoading, s.error,
                            AppleTvHomeLayout.shelves(rows, special, catalogueEnabled) { it.row() },
                            artwork = hero?.backdropUrl, logo = hero?.logoUrl, overview = hero?.overview,
                            ratings = hero?.let { ratingRows(it) }.orEmpty(), hero = hero?.row(), featuredHeroEnabled = heroEnabled)
                    }.collect { emit(it) }
                }
                "library" -> coroutineScope {
                    val latestHistory = MutableStateFlow<List<WatchHistoryEntry>>(emptyList())
                    if (libraryMode == "history") launch {
                        merge(home.state.map { it.recentlyWatched }.distinctUntilChanged().map { Unit },
                            koin.get<WatchProgressRepository>().progressChanges).collectLatest {
                            val ids = koin.get<UserIdProvider>()
                            val owner = ids.currentDataUserId()
                            val entries = koin.get<WatchHistoryRepository>().getAll()
                            val filtered = koin.get<com.torve.presentation.contentpolicy.ContentPolicyFilter>().filterWatchHistory(
                                koin.get<com.torve.data.contentpolicy.ContentPolicyRepository>().state.value,
                                ContentAccessContext.LIBRARY_OR_WATCHLIST, entries)
                            if (owner == ids.currentDataUserId()) latestHistory.value = filtered
                        }
                    }
                    val libraryRatings = MutableStateFlow<Map<String, MediaRatings>>(emptyMap())
                    launch {
                        combine(home.state, watches.state, favorites.state) { h, w, f ->
                            when (libraryMode) {
                                "history" -> h.recentlyWatched
                                "favorites" -> f.items.map { it.toMediaItem() }
                                "continue" -> h.continueWatching.map { MediaItem(id = it.mediaId, type = it.mediaType, title = it.title) }
                                else -> w.items.map { AppleTvLibraryLayout.watchlist(it, h) }
                            }
                        }.distinctUntilChangedBy { items -> items.map { it.type to it.id } }.collectLatest { items ->
                            libraryRatings.value = emptyMap()
                            val owner = koin.get<UserIdProvider>().currentDataUserId()
                            val enricher = koin.get<com.torve.data.mdblist.RatingsEnricher>()
                            fun publish(rated: List<MediaItem>) {
                                if (koin.get<UserIdProvider>().currentDataUserId() != owner) return
                                libraryRatings.value += rated.flatMap { item ->
                                    item.ratings?.let { ratings -> item.ratingEnrichmentLookupKeys().map { it to ratings } }.orEmpty()
                                }.toMap()
                            }
                            val cached = enricher.hydrateListFromCache(items)
                            publish(cached)
                            val key = koin.get<com.torve.domain.integrations.IntegrationSecretStore>()
                                .get(com.torve.domain.integrations.IntegrationSecretKey.MDBLIST_API_KEY).orEmpty()
                            // The first visible rows update first. Reuse cache and the existing
                            // batched provider boundary, with no per-card network fan-out.
                            cached.filter { it.ratings?.imdbScore == null || it.ratings?.rottenTomatoesScore == null }
                                .chunked(24).forEach { batch ->
                                    try { withTimeoutOrNull(15_000) { enricher.enrichList(batch, key, requirePrimaryProviders = true) }?.let(::publish) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { /* Keep cached and baseline scores. */ }
                                }
                        }
                    }
                    combine(home.state, watches.state, favorites.state, libraryRatings) { h, w, f, ratings ->
                        Triple(h.copy(continueWatchingRatings = h.continueWatchingRatings + ratings), w, f)
                    }.combine(latestHistory) { values, events -> values to events }.collect { (values, events) ->
                    val (h, w, f) = values
                    val rows = when (libraryMode) {
                        "history" -> AppleTvLibraryLayout.history(events)
                        "favorites" -> f.items.map { AppleTvLibraryLayout.rated(it.toMediaItem(), h).row().copy(favorite = true) }
                        "continue" -> h.continueWatching.map { p -> AppleTvLibraryLayout.progress(p).copy(
                            ratings = ratingRows(AppleTvLibraryLayout.rated(MediaItem(id = p.mediaId, type = p.mediaType, title = p.title), h))) }
                        else -> w.items.map { p -> AppleTvLibraryLayout.watchlist(p, h).row().copy(id = p.mediaId, targetId = p.mediaId) }
                    }
                    emit(AppleTvSnapshot(screen, "Library", h.isLoading || w.isLoading, w.error, rows = rows, message = w.snackbarMessage))
                    }
                }
                "movies", "shows" -> settings.state.map { it.tmdbCatalogueEnabled }.distinctUntilChanged().collectLatest { enabled ->
                    val title = if (destination == "movies") "Movies" else "TV shows"
                    if (enabled) (if (destination == "movies") movies else series).state.collect { s ->
                        emit(AppleTvSnapshot(screen, title, s.isLoading, s.error, rows = s.items.map { it.row() }))
                    } else home.state.collect { s ->
                        val type = if (destination == "movies") MediaType.MOVIE else MediaType.SERIES
                        val shelves = AppleTvHomeLayout.catalogues(s.addonShelves, type).map { shelf ->
                            val key = "catalogue:${shelf.id}"
                            features.retainHomeShelf(key, shelf.title, shelf.items)
                            AppleTvShelf(key, shelf.title, shelf.items.map { it.row() }, section = "ADDON_SHELVES")
                        }
                        emit(AppleTvSnapshot(screen, title, s.isLoading, s.error?.takeIf { shelves.isEmpty() }, shelves = shelves))
                    }
                }
                "search" -> search.state.collect { s -> emit(AppleTvSnapshot(screen, "Search", s.isSearching, s.error,
                    rows = s.displayItems.map { it.row() } + s.peopleResults.map { p -> AppleTvRow("person:${p.id}", p.name, p.knownForDepartment ?: "", p.profileUrl, "person", targetId = p.id.toString()) },
                    aiSearchEnabled = s.aiSearchEnabled)) }
                "yourshows" -> shows.state.collect { s ->
                    showRows = if (calendarMode) s.calendar else s.upNext
                    emit(AppleTvSnapshot(screen, "Your shows", s.loading, s.error, rows = showRows.map { it.row(s.hideSpoilers) }))
                }
                "channels" -> {
                    if (channelMode == "guide") channels.selectSubTab(ChannelsSubTab.GUIDE)
                    launch {
                        channels.state.map { it.selectedPlaylistId to Triple(it.selectedGroup, it.categoryChannels, it.selectedCountries) }
                            .distinctUntilChanged().collect {
                                if (channelMode == "guide") channels.requestGuideBuild()
                            }
                    }
                    channels.state.collect { s -> renderChannels(s) }
                }
                "detail" -> combine(details.state, watches.state, settings.state, favorites.state) { d, w, s, f ->
                    DetailSnapshot(d, w, s, f)
                }.collect { (d, w, s, f) ->
                    val item = d.mediaItem
                    sourceRows = d.streams
                    if (playbackUrl == null) {
                        val ready = d.usenetPlaybackIntent?.url ?: d.resolvedStream?.let { it.transcodeUrls?.hls ?: it.url }
                        if (!ready.isNullOrBlank()) {
                            playbackUrl = ready; playbackToken = playback.begin(d); playbackFallback = d.resolvedStream?.url?.takeIf { it != ready }
                            if (d.usenetPlaybackIntent != null) details.consumeUsenetPlaybackIntent() else details.clearResolvedStream()
                        }
                    }
                    val ordered = features.sortedSources(sourceRows, sourceSort, sourceQuality, cachedOnly)
                    emit(AppleTvSnapshot(screen, item?.title ?: "Details", d.isLoading || d.isLoadingStreams || d.isResolving,
                        d.error ?: d.streamsError ?: d.resolveError, artwork = item?.backdropUrl, logo = item?.logoUrl, overview = item?.overview,
                        sources = ordered.map { source -> AppleTvRow(sourceRows.indexOf(source).toString(), source.title,
                            listOfNotNull(source.addonName, source.quality, source.size, "Cached".takeIf { source.isCached }).joinToString(" · "), mediaType = "source") },
                        seasonNumbers = item?.seasons.orEmpty().filter { it.seasonNumber > 0 }.map { it.seasonNumber },
                        rows = AppleTvDetailLayout.episodes(d), playerUrl = playbackUrl, playbackToken = playbackToken, playerFallback = playbackFallback,
                        selectedSeason = d.selectedSeason, primaryPlayLabel = d.primaryPlayLabel, inWatchlist = item?.let { watches.isInWatchlist(it.id) } == true,
                        watched = d.isMarkedWatched, canRequest = s.seerrConnected, favorite = item?.let { media -> f.items.any { it.matchesMediaItemFavorite(media) } } == true,
                        libraryStatus = d.mediaLifecycleStatus?.state?.name,
                        cast = item?.cast.orEmpty().map { AppleTvRow("person:${it.id}", it.name, it.character.orEmpty(), it.profileUrl, "person", targetId = it.id.toString()) },
                        ratings = item?.let { features.ratings(it, true) }.orEmpty(), trailer = item?.trailerKey, message = w.snackbarMessage,
                        sourcePickerVisible = d.showStreamPicker || d.isLoadingStreams || d.isResolving || d.preparing != null,
                        related = AppleTvDetailLayout.related(d) { it.row() }, relatedBusy = d.isLoadingSimilar, relatedError = d.similarError,
                        collectionId = item?.collectionId, collectionName = item?.collectionName,
                        detailId = item?.id, detailType = if (item?.type == MediaType.SERIES) "tv" else "movie", hideUnwatchedSpoilers = d.hideUnwatchedSpoilers))
                }
                "connections" -> combine(settings.state, health.entries) { s, h -> s to h }.collect { (s, h) ->
                    emit(AppleTvSnapshot(screen, "Connections", error = s.traktError ?: s.simklError ?: s.debridError,
                        rows = listOf(AppleTvRow("account", "Torve account", koin.get<AuthClient>().authUserFlow.value?.email ?: "Sign in to sync your devices", mediaType = "setting"),
                            AppleTvRow("debrid", "Stream providers", if (s.debridConnected) "Connected" else "Connect provider", mediaType = "setting"),
                            AppleTvRow("trakt", "Trakt", s.traktDeviceCode?.let { "${it.verificationUrl} · ${it.userCode}" } ?: if (s.traktConnected) "Connected" else "Authorize Trakt", mediaType = "setting"),
                            AppleTvRow("simkl", "SIMKL", s.simklDeviceCode?.let { "${it.verificationUrl} · ${it.userCode}" } ?: if (s.simklConnected) "Connected" else "Authorize SIMKL", mediaType = "setting"),
                            AppleTvRow("iptv", "Channels & playlists", "M3U, Xtream and guide settings", mediaType = "setting")) +
                            h.map { AppleTvRow("health:${it.providerKey}", it.label, it.message ?: it.status.name, mediaType = "health", targetId = it.providerKey, status = it.status.name) }))
                }
            }
        }
    }
    private fun renderChannels(s: ChannelsUiState) {
        val enriched = (if (s.searchQuery.isNotBlank()) s.searchResults.map { EnrichedChannel(it) } else when (channelMode) {
            "favorites" -> s.favorites.map { c -> s.channels.firstOrNull { stableChannelId(it.channel) == stableChannelId(c) } ?: EnrichedChannel(c) }
            "recent" -> s.recentlyViewedChannels.map { c -> s.channels.firstOrNull { stableChannelId(it.channel) == stableChannelId(c) } ?: EnrichedChannel(c) }
            "guide" -> LiveTvGuideSourceResolver.resolve(s)
            else -> channels.getDisplayChannels()
        }).let(channels::filterVisibleLiveChannels).filter { AppleChannelBrowsePolicy.isDisplayable(it.channel) && stableChannelId(it.channel) !in s.hiddenChannels &&
            (s.searchQuery.isBlank() || it.channel.name.contains(s.searchQuery, ignoreCase = true)) }
        channelRows = enriched.map { it.channel }
        val start = Clock.System.now().toEpochMilliseconds() + guideOffsetHours * 3_600_000L
        emit(AppleTvSnapshot(screen, "Channels", s.isLoading || s.isLoadingChannels || (channelMode == "guide" && s.isLoadingGuide),
            s.error ?: s.guideError.takeIf { channelMode == "guide" }, rows = enriched.map { e ->
                val programmes = channels.guideProgrammeSlotsForChannel(e.channel)
                AppleTvRow(stableChannelId(e.channel), e.channel.name, e.currentProgramme?.title.orEmpty(), e.channel.tvgLogo, "channel",
                    favorite = s.favorites.any { stableChannelId(it) == stableChannelId(e.channel) }, programmes = programmes.filter { it.endTime > start && it.startTime < start + 3 * 3_600_000L }.take(16).map { slot ->
                        val p = slot.programme
                        AppleTvProgramme("${stableChannelId(e.channel)}:${p.startTime}", p.title, p.startTime, p.endTime, p.description.orEmpty(), channels.resolveCatchupUrl(e.channel, p) != null,
                            displayStart = slot.startTime, displayEnd = slot.endTime)
                    })
            }, groups = s.categories.map { it.name }, selectedGroup = s.selectedGroup,
            playlists = s.playlists.map { AppleTvRow(it.id, it.name, mediaType = "playlist") }, selectedPlaylist = s.selectedPlaylistId,
            channelMode = channelMode, hiddenGroups = s.hiddenCategories.toList(), allGroups = s.allCategories.map { it.name },
            channelCountries = s.allCategories.associate { it.name to it.countryCode.orEmpty() },
            selectedCountry = s.selectedCountries.singleOrNull()))
    }
    fun open(mediaId: String, type: String, season: Int, episode: Int, autoPlay: Boolean = false) {
        val row = (visibleSnapshot.rows + visibleSnapshot.shelves.flatMap { it.items }).firstOrNull {
            it.targetId == mediaId && MediaType.fromString(it.mediaType) == MediaType.fromString(type)
        }
        val cached = catalogueSeeds[MediaType.fromString(type) to mediaId]?.takeIf { seedOwner == koin.get<UserIdProvider>().currentDataUserId() }
        val seed = cached ?: row?.let(AppleTvCataloguePolicy::seed) ?: MediaItem(mediaId, type = MediaType.fromString(type), title = mediaId)
        openItem(seed, season, episode, autoPlay)
    }
    fun openItem(item: MediaItem, season: Int, episode: Int, autoPlay: Boolean = false) {
        detailOpenJob?.cancel()
        if (AppleTvCataloguePolicy.needsExtensionDetail(item, settings.state.value.tmdbCatalogueEnabled)) {
            val owner = koin.get<UserIdProvider>().currentDataUserId()
            observer?.cancel(); screen = "detail"
            emit(AppleTvSnapshot(screen = screen, title = item.title, artwork = item.backdropUrl, busy = true))
            detailOpenJob = scope.launch {
                val seed = AppleCatalogueDetail.encode(item)
                val resolved = try { features.catalogueDetail(seed, settings.state.value.appLanguage.code) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { seed }
                if (screen != "detail" || owner != koin.get<UserIdProvider>().currentDataUserId()) return@launch
                details.loadExtensionDetail(resolved)
                activate("detail")
                if (season > 0 && episode > 0) details.selectEpisodeForPlayback(season, episode, true)
                else if (season > 0 && item.type == MediaType.SERIES) details.selectExtensionSeason(season)
                if (autoPlay) play()
            }
            return
        }
        val id = AppleDetailNavigation.catalogId(item.id) ?: return
        details.loadDetail(if (item.type == MediaType.SERIES) "tv" else "movie", id); activate("detail")
        detailOpenJob = scope.launch {
            details.state.filter { !it.isLoading && it.mediaItem?.tmdbId == id }.first()
            if (screen != "detail" || details.state.value.mediaItem?.tmdbId != id) return@launch
            if (season > 0 && episode > 0) details.selectEpisodeForPlayback(season, episode, true)
            else if (season > 0 && details.state.value.mediaItem?.type == MediaType.SERIES) details.selectSeason(season)
            if (autoPlay) play()
        }
    }
    fun query(value: String) = search.updateQuery(value)
    fun setAiSearchEnabled(enabled: Boolean) = search.setAiSearchEnabled(enabled)
    fun submitSearch() = search.submitSearch()
    /** The native TV source controls observe and resolve through this exact presenter-owned VM. */
    fun detailViewModel(): DetailViewModel = details
    fun channelSearch(value: String) = channels.updateSearchQuery(value)
    fun country(value: String) {
        channels.selectGroup(null)
        channels.setCountryFilter(value.takeIf { it.isNotBlank() }?.let(::setOf).orEmpty())
        if (channelMode == "guide") channels.selectSubTab(ChannelsSubTab.GUIDE)
    }
    fun group(value: String) {
        if (value.isBlank()) channels.selectGroup(null) else channels.loadCategoryChannels(value)
    }
    fun channelMode(value: String) { channelMode = value; if (value == "guide") channels.selectSubTab(ChannelsSubTab.GUIDE); renderChannels(channels.state.value) }
    fun guideOffset(hours: Int) { guideOffsetHours = hours.coerceIn(-168, 72); renderChannels(channels.state.value) }
    fun playlist(id: String) = channels.selectPlaylist(id)
    fun hideGroup(name: String) = channels.toggleHiddenCategory(name)
    fun hideChannel(id: String) = channels.toggleHiddenChannel(id)
    fun favoriteChannel(id: String) { channelRows.firstOrNull { stableChannelId(it) == id }?.let(channels::toggleFavorite) }
    fun qualityChannel(value: String) { ChannelsFilterType.entries.firstOrNull { it.name == value }?.let(channels::setFilter) }
    fun sortChannels(value: String) { ChannelsSortType.entries.firstOrNull { it.name == value }?.let(channels::setSort) }
    fun nextChannel(direction: Int) { channels.selectAdjacentChannel(direction)?.let(::launchChannel) }
    fun loadMore() { when (screen) { "movies" -> movies.loadMore(); "shows" -> series.loadMore(); "search" -> search.loadMore() } }
    fun homeShelfSection(key: String): String? {
        if (key.startsWith("catalogue:") && visibleSnapshot.shelves.any { it.id == key }) return "shelf:$key"
        val row = AppleHomeLayoutPolicy.rows(home.state.value, home.sectionConfigs.value,
            home.customSections.value, home.homeLayoutOrder.value).firstOrNull { it.key == key } ?: return null
        row.section?.takeIf { it.shelfId != null }?.let { return it.name }
        row.seeAllSection?.takeIf { !it.startsWith("shelf:") }?.let { return it }
        features.retainHomeShelf(row.key, row.title, row.items)
        return "shelf:${row.key}"
    }
    fun searchFilter(type: String, rating: Float, from: Int, to: Int, sort: String) = features.searchFilter(search, type, rating, from, to, sort)
    fun sortSources(mode: String, quality: String, cached: Boolean) { sourceSort = mode; sourceQuality = quality; cachedOnly = cached; if (screen == "detail") activate("detail") }
    fun play() { val d = details.state.value; val target = d.primaryPlaybackTarget; details.fetchStreams(target.season, target.episode, true) }
    fun season(number: Int) {
        val item = details.state.value.mediaItem ?: return
        if (AppleTvCataloguePolicy.needsExtensionDetail(item, settings.state.value.tmdbCatalogueEnabled)) details.selectExtensionSeason(number)
        else details.selectSeason(number)
    }
    fun episode(season: Int, episode: Int) { details.selectEpisodeForPlayback(season, episode, true); details.fetchStreams(season, episode, true) }
    fun toggleWatchlist() { details.state.value.mediaItem?.let(watches::toggleWatchlist) }
    fun toggleFavorite() { details.state.value.mediaItem?.let(favorites::toggleFavorite) }
    fun watched() { if (details.state.value.isMarkedWatched) details.markUnwatched() else details.markWatched() }
    fun watchedSeason(number: Int) = details.markSeasonWatched(number)
    fun watchedEpisode(season: Int, episode: Int) = details.toggleEpisodeWatched(season, episode)
    fun rating(value: Int) = details.setUserRating(value.takeIf { it in 1..10 })
    fun requestCopy() = details.requestPermanentCopy()
    fun showsCalendar(enabled: Boolean) { calendarMode = enabled; if (screen == "yourshows") activate(screen) }
    fun markShow(id: String) { showRows.firstOrNull { it.row(shows.state.value.hideSpoilers).id == id }?.let(shows::markWatched) }
    fun libraryTab(value: String) { libraryMode = value; if (value == "favorites") favorites.refresh(); activate("library") }
    fun source(index: Int) { sourceRows.getOrNull(index)?.let { source ->
        if (source.accelerationProvenanceKind == CandidateProvenanceKind.USENET_NZBDAV) details.selectUsenetSource(source)
        else details.resolveStream(source, settings.getDebridProvider(), settings.getDebridApiKey())
    } }
    private fun launchChannel(c: Channel) { channels.selectChannel(c); emit(AppleTvSnapshot("channels", c.name, playerUrl = c.url, channelMode = channelMode)) }
    fun channel(id: String) {
        val selected = channelRows.firstOrNull { stableChannelId(it) == id } ?: return
        if (screen != "channels") activate("channels")
        launchChannel(selected)
    }
    fun streamingProvider(id: Int) { movies.setProvider(id); activate("movies") }
    fun replay(channelId: String, start: Long) {
        val channel = channelRows.firstOrNull { stableChannelId(it) == channelId } ?: return
        val s = channels.state.value
        val programme = channels.guideProgrammesForChannel(channel).firstOrNull { it.startTime == start } ?: return
        val url = channels.resolveCatchupUrl(channel, programme) ?: return
        emit(AppleTvSnapshot("channels", programme.title, playerUrl = url, channelMode = channelMode))
    }
    fun playerClosed() { details.resumeDetail(); playbackUrl = null; playbackToken = null; activate(screen) }
    fun refresh() { when (screen) { "home", "library" -> home.refresh(); "yourshows" -> shows.refresh(true); "channels" -> channels.refreshPlaylist(); "movies", "shows" -> if (!settings.state.value.tmdbCatalogueEnabled) home.refresh() else if (screen == "movies") movies.refresh() else series.refresh(); "connections" -> { settings.refreshSettings(); health.runAll() } } }
    fun refreshMetadataSelection() {
        home.refresh(); movies.refresh(); series.refresh()
    }
    fun refreshLocalizedContent() {
        // Settings overlays can change language while the catalogue remains retained.
        home.refresh()
        movies.refreshLocalizedContent()
        series.refreshLocalizedContent()
        if (search.state.value.hasActiveSearch && !search.state.value.awaitingAiSubmission) search.retry()
        details.state.value.mediaItem?.let { item ->
            item.tmdbId?.let { id -> details.loadDetail(if (item.type == MediaType.SERIES) "tv" else "movie", id) }
        }
    }
    fun checkConnection(key: String) = health.runCheck(key)
    fun authorizeTrakt() = settings.startTraktDeviceAuth()
    fun authorizeSimkl() = settings.startSimklDeviceAuth()
    fun connectProvider(provider: String, key: String) { DebridServiceType.entries.firstOrNull { it.name == provider }?.let { settings.connectDebridProviderWithApiKey(it, key) } }
    fun addPlaylist(name: String, url: String, epg: String, server: String, username: String, password: String) {
        channels.setNewPlaylistType(if (server.isBlank()) "m3u" else "xtream")
        channels.setNewPlaylistName(name); channels.setNewPlaylistUrl(url); channels.setNewPlaylistEpgUrl(epg)
        channels.setNewXtreamServer(server); channels.setNewXtreamUsername(username); channels.setNewXtreamPassword(password); channels.addPlaylist()
    }
    @Throws(Exception::class)
    suspend fun login(email: String, password: String): String? {
        val result = koin.get<AuthClient>().login(email, password)
        if (result.success) { features.bootstrap(); settings.refreshSettings(); home.refresh(); activate("connections") }
        return result.error
    }
    fun close() { observer?.cancel(); scope.cancel() }
    private data class DetailSnapshot(val detail: DetailUiState, val watchlist: com.torve.presentation.watchlist.WatchlistUiState,
        val settings: com.torve.presentation.settings.SettingsUiState, val favorites: MediaFavoritesState)
}
