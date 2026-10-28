package com.torve.presentation.channels

import kotlin.test.assertNull

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelStartupTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun saved_group_is_published_before_initial_channel_hydration_finishes() = runTest(dispatcher) {
        val news = sampleChannel("playlist-1", "News One", "news.one")
        val sport = sampleChannel("playlist-1", "Sports One", "sports.one").copy(groupTitle = "Sports")
        val base = FakeChannelRepository(listOf(samplePlaylist("playlist-1")),
            mutableMapOf("playlist-1" to listOf(news, sport)), mutableMapOf("playlist-1" to listOf(news, sport)))
        val hydration = CompletableDeferred<Unit>()
        val repo = object : ChannelRepository by base {
            override suspend fun getChannelsForCategory(playlistId: String, categoryName: String): List<Channel> {
                hydration.await()
                return base.getChannelsForCategory(playlistId, categoryName)
            }
        }
        val prefs = FakePreferencesRepository(mutableMapOf("channels_selected_playlist" to "playlist-1",
            "channels_selected_group_playlist-1" to "Sports"))
        val viewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.categories.isNotEmpty())
        assertEquals("Sports", viewModel.state.value.selectedGroup)
        assertTrue(viewModel.state.value.categoryChannels.isEmpty())
        hydration.complete(Unit)
        advanceUntilIdle()
        assertEquals("Sports One", viewModel.state.value.selectedChannel?.name)
    }

    @Test fun categoryDisplayDoesNotDependOnTheFullPlaylistAndCountryVisibilityPersists() = runTest(dispatcher) {
        val german = sampleChannel("playlist-1", "German News", "de.news").copy(groupTitle = "DE| Germany News")
        val albanian = sampleChannel("playlist-1", "Albanian News", "al.news").copy(groupTitle = "AL| Albania News")
        val data = mutableMapOf("playlist-1" to listOf(german, albanian))
        val prefs = FakePreferencesRepository(mutableMapOf("channels_selected_playlist" to "playlist-1"))
        val repo = FakeChannelRepository(listOf(samplePlaylist("playlist-1")), data, data)
        val vm = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        assertTrue(vm.state.value.channels.isEmpty())
        vm.loadCategoryChannels("Germany News"); advanceUntilIdle()
        assertEquals(listOf("German News"), vm.getDisplayChannels().map { it.channel.name })
        vm.toggleCountry("DE"); advanceUntilIdle()
        assertEquals(listOf("German News"), vm.getDisplayChannels().map { it.channel.name })
        vm.hideCountryCategories("DE"); advanceUntilIdle()
        assertFalse(vm.state.value.categories.any { it.countryCode == "DE" })
        assertTrue(vm.getDisplayChannels().isEmpty())
        vm.showCountryCategories("DE"); advanceUntilIdle()
        assertTrue(vm.state.value.categories.any { it.countryCode == "DE" })
        vm.setCategoryManagerVisible(true); vm.setCategoryManagerVisible(false); vm.setCategoryManagerVisible(false)
        assertFalse(vm.state.value.showCategoryManager)
    }

    @Test fun slowCategoryRequestCannotReplaceTheMostRecentSelection() = runTest(dispatcher) {
        val news = sampleChannel("playlist-1", "News One", "news.one")
        val sport = sampleChannel("playlist-1", "Sport One", "sport.one").copy(groupTitle = "Sports")
        val data = mutableMapOf("playlist-1" to listOf(news, sport))
        val base = FakeChannelRepository(listOf(samplePlaylist("playlist-1")), data, data)
        val gate = CompletableDeferred<Unit>()
        val repo = object : ChannelRepository by base {
            override suspend fun getChannelsForCategory(playlistId: String, categoryName: String): List<Channel> {
                if (categoryName == "Sports") gate.await()
                return base.getChannelsForCategory(playlistId, categoryName)
            }
        }
        val vm = ChannelsViewModel(repo, FakePreferencesRepository(mutableMapOf("channels_selected_playlist" to "playlist-1")), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        vm.loadCategoryChannels("Sports"); runCurrent()
        vm.loadCategoryChannels("News"); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("News", vm.state.value.selectedGroup)
        assertEquals(listOf("News One"), vm.getDisplayChannels().map { it.channel.name })
    }

    @Test fun hidingACountryDuringLoadingCannotRestoreItsHiddenGroup() = runTest(dispatcher) {
        val data = mutableMapOf("playlist-1" to listOf(
            sampleChannel("playlist-1", "News One", "news"),
            sampleChannel("playlist-1", "Albanian News", "al").copy(groupTitle = "AL| Albania News")))
        val base = FakeChannelRepository(listOf(samplePlaylist("playlist-1")), data, data)
        val gate = CompletableDeferred<Unit>()
        val repo = object : ChannelRepository by base {
            override suspend fun getChannelsForCategory(playlistId: String, categoryName: String): List<Channel> {
                if (categoryName.startsWith("AL|")) gate.await()
                return base.getChannelsForCategory(playlistId, categoryName)
            }
        }
        val vm = ChannelsViewModel(repo, FakePreferencesRepository(mutableMapOf("channels_selected_playlist" to "playlist-1")), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        vm.loadCategoryChannels("Albania News"); runCurrent()
        assertTrue(vm.state.value.isLoadingChannels)
        vm.hideCountryCategories("AL"); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.selectedGroup)
        assertFalse(vm.state.value.isLoadingChannels)
        assertFalse(vm.state.value.categories.any { it.countryCode == "AL" })
        assertTrue(vm.getDisplayChannels().isEmpty())
    }

    @Test fun failedCategoryReadLeavesAnExplanationAndStopsTheLoadingIndicator() = runTest(dispatcher) {
        val data = mutableMapOf("playlist-1" to listOf(sampleChannel("playlist-1", "News One", "news")))
        val base = FakeChannelRepository(listOf(samplePlaylist("playlist-1")), data, data)
        var fail = false
        val repo = object : ChannelRepository by base {
            override suspend fun getChannelsForCategory(playlistId: String, categoryName: String): List<Channel> {
                if (fail) error("Local database failure")
                return base.getChannelsForCategory(playlistId, categoryName)
            }
        }
        val vm = ChannelsViewModel(repo, FakePreferencesRepository(mutableMapOf("channels_selected_playlist" to "playlist-1")), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        fail = true
        vm.loadCategoryChannels("News"); advanceUntilIdle()
        assertFalse(vm.state.value.isLoadingChannels)
        assertTrue(vm.state.value.error.orEmpty().contains("Could not load"))
    }

    @Test
    fun startup_with_stale_persisted_catalog_refreshes_in_background() = runTest(dispatcher) {
        val channel = sampleChannel("playlist-1", "News One", tvgId = "news.one")
        val repo = FakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(channel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(channel.copy(name = "News One HD"))),
        )
        val prefs = FakePreferencesRepository(
            mutableMapOf(
                "channels_selected_playlist" to "playlist-1",
                "channels_selected_group_playlist-1" to "News",
                "channels_selected_channel_playlist-1" to stableChannelId(channel),
            ),
        )

        val viewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals("playlist-1", viewModel.state.value.selectedPlaylistId)
        assertEquals(1, viewModel.state.value.categoryChannels.size)
        assertEquals("News One HD", viewModel.state.value.categoryChannels.first().channel.name)
        assertEquals("News", viewModel.state.value.selectedGroup)
        assertEquals(stableChannelId(channel), stableChannelId(viewModel.state.value.selectedChannel!!))
        assertEquals(0, repo.refreshCalls)
        assertEquals(1, repo.catalogRefreshCalls)
    }

    @Test
    fun failed_background_refresh_keeps_last_known_good_catalog() = runTest(dispatcher) {
        val localChannel = sampleChannel("playlist-1", "Sports One", tvgId = "sports.one")
        val repo = FakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(localChannel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(localChannel.copy(name = "Sports One Remote"))),
            refreshFailures = mutableSetOf("playlist-1"),
        )
        val prefs = FakePreferencesRepository(
            mutableMapOf("channels_selected_playlist" to "playlist-1"),
        )

        val viewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.categoryChannels.size)
        assertEquals("Sports One", viewModel.state.value.categoryChannels.first().channel.name)
        assertFalse(viewModel.state.value.isLoadingChannels)
        assertEquals(0, repo.refreshCalls)
        assertEquals(1, repo.catalogRefreshCalls)
    }

    @Test
    fun startup_without_persisted_catalog_populates_after_background_refresh() = runTest(dispatcher) {
        val remoteChannel = sampleChannel("playlist-1", "Movie Max", tvgId = "movie.max")
        val repo = FakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to emptyList()),
            remoteChannels = mutableMapOf("playlist-1" to listOf(remoteChannel)),
        )
        val prefs = FakePreferencesRepository(
            mutableMapOf("channels_selected_playlist" to "playlist-1"),
        )

        val viewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.categoryChannels.size)
        assertEquals("Movie Max", viewModel.state.value.categoryChannels.first().channel.name)
        assertFalse(viewModel.state.value.isLoadingChannels)
        assertEquals(0, repo.refreshCalls)
        assertEquals(1, repo.catalogRefreshCalls)
    }

    @Test
    fun selected_channel_and_recent_channel_survive_restart() = runTest(dispatcher) {
        val channel = sampleChannel("playlist-1", "Cinema One", tvgId = "cinema.one")
        val repo = FakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(channel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(channel)),
        )
        val prefs = FakePreferencesRepository(
            mutableMapOf("channels_selected_playlist" to "playlist-1"),
        )

        val firstViewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        firstViewModel.selectChannel(channel)
        firstViewModel.recordChannelViewed(channel)
        advanceUntilIdle()

        val secondViewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals(stableChannelId(channel), stableChannelId(secondViewModel.state.value.selectedChannel!!))
        assertTrue(secondViewModel.state.value.recentlyViewedChannels.any { stableChannelId(it) == stableChannelId(channel) })
    }

    @Test
    fun favorite_state_survives_restart_and_rebinds_to_restored_catalog() = runTest(dispatcher) {
        val channel = sampleChannel("playlist-1", "Docs One", tvgId = "docs.one")
        val repo = FakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(channel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(channel)),
        )
        val prefs = FakePreferencesRepository(
            mutableMapOf("channels_selected_playlist" to "playlist-1"),
        )

        val firstViewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        firstViewModel.toggleFavorite(channel)
        advanceUntilIdle()

        val secondViewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()

        assertTrue(secondViewModel.state.value.favorites.any { stableChannelId(it) == stableChannelId(channel) })
        assertTrue(secondViewModel.state.value.categoryChannels.first().channel.isFavorite)
    }

    @Test
    fun last_watched_channel_restores_when_selected_channel_key_is_missing() = runTest(dispatcher) {
        val channel = sampleChannel("playlist-1", "Kids One", tvgId = "kids.one")
        val repo = FakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(channel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(channel)),
        )
        val prefs = FakePreferencesRepository(
            mutableMapOf("channels_selected_playlist" to "playlist-1"),
        )

        val firstViewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        firstViewModel.recordChannelViewed(channel)
        advanceUntilIdle()
        prefs.remove("channels_selected_channel_playlist-1")

        val secondViewModel = ChannelsViewModel(repo, prefs, backgroundDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals(stableChannelId(channel), stableChannelId(secondViewModel.state.value.selectedChannel!!))
    }

    @Test
    fun favorite_changes_update_local_catalog_without_refresh_or_selection_loss() = runTest(dispatcher) {
        val channel = sampleChannel("playlist-1", "News One", tvgId = "news.one")
        val repo = FakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(channel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(channel)),
        )
        val viewModel = ChannelsViewModel(repo, FakePreferencesRepository(), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        viewModel.selectChannel(channel)
        advanceUntilIdle()
        val refreshCount = repo.refreshCalls
        val catalogRefreshCount = repo.catalogRefreshCalls

        viewModel.toggleFavorite(channel)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.favorites.any { stableChannelId(it) == stableChannelId(channel) })
        assertTrue(viewModel.state.value.categoryChannels.single().channel.isFavorite)
        assertEquals(stableChannelId(channel), stableChannelId(viewModel.state.value.selectedChannel!!))

        viewModel.toggleFavorite(channel)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.favorites.isEmpty())
        assertFalse(viewModel.state.value.categoryChannels.single().channel.isFavorite)
        assertEquals(refreshCount, repo.refreshCalls)
        assertEquals(catalogRefreshCount, repo.catalogRefreshCalls)
        assertFalse(viewModel.state.value.isLoadingChannels)
    }

    @Test
    fun consecutive_favorite_toggles_are_serialized() = runTest(dispatcher) {
        val channel = sampleChannel("playlist-1", "News One", tvgId = "news.one")
        val repo = FakeChannelRepository(listOf(samplePlaylist("playlist-1")),
            mutableMapOf("playlist-1" to listOf(channel)), mutableMapOf("playlist-1" to listOf(channel)))
        val viewModel = ChannelsViewModel(repo, FakePreferencesRepository(), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        viewModel.toggleFavorite(channel)
        viewModel.toggleFavorite(channel)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.favorites.isEmpty())
        assertFalse(viewModel.state.value.categoryChannels.single().channel.isFavorite)
    }

    @Test
    fun changing_search_query_clears_results_until_new_debounced_results_arrive() = runTest(dispatcher) {
        val channel = sampleChannel("playlist-1", "News One", tvgId = "news.one")
        val repo = FakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(channel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(channel)),
        )
        val viewModel = ChannelsViewModel(repo, FakePreferencesRepository(), backgroundDispatcher = dispatcher)
        advanceUntilIdle()

        viewModel.updateSearchQuery("News")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("News One"), viewModel.state.value.searchResults.map { it.name })

        viewModel.updateSearchQuery("Sports")

        assertTrue(viewModel.state.value.searchResults.isEmpty())
    }

    @Test
    fun repeated_add_presses_start_only_one_xtream_import() = runTest(dispatcher) {
        val base = FakeChannelRepository(emptyList(), mutableMapOf(), mutableMapOf())
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val repo = object : ChannelRepository by base {
            override suspend fun addXtreamPlaylist(name: String, server: String, username: String, password: String, id: String?, epgUrl: String?): ChannelPlaylist {
                calls++
                finish.await()
                throw IllegalStateException("import failed")
            }
        }
        val model = ChannelsViewModel(repo, FakePreferencesRepository(), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        enterXtreamDraft(model)
        model.addPlaylist()
        assertTrue(model.state.value.isAddingPlaylist, "Busy state must be immediate, before the coroutine runs")
        model.addPlaylist()
        runCurrent()
        assertEquals(1, calls)
        finish.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.isAddingPlaylist)
        assertTrue(model.state.value.showAddPlaylist)
    }

    @Test
    fun rejected_login_keeps_the_draft_and_retry_can_select_the_imported_playlist() = runTest(dispatcher) {
        val playlists = mutableListOf<ChannelPlaylist>()
        val base = FakeChannelRepository(playlists, mutableMapOf(), mutableMapOf())
        var calls = 0
        val repo = object : ChannelRepository by base {
            override suspend fun addXtreamPlaylist(name: String, server: String, username: String, password: String, id: String?, epgUrl: String?): ChannelPlaylist {
                calls++
                if (calls == 1) throw com.torve.data.channels.XtreamAccountRejected(inactive = false)
                return samplePlaylist("new-playlist").also { playlists += it }
            }
        }
        val model = ChannelsViewModel(repo, FakePreferencesRepository(), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        enterXtreamDraft(model)
        model.addPlaylist()
        advanceUntilIdle()
        assertTrue(model.state.value.showAddPlaylist)
        assertFalse(model.state.value.isAddingPlaylist)
        assertEquals("Local test", model.state.value.newPlaylistName)
        assertEquals("test-only", model.state.value.newXtreamPassword)
        assertEquals("Playlist sign-in failed. Check the server, username and password.", model.playlistImportErrorMessage())
        model.addPlaylist()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertFalse(model.state.value.showAddPlaylist)
        assertEquals("new-playlist", model.state.value.selectedPlaylistId)
        assertEquals(null, model.playlistImportErrorMessage())
        assertEquals("", model.state.value.newXtreamPassword)
    }

    @Test
    fun m3u_failure_shows_safe_retry_copy_and_preserves_the_input() = runTest(dispatcher) {
        val base = FakeChannelRepository(emptyList(), mutableMapOf(), mutableMapOf())
        val repo = object : ChannelRepository by base {
            override suspend fun addPlaylist(name: String, url: String, epgUrl: String?, id: String?, onProgress: ((com.torve.domain.repository.PlaylistAddProgress) -> Unit)?): ChannelPlaylist {
                throw IllegalStateException("Failed fetch: https://example.test/list?password=test-secret")
            }
        }
        val model = ChannelsViewModel(repo, FakePreferencesRepository(), backgroundDispatcher = dispatcher)
        advanceUntilIdle()
        model.showAddPlaylistDialog()
        model.setNewPlaylistName("Local test")
        model.setNewPlaylistUrl("https://example.test/list.m3u")
        model.addPlaylist()
        advanceUntilIdle()
        assertTrue(model.state.value.showAddPlaylist)
        assertFalse(model.state.value.isAddingPlaylist)
        assertEquals("https://example.test/list.m3u", model.state.value.newPlaylistUrl)
        val copy = model.playlistImportErrorMessage().orEmpty()
        assertTrue(copy.contains("try again"))
        assertFalse(copy.contains("test-secret"))
        assertFalse(copy.contains("example.test"))
    }

    private fun enterXtreamDraft(model: ChannelsViewModel) {
        model.showAddPlaylistDialog()
        model.setNewPlaylistType("xtream")
        model.setNewPlaylistName("Local test")
        model.setNewXtreamServer("http://127.0.0.1")
        model.setNewXtreamUsername("fixture")
        model.setNewXtreamPassword("test-only")
    }

    private fun samplePlaylist(id: String) = ChannelPlaylist(
        id = id,
        name = "Playlist $id",
        url = "https://example.com/$id.m3u",
        type = PlaylistType.M3U,
    )

    private fun sampleChannel(
        playlistId: String,
        name: String,
        tvgId: String,
    ) = Channel(
        name = name,
        url = "https://example.com/live/$tvgId.m3u8",
        tvgId = tvgId,
        groupTitle = "News",
        playlistId = playlistId,
        contentType = ChannelContentType.LIVE,
    )
}

