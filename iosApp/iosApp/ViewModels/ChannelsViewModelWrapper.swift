import Foundation
import shared

final class ChannelsViewModelWrapper: ObservableObject {
    let viewModel: ChannelsViewModel
    @Published var state: ChannelsUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.channelsViewModel()
        self.state = viewModel.state.value as! ChannelsUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? ChannelsUiState {
                    self?.state = s
                }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Sub-tab & view

    func selectSubTab(_ tab: ChannelsSubTab) { viewModel.selectSubTab(tab: tab) }
    func toggleViewMode() { viewModel.toggleViewMode() }

    // MARK: - Category

    func toggleCategoryExpanded(_ name: String) { viewModel.toggleCategoryExpanded(categoryName: name) }
    func toggleCategoryManager() { viewModel.toggleCategoryManager() }
    func toggleHiddenCategory(_ name: String) { viewModel.toggleHiddenCategory(categoryName: name) }
    func toggleHiddenChannel(_ id: String) { viewModel.toggleHiddenChannel(channelId: id) }
    func hideAllCategories() { viewModel.hideAllCategories() }
    func showAllCategories() { viewModel.showAllCategories() }

    // MARK: - Channel actions

    func recordChannelViewed(_ channel: Channel) { viewModel.recordChannelViewed(channel: channel) }
    func toggleFavorite(_ channel: Channel) { viewModel.toggleFavorite(channel: channel) }
    func selectChannel(_ channel: Channel) { viewModel.selectChannel(channel: channel) }
    func clearSelectedChannel() { viewModel.clearSelectedChannel() }
    func clearRecentlyViewed() { viewModel.clearRecentlyViewed() }

    // MARK: - Search

    func updateSearchQuery(_ query: String) { viewModel.updateSearchQuery(query: query) }
    func clearSearch() { viewModel.clearSearch() }

    // MARK: - Playlist management

    func selectPlaylist(_ id: String) { viewModel.selectPlaylist(playlistId: id) }
    func refreshPlaylist() { viewModel.refreshPlaylist() }
    func removePlaylist(_ id: String) { viewModel.removePlaylist(playlistId: id) }
    func deletePlaylist(_ id: String) { viewModel.deletePlaylist(id: id) }
    func showAddPlaylistDialog() { viewModel.showAddPlaylistDialog() }
    func dismissAddPlaylistDialog() { viewModel.dismissAddPlaylistDialog() }
    func setNewPlaylistName(_ name: String) { viewModel.setNewPlaylistName(name: name) }
    func setNewPlaylistUrl(_ url: String) { viewModel.setNewPlaylistUrl(url: url) }
    func setNewPlaylistEpgUrl(_ url: String) { viewModel.setNewPlaylistEpgUrl(url: url) }
    func setNewPlaylistType(_ type: String) { viewModel.setNewPlaylistType(type: type) }
    func setNewXtreamServer(_ server: String) { viewModel.setNewXtreamServer(server: server) }
    func setNewXtreamUsername(_ username: String) { viewModel.setNewXtreamUsername(username: username) }
    func setNewXtreamPassword(_ password: String) { viewModel.setNewXtreamPassword(password: password) }
    func addPlaylist() { viewModel.addPlaylist() }
    func updatePlaylistEpgUrl(_ playlistId: String, epgUrl: String) {
        viewModel.updatePlaylistEpgUrl(playlistId: playlistId, epgUrl: epgUrl)
    }

    // MARK: - Filter & sort

    func setFilter(_ filter: ChannelsFilterType) { viewModel.setFilter(filter: filter) }
    func setSort(_ sort: ChannelsSortType) { viewModel.setSort(sort: sort) }
    func toggleFilterSheet() { viewModel.toggleFilterSheet() }

    // MARK: - Country filter

    func toggleCountryFilter() { viewModel.toggleCountryFilter() }
    func toggleCountry(_ country: String) { viewModel.toggleCountry(country: country) }
    func clearCountryFilter() { viewModel.clearCountryFilter() }
    func setXxxEnabled(_ enabled: Bool) { viewModel.setXxxEnabled(enabled: enabled) }

    // MARK: - Audio

    func setAudioPassthroughEnabled(_ enabled: Bool) { viewModel.setAudioPassthroughEnabled(enabled: enabled) }
    func setPreferSurroundCodecs(_ enabled: Bool) { viewModel.setPreferSurroundCodecs(enabled: enabled) }

    // MARK: - EPG

    func retryGuideLoad() { viewModel.retryGuideLoad() }
}
