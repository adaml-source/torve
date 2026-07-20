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
    fun startup_with_persisted_catalog_restores_local_channels_without_startup_refresh() = runTest(dispatcher) {
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
        assertEquals("News One", viewModel.state.value.categoryChannels.first().channel.name)
        assertEquals("News", viewModel.state.value.selectedGroup)
        assertEquals(stableChannelId(channel), stableChannelId(viewModel.state.value.selectedChannel!!))
        assertEquals(0, repo.refreshCalls)
        assertEquals(0, repo.catalogRefreshCalls)
    }

    @Test
    fun startup_with_persisted_catalog_keeps_last_known_good_catalog_without_startup_refresh() = runTest(dispatcher) {
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
        assertEquals(0, repo.catalogRefreshCalls)
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
