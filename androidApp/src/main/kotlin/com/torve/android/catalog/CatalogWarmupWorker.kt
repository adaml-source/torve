package com.torve.android.catalog

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.torve.android.background.BackgroundWork
import com.torve.data.auth.AuthClient
import com.torve.data.catalog.CatalogTopCacheWorker
import com.torve.data.usenet.NewznabClient
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.Genre
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.channelIdentityCandidates
import com.torve.domain.model.programmesForEpgChannel
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.VodCategoryTypeCount
import com.torve.domain.usenet.UsenetIndexerCategoryMap
import com.torve.domain.usenet.UsenetIndexerUrlResolver
import com.torve.presentation.channels.CategoryNameCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.TimeUnit

class CatalogWarmupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val authClient: AuthClient = getKoin().get()
            val user = authClient.getAuthenticatedUser() ?: return Result.success()
            val lightweight = inputData.getBoolean(KEY_LIGHTWEIGHT, false)
            val localSettingsRepo: DeviceLocalSettingsRepository = getKoin().get()
            if (lightweight && isLightweightWarmupFresh(localSettingsRepo, user.id)) {
                println("CATALOG_WARMUP: lightweight cache fresh, skipping foreground warmup")
                return Result.success()
            }

            publishProgress("Preparing cached content", 0.05f)
            if (!lightweight) {
                publishProgress("Refreshing home cache", 0.12f)
                runCatching { getKoin().get<CatalogTopCacheWorker>().runNow() }
            }
            publishProgress("Preparing movies and shows", 0.18f)
            runCatching { warmCatalogRailsBootstrap(user.id) }
            publishProgress("Preparing live TV", 0.35f)
            runCatching {
                warmLiveBootstrap(
                    userId = user.id,
                    maxShelfCategories = if (lightweight) IMMEDIATE_LIVE_SHELF_CATEGORIES else FULL_LIVE_SHELF_CATEGORIES,
                    includeEpg = true,
                )
            }
            publishProgress("Preparing VOD", 0.62f)
            runCatching {
                warmVodBootstrap(
                    userId = user.id,
                    maxProviderCategories = if (lightweight) IMMEDIATE_VOD_PROVIDER_CATEGORIES else FULL_VOD_PROVIDER_CATEGORIES,
                    includePinnedShelves = !lightweight,
                )
            }
            if (!lightweight) {
                publishProgress("Preparing sports", 0.82f)
                runCatching { warmSportsBootstrap(user.id) }
            }
            publishProgress("Cached content ready", 1f)
            if (lightweight) {
                localSettingsRepo.setString(
                    lightweightWarmupLastSuccessKey(user.id),
                    System.currentTimeMillis().toString(),
                )
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun warmCatalogRailsBootstrap(userId: String) = withContext(Dispatchers.IO) {
        val metadataRepo: MetadataRepository = getKoin().get()
        val localSettingsRepo: DeviceLocalSettingsRepository = getKoin().get()
        warmCatalogRailsForMedia(
            userId = userId,
            mediaType = "movie",
            genreIds = listOf(28, 35, 878, 27, 18, 16),
            metadataRepo = metadataRepo,
            localSettingsRepo = localSettingsRepo,
        )
        warmCatalogRailsForMedia(
            userId = userId,
            mediaType = "tv",
            genreIds = listOf(10759, 35, 18, 10765, 80, 16),
            metadataRepo = metadataRepo,
            localSettingsRepo = localSettingsRepo,
        )
    }

    private suspend fun warmCatalogRailsForMedia(
        userId: String,
        mediaType: String,
        genreIds: List<Int>,
        metadataRepo: MetadataRepository,
        localSettingsRepo: DeviceLocalSettingsRepository,
    ) {
        val rails = buildList {
            runCatching { metadataRepo.getTrending(mediaType).take(CATALOG_RAIL_LIMIT) }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { add(CatalogRailsBootstrapRail("trending_$mediaType", it.dedupeByStableKey())) }
            runCatching { metadataRepo.getPopular(mediaType).take(CATALOG_RAIL_LIMIT) }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { add(CatalogRailsBootstrapRail("popular_$mediaType", it.dedupeByStableKey())) }
            runCatching { metadataRepo.getTopRated(mediaType).take(CATALOG_RAIL_LIMIT) }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { add(CatalogRailsBootstrapRail("top_rated_$mediaType", it.dedupeByStableKey())) }
            genreIds.forEach { genreId ->
                runCatching {
                    metadataRepo.discover(
                        type = mediaType,
                        withGenres = genreId.toString(),
                    ).items.take(CATALOG_RAIL_LIMIT)
                }
                    .getOrDefault(emptyList())
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(CatalogRailsBootstrapRail("genre_${mediaType}_$genreId", it.dedupeByStableKey())) }
                delay(WORK_YIELD_DELAY_MS)
            }
        }
        if (rails.isEmpty()) return
        localSettingsRepo.setString(
            catalogRailsBootstrapKey(userId, mediaType),
            CatalogRailsBootstrapJson.encodeToString(
                CatalogRailsBootstrapPayload(
                    savedAtMs = System.currentTimeMillis(),
                    mediaType = mediaType,
                    rails = rails,
                ),
            ),
        )
        println("CATALOG_WARMUP: catalog rails saved mediaType=$mediaType rails=${rails.size}")
    }

    private suspend fun publishProgress(label: String, progress: Float) {
        setProgress(
            workDataOf(
                BackgroundWork.KEY_LABEL to label,
                BackgroundWork.KEY_PROGRESS to progress.coerceIn(0f, 1f),
                BackgroundWork.KEY_BLOCK_NAVIGATION to true,
            ),
        )
    }

    private suspend fun isLightweightWarmupFresh(
        localSettingsRepo: DeviceLocalSettingsRepository,
        userId: String,
    ): Boolean {
        val lastSuccess = localSettingsRepo.getString(lightweightWarmupLastSuccessKey(userId))
            ?.toLongOrNull()
            ?: return false
        val ageMs = System.currentTimeMillis() - lastSuccess
        if (ageMs > LIGHTWEIGHT_WARMUP_FRESH_MS) return false
        val hasCatalog = localSettingsRepo.getString(catalogRailsBootstrapKey(userId, "movie")) != null &&
            localSettingsRepo.getString(catalogRailsBootstrapKey(userId, "tv")) != null
        val hasLiveCategories = localSettingsRepo.getString("channels_bootstrap_categories_$userId") != null
        val selectedPlaylistId = localSettingsRepo.getString(channelsBootstrapSelectedPlaylistKey(userId))
        val firstLiveCategory = localSettingsRepo.getString("channels_bootstrap_categories_$userId")
            ?.lineSequence()
            ?.mapNotNull { line -> line.substringBefore('\t').takeIf { it.isNotBlank() } }
            ?.firstOrNull()
        val hasFirstLiveShelf = selectedPlaylistId != null &&
            firstLiveCategory != null &&
            localSettingsRepo.getString(liveDisplayShelfBootstrapKey(userId, selectedPlaylistId, firstLiveCategory)) != null
        return hasCatalog && hasLiveCategories && hasFirstLiveShelf
    }

    private suspend fun warmLiveBootstrap(
        userId: String,
        maxShelfCategories: Int,
        includeEpg: Boolean,
    ) = withContext(Dispatchers.IO) {
        val channelRepo: ChannelRepository = getKoin().get()
        val localSettingsRepo: DeviceLocalSettingsRepository = getKoin().get()
        val playlist = channelRepo.getPlaylistSummaries()
            .firstOrNull { it.channelCount > 0 }
            ?: return@withContext
        val categories = channelRepo.getLiveCategoryCounts(playlist.id)
            .filterNot { (name, _) -> name.startsWith("VOD:", ignoreCase = true) }
        if (categories.isEmpty()) return@withContext
        val cleanedCategories = CategoryNameCleaner.processCategoryCountsOnly(categories)

        localSettingsRepo.setString(channelsBootstrapSelectedPlaylistKey(userId), playlist.id)
        localSettingsRepo.setString(
            "channels_bootstrap_categories_$userId",
            cleanedCategories.joinToString("\n") { category ->
                "${category.name}\t${category.channelCount}\t${category.countryCode ?: "null"}"
            },
        )
        if (maxShelfCategories > 0) {
            warmLiveShelves(
                userId = userId,
                playlistId = playlist.id,
                rawCounts = categories,
                cleanedCategories = cleanedCategories.take(maxShelfCategories),
                channelRepo = channelRepo,
                localSettingsRepo = localSettingsRepo,
                includeEpg = includeEpg,
            )
        }
        println("CATALOG_WARMUP: live bootstrap saved playlist=${playlist.id} categories=${cleanedCategories.size}")
    }

    private suspend fun warmLiveShelves(
        userId: String,
        playlistId: String,
        rawCounts: List<Pair<String, Long>>,
        cleanedCategories: List<com.torve.domain.model.ChannelCategory>,
        channelRepo: ChannelRepository,
        localSettingsRepo: DeviceLocalSettingsRepository,
        includeEpg: Boolean,
    ) {
        val rawByCleanName = rawCounts
            .groupBy { (rawName, _) -> CategoryNameCleaner.clean(rawName).name }
            .mapValues { (_, rows) -> rows.map { it.first } }
        val favoriteIds = runCatching {
            channelRepo.getFavorites()
                .flatMap(::channelIdentityCandidates)
                .toSet()
        }.getOrDefault(emptySet())
        val programmeWindows = if (includeEpg) {
            val epgData = runCatching { channelRepo.getEpg(playlistId) }.getOrDefault(com.torve.domain.model.EpgData())
            buildLiveProgrammeWindows(epgData.programmesByChannelKey)
        } else {
            emptyMap()
        }

        cleanedCategories.forEach { category ->
            val rawNames = rawByCleanName[category.name].orEmpty()
            if (rawNames.isEmpty()) return@forEach
            val channels = rawNames
                .flatMap { rawName -> channelRepo.getChannelsForCategory(playlistId, rawName) }
                .asSequence()
                .filter { it.contentType == ChannelContentType.LIVE || it.contentType == ChannelContentType.UNKNOWN }
                .filterNot { it.groupTitle.orEmpty().startsWith("VOD:", ignoreCase = true) }
                .distinctBy { it.url }
                .map { channel ->
                    if (channelIdentityCandidates(channel).any(favoriteIds::contains)) {
                        channel.copy(isFavorite = true)
                    } else {
                        channel
                    }
                }
                .map { channel ->
                    val programmes = programmesForEpgChannel(
                        programmesByChannelKey = programmeWindows,
                        playlistId = playlistId,
                        channel = channel,
                    )
                    LiveBootstrapShelfEntry(
                        channel = channel,
                        currentProgramme = programmes.currentProgramme(),
                        nextProgramme = programmes.nextProgramme(),
                        programmes = programmes,
                    )
                }
                .toList()
            if (channels.isEmpty()) return@forEach
            localSettingsRepo.setString(
                liveDisplayShelfBootstrapKey(userId, playlistId, category.name),
                LiveBootstrapJson.encodeToString(LiveBootstrapShelf(entries = channels)),
            )
            delay(WORK_YIELD_DELAY_MS)
        }
    }

    private fun buildLiveProgrammeWindows(
        programmesByChannelKey: Map<String, List<EpgProgramme>>,
    ): Map<String, List<EpgProgramme>> {
        if (programmesByChannelKey.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val fromMs = now - TimeUnit.HOURS.toMillis(1)
        val toMs = now + TimeUnit.HOURS.toMillis(12)
        return programmesByChannelKey.mapValues { (_, programmes) ->
            programmes
                .filter { it.endTime > fromMs && it.startTime < toMs }
                .sortedBy { it.startTime }
        }.filterValues { it.isNotEmpty() }
    }

    private suspend fun warmVodBootstrap(
        userId: String,
        maxProviderCategories: Int,
        includePinnedShelves: Boolean,
    ) = withContext(Dispatchers.IO) {
        val channelRepo: ChannelRepository = getKoin().get()
        val localSettingsRepo: DeviceLocalSettingsRepository = getKoin().get()
        val playlist = channelRepo.getPlaylistSummaries()
            .firstOrNull { it.type.equals("xtream", ignoreCase = true) || it.channelCount > 0 }
            ?: return@withContext

        val typeCounts = channelRepo.getVodCategoryTypeCounts(playlist.id)
        if (typeCounts.isEmpty()) return@withContext
        val categories = buildVodBootstrapCategories(typeCounts)
        if (categories.isEmpty()) return@withContext

        localSettingsRepo.setString(channelsBootstrapSelectedPlaylistKey(userId), playlist.id)
        localSettingsRepo.setString(
            vodCategoryBootstrapKey(userId, playlist.id),
            VodBootstrapJson.encodeToString(categories),
        )
        val shelfCategories = buildList {
            if (includePinnedShelves) {
                addAll(categories.filter { it.pinned })
            }
            addAll(categories.filterNot { it.pinned }.take(maxProviderCategories))
        }
        if (shelfCategories.isNotEmpty()) {
            warmVodShelves(
                userId = userId,
                playlistId = playlist.id,
                categories = shelfCategories,
                channelRepo = channelRepo,
                localSettingsRepo = localSettingsRepo,
            )
        }
        println("CATALOG_WARMUP: VOD bootstrap saved playlist=${playlist.id} categories=${categories.size}")
    }

    private suspend fun warmVodShelves(
        userId: String,
        playlistId: String,
        categories: List<VodBootstrapCategory>,
        channelRepo: ChannelRepository,
        localSettingsRepo: DeviceLocalSettingsRepository,
    ) {
        categories
            .asSequence()
            .filter { it.type != VodBootstrapCategoryType.FAVORITES && it.count > 0 }
            .forEach { category ->
                warmVodShelf(userId, playlistId, category, WarmVodMediaSection.MOVIES, channelRepo, localSettingsRepo)
                warmVodShelf(userId, playlistId, category, WarmVodMediaSection.SHOWS, channelRepo, localSettingsRepo)
                delay(WORK_YIELD_DELAY_MS)
            }
    }

    private suspend fun warmVodShelf(
        userId: String,
        playlistId: String,
        category: VodBootstrapCategory,
        section: WarmVodMediaSection,
        channelRepo: ChannelRepository,
        localSettingsRepo: DeviceLocalSettingsRepository,
    ) {
        val limit = if (category.pinned) MAX_ALL_ITEMS else MAX_CATEGORY_ITEMS
        val channels = when (category.type) {
            VodBootstrapCategoryType.ALL_MOVIES,
            VodBootstrapCategoryType.ALL_SHOWS -> channelRepo.getChannelsForContentType(
                playlistId = playlistId,
                type = section.contentType,
                limit = limit,
            )
            else -> {
                val loaded = mutableListOf<Channel>()
                for (rawName in category.rawNames) {
                    val remaining = limit - loaded.size
                    if (remaining <= 0) break
                    loaded += channelRepo.getChannelsForCategoryContentType(
                        playlistId = playlistId,
                        categoryName = rawName,
                        type = section.contentType,
                        limit = remaining,
                    )
                }
                loaded
            }
        }
        if (channels.isEmpty()) return
        val shelf = VodBootstrapShelf(
            entries = channels
                .distinctBy { it.url }
                .mapIndexed { index, channel -> channel.toVodBootstrapEntry(index) },
        )
        localSettingsRepo.setString(
            vodDisplayShelfBootstrapKey(userId, playlistId, category.cacheKey(section)),
            VodBootstrapJson.encodeToString(shelf),
        )
    }

    private suspend fun warmSportsBootstrap(userId: String) = withContext(Dispatchers.IO) {
        val localSettingsRepo: DeviceLocalSettingsRepository = getKoin().get()
        val secretStore: IntegrationSecretStore = getKoin().get()
        val prefs: PreferencesRepository = getKoin().get()
        val newznab: NewznabClient = getKoin().get()

        val row = resolveFirstIndexer(secretStore, prefs) ?: return@withContext
        val indexerUrl = UsenetIndexerUrlResolver.resolve(row.type, row.url)
        val indexerKey = row.apiKey
        if (indexerUrl.isBlank() || indexerKey.isBlank()) return@withContext

        val categories = UsenetIndexerCategoryMap.sportsCategoriesFor(row.type)
        val items = runCatching {
            newznab.browseAllPages(indexerUrl, indexerKey, categories, maxItems = 200)
        }.getOrDefault(emptyList())
        if (items.isEmpty()) return@withContext
        val payload = SportsBootstrapPayload(
            savedAtMs = System.currentTimeMillis(),
            items = items,
        )
        localSettingsRepo.setString(
            sportsBootstrapKey(userId, indexerUrl, indexerKey),
            SportsBootstrapJson.encodeToString(payload),
        )
        println("CATALOG_WARMUP: sports bootstrap saved items=${items.size}")
    }

    private suspend fun resolveFirstIndexer(
        secretStore: IntegrationSecretStore,
        prefs: PreferencesRepository,
    ): WarmIndexer? {
        val fromSecrets = secretStore
            .getSubKeys(IntegrationSecretKey.PANDA_INDEXER_API_KEY)
            .filter { it.contains("|") }
            .mapNotNull { subKey ->
                val apiKey = secretStore.get(IntegrationSecretKey.PANDA_INDEXER_API_KEY, subKey)
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val (type, url) = subKey.split("|", limit = 2)
                WarmIndexer(type = type, url = url, apiKey = apiKey)
            }
            .firstOrNull()
        if (fromSecrets != null) return fromSecrets

        val type = prefs.getString("panda_nzb_indexer")
            ?: prefs.getString("nzb_indexer")
            ?: return null
        val apiKey = prefs.getString("panda_nzb_indexer_api_key")
            ?: prefs.getString("nzb_indexer_api_key")
            ?: return null
        val url = prefs.getString("panda_nzb_indexer_url")
            ?: prefs.getString("nzb_indexer_url")
            ?: ""
        return WarmIndexer(type = type, url = url, apiKey = apiKey)
    }

    companion object {
        private const val WORK_NAME = "catalog_warmup_worker"
        private const val IMMEDIATE_WORK_NAME = "catalog_warmup_worker_immediate"
        private const val KEY_LIGHTWEIGHT = "lightweight"
        private const val CATALOG_RAIL_LIMIT = 24
        private const val IMMEDIATE_LIVE_SHELF_CATEGORIES = 10_000
        private const val FULL_LIVE_SHELF_CATEGORIES = 10_000
        private const val IMMEDIATE_VOD_PROVIDER_CATEGORIES = 16
        private const val FULL_VOD_PROVIDER_CATEGORIES = 16
        private const val WORK_YIELD_DELAY_MS = 35L
        private const val MAX_CATEGORY_ITEMS = 160
        private const val MAX_ALL_ITEMS = 180
        private const val LIGHTWEIGHT_WARMUP_FRESH_MS = 6L * 60L * 60L * 1000L

        private fun lightweightWarmupLastSuccessKey(userId: String): String {
            return "catalog_warmup_lightweight_last_success_$userId"
        }

        fun schedule(context: Context) {
            val periodicConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()
            val immediateConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val periodic = PeriodicWorkRequestBuilder<CatalogWarmupWorker>(
                6, TimeUnit.HOURS,
            )
                .setConstraints(periodicConstraints)
                .addTag(BackgroundWork.TAG_HEAVY_PRELOAD)
                .build()
            val immediate = OneTimeWorkRequestBuilder<CatalogWarmupWorker>()
                .setConstraints(immediateConstraints)
                .setInputData(workDataOf(KEY_LIGHTWEIGHT to true))
                .addTag(BackgroundWork.TAG_HEAVY_PRELOAD)
                .build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic,
            )
            manager.enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                immediate,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }
}

