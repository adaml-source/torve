package com.torve.presentation.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.PlaylistType
import com.torve.domain.model.stableChannelId
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression tests for local-first startup behavior.
 *
 * Locks down three invariants:
 * 1. Persisted EPG renders the guide without a blocking network refresh.
 * 2. getEpg() hydrates the in-memory cache from the DB result.
 * 3. A renderable local guide never starts an automatic foreground refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelLocalFirstEpgTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Case 1: persisted EPG exists, in-memory cache empty ──
    // The guide must render from local DB data without triggering refreshEpg().
    @Test
    fun persisted_epg_renders_guide_without_network_refresh() = runTest(dispatcher) {
        val channel = sampleChannel("p1", "3sat HD", tvgId = "3sat.hd")
        val epgKey = "p1::3sat.hd"
        val programme = sampleProgramme(epgKey, "Nano")

        val repo = EpgFakeChannelRepository(
            playlists = listOf(samplePlaylist("p1", epgUrl = "https://epg.example.com/xmltv")),
            persistedChannels = mutableMapOf("p1" to listOf(channel)),
            remoteChannels = mutableMapOf("p1" to listOf(channel)),
            // Simulates SQLite having persisted EPG from a previous session.
            persistedEpg = mutableMapOf(
                "p1" to EpgData(
                    programmesByChannelKey = mapOf(epgKey to listOf(programme)),
                    programmes = listOf(programme),
                    generationId = 1L,
                ),
            ),
        )
        val prefs = FakePrefs(mutableMapOf("channels_selected_playlist" to "p1"))

        val vm = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()

        // Guide should be loaded from local EPG, not stuck in Loading.
        val state = vm.state.value
        assertIs<EpgState.Loaded>(state.epgState, "EPG state should be Loaded, not ${state.epgState}")
        assertFalse(state.isLoadingGuide, "Guide should not be in loading state")
        assertTrue(state.guideProgrammes.isNotEmpty(), "Guide programmes should be populated from local EPG")
        assertEquals(0, repo.epgRefreshCalls, "Cached EPG must not start a provider download")
        assertEquals(0, repo.blockingEpgRefreshCalls, "No blocking EPG refresh should occur when local EPG exists")
    }

    // ── Case 2: getEpg() hydrates epgCache after DB load ──
    // Second call to getEpg() must return cached data without re-querying.
    @Test
    fun getEpg_hydrates_cache_after_db_load() = runTest(dispatcher) {
        val epgKey = "p1::news.one"
        val programme = sampleProgramme(epgKey, "Evening News")
        val persistedEpg = EpgData(
            programmesByChannelKey = mapOf(epgKey to listOf(programme)),
            programmes = listOf(programme),
            generationId = 1L,
        )

        val repo = EpgFakeChannelRepository(
            playlists = listOf(samplePlaylist("p1", epgUrl = "https://epg.example.com/xmltv")),
            persistedChannels = mutableMapOf("p1" to listOf(sampleChannel("p1", "News One", tvgId = "news.one"))),
            remoteChannels = mutableMapOf("p1" to listOf(sampleChannel("p1", "News One", tvgId = "news.one"))),
            persistedEpg = mutableMapOf("p1" to persistedEpg),
        )

        // First call loads from "DB" (persistedEpg map).
        val firstResult = repo.getEpg("p1")
        assertEquals(1, firstResult.programmesByChannelKey.size)

        // Second call should return the same data from the in-memory cache
        // without incrementing the DB-read counter.
        val dbReadsBefore = repo.epgDbReads
        val secondResult = repo.getEpg("p1")
        assertEquals(1, secondResult.programmesByChannelKey.size)
        assertEquals(dbReadsBefore, repo.epgDbReads, "Second getEpg() call should use cache, not re-read DB")
    }

    // ── Case 3: cached EPG remains foreground-only ──
    @Test
    fun background_epg_refresh_does_not_block_guide_when_local_catalog_exists() = runTest(dispatcher) {
        val channel = sampleChannel("p1", "ARD HD", tvgId = "ard.hd")
        val epgKey = "p1::ard.hd"
        val staleProgramme = sampleProgramme(epgKey, "Tagesschau (stale)")
        val freshProgramme = sampleProgramme(epgKey, "Tagesschau (fresh)")

        val repo = EpgFakeChannelRepository(
            playlists = listOf(samplePlaylist("p1", epgUrl = "https://epg.example.com/xmltv")),
            persistedChannels = mutableMapOf("p1" to listOf(channel)),
            remoteChannels = mutableMapOf("p1" to listOf(channel)),
            persistedEpg = mutableMapOf(
                "p1" to EpgData(
                    programmesByChannelKey = mapOf(epgKey to listOf(staleProgramme)),
                    programmes = listOf(staleProgramme),
                    generationId = 1L,
                ),
            ),
            // After refreshEpg(), the repo will return this fresh data.
            refreshedEpg = mutableMapOf(
                "p1" to EpgData(
                    programmesByChannelKey = mapOf(epgKey to listOf(freshProgramme)),
                    programmes = listOf(freshProgramme),
                    generationId = 2L,
                ),
            ),
        )
        val prefs = FakePrefs(mutableMapOf("channels_selected_playlist" to "p1"))

        val vm = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        // Collect intermediate states to verify Loading never appears after initial render.
        val observedEpgStates = mutableListOf<EpgState>()
        var sawLoadedOnce = false
        val collectJob = launch(dispatcher) {
            vm.state.collect { state ->
                // Only record states after the first Loaded, to detect regressions.
                if (state.epgState is EpgState.Loaded) sawLoadedOnce = true
                if (sawLoadedOnce) observedEpgStates.add(state.epgState)
            }
        }

        advanceUntilIdle()
        collectJob.cancel()

        // After startup, the persisted guide is loaded without provider IO.
        val finalState = vm.state.value
        assertIs<EpgState.Loaded>(finalState.epgState)
        assertFalse(finalState.isLoadingGuide)
        assertEquals(0, repo.epgRefreshCalls)
        assertEquals("Tagesschau (stale)", finalState.guideProgrammes[epgKey]?.firstOrNull()?.title)

        // Verify that once epgState reached Loaded, it never went back to Loading.
        val loadingAfterFirstLoaded = observedEpgStates.any { it is EpgState.Loading }
        assertFalse(
            loadingAfterFirstLoaded,
            "epgState must never revert to Loading after reaching Loaded. Observed: $observedEpgStates",
        )
    }

    @Test
    fun selected_group_reads_programmes_outside_bounded_playlist_snapshot() = runTest(dispatcher) {
        val channel = sampleChannel("p1", "US: ABC (WPLG) Miami", "wplg.miami")
            .copy(groupTitle = "Miami / 60fps")
        val channelKey = "p1::wplg.miami"
        val expected = sampleProgramme(channelKey, "Miami evening news")
        val cached = (1..160).associate { index ->
            val key = "p1::other.$index"
            key to listOf(sampleProgramme(key, "Other schedule $index"))
        }
        val repo = EpgFakeChannelRepository(
            playlists = listOf(samplePlaylist("p1", epgUrl = "https://epg.example.com/xmltv")),
            persistedChannels = mutableMapOf("p1" to listOf(channel)),
            remoteChannels = mutableMapOf("p1" to listOf(channel)),
            persistedEpg = mutableMapOf("p1" to EpgData(programmesByChannelKey = cached, programmes = cached.values.flatten(), generationId = 1L)),
            databaseProgrammes = mapOf(channelKey to listOf(expected)),
        )
        val vm = ChannelsViewModel(repo, FakePrefs(mutableMapOf("channels_selected_playlist" to "p1")), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        vm.loadCategoryChannels("Miami / 60fps")
        advanceUntilIdle()
        vm.selectSubTab(ChannelsSubTab.GUIDE)
        advanceUntilIdle()

        assertEquals(listOf(expected), vm.guideProgrammesForChannel(channel))
        assertEquals(0, repo.epgRefreshCalls, "Already imported programmes must be read without another provider download")
        assertFalse(vm.state.value.isLoadingGuide)
    }

    @Test
    fun large_playlist_guide_uses_category_rows_and_rebuilds_after_group_switch() = runTest(dispatcher) {
        val first = sampleChannel("p1", "News", "news").copy(groupTitle = "DE| News")
        val second = sampleChannel("p1", "Sport", "sport").copy(groupTitle = "DE| Sport")
        val news = sampleProgramme("p1::news", "News programme")
        val sport = sampleProgramme("p1::sport", "Sport programme")
        val repo = EpgFakeChannelRepository(
            playlists = listOf(samplePlaylist("p1", "https://epg.example.com/xmltv")),
            persistedChannels = mutableMapOf("p1" to listOf(first, second)),
            remoteChannels = mutableMapOf("p1" to listOf(first, second)),
            persistedEpg = mutableMapOf("p1" to EpgData(programmesByChannelKey = mapOf("p1::news" to listOf(news)))),
            databaseProgrammes = mapOf("p1::sport" to listOf(sport)),
            totalRows = 70_000L,
        )
        val vm = ChannelsViewModel(repo, FakePrefs(mutableMapOf("channels_selected_playlist" to "p1")), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        vm.selectSubTab(ChannelsSubTab.GUIDE)
        advanceUntilIdle()
        assertTrue(vm.state.value.channels.isEmpty(), "Large playlist must stay out of memory")
        assertEquals(listOf(first), vm.state.value.guideChannels.map { it.channel })
        vm.loadCategoryChannels("Germany Sport")
        advanceUntilIdle()
        assertEquals(listOf(second), vm.state.value.guideChannels.map { it.channel })
        assertEquals(listOf(sport), vm.guideProgrammesForChannel(second))
        assertFalse(vm.state.value.isLoadingGuide)
        assertEquals(0, repo.epgRefreshCalls)
        assertEquals(0, repo.fullLoadCalls)
    }

    @Test
    fun count_rebuild_keeps_loaded_channels_in_expanded_groups() = runTest(dispatcher) {
        val channel = sampleChannel("p1", "News", "news").copy(groupTitle = "DE| News")
        val repo = EpgFakeChannelRepository(listOf(samplePlaylist("p1")),
            mutableMapOf("p1" to listOf(channel)), mutableMapOf("p1" to listOf(channel)), totalRows = 70_000L)
        val vm = ChannelsViewModel(repo, FakePrefs(mutableMapOf("channels_selected_playlist" to "p1")), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        vm.toggleCategoryExpanded("Germany News")
        advanceUntilIdle()
        vm.setSort(ChannelsSortType.NAME_AZ)
        advanceUntilIdle()
        assertTrue("Germany News" in vm.state.value.expandedCategories)
        assertEquals(listOf(channel), vm.state.value.categories.first().channels.map { it.channel })
    }

    // ── Helpers ──

    private fun samplePlaylist(id: String, epgUrl: String? = null) = ChannelPlaylist(
        id = id,
        name = "Playlist $id",
        url = "https://example.com/$id.m3u",
        epgUrl = epgUrl,
        type = PlaylistType.M3U,
    )

    private fun sampleChannel(playlistId: String, name: String, tvgId: String) = Channel(
        name = name,
        url = "https://example.com/live/$tvgId.m3u8",
        tvgId = tvgId,
        groupTitle = "TV",
        playlistId = playlistId,
        contentType = ChannelContentType.LIVE,
    )

    private fun sampleProgramme(channelKey: String, title: String) = EpgProgramme(
        channelId = channelKey,
        startTime = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - 1_800_000,
        endTime = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() + 1_800_000,
        title = title,
    )
}

/**
 * Fake ChannelRepository that supports persisted EPG data and tracks
 * blocking vs background EPG refresh calls.
 */
private class EpgFakeChannelRepository(
    private val playlists: List<ChannelPlaylist>,
    private val persistedChannels: MutableMap<String, List<Channel>>,
    private val remoteChannels: MutableMap<String, List<Channel>>,
    private val persistedEpg: MutableMap<String, EpgData> = mutableMapOf(),
    private val refreshedEpg: MutableMap<String, EpgData> = mutableMapOf(),
    private val databaseProgrammes: Map<String, List<EpgProgramme>> = emptyMap(),
    private val totalRows: Long? = null,
) : ChannelRepository {
    private val favorites = linkedMapOf<String, Channel>()
    private val recents = linkedMapOf<String, Channel>()
    private val epgCache = mutableMapOf<String, EpgData>()

    var fullLoadCalls: Int = 0; private set
    var refreshCalls: Int = 0; private set
    var epgRefreshCalls: Int = 0; private set
    var blockingEpgRefreshCalls: Int = 0; private set
    var epgDbReads: Int = 0; private set

    override suspend fun getPlaylists(): List<ChannelPlaylist> = playlists
    override suspend fun getChannels(playlistId: String): List<Channel> = persistedChannels[playlistId].orEmpty()

    override suspend fun getEnrichedChannels(playlistId: String): List<EnrichedChannel> {
        fullLoadCalls++
        return getChannels(playlistId).map { ch ->
            val fav = favorites[stableChannelId(ch)]
            EnrichedChannel(channel = fav?.copy(isFavorite = true) ?: ch)
        }
    }

    override suspend fun refreshPlaylist(playlistId: String) {
        refreshCalls++
        persistedChannels[playlistId] = remoteChannels[playlistId].orEmpty()
    }

    override suspend fun refreshEpg(playlistId: String, hiddenChannelIds: Set<String>) {
        epgRefreshCalls++
        // If the ViewModel calls refreshEpg before getEpg returned data,
        // that counts as a blocking refresh (the old bad behavior).
        if (epgCache[playlistId] == null && persistedEpg[playlistId]?.programmesByChannelKey?.isNotEmpty() == true) {
            blockingEpgRefreshCalls++
        }
        // Simulate network refresh: replace persisted EPG with fresh data.
        refreshedEpg[playlistId]?.let {
            persistedEpg[playlistId] = it
            epgCache[playlistId] = it
        }
    }

    override suspend fun getEpg(playlistId: String): EpgData {
        // Simulate the real getEpg: check in-memory cache first, then "DB".
        epgCache[playlistId]?.let { return it }
        epgDbReads++
        val dbResult = persistedEpg[playlistId] ?: return EpgData()
        if (dbResult.programmesByChannelKey.isNotEmpty()) {
            epgCache[playlistId] = dbResult
        }
        return dbResult
    }

    override suspend fun getEpgLoadError(playlistId: String): String? = null
    override suspend fun getProgrammes(channelId: String): List<EpgProgramme> = databaseProgrammes[channelId].orEmpty()
    override suspend fun searchChannels(query: String): List<Channel> = emptyList()
    override suspend fun getChannelsByGroup(playlistId: String): Map<String, List<Channel>> =
        getChannels(playlistId).groupBy { it.groupTitle ?: "Ungrouped" }

    override suspend fun addPlaylist(
        name: String,
        url: String,
        epgUrl: String?,
        id: String?,
        onProgress: ((com.torve.domain.repository.PlaylistAddProgress) -> Unit)?,
    ): ChannelPlaylist = error("Not used")
    override suspend fun addXtreamPlaylist(
        name: String,
        server: String,
        username: String,
        password: String,
        id: String?,
        epgUrl: String?,
    ): ChannelPlaylist = error("Not used")
    override suspend fun removePlaylist(id: String) { persistedChannels.remove(id) }
    override suspend fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String?) = Unit
    override suspend fun addFavorite(channel: Channel) { favorites[stableChannelId(channel)] = channel }
    override suspend fun removeFavorite(channelId: String) { favorites.remove(channelId) }
    override suspend fun getFavorites(): List<Channel> = favorites.values.toList()
    override suspend fun isFavorite(channelId: String): Boolean = channelId in favorites
    override suspend fun recordChannelViewed(channel: Channel) { recents[stableChannelId(channel)] = channel }
    override suspend fun getRecentlyViewedChannels(limit: Long): List<Channel> = recents.values.toList().takeLast(limit.toInt()).reversed()
    override suspend fun clearRecentlyViewedChannels() { recents.clear() }
    override suspend fun getChannelsByContentType(playlistId: String, type: ChannelContentType): List<EnrichedChannel> =
        getEnrichedChannels(playlistId).filter { it.channel.contentType == type }

    override suspend fun clearAll() {
        persistedChannels.clear()
        favorites.clear()
        recents.clear()
        epgCache.clear()
        persistedEpg.clear()
        refreshedEpg.clear()
    }

    override suspend fun getCategoryCounts(playlistId: String): List<Pair<String, Long>> {
        return getChannelsByGroup(playlistId).map { (group, channels) -> group to channels.size.toLong() }
    }

    override suspend fun getLiveCategoryCounts(playlistId: String): List<Pair<String, Long>> {
        return getChannelsByGroup(playlistId)
            .mapValues { (_, channels) ->
                channels.filter { it.contentType == ChannelContentType.LIVE || it.contentType == ChannelContentType.UNKNOWN }
            }
            .filter { (group, channels) ->
                channels.isNotEmpty() &&
                    !group.startsWith("VOD:", ignoreCase = true) &&
                    !group.equals("VOD", ignoreCase = true)
            }
            .map { (group, channels) -> group to channels.size.toLong() }
    }

    override suspend fun getVodCategoryCounts(playlistId: String): List<Pair<String, Long>> {
        return getChannelsByGroup(playlistId).mapNotNull { (group, channels) ->
            val isVodGroup = group.startsWith("VOD:", ignoreCase = true) || group.equals("VOD", ignoreCase = true)
            val vodChannels = if (isVodGroup) {
                channels
            } else {
                channels.filter {
                    it.contentType == ChannelContentType.VOD_MOVIE || it.contentType == ChannelContentType.VOD_SERIES
                }
            }
            if (vodChannels.isEmpty()) null else group to vodChannels.size.toLong()
        }
    }

    override suspend fun getChannelsForCategory(playlistId: String, categoryName: String): List<Channel> {
        return getChannelsByGroup(playlistId)[categoryName].orEmpty()
    }

    override suspend fun getTotalChannelCount(playlistId: String): Long {
        return totalRows ?: getChannels(playlistId).size.toLong()
    }

    override fun syncHiddenChannelsToDb(hiddenIds: Set<String>) = Unit

    override fun getHiddenChannelIds(): Set<String> = emptySet()
}

private class FakePrefs(
    private val store: MutableMap<String, String> = mutableMapOf(),
) : PreferencesRepository {
    override suspend fun getString(key: String): String? = store[key]
    override suspend fun setString(key: String, value: String) { store[key] = value }
    override suspend fun remove(key: String) { store.remove(key) }
}
