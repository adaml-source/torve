package com.torve.android.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.TvIptvScreen
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
import com.torve.presentation.channels.ChannelsViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class TvIptvStartupRecoveryTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun startup_renders_local_catalog_while_background_refresh_is_in_flight() {
        val refreshGate = CompletableDeferred<Unit>()
        val channel = sampleChannel("playlist-1", "News One", "news.one")
        val repo = AndroidFakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(channel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(channel.copy(name = "News One HD"))),
            refreshGate = refreshGate,
        )
        val prefs = AndroidFakePreferencesRepository(
            mutableMapOf(
                "channels_selected_playlist" to "playlist-1",
                "channels_selected_group_playlist-1" to "News",
                "channels_selected_channel_playlist-1" to stableChannelId(channel),
            ),
        )
        val viewModel = ChannelsViewModel(repo, prefs)

        composeRule.setContent {
            TvIptvHarness(viewModel)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { repo.refreshStarted }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.channels.singleOrNull()?.channel?.name == "News One" &&
                viewModel.state.value.selectedChannel?.name == "News One" &&
                viewModel.state.value.selectedGroup == "News"
        }

        refreshGate.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.channels.singleOrNull()?.channel?.name == "News One HD" &&
                viewModel.state.value.selectedChannel?.name == "News One HD"
        }

        val restartedViewModel = ChannelsViewModel(repo, prefs)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            restartedViewModel.state.value.channels.singleOrNull()?.channel?.name == "News One HD" &&
                restartedViewModel.state.value.selectedChannel?.name == "News One HD" &&
                restartedViewModel.state.value.selectedGroup == "News"
        }
    }

    @Test
    fun activity_recreate_restores_persisted_channel_selection_on_tv() {
        val firstChannel = sampleChannel("playlist-1", "News One", "news.one")
        val secondChannel = sampleChannel("playlist-1", "News Two", "news.two")
        val repo = AndroidFakeChannelRepository(
            playlists = listOf(samplePlaylist("playlist-1")),
            persistedChannels = mutableMapOf("playlist-1" to listOf(firstChannel, secondChannel)),
            remoteChannels = mutableMapOf("playlist-1" to listOf(firstChannel, secondChannel)),
        )
        val prefs = AndroidFakePreferencesRepository(
            mutableMapOf(
                "channels_selected_playlist" to "playlist-1",
                "channels_selected_group_playlist-1" to "News",
                "channels_selected_channel_playlist-1" to stableChannelId(secondChannel),
            ),
        )
        val viewModel = ChannelsViewModel(repo, prefs)

        composeRule.setContent {
            TvIptvHarness(viewModel)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.selectedChannel?.name == "News Two" &&
                viewModel.state.value.selectedGroup == "News" &&
                viewModel.state.value.channels.size == 2
        }

        val restartedViewModel = ChannelsViewModel(repo, prefs)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            restartedViewModel.state.value.selectedChannel?.name == "News Two" &&
                restartedViewModel.state.value.selectedGroup == "News" &&
                restartedViewModel.state.value.channels.size == 2
        }
    }

    @Composable
    private fun TvIptvHarness(viewModel: ChannelsViewModel) {
        val railFocusRequester = remember { FocusRequester() }
        TvIptvScreen(
            railFocusRequester = railFocusRequester,
            onChannelPlay = {},
            onFirstContentRequester = {},
            onContentFocused = {},
            viewModel = viewModel,
            shouldAutoFocus = false,
            isActive = true,
            isRailFocused = false,
            isRailExpanded = false,
        )
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

private class AndroidFakePreferencesRepository(
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

private class AndroidFakeChannelRepository(
    private val playlists: List<ChannelPlaylist>,
    private val persistedChannels: MutableMap<String, List<Channel>>,
    private val remoteChannels: MutableMap<String, List<Channel>>,
    private val refreshGate: CompletableDeferred<Unit>? = null,
) : ChannelRepository {
    private val favorites = linkedMapOf<String, Channel>()
    private val recents = linkedMapOf<String, Channel>()
    @Volatile
    var refreshStarted: Boolean = false

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
        refreshStarted = true
        refreshGate?.await()
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
