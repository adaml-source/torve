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
import com.torve.android.epg.EpgWarmupWorker
import com.torve.data.auth.AuthClient
import com.torve.data.catalog.CatalogTopCacheWorker
import com.torve.data.panda.NzbIndexerRow
import com.torve.data.panda.PandaApiClient
import com.torve.data.usenet.NewznabClient
import com.torve.domain.diagnostics.DiagnosticsRedactor
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

    private inline fun warmupLog(message: () -> String) {
        if (com.torve.android.BuildConfig.DEBUG) {
            println(DiagnosticsRedactor.redact(message()))
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val authClient: AuthClient = getKoin().get()
            val user = authClient.getAuthenticatedUser()
            val userId = user?.id ?: PUBLIC_CATALOG_RAILS_USER_ID
            val lightweight = inputData.getBoolean(KEY_LIGHTWEIGHT, false)
            val credentialImport = inputData.getBoolean(KEY_CREDENTIAL_IMPORT, false)
            val missingOnly = inputData.getBoolean(KEY_MISSING_ONLY, false)
            val visibleProgress = inputData.getBoolean(KEY_VISIBLE_PROGRESS, true)
            val localSettingsRepo: DeviceLocalSettingsRepository = getKoin().get()
            if (lightweight && missingOnly && user == null && hasPublicCatalogWarmupCache(localSettingsRepo)) {
                warmupLog { "CATALOG_WARMUP: public catalog cache present, skipping startup warmup" }
                return Result.success()
            }
            if (lightweight && missingOnly && user != null && hasLightweightWarmupCache(localSettingsRepo, userId)) {
                warmupLog { "CATALOG_WARMUP: lightweight cache present, skipping startup warmup" }
                return Result.success()
            }
            if (lightweight && user == null && isPublicCatalogWarmupFresh(localSettingsRepo)) {
                warmupLog { "CATALOG_WARMUP: public catalog cache fresh, skipping foreground warmup" }
                return Result.success()
            }
            if (lightweight && user != null && isLightweightWarmupFresh(localSettingsRepo, userId)) {
                warmupLog { "CATALOG_WARMUP: lightweight cache fresh, skipping foreground warmup" }
                return Result.success()
            }

            val defaultBlockNavigation = !lightweight && !credentialImport
            suspend fun progress(
                label: String,
                progress: Float,
                blockNavigation: Boolean = defaultBlockNavigation,
            ) {
                if (visibleProgress) {
                    publishProgress(label, progress, blockNavigation)
                }
            }

            if (credentialImport) {
                progress("Connecting IPTV provider", 0.05f, blockNavigation = true)
                progress("Loading IPTV catalog", 0.18f, blockNavigation = true)
                runCatching { refreshPlaylistsForWarmup(catalogOnly = true) }

                progress("IPTV catalog ready", 0.46f, blockNavigation = false)
                EpgWarmupWorker.refreshNow(applicationContext, blockNavigation = false)

                progress("Preparing live TV shelves", 0.58f, blockNavigation = false)
                runCatching {
                    warmLiveBootstrap(
                        userId = userId,
                        maxShelfCategories = STAGED_LIVE_SHELF_CATEGORIES,
                        includeEpg = false,
                        progressReporter = { label, value -> progress(label, value, blockNavigation = false) },
                        progressStart = 0.58f,
                        progressEnd = 0.72f,
                    )
                }
                progress("Preparing VOD shelves", 0.72f, blockNavigation = false)
                runCatching {
                    warmVodBootstrap(
                        userId = userId,
                        maxProviderCategories = STAGED_VOD_PROVIDER_CATEGORIES,
                        includePinnedShelves = false,
                    )
                }
                progress("Preparing movies and shows", 0.84f, blockNavigation = false)
                runCatching { warmCatalogRailsBootstrap(userId) }
                progress("Preparing integrations", 0.92f, blockNavigation = false)
                runCatching { hydratePandaSecretsForWarmup(authClient) }
                progress("Cached content ready", 1f, blockNavigation = false)
                localSettingsRepo.setString(
                    lightweightWarmupLastSuccessKey(userId),
                    System.currentTimeMillis().toString(),
                )
                return Result.success()
            }

            progress("Preparing cached content", 0.05f)
            if (!lightweight) {
                progress("Refreshing home cache", 0.12f)
                runCatching { getKoin().get<CatalogTopCacheWorker>().runNow() }
            }
            progress("Preparing movies and shows", 0.18f)
            runCatching { warmCatalogRailsBootstrap(userId) }
            if (user == null) {
                progress("Cached content ready", 1f)
                localSettingsRepo.setString(
                    lightweightWarmupLastSuccessKey(userId),
                    System.currentTimeMillis().toString(),
                )
                return Result.success()
            }
            if (!lightweight) {
                progress("Refreshing IPTV and VOD", 0.28f)
                runCatching { refreshPlaylistsForWarmup(catalogOnly = credentialImport) }
            }
            progress("Preparing live TV", 0.42f)
            val liveShelfLimit = when {
                lightweight -> IMMEDIATE_LIVE_SHELF_CATEGORIES
                credentialImport -> CREDENTIAL_IMPORT_LIVE_SHELF_CATEGORIES
                else -> FULL_LIVE_SHELF_CATEGORIES
            }
            runCatching {
                warmLiveBootstrap(
                    userId = userId,
                    maxShelfCategories = liveShelfLimit,
                    includeEpg = !credentialImport,
                    progressReporter = { label, value -> progress(label, value) },
                    progressStart = 0.42f,
                    progressEnd = 0.66f,
                )
            }
            progress("Preparing VOD", 0.66f)
            val vodCategoryLimit = if (lightweight || credentialImport) {
                IMMEDIATE_VOD_PROVIDER_CATEGORIES
            } else {
                FULL_VOD_PROVIDER_CATEGORIES
            }
            runCatching {
                warmVodBootstrap(
                    userId = userId,
                    maxProviderCategories = vodCategoryLimit,
                    includePinnedShelves = !lightweight && !credentialImport,
                )
            }
            if (!lightweight && !credentialImport) {
                progress("Preparing Panda integrations", 0.78f)
                runCatching { hydratePandaSecretsForWarmup(authClient) }
                progress("Preparing sports", 0.82f)
                runCatching { warmSportsBootstrap(userId) }
            } else if (credentialImport) {
                progress("Preparing integrations", 0.82f)
                runCatching { hydratePandaSecretsForWarmup(authClient) }
            }
            progress("Cached content ready", 1f)
            localSettingsRepo.setString(
                lightweightWarmupLastSuccessKey(userId),
                System.currentTimeMillis().toString(),
            )
            Result.success()
        } catch (error: Exception) {
            val visibleProgress = inputData.getBoolean(KEY_VISIBLE_PROGRESS, true)
            if (visibleProgress) {
                runCatching {
                    publishProgress(
                        label = "Refresh failed",
                        progress = 1f,
                        blockNavigation = false,
                    )
                }
                android.util.Log.w("CatalogWarmupWorker", "foreground refresh failed: ${error.message}")
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    private suspend fun refreshPlaylistsForWarmup(catalogOnly: Boolean) = withContext(Dispatchers.IO) {
        val channelRepo: ChannelRepository = getKoin().get()
        val playlists = channelRepo.getPlaylists()
        if (playlists.isEmpty()) return@withContext
        val startedAt = System.currentTimeMillis()
        warmupLog { "CATALOG_WARMUP: refreshing playlists count=${playlists.size} catalogOnly=$catalogOnly" }
        playlists.forEach { playlist ->
            val playlistStartedAt = System.currentTimeMillis()
            runCatching {
                if (catalogOnly) channelRepo.refreshPlaylistCatalog(playlist.id)
                else channelRepo.refreshPlaylist(playlist.id)
            }
                .onSuccess {
                    warmupLog {
                        "CATALOG_WARMUP: playlist refresh done id=${playlist.id} type=${playlist.type} " +
                            "catalogOnly=$catalogOnly durationMs=${System.currentTimeMillis() - playlistStartedAt}"
                    }
                }
                .onFailure { err ->
                    warmupLog {
                        "CATALOG_WARMUP: playlist refresh failed id=${playlist.id} type=${playlist.type} " +
                            "error=${err::class.simpleName} ${DiagnosticsRedactor.redact(err.message)}"
                    }
                }
            delay(WORK_YIELD_DELAY_MS)
        }
        warmupLog {
            "CATALOG_WARMUP: playlist refresh batch done count=${playlists.size} " +
                "catalogOnly=$catalogOnly durationMs=${System.currentTimeMillis() - startedAt}"
        }
    }

    private suspend fun warmCatalogRailsBootstrap(userId: String) = withContext(Dispatchers.IO) {
        val metadataRepo: MetadataRepository = getKoin().get()
        val localSettingsRepo: DeviceLocalSettingsRepository = getKoin().get()
        warmCatalogRailsForMedia(
            userId = userId,
            mediaType = "movie",
            genreIds = listOf(28, 35, 18, 878, 27),
            metadataRepo = metadataRepo,
            localSettingsRepo = localSettingsRepo,
        )
        warmCatalogRailsForMedia(
            userId = userId,
            mediaType = "tv",
            genreIds = listOf(18, 35, 10765, 16, 99, 10759, 80),
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
            if (mediaType == "movie") {
                runCatching { metadataRepo.getNowPlaying(1).take(CATALOG_RAIL_LIMIT) }
                    .getOrDefault(emptyList())
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(CatalogRailsBootstrapRail("now-playing", it.dedupeByStableKey())) }
                runCatching { metadataRepo.getUpcoming(1).take(CATALOG_RAIL_LIMIT) }
                    .getOrDefault(emptyList())
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(CatalogRailsBootstrapRail("upcoming", it.dedupeByStableKey())) }
            }
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
        warmupLog { "CATALOG_WARMUP: catalog rails saved mediaType=$mediaType rails=${rails.size}" }
    }

    private suspend fun publishProgress(label: String, progress: Float, blockNavigation: Boolean = true) {
        setProgress(
            workDataOf(
                BackgroundWork.KEY_LABEL to label,
                BackgroundWork.KEY_PROGRESS to progress.coerceIn(0f, 1f),
                BackgroundWork.KEY_BLOCK_NAVIGATION to blockNavigation,
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

    private suspend fun hasLightweightWarmupCache(
        localSettingsRepo: DeviceLocalSettingsRepository,
        userId: String,
    ): Boolean {
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

    private suspend fun isPublicCatalogWarmupFresh(
        localSettingsRepo: DeviceLocalSettingsRepository,
    ): Boolean {
        val lastSuccess = localSettingsRepo.getString(lightweightWarmupLastSuccessKey(PUBLIC_CATALOG_RAILS_USER_ID))
            ?.toLongOrNull()
            ?: return false
        val ageMs = System.currentTimeMillis() - lastSuccess
        if (ageMs > LIGHTWEIGHT_WARMUP_FRESH_MS) return false
        return hasPublicCatalogWarmupCache(localSettingsRepo)
    }

    private suspend fun hasPublicCatalogWarmupCache(
        localSettingsRepo: DeviceLocalSettingsRepository,
    ): Boolean {
        return localSettingsRepo.getString(catalogRailsBootstrapKey(PUBLIC_CATALOG_RAILS_USER_ID, "movie")) != null &&
            localSettingsRepo.getString(catalogRailsBootstrapKey(PUBLIC_CATALOG_RAILS_USER_ID, "tv")) != null
    }

    private suspend fun warmLiveBootstrap(
        userId: String,
        maxShelfCategories: Int,
        includeEpg: Boolean,
        progressReporter: (suspend (String, Float) -> Unit)? = null,
        progressStart: Float = 0.42f,
        progressEnd: Float = 0.66f,
    ) = withContext(Dispatchers.IO) {
        val channelRepo: ChannelRepository = getKoin().get()
        val localSettingsRepo: DeviceLocalSettingsRepository = getKoin().get()
        val playlist = channelRepo.getPlaylistSummaries()
            .firstOrNull { it.channelCount > 0 }
            ?: run {
                progressReporter?.invoke("No live TV provider found", progressEnd)
                return@withContext
            }
        val categories = channelRepo.getLiveCategoryCounts(playlist.id)
            .filterNot { (name, _) -> name.startsWith("VOD:", ignoreCase = true) }
        if (categories.isEmpty()) {
            progressReporter?.invoke("No live TV categories found", progressEnd)
            return@withContext
        }
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
                progressReporter = progressReporter,
                progressStart = progressStart,
                progressEnd = progressEnd,
            )
        }
        progressReporter?.invoke("Live TV ready", progressEnd)
        warmupLog { "CATALOG_WARMUP: live bootstrap saved playlist=${playlist.id} categories=${cleanedCategories.size}" }
    }

    private suspend fun warmLiveShelves(
        userId: String,
        playlistId: String,
        rawCounts: List<Pair<String, Long>>,
        cleanedCategories: List<com.torve.domain.model.ChannelCategory>,
        channelRepo: ChannelRepository,
        localSettingsRepo: DeviceLocalSettingsRepository,
        includeEpg: Boolean,
        progressReporter: (suspend (String, Float) -> Unit)?,
        progressStart: Float,
        progressEnd: Float,
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
            progressReporter?.invoke("Loading guide data", progressStart + ((progressEnd - progressStart) * 0.15f))
            val epgData = runCatching { channelRepo.getEpg(playlistId) }.getOrDefault(com.torve.domain.model.EpgData())
            buildLiveProgrammeWindows(epgData.programmesByChannelKey)
        } else {
            emptyMap()
        }

        val total = cleanedCategories.size.coerceAtLeast(1)
        cleanedCategories.forEachIndexed { index, category ->
            if (index == 0 || index % 3 == 0 || index == cleanedCategories.lastIndex) {
                val fraction = (index.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                val value = progressStart + ((progressEnd - progressStart) * (0.2f + fraction * 0.75f))
                progressReporter?.invoke("Preparing live TV ${index + 1}/$total", value)
            }
            val rawNames = rawByCleanName[category.name].orEmpty()
            if (rawNames.isEmpty()) return@forEachIndexed
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
            if (channels.isEmpty()) return@forEachIndexed
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
        warmupLog { "CATALOG_WARMUP: VOD bootstrap saved playlist=${playlist.id} categories=${categories.size}" }
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
        warmupLog { "CATALOG_WARMUP: sports bootstrap saved items=${items.size}" }
    }

    private suspend fun hydratePandaSecretsForWarmup(authClient: AuthClient) = withContext(Dispatchers.IO) {
        val secretStore: IntegrationSecretStore = getKoin().get()
        val prefs: PreferencesRepository = getKoin().get()
        val pandaClient: PandaApiClient = getKoin().get()
        val pandaToken = secretStore.get(IntegrationSecretKey.PANDA_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext
        val torveToken = authClient.getValidAccessToken()?.takeIf { it.isNotBlank() }

        val manifestRecord = runCatching { pandaClient.getConfig(pandaToken) }
            .onFailure { warmupLog { "CATALOG_WARMUP: Panda manifest hydrate failed: ${it::class.simpleName} ${DiagnosticsRedactor.redact(it.message)}" } }
            .getOrNull() ?: return@withContext
        val configId = manifestRecord.configId?.takeIf { it.isNotBlank() } ?: return@withContext

        val record = if (torveToken != null) {
            runCatching { pandaClient.getConfigAsManager(configId, torveToken) }
                .onFailure { warmupLog { "CATALOG_WARMUP: Panda owner hydrate failed: ${it::class.simpleName} ${DiagnosticsRedactor.redact(it.message)}" } }
                .getOrNull() ?: manifestRecord
        } else {
            manifestRecord
        }
        val config = record.config ?: return@withContext
        val secrets = if (torveToken != null) {
            runCatching { pandaClient.getConfigSecrets(configId, torveToken) }
                .onFailure { warmupLog { "CATALOG_WARMUP: Panda credential hydrate failed: ${it::class.simpleName} ${DiagnosticsRedactor.redact(it.message)}" } }
                .getOrNull()
        } else {
            null
        }

        val rows = config.nzbIndexers.ifEmpty {
            if (config.nzbIndexer != "none") {
                listOf(
                    NzbIndexerRow(
                        type = config.nzbIndexer,
                        url = config.nzbIndexerUrl,
                        apiKey = config.nzbIndexerApiKey,
                    ),
                )
            } else {
                emptyList()
            }
        }
        var cachedIndexerKeys = 0
        rows.forEach { row ->
            if (row.type == "none") return@forEach
            val apiKeyFromServer = secrets?.nzbIndexers
                ?.firstOrNull { it.type == row.type && it.url == row.url }
                ?.apiKey
                ?.takeIf { it.isNotBlank() }
            val apiKeyFromRow = row.apiKey
                .takeUnless { isRedactedSecret(it) }
                ?.takeIf { it.isNotBlank() }
            val legacyApiKey = secrets?.nzbIndexerApiKey?.takeIf { it.isNotBlank() }
            val apiKey = apiKeyFromServer ?: apiKeyFromRow ?: legacyApiKey
            if (apiKey.isNullOrBlank()) return@forEach
            val subKey = "${row.type}|${row.url}"
            secretStore.put(IntegrationSecretKey.PANDA_INDEXER_API_KEY, apiKey, subKey)
            secretStore.put(IntegrationSecretKey.PANDA_INDEXER_API_KEY, apiKey, row.type)
            cachedIndexerKeys++
        }

        val firstConfigured = rows.firstOrNull { it.type != "none" }
        if (firstConfigured != null) {
            prefs.setString("panda_nzb_indexer", firstConfigured.type)
            prefs.setString("panda_nzb_indexer_url", firstConfigured.url)
            val firstKey = secretStore.get(
                IntegrationSecretKey.PANDA_INDEXER_API_KEY,
                "${firstConfigured.type}|${firstConfigured.url}",
            )
            if (!firstKey.isNullOrBlank()) {
                prefs.setString("panda_nzb_indexer_api_key", firstKey)
            }
        }
        prefs.setString("panda_download_client", config.downloadClient)
        prefs.setString("panda_usenet_provider", if (config.enableUsenet) config.usenetProvider else "none")
        warmupLog { "CATALOG_WARMUP: Panda hydrated indexerRows=${rows.size} cachedIndexerCredentialCount=$cachedIndexerKeys" }
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
        private const val LEGACY_WORK_NAME = "catalog_warmup_worker"
        private const val LEGACY_IMMEDIATE_WORK_NAME = "catalog_warmup_worker_immediate"
        private const val WORK_NAME = "catalog_warmup_worker_silent_v2"
        private const val IMMEDIATE_WORK_NAME = "catalog_warmup_worker_immediate_v2"
        private const val KEY_LIGHTWEIGHT = "lightweight"
        private const val KEY_CREDENTIAL_IMPORT = "credential_import"
        private const val KEY_MISSING_ONLY = "missing_only"
        private const val KEY_VISIBLE_PROGRESS = "visible_progress"
        private const val CATALOG_RAIL_LIMIT = 24
        private const val STAGED_LIVE_SHELF_CATEGORIES = 4
        private const val STAGED_VOD_PROVIDER_CATEGORIES = 4
        private const val IMMEDIATE_LIVE_SHELF_CATEGORIES = 8
        private const val CREDENTIAL_IMPORT_LIVE_SHELF_CATEGORIES = 12
        private const val FULL_LIVE_SHELF_CATEGORIES = 48
        private const val IMMEDIATE_VOD_PROVIDER_CATEGORIES = 8
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
                .setInputData(workDataOf(KEY_VISIBLE_PROGRESS to false))
                .build()
            val immediate = OneTimeWorkRequestBuilder<CatalogWarmupWorker>()
                .setConstraints(immediateConstraints)
                .setInputData(
                    workDataOf(
                        KEY_LIGHTWEIGHT to true,
                        KEY_MISSING_ONLY to true,
                        KEY_VISIBLE_PROGRESS to false,
                    ),
                )
                .build()
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(LEGACY_WORK_NAME)
            manager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK_NAME)
            manager.cancelUniqueWork("${LEGACY_IMMEDIATE_WORK_NAME}_refresh")
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
            manager.enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                immediate,
            )
        }

        fun refreshNow(
            context: Context,
            lightweight: Boolean = false,
            visibleProgress: Boolean = true,
            missingOnly: Boolean = false,
        ) {
            enqueueImmediateRefresh(
                context = context,
                lightweight = lightweight,
                credentialImport = false,
                visibleProgress = visibleProgress,
                missingOnly = missingOnly,
            )
        }

        fun refreshAfterCredentialImport(context: Context) {
            enqueueImmediateRefresh(
                context = context,
                lightweight = false,
                credentialImport = true,
                visibleProgress = true,
                missingOnly = false,
            )
        }

        private fun enqueueImmediateRefresh(
            context: Context,
            lightweight: Boolean,
            credentialImport: Boolean,
            visibleProgress: Boolean,
            missingOnly: Boolean,
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val requestBuilder = OneTimeWorkRequestBuilder<CatalogWarmupWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_LIGHTWEIGHT to lightweight,
                        KEY_CREDENTIAL_IMPORT to credentialImport,
                        KEY_MISSING_ONLY to missingOnly,
                        KEY_VISIBLE_PROGRESS to visibleProgress,
                    ),
                )
            if (visibleProgress) {
                requestBuilder.addTag(BackgroundWork.TAG_HEAVY_PRELOAD)
            }
            val request = requestBuilder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${IMMEDIATE_WORK_NAME}_refresh",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork("${IMMEDIATE_WORK_NAME}_refresh")
        }
    }
}

private data class WarmIndexer(
    val type: String,
    val url: String,
    val apiKey: String,
)

private fun isRedactedSecret(value: String?): Boolean {
    val trimmed = value?.trim().orEmpty()
    return trimmed.isBlank() ||
        trimmed.equals("[redacted]", ignoreCase = true) ||
        trimmed.equals("redacted", ignoreCase = true)
}

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