private class FakePreferencesRepository(
    private val store: MutableMap<String, String> = mutableMapOf(),
) : PreferencesRepository {
    override suspend fun getString(key: String): String? = store[key]

    override suspend fun setString(key: String, value: String) {
        store[key] = value
    }

    override suspend fun remove(key: String) {
        store.remove(key)
    }
}

private class FakeChannelRepository(
    private val playlists: List<ChannelPlaylist>,
    private val persistedChannels: MutableMap<String, List<Channel>>,
    private val remoteChannels: MutableMap<String, List<Channel>>,
    private val refreshFailures: MutableSet<String> = mutableSetOf(),
) : ChannelRepository {
    private val favorites = linkedMapOf<String, Channel>()
    private val recents = linkedMapOf<String, Channel>()
    var refreshCalls: Int = 0
        private set
    var catalogRefreshCalls: Int = 0
        private set

    override suspend fun addPlaylist(
        name: String,
        url: String,
        epgUrl: String?,
        id: String?,
        onProgress: ((com.torve.domain.repository.PlaylistAddProgress) -> Unit)?,
    ): ChannelPlaylist {
        error("Not used")
    }

    override suspend fun addXtreamPlaylist(
        name: String,
        server: String,
        username: String,
        password: String,
        id: String?,
        epgUrl: String?,
    ): ChannelPlaylist {
        error("Not used")
    }

    override suspend fun removePlaylist(id: String) {
        persistedChannels.remove(id)
    }

    override suspend fun updatePlaylistEpgUrl(playlistId: String, epgUrl: String?) = Unit

    override suspend fun getPlaylists(): List<ChannelPlaylist> = playlists

    override suspend fun refreshPlaylist(playlistId: String) {
        refreshCalls++
        refreshFromRemote(playlistId)
    }

    override suspend fun refreshPlaylistCatalog(playlistId: String) {
        catalogRefreshCalls++
        refreshFromRemote(playlistId)
    }

    private fun refreshFromRemote(playlistId: String) {
        if (playlistId in refreshFailures) {
            throw IllegalStateException("refresh failed")
        }
        persistedChannels[playlistId] = remoteChannels[playlistId].orEmpty()
    }

    override suspend fun refreshEpg(playlistId: String, hiddenChannelIds: Set<String>) = Unit

    override suspend fun getChannels(playlistId: String): List<Channel> = persistedChannels[playlistId].orEmpty()

    override suspend fun getChannelsByGroup(playlistId: String): Map<String, List<Channel>> {
        return getChannels(playlistId).groupBy { it.groupTitle ?: "Ungrouped" }
    }

    override suspend fun getEnrichedChannels(playlistId: String): List<EnrichedChannel> {
        return getChannels(playlistId).map { channel ->
            EnrichedChannel(channel = favorites[stableChannelId(channel)]?.copy(isFavorite = true) ?: channel)
        }
    }

    override suspend fun searchChannels(query: String): List<Channel> {
        return persistedChannels.values.flatten().filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun getEpg(playlistId: String): EpgData = EpgData()

    override suspend fun getEpgLoadError(playlistId: String): String? = null

    override suspend fun getProgrammes(channelId: String): List<EpgProgramme> = emptyList()

    override suspend fun addFavorite(channel: Channel) {
        favorites[stableChannelId(channel)] = channel
    }

    override suspend fun removeFavorite(channelId: String) {
        favorites.remove(channelId)
    }

    override suspend fun getFavorites(): List<Channel> = favorites.values.toList()

    override suspend fun isFavorite(channelId: String): Boolean = favorites.containsKey(channelId)

    override suspend fun recordChannelViewed(channel: Channel) {
        recents[stableChannelId(channel)] = channel
    }

    override suspend fun getRecentlyViewedChannels(limit: Long): List<Channel> {
        return recents.values.toList().takeLast(limit.toInt()).reversed()
    }

    override suspend fun clearRecentlyViewedChannels() {
        recents.clear()
    }

    override suspend fun clearAll() {
        persistedChannels.clear()
        favorites.clear()
        recents.clear()
    }

    override suspend fun getChannelsByContentType(
        playlistId: String,
        type: ChannelContentType,
    ): List<EnrichedChannel> {
        return getEnrichedChannels(playlistId).filter { it.channel.contentType == type }
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
        return getChannels(playlistId).size.toLong()
    }

    override fun syncHiddenChannelsToDb(hiddenIds: Set<String>) = Unit

    override fun getHiddenChannelIds(): Set<String> = emptySet()
}