private data class WarmIndexer(
    val type: String,
    val url: String,
    val apiKey: String,
)

private enum class WarmVodMediaSection(val mediaType: MediaType) {
    MOVIES(MediaType.MOVIE),
    SHOWS(MediaType.SERIES),
}

private data class WarmVodCounts(
    val rawNames: Set<String> = emptySet(),
    val movieCount: Long = 0,
    val showCount: Long = 0,
)

private fun buildVodBootstrapCategories(
    typeCounts: List<VodCategoryTypeCount>,
): List<VodBootstrapCategory> {
    val grouped = linkedMapOf<String, WarmVodCounts>()
    typeCounts.forEach { row ->
        val rawName = row.groupTitle.ifBlank { "VOD" }
        val label = rawName.cleanVodCategoryLabel()
        val existing = grouped[label] ?: WarmVodCounts()
        grouped[label] = when (row.contentType) {
            ChannelContentType.VOD_SERIES -> existing.copy(
                rawNames = existing.rawNames + rawName,
                showCount = existing.showCount + row.count,
            )
            ChannelContentType.VOD_MOVIE -> existing.copy(
                rawNames = existing.rawNames + rawName,
                movieCount = existing.movieCount + row.count,
            )
            else -> existing
        }
    }

    val movieTotal = grouped.values.sumOf { it.movieCount }
    val showTotal = grouped.values.sumOf { it.showCount }
    return buildList {
        add(
            VodBootstrapCategory(
                id = "favorites",
                label = "Favorites",
                rawNames = emptyList(),
                count = 0,
                type = VodBootstrapCategoryType.FAVORITES,
                pinned = true,
                movieCount = 0,
                showCount = 0,
            ),
        )
        if (movieTotal > 0) {
            add(
                VodBootstrapCategory(
                    id = "all_movies",
                    label = "All movies",
                    rawNames = grouped.values.flatMap { it.rawNames },
                    count = movieTotal,
                    type = VodBootstrapCategoryType.ALL_MOVIES,
                    pinned = true,
                    movieCount = movieTotal,
                    showCount = 0,
                ),
            )
        }
        if (showTotal > 0) {
            add(
                VodBootstrapCategory(
                    id = "all_shows",
                    label = "All shows",
                    rawNames = grouped.values.flatMap { it.rawNames },
                    count = showTotal,
                    type = VodBootstrapCategoryType.ALL_SHOWS,
                    pinned = true,
                    movieCount = 0,
                    showCount = showTotal,
                ),
            )
        }
        addAll(
            grouped.map { (label, counts) ->
                VodBootstrapCategory(
                    id = "category:${label.hashCode()}",
                    label = label,
                    rawNames = counts.rawNames.toList(),
                    count = counts.movieCount + counts.showCount,
                    type = VodBootstrapCategoryType.PROVIDER,
                    movieCount = counts.movieCount,
                    showCount = counts.showCount,
                )
            }.sortedBy { it.label.lowercase() },
        )
    }
}

private fun VodBootstrapCategory.cacheKey(section: WarmVodMediaSection): String = "${section.name}:$id"

private val WarmVodMediaSection.contentType: ChannelContentType
    get() = when (this) {
        WarmVodMediaSection.MOVIES -> ChannelContentType.VOD_MOVIE
        WarmVodMediaSection.SHOWS -> ChannelContentType.VOD_SERIES
    }

private fun Channel.isVodFor(section: WarmVodMediaSection): Boolean {
    return when (section) {
        WarmVodMediaSection.MOVIES -> contentType == ChannelContentType.VOD_MOVIE
        WarmVodMediaSection.SHOWS -> contentType == ChannelContentType.VOD_SERIES
    }
}

private fun Channel.toVodBootstrapEntry(index: Int): VodBootstrapShelfEntry {
    val mediaType = when (contentType) {
        ChannelContentType.VOD_SERIES -> MediaType.SERIES
        else -> MediaType.MOVIE
    }
    val rawTitle = tvgName ?: name
    val parsed = parseVodTitle(rawTitle)
    val sourceId = (kodiProps["vod_stream_id"] ?: kodiProps["vod_series_id"])
        ?.let { "vod_source:$playlistId:${mediaType.name}:$it" }
        ?: "vod_source:${url.hashCode()}"
    val rating = kodiProps["vod_rating"]?.toDoubleOrNull()?.takeIf { it > 0 }
        ?: kodiProps["vod_rating_5based"]?.toDoubleOrNull()?.takeIf { it > 0 }?.times(2.0)
    val genres = kodiProps["vod_genre"]
        ?.split(',', '/', '|')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.mapIndexed { genreIndex, genre -> Genre(id = genre.hashCode() + genreIndex, name = genre) }
        .orEmpty()
    return VodBootstrapShelfEntry(
        sourceId = sourceId,
        sourceOrder = index,
        channel = this,
        item = MediaItem(
            id = "vod:${mediaType.name.lowercase()}:${parsed.searchTitle.hashCode()}:$sourceId",
            type = mediaType,
            title = parsed.displayTitle,
            year = parsed.year,
            overview = kodiProps["vod_plot"],
            posterUrl = tvgLogo,
            backdropUrl = kodiProps["vod_backdrop"],
            rating = rating,
            ratings = rating?.let { MediaRatings(tmdbScore = it.toFloat()) },
            runtime = kodiProps["vod_episode_run_time"]?.toIntOrNull(),
            genres = genres,
            cast = emptyList(),
            director = kodiProps["vod_director"],
            releaseDate = kodiProps["vod_release_date"],
        ),
        searchTitle = parsed.searchTitle,
        language = null,
        category = groupTitle.cleanVodCategoryLabel(),
    )
}

private data class ParsedVodTitle(
    val displayTitle: String,
    val searchTitle: String,
    val year: Int?,
)

private fun parseVodTitle(raw: String): ParsedVodTitle {
    val year = Regex("""(?:\(|\[|\s)(19\d{2}|20\d{2})(?:\)|\]|\s|$)""")
        .find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val cleaned = raw
        .replace(Regex("""(?i)\b(1080p|2160p|720p|4k|uhd|hdr|hevc|x265|x264|bluray|web-dl|webrip|dvdrip)\b"""), " ")
        .replace(Regex("""[\[\(]?(19\d{2}|20\d{2})[\]\)]?"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '_', '.', '|')
        .ifBlank { raw.trim() }
    return ParsedVodTitle(
        displayTitle = cleaned,
        searchTitle = cleaned.lowercase(),
        year = year,
    )
}

private fun String?.cleanVodCategoryLabel(): String {
    return orEmpty()
        .removePrefix("VOD:")
        .trim()
        .replace(Regex("""\s+"""), " ")
        .ifBlank { "VOD" }
}

private fun List<EpgProgramme>.currentProgramme(now: Long = System.currentTimeMillis()): EpgProgramme? {
    return filter { it.startTime <= now && it.endTime > now }
        .maxByOrNull { it.startTime }
}

private fun List<EpgProgramme>.nextProgramme(now: Long = System.currentTimeMillis()): EpgProgramme? {
    return filter { it.startTime > now }
        .minByOrNull { it.startTime }
}
